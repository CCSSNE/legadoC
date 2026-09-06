package io.legado.app.data.agent

import android.content.ContentValues
import androidx.sqlite.db.SupportSQLiteDatabase
import splitties.init.appCtx
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Agent 的 JSON 原文存储。
 *
 * SQLite 的 CursorWindow 对单行可返回数据有系统限制，不能把不受控的 JSON
 * 原文继续放在行字段里。这里把原文按内容寻址写入应用私有文件，数据库只保留
 * 短引用；读取旧数据库时按小块读取，完整迁移，不会截断、拒绝或丢弃用户内容。
 */
object AgentPayloadStore {
    private const val REFERENCE_PREFIX = "\u0000agent-payload-v1:"
    private const val READ_CHUNK_CHARS = 64 * 1024
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val root: File get() = File(appCtx.filesDir, "agent/payloads")

    private data class Column(val table: String, val column: String)

    private val payloadColumns = listOf(
        Column("documents", "json"),
        Column("runs", "input"),
        Column("runs", "error"),
        Column("events", "json"),
        Column("messages", "json"),
        Column("vectors", "json")
    )

    fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val hash = sha256(bytes)
        writeIfAbsent(hash) { output -> output.write(bytes) }
        return reference(hash)
    }

    fun decode(value: String): String {
        if (!value.startsWith(REFERENCE_PREFIX)) return value
        val hash = value.removePrefix(REFERENCE_PREFIX)
        require(hashPattern.matches(hash)) { "Agent 内容引用损坏：$hash" }
        val file = payloadFile(hash)
        check(file.isFile) { "Agent 内容文件缺失：$hash" }
        return file.readText(Charsets.UTF_8)
    }

    /**
     * 将已有数据库中的原文迁移到文件。查询只取长度和短前缀，正文使用 substr
     * 分块读取，避免迁移本身再次触发 CursorWindow 的单行大小限制。
     */
    fun migrate(database: SupportSQLiteDatabase) {
        payloadColumns.forEach { column ->
            val pending = mutableListOf<Pair<Long, Long>>()
            val rows = database.query(
                "SELECT rowid, length(${column.column}), substr(${column.column}, 1, ?) " +
                    "FROM ${column.table}",
                arrayOf((REFERENCE_PREFIX.length + 64).toString())
            )
            rows.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(0)
                    val length = if (cursor.isNull(1)) null else cursor.getLong(1)
                    if (length == null) continue
                    val prefix = cursor.getString(2).orEmpty()
                    if (prefix.startsWith(REFERENCE_PREFIX)) {
                        val hash = prefix.removePrefix(REFERENCE_PREFIX)
                        require(hashPattern.matches(hash)) { "Agent 内容引用损坏：$hash" }
                        check(payloadFile(hash).isFile) { "Agent 内容文件缺失：$hash" }
                        continue
                    }
                    pending += rowId to length
                }
            }
            pending.forEach { (rowId, length) ->
                val reference = migrateRow(database, column, rowId, length)
                val values = ContentValues().apply { put(column.column, reference) }
                check(database.update(column.table, 0, values, "rowid = ?", arrayOf(rowId.toString())) == 1) {
                    "Agent 内容迁移未更新行：${column.table}#$rowId"
                }
            }
        }
    }

    fun clear() {
        if (root.exists()) check(root.deleteRecursively()) { "无法清理 Agent 内容文件：$root" }
    }

    private fun migrateRow(
        database: SupportSQLiteDatabase,
        column: Column,
        rowId: Long,
        length: Long
    ): String {
        val temporary = createTemporaryFile()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            temporary.outputStream().use { output ->
                var start = 1L
                while (start <= length) {
                    val cursor = database.query(
                        "SELECT substr(${column.column}, ?, ?) FROM ${column.table} WHERE rowid = ?",
                        arrayOf(start.toString(), READ_CHUNK_CHARS.toString(), rowId.toString())
                    )
                    cursor.use {
                        check(it.moveToFirst()) { "Agent 内容行不存在：${column.table}#$rowId" }
                        val chunk = it.getString(0) ?: error("Agent 内容块为空：${column.table}#$rowId@$start")
                        check(chunk.isNotEmpty()) { "Agent 内容迁移提前结束：${column.table}#$rowId@$start" }
                        val bytes = chunk.toByteArray(Charsets.UTF_8)
                        digest.update(bytes)
                        output.write(bytes)
                    }
                    start += READ_CHUNK_CHARS
                }
            }
            val hash = digest.digest().toHex()
            moveIntoStore(temporary, hash)
            return reference(hash)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun writeIfAbsent(hash: String, writer: (OutputStream) -> Unit) {
        val target = payloadFile(hash)
        if (target.isFile) return
        val temporary = createTemporaryFile()
        try {
            temporary.outputStream().use(writer)
            moveIntoStore(temporary, hash)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun createTemporaryFile(): File {
        check(root.exists() || root.mkdirs()) { "无法创建 Agent 内容目录：$root" }
        return File.createTempFile("payload-", ".tmp", root)
    }

    private fun moveIntoStore(temporary: File, hash: String) {
        val target = payloadFile(hash)
        if (target.isFile) return
        check(temporary.renameTo(target)) { "无法保存 Agent 内容文件：$hash" }
    }

    private fun payloadFile(hash: String): File {
        require(hashPattern.matches(hash)) { "Agent 内容摘要无效：$hash" }
        return File(root, hash)
    }

    private fun reference(hash: String) = REFERENCE_PREFIX + hash

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
