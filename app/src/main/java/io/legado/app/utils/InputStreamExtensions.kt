package io.legado.app.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

fun InputStream?.isJson(): Boolean {
    this ?: return false
    this.use {
        val byteArray = ByteArray(128)
        it.read(byteArray)
        val a = String(byteArray).trim()
        it.skip(it.available() - 128L)
        it.read(byteArray)
        val b = String(byteArray).trim()
        return (a + b).isJson()
    }
}

fun InputStream?.contains(str: String): Boolean {
    this ?: return false
    this.use {
        val scanner = Scanner(it)
        return scanner.findWithinHorizon(str, 0) != null
    }
}

@Throws(IOException::class)
fun InputStream.readBytesLimited(maxBytes: Long = Long.MAX_VALUE): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be >= 0" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count.toLong()
        if (total > maxBytes) {
            throw IOException("Input is too large: $total > $maxBytes bytes")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
