package io.legado.app.ui.book.read.page.entities

import android.net.Uri
import io.legado.app.utils.ImageSource
import io.legado.app.utils.decodeBase64DataUrlBytes
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * 书源定义的段评入口。src 必须保持原样，以便交给书源原有的 click/js 执行路径。
 */
data class ReviewButton(
    val src: String,
    val click: String?,
)

/**
 * 只解析书源内嵌的 SVG 文本，不对位图或远程图片猜测评论数。
 */
object ReviewBubble {

    fun hasZeroCount(src: String): Boolean {
        val dataUrl = ImageSource.normalizeForStorage(src)
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return false
        val header = dataUrl.substring(0, comma)
        if (!header.startsWith("data:image/svg+xml", ignoreCase = true)) return false
        val svg = if (header.contains(";base64", ignoreCase = true)) {
            dataUrl.decodeBase64DataUrlBytes()?.toString(Charsets.UTF_8)
        } else {
            Uri.decode(dataUrl.substring(comma + 1))
        } ?: return false
        val document = Jsoup.parse(svg, "", Parser.xmlParser())
        if (document.selectFirst("svg") == null) return false
        val labels = document
            .select("text")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        return labels.any { it == "0" }
    }
}
