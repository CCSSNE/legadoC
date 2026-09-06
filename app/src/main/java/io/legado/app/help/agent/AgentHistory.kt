package io.legado.app.help.agent

import io.legado.app.ui.book.read.ReadAiBookHistory
import io.legado.app.ui.main.ai.AiChatSession
import io.legado.app.utils.GSON
import org.json.JSONArray
import org.json.JSONObject

object AgentHistory {
    var chat: List<AiChatSession>
        get() { AgentMigration.ensure(); return AgentStore.dao.documents("history.chat").map { GSON.fromJson(it.json, AiChatSession::class.java) }.sortedByDescending { it.updatedAt } }
        set(value) = replace("history.chat", value.associate { it.id to JSONObject(GSON.toJson(it)) }, value.size)
    var reading: List<ReadAiBookHistory>
        get() { AgentMigration.ensure(); return AgentStore.dao.documents("history.read").map { GSON.fromJson(it.json, ReadAiBookHistory::class.java) }.sortedByDescending { it.updatedAt } }
        set(value) = replace("history.read", value.associate { it.bookUrl to JSONObject(GSON.toJson(it)) }, value.size)
    var currentChat: String?
        get() { AgentMigration.ensure(); return AgentStore.get("config", "chat.current")?.getString("id") }
        set(value) {
            AgentMigration.ensure()
            AgentStore.database.runInTransaction {
                if (value == null) AgentStore.dao.deleteDocument("config", "chat.current")
                else {
                    require(AgentStore.get("history.chat", value) != null) { "当前聊天会话不存在：$value" }
                    AgentStore.put("config", "chat.current", JSONObject().put("id", value))
                }
            }
        }
    private fun replace(namespace: String, records: Map<String, JSONObject>, size: Int) {
        AgentMigration.ensure()
        require(records.size == size && records.keys.none { it.isBlank() }) { "历史 ID 为空或重复" }
        AgentStore.database.runInTransaction {
            val originals = AgentStore.dao.documents(namespace)
            val previous = sessions(namespace, originals.associate { it.key to JSONObject(it.json) })
            val next = sessions(namespace, records)
            originals.forEach { document ->
                if (records[document.key]?.toString() != document.json) {
                    AgentStore.put("history.revisions.$namespace", "${document.key}@${document.revision}",
                        JSONObject().put("value", JSONObject(document.json)).put("revision", document.revision)
                            .put("changedAt", System.currentTimeMillis()).put("deleted", document.key !in records))
                }
            }
            previous.forEach { (sessionId, old) -> reconcile(sessionId, old, next[sessionId]) }
            AgentStore.dao.documents(namespace).filter { it.key !in records }.forEach { AgentStore.dao.deleteDocument(namespace, it.key) }
            records.forEach { (key, value) -> AgentStore.put(namespace, key, value) }
            if (namespace == "history.chat") {
                val current = AgentStore.get("config", "chat.current")?.getString("id")
                if (current != null && current !in records) AgentStore.dao.deleteDocument("config", "chat.current")
            }
        }
    }

    private fun sessions(namespace: String, records: Map<String, JSONObject>): Map<String, JSONArray> = buildMap {
        records.forEach { (key, value) ->
            fun addSession(id: String, messages: JSONArray) {
                require(id !in keys) { "历史会话 ID 重复：$id" }
                val ids = mutableSetOf<String>()
                for (index in 0 until messages.length()) {
                    val message = messages.getJSONObject(index)
                    require(message.getString("id").isNotBlank() && ids.add(message.getString("id"))) { "$id 消息 ID 重复或为空" }
                    require(message.getString("role") in setOf("USER", "ASSISTANT")) { "$id 消息角色无效" }
                    message.getString("content")
                }
                put(id, messages)
            }
            if (namespace == "history.chat") addSession("chat:$key", value.getJSONArray("messages"))
            else {
                val sessions = value.getJSONArray("sessions")
                for (index in 0 until sessions.length()) {
                    val session = sessions.getJSONObject(index)
                    require(session.getString("id").isNotBlank()) { "阅读会话 ID 为空" }
                    addSession("read:$key:${session.getString("id")}", session.getJSONArray("messages"))
                }
            }
        }
    }

    private fun reconcile(sessionId: String, old: JSONArray, next: JSONArray?) {
        fun texts(messages: JSONArray): Map<String, JSONObject> = (0 until messages.length()).map { messages.getJSONObject(it) }
            .filter { it.optString("kind") != "STATUS" }.associateBy { it.getString("id") }
        if (next == null) {
            check(!AgentRuntime.isRunning(sessionId)) { "删除会话前请先停止任务" }
            AgentStore.put("history.deleted", sessionId, JSONObject().put("deletedAt", System.currentTimeMillis())
                .put("messages", JSONArray(AgentStore.dao.messages(sessionId).map { JSONObject(it.json) })))
            AgentStore.dao.deleteMessages(sessionId)
            AgentStore.dao.deleteDocument("history.origin", sessionId)
            return
        }
        val current = texts(next)
        val changed = texts(old).filter { (id, before) ->
            val after = current[id]
            !before.optBoolean("pending") && (after == null || before.getString("content") != after.getString("content") || before.getString("role") != after.getString("role"))
        }.keys
        if (changed.isEmpty()) return
        check(!AgentRuntime.isRunning(sessionId)) { "编辑或删除历史消息前请先停止任务" }
        val runs = AgentStore.dao.runs().filter { it.sessionId == sessionId }.associateBy { it.id }
        AgentStore.dao.messages(sessionId).groupBy { it.runId }.forEach { (runId, messages) ->
            val input = runs[runId]?.let { JSONObject(it.input) }
            val ids = if (input == null && runId.startsWith("legacy:")) listOf(runId.removePrefix("legacy:"))
                else listOfNotNull(input?.optString("messageId"), input?.optString("assistantMessageId")).filter { it.isNotBlank() }
            if (ids.none { it in changed }) return@forEach
            AgentStore.event(runId, "history.revised", JSONObject().put("sessionId", sessionId).put("changed", JSONArray(changed.toList()))
                .put("original", JSONArray(messages.map { JSONObject(it.json) })).put("reason", "用户编辑或删除；派生上下文不再包含本回合工具链，原始往返保留在事件记录"))
            AgentStore.dao.deleteRunMessages(sessionId, runId)
            ids.mapNotNull { current[it] }.forEach { message ->
                val role = message.getString("role").lowercase()
                val original = messages.lastOrNull { JSONObject(it.json).let { value -> value.getString("role") == role && !value.has("tool_calls") } }
                    ?: return@forEach
                AgentStore.dao.append(original.copy(json = JSONObject().put("role", role)
                    .put("content", message.getString("content")).toString()))
            }
        }
    }
}
