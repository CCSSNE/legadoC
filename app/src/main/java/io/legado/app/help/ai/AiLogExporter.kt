package io.legado.app.help.ai

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiLogExporter {

    fun fileName(): String {
        return "ai-log-${SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.getDefault()
        ).format(Date())}.txt"
    }

    fun write(context: Context, uri: Uri, logs: List<Triple<Long, String, Throwable?>>) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法打开导出文件")
        output.use {
            it.write(AppLog.formatLogs(logs).toByteArray(Charsets.UTF_8))
        }
    }
}
