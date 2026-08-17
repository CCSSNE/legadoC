package io.legado.app.constant

import androidx.annotation.IntDef

/**
 * Stable content identity used independently from the mutable flags in [BookType].
 */
@Suppress("ConstPropertyName")
object BookMediaType {
    const val EXTRA_MEDIA_TYPE = "mediaType"

    const val text = 0
    const val audio = 1
    const val image = 2
    const val video = 3
    const val webFile = 4

    @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(text, audio, image, video, webFile)
    annotation class Type

    fun fromBookType(@BookType.Type bookType: Int): Int {
        return when {
            bookType and BookType.video > 0 -> video
            bookType and BookType.image > 0 -> image
            bookType and BookType.audio > 0 -> audio
            bookType and BookType.webFile > 0 -> webFile
            else -> text
        }
    }

    fun toBookType(@Type mediaType: Int): Int {
        return when (mediaType) {
            audio -> BookType.audio
            image -> BookType.image
            video -> BookType.video
            webFile -> BookType.webFile
            else -> BookType.text
        }
    }
}
