package io.legado.app.utils

import android.util.Base64

const val MAX_DATA_URL_BYTES = 32 * 1024 * 1024

fun String.decodeBase64DataUrlBytes(maxBytes: Long = MAX_DATA_URL_BYTES.toLong()): ByteArray? {
    val clean = trim()
        .trimMatchingDataUrlWrapper()
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
    val rawPayload = when {
        clean.startsWith("data:", ignoreCase = true) -> {
            val commaIndex = clean.indexOf(',')
            if (commaIndex < 0) return null
            val meta = clean.substring(0, commaIndex).lowercase()
            if (!meta.contains(";base64")) return null
            clean.substring(commaIndex + 1).substringBefore(",{")
        }
        clean.startsWith("data64:", ignoreCase = true) -> {
            clean.substringAfter(':').substringBefore(",{")
        }
        else -> return null
    }
    val payload = rawPayload
        .trimMatchingDataUrlWrapper()
        .let { it.decodePercentEscapesPreservingPlus() }
        .filterNot { it.isWhitespace() }
    if (payload.isBlank()) return null
    if (payload.estimatedBase64Bytes() > maxBytes) return null
    val paddedPayload = payload.padBase64()
    fun decode(payload: String): ByteArray? {
        return runCatching {
            Base64.decode(payload, Base64.DEFAULT)
        }.getOrElse {
            runCatching {
                Base64.decode(payload, Base64.URL_SAFE)
            }.getOrNull()
        }
    }
    decode(paddedPayload)?.takeIf { it.size.toLong() <= maxBytes }?.let { return it }
    val sanitizedPayload = payload
        .replace(Regex("[^A-Za-z0-9+/=_-]"), "")
        .padBase64()
    if (sanitizedPayload.isBlank()) return null
    if (sanitizedPayload.estimatedBase64Bytes() > maxBytes) return null
    return decode(sanitizedPayload)?.takeIf { it.size.toLong() <= maxBytes }
}

fun String.estimateBase64DataUrlBytes(): Long? {
    val clean = trim().trimMatchingDataUrlWrapper()
    val payload = when {
        clean.startsWith("data:", ignoreCase = true) -> {
            val commaIndex = clean.indexOf(',')
            if (commaIndex < 0 || !clean.substring(0, commaIndex).contains(";base64", true)) {
                return null
            }
            clean.substring(commaIndex + 1).substringBefore(",{")
        }
        clean.startsWith("data64:", ignoreCase = true) -> {
            clean.substringAfter(':').substringBefore(",{")
        }
        else -> return null
    }.filterNot { it.isWhitespace() }
    if (payload.isBlank()) return null
    return payload.estimatedBase64Bytes()
}

private fun String.decodePercentEscapesPreservingPlus(): String {
    if (!contains('%')) return this
    val bytes = ByteArray(length)
    var write = 0
    var index = 0
    while (index < length) {
        val ch = this[index]
        if (ch == '%' && index + 2 < length) {
            val hi = this[index + 1].digitToIntOrNull(16)
            val lo = this[index + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                bytes[write++] = ((hi shl 4) or lo).toByte()
                index += 3
                continue
            }
        }
        if (ch.code <= 0x7F) {
            bytes[write++] = ch.code.toByte()
        } else {
            val encoded = ch.toString().toByteArray(Charsets.UTF_8)
            encoded.forEach { b -> bytes[write++] = b }
        }
        index++
    }
    return bytes.copyOf(write).toString(Charsets.UTF_8)
}

private fun String.trimMatchingDataUrlWrapper(): String {
    var value = trim()
    while (value.isNotEmpty() && value.first() in charArrayOf('\'', '"')) {
        value = value.drop(1).trimStart()
    }
    while (value.isNotEmpty() && value.last() in charArrayOf('\'', '"', ')', ';')) {
        value = value.dropLast(1).trimEnd()
    }
    return value
}

private fun String.padBase64(): String {
    val remainder = length % 4
    return if (remainder == 0) this else this + "=".repeat(4 - remainder)
}

private fun String.estimatedBase64Bytes(): Long {
    val padding = takeLastWhile { it == '=' }.length.coerceAtMost(2)
    return (length.toLong() * 3L / 4L - padding).coerceAtLeast(0L)
}
