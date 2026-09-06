package io.legado.app.help.agent.mcp

import io.legado.app.data.agent.AgentRun
import io.legado.app.help.agent.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object AgentPluginTools {
    fun names(): Map<String, String> = AgentStore.dao.documents("plugins").mapNotNull { document ->
        val metadata = JSONObject(document.json)
        val manifest = AgentStore.get("plugin.revisions", "${document.key}@${metadata.getString("revision")}") ?: error("插件清单丢失：${document.key}")
        if ((manifest.optJSONArray("tools")?.length() ?: 0) == 0) null else "plugin.${document.key}" to metadata.getString("name")
    }.toMap()

    fun snapshots(pinned: AgentPluginSnapshot? = null, modules: Set<String> = names().keys): List<AgentPluginSnapshot> {
        val pinnedSnapshots = mutableMapOf<String, AgentPluginSnapshot>()
        fun collect(snapshot: AgentPluginSnapshot) { pinnedSnapshots[snapshot.id] = snapshot; snapshot.dependencies.values.forEach(::collect) }
        pinned?.let(::collect)
        return modules.map { moduleId ->
            val id = moduleId.removePrefix("plugin.")
            pinnedSnapshots[id] ?: AgentPlugins.snapshot(id, allowDisabled = true)
        }
    }

    fun tools(pinned: AgentPluginSnapshot? = null, script: AgentScript? = null,
              moduleId: String? = null, captured: List<AgentPluginSnapshot> = snapshots(pinned, names().keys.filter { moduleId == null || it == moduleId }.toSet())): List<AgentTool> {
        return captured.flatMap { snapshot ->
            val id = snapshot.id
            val module = "plugin.$id"
            val definitions = snapshot.manifest.getJSONArray("tools")
            (0 until definitions.length()).map { index ->
                val definition = definitions.getJSONObject(index)
                AgentTool(module, definition.getString("id"), definition.getString("description"), definition.getJSONObject("inputSchema")) { arguments, control ->
                    val entry = definition.getString("entry")
                    val export = definition.optString("export", "execute")
                    if (script != null) AgentCapabilities.normalize(script.execute(snapshot, entry, export, arguments) ?: error("脚本工具未返回结果"))
                    else withContext(Dispatchers.IO) {
                        val run = AgentRun(UUID.randomUUID().toString(), "mcp:$module", UUID.randomUUID().toString(), id, snapshot.revision, arguments.toString())
                        val execution = AgentExecution(run, snapshot, control, external = true) { type, value ->
                            AgentStore.database.runInTransaction {
                                AgentStore.event(run.id, type, value)
                                if (type in setOf("running", "paused", "waiting_input")) AgentStore.dao.state(run.id, type, null)
                            }
                        }
                        AgentRuntime.registerExternal(run.id, control)
                        try {
                            AgentStore.dao.put(run)
                            val result = AgentScript(execution).use { engine ->
                                execution.script = engine
                                engine.execute(snapshot, entry, export, arguments) ?: error("脚本工具未返回结果")
                            }
                            val normalized = AgentCapabilities.normalize(result)
                            AgentStore.database.runInTransaction {
                                AgentStore.event(run.id, "completed", normalized)
                                AgentStore.dao.state(run.id, "completed", null)
                            }
                            normalized
                        } catch (error: Throwable) {
                            val state = if (error is CancellationException || control.cancelled) "cancelled" else "failed"
                            AgentStore.database.runInTransaction {
                                AgentStore.event(run.id, state, JSONObject().put("error", error.stackTraceToString()).put("javascript", execution.lastStack))
                                AgentStore.dao.state(run.id, state, AgentDiagnostics.protect(JSONObject().put("error", error.stackTraceToString())).toString())
                            }
                            throw error
                        } finally { execution.finished = true; AgentRuntime.unregisterExternal(run.id) }
                    }
                }
            }
        }
    }
}
