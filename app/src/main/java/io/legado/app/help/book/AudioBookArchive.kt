package io.legado.app.help.book

import android.net.Uri
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getFile
import io.legado.app.utils.isJsonArray
import splitties.init.appCtx
import java.io.File

/** Stable sidecar contract for an exported audio-book TXT-ZIP. */
object AudioBookArchive {
    const val MANIFEST_FILE_NAME = "audio_book.json"
    const val MEDIA_DIR_NAME = "audio"
    const val MIN_SUPPORTED_VERSION = 1
    const val VERSION = 2

    /** Imported media is book content, deliberately outside BookHelp's cache domain. */
    const val PERSISTENT_MEDIA_DIR_NAME = "audio_book_resources"
    private const val LEGACY_CACHE_MEDIA_DIR_NAME = "audio_archive"

    fun audioFileName(fileName: String): String {
        return if (fileName.startsWith("audio_", ignoreCase = true)) {
            fileName
        } else {
            "audio_$fileName"
        }
    }

    fun importedChapterUrl(bookUrl: String, order: Int): String {
        require(bookUrl.isNotBlank()) { "Audio archive book URL must not be blank" }
        require(order >= 0) { "Audio archive chapter order must be non-negative" }
        return "audio-archive://${MD5Utils.md5Encode16(bookUrl)}/chapter/$order"
    }

    fun persistentMediaDir(book: Book): File {
        return appCtx.externalFiles.getFile(PERSISTENT_MEDIA_DIR_NAME, book.getFolderName())
    }

    /** Deletes imported audio only when its book entity is being deleted. */
    fun deletePersistentMedia(book: Book) {
        if (!book.isAudio || !book.isArchive) return
        val targetDir = persistentMediaDir(book)
        if (targetDir.exists()) {
            require(FileUtils.delete(targetDir, deleteRootDir = true) && !targetDir.exists()) {
                "Unable to delete imported audio resources: ${targetDir.absolutePath}"
            }
        }
    }

    /**
     * Moves pre-fix imported media out of book_cache before that cache is deleted.
     * The remap is strict: every old local resource URL must resolve to a non-empty
     * file in the persistent directory before the chapter row is updated.
     */
    fun migrateLegacyMedia(book: Book) {
        if (!book.isAudio || !book.isArchive) return
        val legacyDir = File(BookHelp.getCacheDir(book), LEGACY_CACHE_MEDIA_DIR_NAME)
        val targetDir = persistentMediaDir(book)
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val hasLegacyReference = chapters.any { chapter ->
            chapter.resourceUrl?.let { containsLegacyReference(it, legacyDir) } == true
        }
        when {
            legacyDir.exists() -> {
                require(legacyDir.isDirectory) {
                    "Legacy audio archive path is not a directory: ${legacyDir.absolutePath}"
                }
                require(!targetDir.exists()) {
                    "Both legacy and persistent audio directories exist for ${book.bookUrl}"
                }
                targetDir.parentFile?.mkdirs()
                require(legacyDir.renameTo(targetDir)) {
                    "Unable to move imported audio resources to ${targetDir.absolutePath}"
                }
            }

            !hasLegacyReference -> return
            !targetDir.isDirectory -> error(
                "Imported audio resources are missing for ${book.bookUrl}: ${targetDir.absolutePath}"
            )
        }

        val changedChapters = chapters.filter { chapter ->
            val currentUrl = chapter.resourceUrl ?: return@filter false
            val remappedUrl = remapLegacyReference(currentUrl, legacyDir, targetDir)
            if (remappedUrl == currentUrl) {
                false
            } else {
                chapter.resourceUrl = remappedUrl
                true
            }
        }
        if (changedChapters.isNotEmpty()) {
            appDb.bookChapterDao.update(*changedChapters.toTypedArray())
        }
    }

    private fun containsLegacyReference(resourceUrl: String, legacyDir: File): Boolean {
        return mediaUrls(resourceUrl).any { url ->
            localFileUnder(url, legacyDir) != null
        }
    }

    private fun remapLegacyReference(
        resourceUrl: String,
        legacyDir: File,
        targetDir: File,
    ): String {
        val urls = mediaUrls(resourceUrl)
        val remappedUrls = urls.map { url ->
            val source = localFileUnder(url, legacyDir) ?: return@map url
            val target = File(targetDir, source.name)
            require(target.isFile && target.length() > 0L) {
                "Migrated audio file is missing or empty: ${target.absolutePath}"
            }
            Uri.fromFile(target).toString()
        }
        if (urls == remappedUrls) return resourceUrl
        return if (resourceUrl.isJsonArray()) GSON.toJson(remappedUrls) else remappedUrls.single()
    }

    private fun mediaUrls(resourceUrl: String): List<String> {
        return if (resourceUrl.isJsonArray()) {
            GSON.fromJsonArray<String>(resourceUrl).getOrThrow()
        } else {
            listOf(resourceUrl)
        }
    }

    private fun localFileUnder(url: String, directory: File): File? {
        val uri = Uri.parse(url)
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val file = File(requireNotNull(uri.path) { "Local audio URI has no path: $url" })
        return file.takeIf { it.parentFile?.canonicalFile == directory.canonicalFile }
    }
}

data class AudioBookArchiveManifest(
    val version: Int = AudioBookArchive.VERSION,
    val textFile: String = "",
    val name: String = "",
    val author: String = "",
    val intro: String? = null,
    val chapters: List<AudioBookArchiveChapter> = emptyList(),
)

data class AudioBookArchiveChapter(
    val index: Int = 0,
    val title: String = "",
    /** Stable source identity used to remap chapter-owned sidecars after import. */
    val sourceChapterUrl: String? = null,
    val mediaFiles: List<String> = emptyList(),
    val variable: String? = null,
    val start: Long? = null,
    val end: Long? = null,
)
