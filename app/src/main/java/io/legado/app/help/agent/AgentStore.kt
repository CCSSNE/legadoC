package io.legado.app.help.agent

import io.legado.app.data.agent.AgentDatabase
import io.legado.app.data.agent.AgentDocument
import io.legado.app.data.agent.AgentEvent
import io.legado.app.data.agent.AgentDao
import io.legado.app.data.agent.AgentRun
import io.legado.app.data.agent.AgentMessage
import io.legado.app.data.agent.AgentVector
import io.legado.app.data.agent.AgentPayloadStore
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
        override fun document(namespace: String, key: String) = io { rawDao.document(namespace, key)?.decode() }
        override fun documents(namespace: String) = io { rawDao.documents(namespace).map(AgentDocument::decode) }
        override fun allDocuments() = io { rawDao.allDocuments().map(AgentDocument::decode) }
        override fun put(document: AgentDocument) = io {
            AgentConfig.validateDocument(document.namespace, document.key, JSONObject(document.json))
            require(document.namespace.isNotBlank() && document.key.isNotBlank() && document.revision > 0) { "文档标识或修订无效" }
            rawDao.put(document.encode())
        }
        override fun deleteDocument(namespace: String, key: String) = io { rawDao.deleteDocument(namespace, key) }
        override fun put(run: AgentRun) = io { rawDao.put(run.encode()) }
        override fun run(id: String) = io { rawDao.run(id)?.decode() }
        override fun runs() = io { rawDao.runs().map(AgentRun::decode) }
        override fun state(id: String, state: String, error: String?, now: Long) = io {
            rawDao.state(id, state, error?.let(AgentPayloadStore::encode), now)
        }
        override fun unfinished() = io { rawDao.unfinished().map(AgentRun::decode) }
        override fun deleteRun(id: String) = io { rawDao.deleteRun(id) }
        override fun append(event: AgentEvent) = io { rawDao.append(event.encode()) }
        override fun events(runId: String, after: Long) = io { rawDao.events(runId, after).map(AgentEvent::decode) }
        override fun allEvents() = io { rawDao.allEvents().map(AgentEvent::decode) }
        override fun deleteEvents(runId: String) = io { rawDao.deleteEvents(runId) }
        override fun append(message: AgentMessage) = io { rawDao.append(message.encode()) }
        override fun messages(sessionId: String) = io { rawDao.messages(sessionId).map(AgentMessage::decode) }
        override fun allMessages() = io { rawDao.allMessages().map(AgentMessage::decode) }
        override fun deleteMessages(sessionId: String) = io { rawDao.deleteMessages(sessionId) }
        override fun deleteRunMessages(sessionId: String, runId: String) = io { rawDao.deleteRunMessages(sessionId, runId) }
        override fun put(vector: AgentVector) = io { rawDao.put(vector.encode()) }
        override fun vectors(namespace: String) = io { rawDao.vectors(namespace).map(AgentVector::decode) }
        override fun allVectors() = io { rawDao.allVectors().map(AgentVector::decode) }
        override fun deleteVector(namespace: String, key: String) = io { rawDao.deleteVector(namespace, key) }
        override fun clearVectors(namespace: String) = io { rawDao.clearVectors(namespace) }
        override fun clearDocuments() = io { rawDao.clearDocuments() }
        override fun clearRuns() = io { rawDao.clearRuns() }
        override fun clearEvents() = io { rawDao.clearEvents() }
        override fun clearMessages() = io { rawDao.clearMessages() }
        override fun clearAllVectors() = io { rawDao.clearAllVectors() }
    }

    private fun AgentDocument.encode() = copy(json = AgentPayloadStore.encode(json))
    private fun AgentDocument.decode() = copy(json = AgentPayloadStore.decode(json))
    private fun AgentRun.encode() = copy(input = AgentPayloadStore.encode(input), error = error?.let(AgentPayloadStore::encode))
    private fun AgentRun.decode() = copy(input = AgentPayloadStore.decode(input), error = error?.let(AgentPayloadStore::decode))
    private fun AgentEvent.encode() = copy(json = AgentPayloadStore.encode(json))
    private fun AgentEvent.decode() = copy(json = AgentPayloadStore.decode(json))
    private fun AgentMessage.encode() = copy(json = AgentPayloadStore.encode(json))
    private fun AgentMessage.decode() = copy(json = AgentPayloadStore.decode(json))
    private fun AgentVector.encode() = copy(json = AgentPayloadStore.encode(json))
    private fun AgentVector.decode() = copy(json = AgentPayloadStore.decode(json))
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
