package io.legado.app.utils

import io.legado.app.constant.AppPattern

/**
 * Normalizes image sources only for local storage/decoding.
 * A data URI can carry legado URL options after `,{...}`; those options are
 * for the source action and must not become part of the image bytes.
 * Remote URLs keep their original form because AnalyzeUrl consumes options.
 */
object ImageSource {

    fun normalizeForStorage(src: String): String {
        val matcher = AppPattern.urlOptionPattern.matcher(src)
        if (!matcher.find()) return src
        val base = src.substring(0, matcher.start()).trim()
        return if (base.isDataUrl()) base else src
    }
}
