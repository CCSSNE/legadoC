package io.legado.app.help.webView

import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Stores HTML outside Fragment/Activity argument Bundles.
 *
 * Web pages can contain large inline images. Passing that HTML through a Bundle
 * makes Android serialize the whole document during state saving and can exceed
 * the Binder transaction limit.
 */
object WebViewHtmlStore {

    private const val DIRECTORY_NAME = "webview_html"
    private const val FILE_SUFFIX = ".html"

    fun write(html: String): String {
        val directory = File(appCtx.filesDir, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create WebView HTML directory: ${directory.absolutePath}")
        }
        val file = File(directory, "${UUID.randomUUID()}$FILE_SUFFIX")
        try {
            file.writeText(html, Charsets.UTF_8)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        return file.name
    }

    fun read(reference: String): String? {
        if (!reference.matches(REFERENCE_PATTERN)) {
            throw IllegalArgumentException("Invalid WebView HTML reference: $reference")
        }
        val file = File(File(appCtx.filesDir, DIRECTORY_NAME), reference)
        return file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    fun delete(reference: String?) {
        if (reference == null || !reference.matches(REFERENCE_PATTERN)) return
        File(File(appCtx.filesDir, DIRECTORY_NAME), reference).delete()
    }

    private val REFERENCE_PATTERN = Regex("""[0-9a-fA-F-]{36}\.html""")
}
