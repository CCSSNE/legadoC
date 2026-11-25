package io.legado.app.help.tts

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 角色分析日志：记录 AI 分镜/角色识别/自动选音全链路，供「AI 角色 → 角色分析日志」查看。
 *
 * 关注点是「哪里不工作」：模型请求是否发出、返回多少字符、校验为什么失败、
 * 角色是否被采纳、候选音色几个、分配结果与置信度过滤情况。
 * 同样只做环形缓冲，不落盘、不外发。
 */
object CastingDebugLog {

    private const val MAX_ENTRIES = 400

    private data class Entry(val time: Long, val tag: String, val message: String)

    private val entries = ArrayDeque<Entry>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun add(tag: String, message: String) {
        synchronized(entries) {
            entries.addLast(Entry(System.currentTimeMillis(), tag, message))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun dump(): String {
        val snapshot = synchronized(entries) { entries.toList() }
        if (snapshot.isEmpty()) return "（暂无角色分析日志）"
        return snapshot.joinToString("\n") { entry ->
            "${formatter.format(Date(entry.time))} [${entry.tag}] ${entry.message}"
        }
    }
}
