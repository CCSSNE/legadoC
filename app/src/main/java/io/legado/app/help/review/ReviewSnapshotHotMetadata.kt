package io.legado.app.help.review

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/** Metadata read by snapshot hot paths without materializing the HTML field. */
internal data class ReviewSnapshotHotMetadata(
    val bookUrl: String,
    val chapterUrl: String,
    val chapterIndex: Int,
    val buttonSrc: String,
    val resourceKeys: List<String>?,
    val htmlPresent: Boolean,
    val partial: Boolean,
)

internal fun readReviewSnapshotHotMetadata(input: Reader): ReviewSnapshotHotMetadata {
    JsonReader(input).use { reader ->
        var bookUrl = ""
        var chapterUrl = ""
        var chapterIndex = 0
        var buttonSrc = ""
        var resourceKeys: List<String>? = null
        var htmlPresent = false
        var partial = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "bookUrl" -> bookUrl = reader.nextString()
                "chapterUrl" -> chapterUrl = reader.nextString()
                "chapterIndex" -> chapterIndex = reader.nextInt()
                "buttonSrc" -> buttonSrc = reader.nextString()
                "resourceKeys" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        val keys = ArrayList<String>()
                        reader.beginArray()
                        while (reader.hasNext()) keys += reader.nextString()
                        reader.endArray()
                        resourceKeys = keys
                    }
                }
                "partial" -> partial = reader.nextBoolean()
                "html" -> {
                    htmlPresent = reader.peek() != JsonToken.NULL
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ReviewSnapshotHotMetadata(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            chapterIndex = chapterIndex,
            buttonSrc = buttonSrc,
            resourceKeys = resourceKeys,
            htmlPresent = htmlPresent,
            partial = partial,
        )
    }
}
