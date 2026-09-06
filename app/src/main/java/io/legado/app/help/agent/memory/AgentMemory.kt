package io.legado.app.help.agent.memory

import io.legado.app.data.agent.AgentVector
import io.legado.app.help.agent.AgentConfig
import io.legado.app.help.agent.AgentControl
import io.legado.app.help.agent.AgentHttp
import io.legado.app.help.agent.AgentStore
import io.legado.app.help.agent.mcp.AgentCapabilities
import io.legado.app.help.agent.mcp.AgentTool
import io.legado.app.help.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.sqrt

object AgentMemory {
    private const val NAMESPACE = "memory"
    private data class Embedding(val space: String, val values: DoubleArray)

    private fun embedding(text: String, control: AgentControl): Embedding {
        val config = AgentConfig.moduleSettings("memory")
        val providerId = config.getString("providerId")
        val model = config.getString("model")
        require(providerId.isNotBlank() && model.isNotBlank()) { "记忆嵌入配置缺失：请在工具设置中选择供应商与嵌入模型" }
        val provider = AppConfig.aiProviderList.singleOrNull { it.id == providerId } ?: error("嵌入供应商不存在：$providerId")
        val request = AgentHttp.providerRequest(provider, "embeddings", JSONObject().put("model", model).put("input", text))
        val response = AgentHttp.exchange(request, control)
        check(response.getInt("status") in 200..299) { "嵌入 HTTP ${response.getInt("status")}：${response.optString("body")}" }
        require(!response.getBoolean("stream")) { "嵌入接口返回了未声明支持的 SSE" }
        val json = JSONObject(response.getString("body"))
        json.optJSONObject("error")?.let { error("嵌入失败：$it") }
        val data = json.getJSONArray("data")
        require(data.length() == 1) { "单条嵌入请求返回 ${data.length()} 条记录" }
        val vector = values(data.getJSONObject(0).getJSONArray("embedding"))
        return Embedding("$providerId|${request.getString("url")}|$model", vector)
    }

    private fun values(array: JSONArray): DoubleArray {
        require(array.length() > 0) { "向量不能为空" }
        val values = DoubleArray(array.length()) { array.getDouble(it) }
        require(values.all { it.isFinite() } && values.any { it != 0.0 }) { "嵌入包含非有限数值或全零向量" }
        return values
    }

    private fun cosine(left: DoubleArray, right: DoubleArray): Double {
        require(left.size == right.size) { "向量维度不一致：${left.size}/${right.size}，请重建索引" }
        var product = 0.0; var leftNorm = 0.0; var rightNorm = 0.0
        left.indices.forEach { index ->
            product += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        return product / sqrt(leftNorm * rightNorm)
    }

    private fun search(arguments: JSONObject, control: AgentControl): JSONObject {
        val query = arguments.getString("query")
        require(query.isNotBlank()) { "记忆检索 query 不能为空" }
        val scope = arguments.getString("scope")
        val documents = AgentStore.dao.documents(NAMESPACE).filter { JSONObject(it.json).getString("scope") == scope }
        val mode = arguments.optString("mode", "vector")
        val scores = when (mode) {
            "keyword" -> documents.filter { JSONObject(it.json).getString("content").contains(query, true) }.associate { it.key to 1.0 }
            "vector" -> {
                val queryVector = embedding(query, control)
                val vectors = AgentStore.dao.vectors(NAMESPACE).associateBy { it.documentKey }
                documents.associate { document ->
                    control.check()
                    val vector = vectors[document.key] ?: error("记忆 ${document.key} 缺少向量，请明确重建索引")
                    require(vector.space == queryVector.space && vector.dimension == queryVector.values.size && vector.contentRevision == document.revision) {
                        "记忆 ${document.key} 的嵌入模型、维度或内容修订不匹配；请重建索引，不能混算向量空间"
                    }
                    document.key to cosine(queryVector.values, values(JSONArray(vector.json)))
                }
            }
            else -> error("未知记忆检索模式：$mode")
        }
        val matches = documents.filter { scores.containsKey(it.key) }.map {
            JSONObject(it.json).put("id", it.key).put("revision", it.revision).put("score", scores.getValue(it.key))
        }
        return JSONObject().put("ok", true).put("mode", mode).put("scope", scope).put("matches", JSONArray(matches))
            .put("total", documents.size).put("complete", true)
    }

    private fun save(arguments: JSONObject, control: AgentControl, update: Boolean): JSONObject {
        val id = if (update) arguments.getString("id") else UUID.randomUUID().toString()
        val previous = AgentStore.dao.document(NAMESPACE, id)
        if (update) require(previous != null) { "记忆不存在：$id" }
        val content = arguments.getString("content")
        require(content.isNotBlank()) { "记忆内容不能为空" }
        val document = if (previous != null) JSONObject(previous.json) else JSONObject()
        listOf("type", "scope", "bookUrl", "chapterIndex", "sourceMessageId").forEach { key -> if (arguments.has(key)) document.put(key, arguments.get(key)) }
        require(document.getString("scope").isNotBlank()) { "记忆 scope 不能为空" }
        require(document.getString("type").isNotBlank()) { "记忆 type 不能为空" }
        val source = arguments.optString("source", "manual")
        require(source in setOf("manual", "automatic")) { "未知记忆来源：$source" }
        if (source == "automatic") require(AgentConfig.moduleSettings("memory").getBoolean("autoSave")) { "自动记忆保存已关闭" }
        document.put("content", content).put("source", source).put("updatedAt", System.currentTimeMillis())
        if (previous == null) document.put("createdAt", System.currentTimeMillis())
        val vector = embedding(content, control)
        control.check()
        var revision = 0L
        AgentStore.database.runInTransaction {
            if (previous != null) AgentStore.put("memory.revisions", "$id@${previous.revision}", JSONObject(previous.json))
            revision = AgentStore.put(NAMESPACE, id, document, previous?.revision ?: 0L)
            AgentStore.dao.put(AgentVector(NAMESPACE, id, vector.space, vector.values.size, revision, JSONArray(vector.values.toList()).toString()))
        }
        return JSONObject().put("ok", true).put("id", id).put("revision", revision).put("document", document)
    }

    fun rebuild(control: AgentControl): JSONObject {
        val documents = AgentStore.dao.documents(NAMESPACE)
        val vectors = documents.map { document ->
            control.check()
            val embedded = embedding(JSONObject(document.json).getString("content"), control)
            AgentVector(NAMESPACE, document.key, embedded.space, embedded.values.size, document.revision, JSONArray(embedded.values.toList()).toString())
        }
        require(vectors.map { it.space to it.dimension }.distinct().size <= 1) { "重建期间嵌入配置或模型维度发生变化" }
        AgentStore.database.runInTransaction {
            require(AgentStore.dao.documents(NAMESPACE).map { it.key to it.revision } == documents.map { it.key to it.revision }) { "重建期间记忆被修改，请重新发起" }
            AgentStore.dao.clearVectors(NAMESPACE)
            vectors.forEach { AgentStore.dao.put(it) }
        }
        return JSONObject().put("ok", true).put("indexed", vectors.size).put("complete", true)
    }

    fun vectorOperation(operation: String, namespace: String, arguments: JSONObject): JSONObject {
        val space = arguments.getString("space")
        val vector = values(arguments.getJSONArray("vector"))
        return when (operation) {
            "vectors.put" -> {
                val key = arguments.getString("key")
                AgentStore.database.runInTransaction {
                    val document = AgentStore.dao.document(namespace, key) ?: error("向量关联文档不存在：$namespace/$key")
                    require(document.revision == arguments.getLong("contentRevision")) { "向量内容修订不一致" }
                    AgentStore.dao.put(AgentVector(namespace, key, space, vector.size, document.revision, JSONArray(vector.toList()).toString()))
                }
                JSONObject().put("ok", true)
            }
            "vectors.search" -> JSONObject().put("matches", JSONArray().apply {
                AgentStore.dao.vectors(namespace).forEach { record ->
                    require(record.space == space && record.dimension == vector.size) { "向量空间不一致，请重建索引" }
                    val document = AgentStore.dao.document(namespace, record.documentKey) ?: error("向量文档已删除")
                    require(document.revision == record.contentRevision) { "向量修订过期" }
                    put(JSONObject().put("key", record.documentKey).put("score", cosine(vector, values(JSONArray(record.json)))).put("value", JSONObject(document.json)))
                }
            }).put("complete", true)
            else -> error("未知向量操作：$operation")
        }
    }

    fun tools(): List<AgentTool> {
        fun props(vararg entries: Pair<String, String>) = JSONObject().apply { entries.forEach { put(it.first, AgentCapabilities.property(it.second)) } }
        val document = props("id" to "string", "content" to "string", "type" to "string", "scope" to "string", "source" to "string",
            "bookUrl" to "string", "chapterIndex" to "integer", "sourceMessageId" to "string")
        return listOf(
            AgentCapabilities.tool("memory", "memory_search", "向量或显式关键词检索全部记忆候选及分数；模式插件负责召回排序和上下文预算", props("query" to "string", "mode" to "string", "scope" to "string"), listOf("query", "scope")) { args, control -> search(args, control) },
            AgentCapabilities.tool("memory", "memory_add", "保存记忆及同事务向量，标记 manual/automatic 来源", document, listOf("content", "scope", "type")) { args, control -> save(args, control, false) },
            AgentCapabilities.tool("memory", "memory_update", "修改记忆并重算向量，保留旧修订", document, listOf("id", "content")) { args, control -> save(args, control, true) },
            AgentCapabilities.tool("memory", "memory_delete", "删除记忆及向量，保留带删除标记的修订审计", props("id" to "string"), listOf("id")) { args, _ ->
                val id = args.getString("id")
                AgentStore.database.runInTransaction {
                    val previous = AgentStore.dao.document(NAMESPACE, id) ?: error("记忆不存在：$id")
                    AgentStore.put("memory.revisions", "$id@${previous.revision}", JSONObject(previous.json).put("deletedAt", System.currentTimeMillis()))
                    AgentStore.dao.deleteDocument(NAMESPACE, id); AgentStore.dao.deleteVector(NAMESPACE, id)
                }
                JSONObject().put("ok", true)
            },
            AgentCapabilities.tool("memory", "memory_rebuild_index", "使用当前明确配置的嵌入模型重建全部索引；全部成功才原子替换", JSONObject()) { _, control -> rebuild(control) }
        )
    }
}
