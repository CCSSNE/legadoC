package io.legado.app.help.book

/** Stable sidecar contract for an exported audio-book TXT-ZIP. */
object AudioBookArchive {
    const val MANIFEST_FILE_NAME = "audio_book.json"
    const val MEDIA_DIR_NAME = "audio"
    const val VERSION = 1

    fun audioFileName(fileName: String): String {
        return if (fileName.startsWith("audio_", ignoreCase = true)) {
            fileName
        } else {
            "audio_$fileName"
        }
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
    val mediaFiles: List<String> = emptyList(),
    val variable: String? = null,
    val start: Long? = null,
    val end: Long? = null,
)
