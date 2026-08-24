package io.legado.app.help.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader

class ReviewSnapshotIntegrityTest {

    @Test
    fun resourceIndexAllowsSeveralUrlsForOneContentAddressedBlob() {
        val key = "a".repeat(64)
        val matches = indexedReviewResources(
            resources = listOf(
                ReviewSnapshotResourceEntry("https://a.example/avatar", key, "image/png", 42),
                ReviewSnapshotResourceEntry("https://b.example/avatar", key, "image/png", 42),
            ),
            keys = listOf(key),
        )

        assertEquals(2, requireNotNull(matches[key]).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resourceIndexRejectsSameKeyWithConflictingLengths() {
        val key = "b".repeat(64)
        indexedReviewResources(
            resources = listOf(
                ReviewSnapshotResourceEntry("https://a.example/avatar", key, "image/png", 41),
                ReviewSnapshotResourceEntry("https://b.example/avatar", key, "image/png", 42),
            ),
            keys = listOf(key),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun resourceIndexRejectsRepeatedSnapshotReference() {
        val key = "d".repeat(64)
        indexedReviewResources(
            resources = listOf(
                ReviewSnapshotResourceEntry("https://a.example/avatar", key, "image/png", 42),
            ),
            keys = listOf(key, key),
        )
    }

    @Test
    fun hotMetadataReaderSkipsLargeHtmlAndPreservesSmallFields() {
        val key = "c".repeat(64)
        val reader = LargeSnapshotReader(key, htmlLength = 50 * 1024 * 1024)
        val metadata = readReviewSnapshotHotMetadata(reader)

        assertEquals("book", metadata.bookUrl)
        assertEquals("chapter", metadata.chapterUrl)
        assertEquals(7, metadata.chapterIndex)
        assertEquals("button", metadata.buttonSrc)
        assertEquals(listOf(key), metadata.resourceKeys)
        assertTrue(metadata.htmlPresent)
        assertEquals(50L * 1024L * 1024L, reader.htmlCharsRead)
        assertFalse(metadata.toString().contains("x".repeat(128)))
    }

    /** Emits a 50MB HTML JSON value without allocating that value as a String. */
    private class LargeSnapshotReader(
        key: String,
        private val htmlLength: Int,
    ) : Reader() {
        private val prefix = "{\"bookUrl\":\"book\",\"chapterUrl\":\"chapter\"," +
            "\"chapterIndex\":7,\"buttonSrc\":\"button\",\"resourceKeys\":[\"$key\"],\"html\":\""
        private val suffix = "\"}"
        private var position = 0L
        var htmlCharsRead = 0L
            private set

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val totalLength = prefix.length.toLong() + htmlLength + suffix.length
            if (position >= totalLength) return -1
            var written = 0
            while (written < length && position < totalLength) {
                val value = when {
                    position < prefix.length -> prefix[position.toInt()]
                    position < prefix.length + htmlLength -> {
                        htmlCharsRead++
                        'x'
                    }
                    else -> suffix[(position - prefix.length - htmlLength).toInt()]
                }
                buffer[offset + written] = value
                position++
                written++
            }
            return written
        }

        override fun close() = Unit
    }
}
