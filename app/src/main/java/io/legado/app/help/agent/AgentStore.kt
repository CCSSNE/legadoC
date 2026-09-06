package io.legado.app.help.agent

import io.legado.app.data.agent.AgentDatabase
import io.legado.app.data.agent.AgentDocument
import io.legado.app.data.agent.AgentEvent
import org.json.JSONArray
import org.json.JSONObject

object AgentStore {
    val database get() = AgentDatabase.instance
    val dao get() = database.agentDao()
    fun get(namespace: String, key: String): JSONObject? =
        dao.document(namespace, key)?.let { JSONObject(it.json) }
    fun list(namespace: String): JSONArray = JSONArray().apply {
        dao.documents(namespace).forEach {
            put(JSONObject().put("key", it.key).put("revision", it.revision)
                .put("updatedAt", it.updatedAt).put("value", JSONObject(it.json)))
        }
    }
    fun put(namespace: String, key: String, value: JSONObject, expectedRevision: Long? = null): Long {
        require(namespace.isNotBlank() && key.isNotBlank()) { "文档 namespace/key 不能为空" }
        var revision = 0L
        database.runInTransaction {
            val previous = dao.document(namespace, key)
            check(expectedRevision == null || expectedRevision == (previous?.revision ?: 0L)) {
                "文档修订冲突：$namespace/$key"
            }
            revision = (previous?.revision ?: 0L) + 1
            dao.put(AgentDocument(namespace, key, value.toString(), revision))
        }
        return revision
    }
    fun transaction(operations: JSONArray) {
        database.runInTransaction {
            for (index in 0 until operations.length()) {
                val operation = operations.getJSONObject(index)
                val namespace = operation.getString("namespace")
                val key = operation.getString("key")
                when (operation.getString("action")) {
                    "put" -> put(namespace, key, operation.getJSONObject("value"),
                        if (operation.has("revision")) operation.getLong("revision") else null)
                    "delete" -> {
                        val previous = dao.document(namespace, key)
                        check(!operation.has("revision") || operation.getLong("revision") == (previous?.revision ?: 0L)) { "文档修订冲突：$namespace/$key" }
                        dao.deleteDocument(namespace, key)
                        dao.deleteVector(namespace, key)
                    }
                    else -> error("未知文档事务操作：${operation.getString("action")}")
                }
            }
        }
    }
    fun event(runId: String, type: String, value: JSONObject): AgentEvent {
        val event = AgentEvent(runId = runId, type = type, json = AgentDiagnostics.protect(value).toString())
        return event.copy(sequence = dao.append(event))
    }
}
