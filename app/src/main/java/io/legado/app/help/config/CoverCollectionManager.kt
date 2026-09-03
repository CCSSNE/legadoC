package io.legado.app.help.config

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.UUID

/**
 * 书架封面集合：套件可携带图集，选中后按稳定哈希/顺序为书籍分配集合内封面。
 */
object CoverCollectionManager {

    const val MODE_RANDOM = "random"
    const val MODE_SEQUENCE = "sequence"
    const val MODE_MIXED = "mixed"
    private const val indexFileName = "collections.json"
    private const val packageFileName = "collection.json"
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp")

    @Volatile
    private var dayCache: List<Collection>? = null

    @Volatile
    private var nightCache: List<Collection>? = null
    private val assignmentCache = hashMapOf<String, MutableList<Assignment>>()

    val rootDir: File
        get() = appCtx.externalFiles.getFile("coverCollections")

    private val tempDir: File
        get() = rootDir.getFile("temp").apply { mkdirs() }

    fun isMixedMode(): Boolean {
        val isNight = AppConfig.isNightTheme
        return appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionModeNight else PreferKey.coverCollectionModeDay,
            MODE_RANDOM
        ) == MODE_MIXED
    }

    fun selectionKey(): String {
        val isNight = AppConfig.isNightTheme
        val collectionId = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        ).orEmpty()
        val mode = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionModeNight else PreferKey.coverCollectionModeDay,
            MODE_RANDOM
        ).orEmpty()
        return "$isNight:$collectionId:$mode"
    }

    suspend fun load(isNight: Boolean): List<Collection> = withContext(IO) {
        loadIndex(isNight).sortedByDescending { it.updatedAt }
    }

    suspend fun get(isNight: Boolean, id: String?): Collection? = withContext(IO) {
        if (id.isNullOrBlank()) return@withContext null
        loadIndex(isNight).firstOrNull { it.id == id }
    }

    suspend fun selectedEntry(isNight: Boolean): Entry? = withContext(IO) {
        val key = if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        val id = appCtx.getPrefString(key)?.takeIf { it.isNotBlank() } ?: return@withContext null
        val collection = loadIndex(isNight).firstOrNull { it.id == id } ?: return@withContext null
        Entry(collection, Source.LOCAL, collectionDir(collection))
    }

    suspend fun localEntryForKit(isNight: Boolean, refId: String, name: String): Entry? = withContext(IO) {
        val collection = loadIndex(isNight).firstOrNull {
            it.id == refId || it.dirName == refId || it.name == name
        } ?: return@withContext null
        Entry(collection, Source.LOCAL, collectionDir(collection))
    }

    suspend fun importZip(zipFile: File, isNight: Boolean, overwrite: Boolean = false): Collection =
        withContext(IO) {
            importZipInternal(zipFile, isNight, overwrite = overwrite, remoteUpdatedAt = 0L).collection
        }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        val packageDir = tempDir.getFile("export_${entry.dirName}_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        try {
            val imagesDir = packageDir.getFile("images").apply { mkdirs() }
            val packagedImages = entry.collection.images.mapNotNull { path ->
                val source = File(path)
                if (!source.isFile) return@mapNotNull null
                val target = uniqueFile(
                    imagesDir,
                    source.name.normalizeFileName()
                        .ifBlank { "image_${System.currentTimeMillis()}.${source.extension}" }
                )
                source.copyTo(target, overwrite = true)
                "images/${target.name}"
            }
            if (packagedImages.isEmpty()) {
                throw IllegalArgumentException(appCtx.getString(R.string.cover_collection_no_images_to_export))
            }
            File(packageDir, packageFileName)
                .writeText(GSON.toJson(entry.collection.copy(images = packagedImages)))
            val zipFile = tempDir.getFile("${entry.dirName}.zip")
            if (zipFile.exists()) zipFile.delete()
            if (!ZipUtils.zipFile(packageDir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
                throw IllegalStateException(appCtx.getString(R.string.cover_collection_export_failed))
            }
            zipFile
        } finally {
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
    }

    fun selectedCollectionCover(book: Book): String? {
        return selectedCollectionCover(
            bookKey = book.bookUrl.ifBlank { "${book.origin}|${book.name}|${book.author}" },
            hasOriginalCover = book.getDisplayCover().isRealCoverPath()
        )
    }

    fun selectedCollectionCover(searchBook: SearchBook): String? {
        return selectedCollectionCover(
            bookKey = searchBook.bookUrl.ifBlank { "${searchBook.origin}|${searchBook.name}|${searchBook.author}" },
            hasOriginalCover = searchBook.coverUrl.isRealCoverPath()
        )
    }

    fun String?.isRealCoverPath(): Boolean {
        val value = this?.trim().orEmpty()
        if (value.isBlank() || value.equals("use_default_cover", ignoreCase = true)) {
            return false
        }
        val lowerValue = value.lowercase()
        return when {
            lowerValue.startsWith("http://") ||
                lowerValue.startsWith("https://") ||
                lowerValue.startsWith("content://") ||
                lowerValue.startsWith("android.resource://") ||
                lowerValue.startsWith("file:///android_asset/") -> true
            lowerValue.startsWith("file://") -> runCatching {
                File(Uri.parse(value).path.orEmpty()).isFile
            }.getOrDefault(false)
            File(value).isAbsolute -> File(value).isFile
            else -> true
        }
    }

    private fun selectedCollectionCover(bookKey: String, hasOriginalCover: Boolean): String? {
        val isNight = AppConfig.isNightTheme
        val collectionId = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        ) ?: return null
        val collection = loadIndex(isNight).firstOrNull { it.id == collectionId } ?: return null
        if (collection.images.isEmpty()) return null
        val mode = appCtx.getPrefString(
            if (isNight) PreferKey.coverCollectionModeNight else PreferKey.coverCollectionModeDay,
            MODE_RANDOM
        ) ?: MODE_RANDOM
        if (mode == MODE_MIXED && hasOriginalCover) {
            return null
        }
        val path = when (mode) {
            MODE_SEQUENCE -> assignSequential(collection, bookKey)
            else -> collection.images[stableImageIndex(bookKey, collection.images.size)]
        }
        return path.takeIf { File(it).exists() }
    }

    fun setSelected(isNight: Boolean, collectionId: String?) {
        val key = if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        appCtx.putPrefString(key, collectionId.orEmpty())
    }

    fun setMode(isNight: Boolean, mode: String) {
        val key = if (isNight) PreferKey.coverCollectionModeNight else PreferKey.coverCollectionModeDay
        appCtx.putPrefString(key, mode)
    }

    fun collectionDir(collection: Collection): File {
        return typeDir(collection.isNight).getFile(collection.dirName)
    }

    suspend fun deleteLocal(entry: Entry) = withContext(IO) {
        val collection = entry.collection
        saveIndex(
            collection.isNight,
            loadIndex(collection.isNight).filterNot {
                it.id == collection.id || it.dirName == entry.dirName
            }
        )
        FileUtils.delete(entry.localDir ?: collectionDir(collection), deleteRootDir = true)
        clearAssignments(collection.id)
        clearSelectedIfNeeded(collection)
    }

    private fun importZipInternal(
        zipFile: File,
        fallbackIsNight: Boolean,
        overwrite: Boolean,
        remoteUpdatedAt: Long
    ): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
            val packageRoot = packageFile?.parentFile ?: unzipDir
            val rawCollection = packageFile
                ?.let { GSON.fromJsonObject<Collection>(it.readText()).getOrNull() }
                ?: Collection(
                    name = zipFile.nameWithoutExtension.ifBlank { "Imported collection" },
                    isNight = fallbackIsNight,
                    updatedAt = System.currentTimeMillis()
                )
            val isNight = rawCollection.isNight.takeIf { packageFile != null } ?: fallbackIsNight
            val baseName = rawCollection.name.ifBlank {
                zipFile.nameWithoutExtension.ifBlank { "Imported collection" }
            }
            val baseDirName = rawCollection.dirName.ifBlank { baseName.normalizeFileName() }
                .ifBlank { "collection_${System.currentTimeMillis()}" }
            val dirName = if (overwrite) baseDirName else uniqueDirName(isNight, baseDirName)
            val targetDir = typeDir(isNight).getFile(dirName)
            if (targetDir.exists()) {
                if (overwrite) FileUtils.delete(targetDir, deleteRootDir = true) else error("Collection exists")
            }
            targetDir.mkdirs()
            val imagesDir = targetDir.getFile("images").apply { mkdirs() }
            val candidateFiles = if (packageFile != null) {
                rawCollection.images.mapNotNull { value ->
                    val file = File(value)
                    if (file.isAbsolute) file else File(packageRoot, value)
                }
            } else {
                unzipDir.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in imageExtensions }
                    .toList()
            }
            val images = candidateFiles.mapNotNull { source ->
                if (!source.isFile || source.extension.lowercase() !in imageExtensions) {
                    return@mapNotNull null
                }
                val target = uniqueFile(
                    imagesDir,
                    source.name.normalizeFileName()
                        .ifBlank { "image_${System.currentTimeMillis()}.${source.extension}" }
                )
                source.copyTo(target, overwrite = true)
                target.absolutePath
            }
            if (images.isEmpty()) {
                FileUtils.delete(targetDir, deleteRootDir = true)
                throw IllegalArgumentException(appCtx.getString(R.string.cover_collection_zip_no_images))
            }
            val collection = rawCollection.copy(
                id = rawCollection.id.ifBlank { UUID.randomUUID().toString() },
                name = baseName,
                dirName = dirName,
                isNight = isNight,
                images = images,
                updatedAt = if (remoteUpdatedAt > 0L) remoteUpdatedAt else System.currentTimeMillis()
            )
            saveIndex(
                isNight,
                loadIndex(isNight).filterNot { it.dirName == dirName || it.id == collection.id } + collection
            )
            Entry(collection, Source.LOCAL, targetDir, remoteUpdatedAt)
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun typeDir(isNight: Boolean): File {
        return rootDir.getFile(if (isNight) "night" else "day").apply { mkdirs() }
    }

    private fun indexFile(isNight: Boolean): File {
        return typeDir(isNight).getFile(indexFileName)
    }

    private fun loadIndex(isNight: Boolean): List<Collection> {
        (if (isNight) nightCache else dayCache)?.let { return it }
        val file = indexFile(isNight)
        if (!file.exists()) return emptyList()
        val list = GSON.fromJsonArray<Collection>(file.readText()).getOrDefault(emptyList())
            .map { collection ->
                collection.copy(images = collection.images.filter { File(it).exists() })
            }
        if (isNight) {
            nightCache = list
        } else {
            dayCache = list
        }
        return list
    }

    private fun saveIndex(isNight: Boolean, list: List<Collection>) {
        val file = indexFile(isNight)
        file.parentFile?.mkdirs()
        file.writeText(GSON.toJson(list))
        if (isNight) {
            nightCache = list
        } else {
            dayCache = list
        }
    }

    private fun uniqueDirName(isNight: Boolean, preferred: String): String {
        val clean = preferred.normalizeFileName().ifBlank { "collection_${System.currentTimeMillis()}" }
        val existing = loadIndex(isNight).mapTo(hashSetOf()) { it.dirName }
        if (clean !in existing && !typeDir(isNight).getFile(clean).exists()) return clean
        var index = 1
        while (true) {
            val candidate = "${clean}_$index"
            if (candidate !in existing && !typeDir(isNight).getFile(candidate).exists()) return candidate
            index++
        }
    }

    private fun uniqueFile(dir: File, preferredName: String): File {
        val clean = preferredName.normalizeFileName().ifBlank { "image_${System.currentTimeMillis()}.jpg" }
        val base = clean.substringBeforeLast('.', clean)
        val suffix = clean.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var file = File(dir, clean)
        var index = 1
        while (file.exists()) {
            file = File(dir, "${base}_$index$suffix")
            index++
        }
        return file
    }

    private fun clearSelectedIfNeeded(collection: Collection) {
        val key = if (collection.isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay
        if (appCtx.getPrefString(key) == collection.id) {
            appCtx.putPrefString(key, "")
        }
    }

    private fun assignmentsFile(collectionId: String): File {
        return rootDir.getFile("assignments").apply { mkdirs() }.getFile("$collectionId.json")
    }

    private fun stableImageIndex(bookKey: String, imageCount: Int): Int {
        return (bookKey.hashCode() and Int.MAX_VALUE) % imageCount
    }

    @Synchronized
    private fun assignSequential(collection: Collection, bookUrl: String): String {
        val assignments = assignmentCache.getOrPut(collection.id) {
            readAssignments(collection.id).toMutableList()
        }
        assignments.firstOrNull { it.bookUrl == bookUrl }?.let {
            return collection.images.getOrNull(it.index % collection.images.size)
                ?: collection.images.first()
        }
        val index = assignments.size % collection.images.size
        assignments.add(Assignment(bookUrl, index))
        saveAssignments(collection.id, assignments)
        return collection.images[index]
    }

    private fun readAssignments(collectionId: String): List<Assignment> {
        val file = assignmentsFile(collectionId)
        return GSON.fromJsonArray<Assignment>(
            file.takeIf { it.exists() }?.readText()
        ).getOrDefault(emptyList())
    }

    private fun saveAssignments(collectionId: String, assignments: List<Assignment>) {
        assignmentsFile(collectionId).writeText(GSON.toJson(assignments))
    }

    @Synchronized
    private fun clearAssignments(collectionId: String) {
        assignmentCache.remove(collectionId)
        assignmentsFile(collectionId).delete()
    }

    data class Entry(
        val collection: Collection,
        val source: Source,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    ) {
        val dirName: String get() = collection.dirName
    }

    enum class Source {
        LOCAL,
        REMOTE,
        BOTH
    }

    @Keep
    data class Collection(
        val id: String = "",
        val name: String = "",
        val dirName: String = "",
        val isNight: Boolean = false,
        val mode: String = MODE_RANDOM,
        val images: List<String> = emptyList(),
        val updatedAt: Long = 0L
    )

    @Keep
    private data class Assignment(
        val bookUrl: String = "",
        val index: Int = 0
    )
}
