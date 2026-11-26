package io.legado.app.help.tts

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 朗读调试日志：环形缓冲最近 N 条，供「朗读设置 → 朗读日志」直接查看与复制。
 *
 * 只记录朗读链路的合成/播放关键点（引擎选择、音色、请求、首帧、完成、失败原因），
 * 不落盘、不外发；条数固定上限，避免长时间朗读把内存吃掉。
 */
object ReadAloudDebugLog {

    private const val MAX_ENTRIES = 400

    private data class Entry(val time: Long, val tag: String, val message: String)

    private val entries = ArrayDeque<Entry>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun add(tag: String, message: String) {
        synchronized(entries) {
            entries.addLast(Entry(System.currentTimeMillis(), tag, message))
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun dump(): String {
        val snapshot = synchronized(entries) { entries.toList() }
        if (snapshot.isEmpty()) return "（暂无朗读日志）"
        return snapshot.joinToString("\n") { entry ->
            "${formatter.format(Date(entry.time))} [${entry.tag}] ${entry.message}"
        }
    }
}
