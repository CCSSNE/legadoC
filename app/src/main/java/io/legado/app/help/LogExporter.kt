package io.legado.app.help

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 普通日志 / AI 日志导出为 UTF-8 文本文件的统一入口 */
object LogExporter {

    fun fileName(prefix: String): String {
        return "$prefix-log-${SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.getDefault()
        ).format(Date())}.txt"
    }

    fun write(context: Context, uri: Uri, logs: List<AppLog.Entry>) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法打开导出文件")
        output.use {
            it.write(AppLog.formatLogs(logs).toByteArray(Charsets.UTF_8))
        }
    }
}
