package io.legado.app.utils

import android.provider.DocumentsContract
import android.system.Os
import io.legado.app.constant.AppLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** 完整生成后才提交；失败保留旧文件，SAF 重命名始终使用提供方返回的新 URI。 */
object ExportFileWriter {
    suspend fun save(
        parent: FileDoc,
        name: String,
        source: File,
        mime: String,
        onProgress: (done: Long, total: Long, verifying: Boolean) -> Unit = { _, _, _ -> },
    ): FileDoc {
        require(parent.isDir) { "导出目标不是目录：${parent.uri}" }
        validateName(name)
        require(source.isFile && source.length() > 0) { "导出文件为空：$name" }
        val id = UUID.randomUUID().toString()
        val stagingName = ".$id.${name.substringAfterLast('.', "tmp")}"
        val backupName = ".$id.old.${name.substringAfterLast('.', "tmp")}"
        val previous = parent.find(name)
        require(previous?.isDir != true) { "导出目标是目录：$name" }
        var staging = parent.createFileIfNotExistWithMime(stagingName, mime)
        var backup: FileDoc? = null
        var committed = false
        try {
            val context = currentCoroutineContext()
            val digest = MessageDigest.getInstance("SHA-256")
            val expectedSize = source.length()
            var lastProgressAt = 0L
            fun report(done: Long, verifying: Boolean) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 350L || done == expectedSize) {
                    lastProgressAt = now
                    onProgress(done, expectedSize, verifying)
                }
            }
            var copied = 0L
            staging.openOutputStream(truncate = true).getOrThrow().use { output ->
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        report(copied, false)
                    }
                }
                output.flush()
                if (!parent.isContentScheme && output is FileOutputStream) output.fd.sync()
            }
            check(copied == expectedSize && source.length() == expectedSize) {
                "导出源文件在保存期间发生变化：$name"
            }
            val persistedDigest = MessageDigest.getInstance("SHA-256")
            var persisted = 0L
            staging.openInputStream().getOrThrow().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    persistedDigest.update(buffer, 0, count)
                    persisted += count
                    report(persisted, true)
                }
            }
            check(persisted == copied && digest.digest().contentEquals(persistedDigest.digest())) {
                "导出目标回读校验失败：$name"
            }
            context.ensureActive()
            if (!parent.isContentScheme) {
                val target = File(requireNotNull(parent.asFile()), name)
                Os.rename(requireNotNull(staging.asFile()).absolutePath, target.absolutePath)
                committed = true
                return FileDoc.fromFile(target)
            }
            // SingleDocumentFile.renameTo 不支持重命名；DocumentsContract 支持树下的文档 URI。
            if (previous != null) backup = rename(previous, backupName)
            staging = rename(staging, name)
            check(FileDoc.fromUri(staging.uri, false).name == name) {
                "文档提供方未保留导出文件名：$name"
            }
            committed = true
            backup?.let { old ->
                runCatching { deleteChecked(old) }.onFailure {
                    AppLog.put("导出已保存，但旧文件备份清理失败：${old.uri}", it)
                }
            }
            return staging
        } catch (error: Throwable) {
            if (!committed) {
                runCatching { deleteChecked(staging) }.onFailure(error::addSuppressed)
                backup?.let { old ->
                    runCatching { rename(old, name) }.onFailure { rollback ->
                        error.addSuppressed(rollback)
                        AppLog.put("导出恢复旧文件失败，备份保留于 ${old.uri}", rollback)
                    }
                }
            }
            throw error
        }
    }

    fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name == File(name).name && '/' !in name && '\\' !in name) {
            "导出文件名无效：$name"
        }
    }

    private fun rename(document: FileDoc, name: String): FileDoc {
        val uri = DocumentsContract.renameDocument(appCtx.contentResolver, document.uri, name)
            ?: error("文档提供方无法重命名导出文件：${document.uri} → $name")
        // 先保留返回的身份，不能在成功重命名后因查询元数据失败而丢失回滚目标。
        return document.copy(name = name, uri = uri)
    }

    private fun deleteChecked(document: FileDoc) {
        val deleted = if (document.isContentScheme) {
            DocumentsContract.deleteDocument(appCtx.contentResolver, document.uri)
        } else {
            val file = requireNotNull(document.asFile())
            file.delete() || !file.exists()
        }
        check(deleted) { "无法删除导出临时文件：${document.uri}" }
    }
}
