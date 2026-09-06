package io.legado.app.help.agent

import io.legado.app.data.agent.*
import io.legado.app.service.AgentMcpService
import io.legado.app.utils.GSON
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AgentBackup {
    const val FILE_NAME = "agent.zip"

    fun write(target: File, initialize: Boolean = true) {
        if (initialize) AgentConfig.initialize()
        synchronized(AgentPlugins) {
            val state = JSONObject().put("version", 1)
            AgentStore.database.runInTransaction {
                val dao = AgentStore.dao
                state.put("documents", JSONArray(GSON.toJson(dao.allDocuments())))
                    .put("runs", JSONArray(GSON.toJson(dao.runs())))
                    .put("events", JSONArray(GSON.toJson(dao.allEvents())))
                    .put("messages", JSONArray(GSON.toJson(dao.allMessages())))
                    .put("vectors", JSONArray(GSON.toJson(dao.allVectors())))
            }
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            ZipOutputStream(target.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("state.json")); zip.write(state.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                AgentPlugins.root.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry("plugins/" + file.relativeTo(AgentPlugins.root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
    }

    fun requireIdle() {
        require(AgentRuntime.controls().isEmpty() && AgentMcpService.activeRequests() == 0) { "恢复 Agent 数据前请停止全部 Agent/MCP 调用" }
    }

    fun restore(source: File, initialize: Boolean = true) {
        AgentRuntime.restoreData { restoreContents(source, initialize) }
    }

    private fun restoreContents(source: File, initialize: Boolean) {
        requireIdle()
        val files = source.inputStream().use { AgentPlugins.readZip(it) }
        val state = JSONObject(AgentPlugins.text(files["state.json"] ?: error("备份缺少 state.json")))
        require(state.getInt("version") == 1) { "不支持的 Agent 备份版本" }
        fun <T> read(name: String, type: Class<T>): List<T> {
            val array = state.getJSONArray(name)
            return (0 until array.length()).map { GSON.fromJson(array.getJSONObject(it).toString(), type) }
        }
        val documents = read("documents", AgentDocument::class.java)
        val runs = read("runs", AgentRun::class.java)
        val events = read("events", AgentEvent::class.java)
        val messages = read("messages", AgentMessage::class.java)
        val vectors = read("vectors", AgentVector::class.java)
        require(documents.map { it.namespace to it.key }.distinct().size == documents.size) { "备份文档 ID 重复" }
        require(vectors.map { it.namespace to it.documentKey }.distinct().size == vectors.size) { "备份向量 ID 重复" }
        val indexedDocuments = documents.associateBy { it.namespace to it.key }
        vectors.forEach { vector ->
            val document = indexedDocuments[vector.namespace to vector.documentKey] ?: error("备份向量缺少关联文档")
            val data = JSONArray(vector.json)
            require(vector.dimension > 0 && vector.dimension == data.length() && vector.contentRevision == document.revision && vector.space.isNotBlank()) { "备份向量维度、空间或内容版本损坏" }
            require((0 until data.length()).all { data.getDouble(it).isFinite() }) { "备份向量包含非有限值" }
        }
        require(runs.map { it.id }.distinct().size == runs.size && events.map { it.sequence }.distinct().size == events.size && messages.map { it.sequence }.distinct().size == messages.size) { "备份运行/事件/消息 ID 重复" }
        // 破坏性升级：旧版本备份直接拒绝，不混入当前数据，当前数据未动。
        val backupVersion = documents.singleOrNull { it.namespace == "migration" && it.key == "v1" }?.let { JSONObject(it.json).optInt("schemaVersion", -1) }
        require(backupVersion == AgentConfig.SCHEMA_VERSION) { "备份 Agent 配置版本过旧（$backupVersion），已放弃恢复，当前数据未动" }
        documents.forEach { JSONObject(it.json) }
        documents.filter { it.namespace == "plugin.revisions" }.forEach { document ->
            val id = document.key.substringBefore('@')
            val revision = document.key.substringAfter('@')
            val manifest = JSONObject(document.json)
            require(manifest.getInt("hostApiVersion") == AgentPlugins.API_VERSION) { "插件 $id API 不受支持" }
            require(files.containsKey("plugins/$id/$revision/${AgentPlugins.path(manifest.getString("entry"))}")) { "备份插件入口缺失：${document.key}" }
        }
        AgentMcpService.stopListeners()
        synchronized(AgentPlugins) {
            files.filterKeys { it.startsWith("plugins/") }.forEach { (name, bytes) ->
                val target = File(AgentPlugins.root, name.removePrefix("plugins/"))
                require(target.canonicalPath.startsWith(AgentPlugins.root.canonicalPath + File.separator)) { "备份路径越界：$name" }
                if (target.exists()) require(target.readBytes().contentEquals(bytes)) { "不可变修订内容冲突：$name" }
                else { check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()); target.writeBytes(bytes) }
            }
            AgentStore.database.runInTransaction {
                val dao = AgentStore.dao
                dao.clearDocuments(); dao.clearRuns(); dao.clearEvents(); dao.clearMessages(); dao.clearAllVectors()
                documents.forEach { dao.put(it) }
                runs.forEach { dao.put(it) }
                events.forEach { dao.append(it) }
                messages.forEach { dao.append(it) }
                vectors.forEach { dao.put(it) }
            }
        }
        AgentMigration.resetAfterRestore()
        if (initialize) AgentMigration.ensure()
    }
}
