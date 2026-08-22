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

    /**
     * 清理带 URL 选项的 SVG data URI 图片（书源装饰性小图），保持导出文本干净。
     *
     * 评论泡（选项 JSON 里 style=TEXT 的 img）必须保留：TXT/TXT-ZIP 重新导入后，
     * 正文要能据此渲染评论泡，点击时以 src 命中还原的评论快照；剥离后评论
     * 将无法从正文触达，快照变成无人引用的孤儿。
     *
     * @param keepReviewButtons true = 保留评论泡（txt/txt_zip 导出）；
     *                          false = 全部清除（epub 导出，评论泡无对应渲染与快照）
     */
    fun cleanSvgUrlOptionImages(content: String, keepReviewButtons: Boolean = false): String {
        if (!content.contains("<img", ignoreCase = true) ||
            !content.contains("data:image/svg", ignoreCase = true)
        ) {
            return content
        }
        val matcher = AppPattern.imgPattern.matcher(content)
        val sb = StringBuffer()
        while (matcher.find()) {
            val src = matcher.group(1)
            val isReview = keepReviewButtons && isReviewButton(src)
            if (src != null && normalizeSrc(src).removeTag && !isReview) {
                matcher.appendReplacement(sb, "")
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()))
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /** 评论泡判定：src 选项 JSON 里 style 为 TEXT（与排版层、评论抓取端同一约定） */
    private fun isReviewButton(src: String?): Boolean {
        if (src.isNullOrEmpty()) return false
        val matcher = urlOptionPattern.matcher(src)
        if (!matcher.find()) return false
        val optionJson = src.substring(matcher.end())
        val options = GSON.fromJsonObject<Map<String, String>>(optionJson).getOrNull() ?: return false
        return options["style"].equals("TEXT", ignoreCase = true)
    }
}
