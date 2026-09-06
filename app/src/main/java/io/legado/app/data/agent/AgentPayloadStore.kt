package io.legado.app.data.agent

import splitties.init.appCtx
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Agent 的 JSON 原文存储。
 *
 * SQLite 的 CursorWindow 对单行可返回数据有系统限制，不能把不受控的 JSON
 * 原文继续放在行字段里。这里把原文按内容寻址写入应用私有文件，数据库只保留
 * 短引用。旧版本数据不迁移，由 AgentConfig.SCHEMA_VERSION 失效后直接清空重建。
 */
object AgentPayloadStore {
    private const val REFERENCE_PREFIX = "\u0000agent-payload-v1:"
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val root: File get() = File(appCtx.filesDir, "agent/payloads")

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

    fun clear() {
        if (root.exists()) check(root.deleteRecursively()) { "无法清理 Agent 内容文件：$root" }
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
