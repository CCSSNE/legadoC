package io.legado.app.help.book

import io.legado.app.data.entities.Book
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TXT 末尾或 ZIP 内的自描述导出报告。内容收集失败以最小单元记录，不能阻断其它已有缓存出包。
 */
class BookExportReport(
    private val book: Book,
    private val outputName: String,
) {
    companion object {
        // 不能使用 .txt；否则 ZIP 导入会把报告识别成第二本书。
        const val FILE_NAME = "导出报告.log"
    }

    private data class Counter(var expected: Int = 0, var exported: Int = 0)

    data class Issue(
        val component: String,
        val item: String,
        val reason: String,
    )

    private val counters = linkedMapOf<String, Counter>()
    private val issues = arrayListOf<Issue>()
    private val createdAt = System.currentTimeMillis()

    @Synchronized
    fun expect(component: String, count: Int = 1) {
        counters.getOrPut(component, ::Counter).expected += count
    }

    @Synchronized
    fun exported(component: String, count: Int = 1) {
        counters.getOrPut(component, ::Counter).exported += count
    }

    @Synchronized
    fun issue(component: String, item: String, error: Throwable) {
        issues += Issue(component, item, error.describe())
    }

    @Synchronized
    fun issue(component: String, item: String, reason: String) {
        issues += Issue(component, item, reason.ifBlank { "原因未知" })
    }

    suspend fun <T> capture(component: String, item: String, block: suspend () -> T): T? {
        return try {
            block().also { result ->
                if (result != null) exported(component)
            }
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            issue(component, item, error)
            null
        }
    }

    fun writeTo(directory: File): File {
        return File(directory, FILE_NAME).also { file ->
            file.bufferedWriter(Charsets.UTF_8).use(::writeTo)
        }
    }

    fun writeAppendix(writer: Writer) {
        writer.append("\n\n================ 导出报告 ================\n")
        writeTo(writer)
    }

    @Synchronized
    fun summary(): String {
        return if (issues.isEmpty()) "导出完成，导出报告无缺失记录" else
            "导出完成，导出报告记录 ${issues.size} 项缺失或失败"
    }

    @Synchronized
    private fun writeTo(writer: Writer) {
        writer.appendLine("阅读C 导出报告")
        writer.appendLine("书名：${book.name}")
        writer.appendLine("作者：${book.author}")
        writer.appendLine("输出：$outputName")
        writer.appendLine(
            "时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT)
                .format(Date(createdAt))
        )
        writer.appendLine("状态：${if (issues.isEmpty()) "未记录缺失" else "存在缺失或失败"}")
        writer.appendLine()
        writer.appendLine("导出统计：")
        if (counters.isEmpty()) {
            writer.appendLine("- 无可统计的数据项")
        } else {
            counters.forEach { (component, counter) ->
                val failed = (counter.expected - counter.exported).coerceAtLeast(0)
                writer.appendLine(
                    "- $component：计划 ${counter.expected}，导出 ${counter.exported}，未导出 $failed"
                )
            }
        }
        writer.appendLine()
        writer.appendLine("缺失与失败：")
        if (issues.isEmpty()) {
            writer.appendLine("- 无")
        } else {
            issues.forEachIndexed { index, issue ->
                writer.appendLine("${index + 1}. [${issue.component}] ${issue.item}")
                writer.appendLine("   原因：${issue.reason}")
            }
        }
        writer.appendLine()
        writer.appendLine("说明：本文件只描述本次导出时实际读到并写入导出文件的数据；缺失项没有被伪装成成功。")
    }

    private fun Throwable.describe(): String {
        val parts = generateSequence(this) { it.cause }
            .take(4)
            .map { error ->
                val name = error::class.java.simpleName.ifBlank { error::class.java.name }
                "$name: ${error.localizedMessage?.trim().orEmpty().ifBlank { "无错误说明" }}"
            }
            .distinct()
            .toList()
        return parts.joinToString(" <- ")
    }
}
