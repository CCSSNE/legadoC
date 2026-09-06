package io.legado.app.help.agent

import io.legado.app.data.agent.AgentDatabase
import io.legado.app.data.agent.AgentDocument
import io.legado.app.data.agent.AgentEvent
import io.legado.app.data.agent.AgentDao
import io.legado.app.data.agent.AgentRun
import io.legado.app.data.agent.AgentMessage
import io.legado.app.data.agent.AgentVector
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

object AgentStore {
    // Synchronous preference/script bridges share one IO owner. Nested transactions stay
    // on that owner, so no SQLite operation runs on the Android main thread.
    private val onStorageThread = ThreadLocal<Boolean>()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AgentStorage").apply { isDaemon = true }
    }
    fun <T> io(action: () -> T): T {
        if (onStorageThread.get() == true) return action()
        try {
            return executor.submit(Callable {
                onStorageThread.set(true)
                try { action() } finally { onStorageThread.remove() }
            }).get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
    object database {
        fun runInTransaction(action: () -> Unit) = io {
            AgentDatabase.instance.runInTransaction(Runnable(action))
        }
    }
    private val rawDao get() = AgentDatabase.instance.agentDao()
    val dao: AgentDao = object : AgentDao {
        override fun document(namespace: String, key: String) = io { rawDao.document(namespace, key) }
        override fun documents(namespace: String) = io { rawDao.documents(namespace) }
        override fun allDocuments() = io { rawDao.allDocuments() }
        override fun put(document: AgentDocument) = io {
            AgentConfig.validateDocument(document.namespace, document.key, JSONObject(document.json))
            require(document.namespace.isNotBlank() && document.key.isNotBlank() && document.revision > 0) { "文档标识或修订无效" }
            rawDao.put(document)
        }
        override fun deleteDocument(namespace: String, key: String) = io { rawDao.deleteDocument(namespace, key) }
        override fun put(run: AgentRun) = io { rawDao.put(run) }
        override fun run(id: String) = io { rawDao.run(id) }
        override fun runs() = io { rawDao.runs() }
        override fun state(id: String, state: String, error: String?, now: Long) = io { rawDao.state(id, state, error, now) }
        override fun unfinished() = io { rawDao.unfinished() }
        override fun deleteRun(id: String) = io { rawDao.deleteRun(id) }
        override fun append(event: AgentEvent) = io { rawDao.append(event) }
        override fun events(runId: String, after: Long) = io { rawDao.events(runId, after) }
        override fun allEvents() = io { rawDao.allEvents() }
        override fun deleteEvents(runId: String) = io { rawDao.deleteEvents(runId) }
        override fun append(message: AgentMessage) = io { rawDao.append(message) }
        override fun messages(sessionId: String) = io { rawDao.messages(sessionId) }
        override fun allMessages() = io { rawDao.allMessages() }
        override fun deleteMessages(sessionId: String) = io { rawDao.deleteMessages(sessionId) }
        override fun deleteRunMessages(sessionId: String, runId: String) = io { rawDao.deleteRunMessages(sessionId, runId) }
        override fun put(vector: AgentVector) = io { rawDao.put(vector) }
        override fun vectors(namespace: String) = io { rawDao.vectors(namespace) }
        override fun allVectors() = io { rawDao.allVectors() }
        override fun deleteVector(namespace: String, key: String) = io { rawDao.deleteVector(namespace, key) }
        override fun clearVectors(namespace: String) = io { rawDao.clearVectors(namespace) }
        override fun clearDocuments() = io { rawDao.clearDocuments() }
        override fun clearRuns() = io { rawDao.clearRuns() }
        override fun clearEvents() = io { rawDao.clearEvents() }
        override fun clearMessages() = io { rawDao.clearMessages() }
        override fun clearAllVectors() = io { rawDao.clearAllVectors() }
    }
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
        val json = value.toString()
        AgentConfig.validateDocument(namespace, key, JSONObject(json))
        var revision = 0L
        database.runInTransaction {
            val previous = dao.document(namespace, key)
            check(expectedRevision == null || expectedRevision == (previous?.revision ?: 0L)) {
                "文档修订冲突：$namespace/$key"
            }
            revision = (previous?.revision ?: 0L) + 1
            dao.put(AgentDocument(namespace, key, json, revision))
        }
        return revision
    }
    fun update(namespace: String, key: String, transform: (JSONObject) -> JSONObject): Long {
        var revision = 0L
        database.runInTransaction {
            val previous = dao.document(namespace, key) ?: error("文档不存在：$namespace/$key")
            revision = put(namespace, key, transform(JSONObject(previous.json)), previous.revision)
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
        val event = AgentEvent(runId = runId, type = type, json = value.toString())
        return event.copy(sequence = dao.append(event))
    }
}
