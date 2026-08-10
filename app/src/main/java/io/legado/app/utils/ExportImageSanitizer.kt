package io.legado.app.utils

import io.legado.app.constant.AppPattern
import java.util.regex.Matcher
import java.util.regex.Pattern

object ExportImageSanitizer {

    private val urlOptionPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")

    data class ImageSrc(
        val original: String,
        val src: String,
        val hasUrlOption: Boolean,
        val removeTag: Boolean
    )

    fun normalizeSrc(src: String): ImageSrc {
        val matcher = urlOptionPattern.matcher(src)
        if (!matcher.find()) {
            return ImageSrc(src, src, hasUrlOption = false, removeTag = false)
        }
        val baseSrc = src.substring(0, matcher.start()).trim()
        return ImageSrc(
            original = src,
            src = baseSrc,
            hasUrlOption = true,
            removeTag = baseSrc.startsWith("data:image/svg", ignoreCase = true)
        )
    }

    fun cleanSvgUrlOptionImages(content: String): String {
        if (!content.contains("<img", ignoreCase = true) ||
            !content.contains("data:image/svg", ignoreCase = true)
        ) {
            return content
        }
        val matcher = AppPattern.imgPattern.matcher(content)
        val sb = StringBuffer()
        while (matcher.find()) {
            val src = matcher.group(1)
            if (src != null && normalizeSrc(src).removeTag) {
                matcher.appendReplacement(sb, "")
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()))
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }
}
