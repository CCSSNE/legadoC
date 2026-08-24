package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File

/** Stable book-level sidecar contract shared by normal and audio TXT-ZIP exports. */
object BookArchive {
    const val MANIFEST_FILE_NAME = "book_archive.json"
    const val COVER_FILE_NAME = "cover.image"
    const val MIN_SUPPORTED_VERSION = 1
    const val VERSION = 1

    private const val PERSISTENT_RESOURCE_DIR_NAME = "book_archive_resources"

    fun persistentCoverFile(book: Book): File {
        return appCtx.externalFiles.getFile(
            PERSISTENT_RESOURCE_DIR_NAME,
            book.getFolderName(),
            COVER_FILE_NAME,
        )
    }

    /** Imported archive resources belong to the book entity, not to its disposable cache. */
    fun deletePersistentResources(book: Book) {
        if (!book.isArchive) return
        val resourceDir = persistentCoverFile(book).parentFile
        if (resourceDir.exists()) {
            require(FileUtils.delete(resourceDir, deleteRootDir = true) && !resourceDir.exists()) {
                "Unable to delete imported book archive resources: ${resourceDir.absolutePath}"
            }
        }
    }
}

data class BookArchiveManifest(
    val version: Int = BookArchive.VERSION,
    val textFile: String = "",
    val coverFile: String? = null,
)
