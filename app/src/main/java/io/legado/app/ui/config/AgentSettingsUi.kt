package io.legado.app.ui.config

import android.graphics.Typeface
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import io.legado.app.help.agent.*
import io.legado.app.help.agent.mcp.AgentCapabilities
import io.legado.app.help.agent.mcp.AgentMcpClient
import io.legado.app.help.agent.mcp.AgentMcpProtocol
import io.legado.app.help.agent.mcp.AgentSchema
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.service.AgentMcpService
import io.legado.app.utils.sendToClip
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentSettingsUi(private val fragment: AiConfigFragment, private val changed: () -> Unit) {
    private val context get() = fragment.requireContext()
    private var importKind = "plugin"
    private var exportBytes = byteArrayOf()
    private var migrationError: String? = null
    private val importer = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            when (importKind) {
                "plugin" -> withContext(Dispatchers.IO) { AgentPlugins.install(AgentPlugins.readZip(bytes.inputStream())) }
                "prompts" -> {
                    val entries = JSONArray(AgentPlugins.text(bytes))
                    AgentStore.database.runInTransaction {
                        for (index in 0 until entries.length()) {
                            val entry = entries.getJSONObject(index)
                            val key = entry.getString("key")
                            require(key.isNotBlank() && AgentStore.get("prompts", key) == null) { "提示词 key 已存在或为空：$key" }
                            entry.getString("content"); AgentStore.put("prompts", key, entry)
                        }
                    }
                }
                "skill" -> importSkill(bytes)
            }
            changed()
            showText("导入完成", "数据已保存；新运行使用新修订。")
        }
    }
    private val exporter = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = exportBytes
        exportBytes = byteArrayOf()
        if (uri != null) work { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } } }
    }

    fun initialize() {
        try { AgentConfig.initialize() } catch (error: Exception) { migrationError = error.stackTraceToString() }
    }

    fun refresh() {
        fragment.findPreference<TwoStatePreference>("agentEnabled")?.apply {
            isPersistent = false
            isEnabled = migrationError == null
            if (migrationError == null) isChecked = AgentConfig.enabled
            summary = migrationError ?: "只控制内部任务；对外 MCP 服务独立管理"
            setOnPreferenceChangeListener { _, enabled ->
                AgentConfig.enabled = enabled as Boolean
                changed()
                true
            }
        }
        fragment.findPreference<Preference>("agentModes")?.summary = migrationError ?: if (AgentStore.get("migration", "v1") != null) {
            val mode = AgentConfig.mode
            val plugin = AgentStore.get("plugins", mode)
            "$mode @ ${plugin?.optString("revision")} · 代码 / 修订 / 运行诊断"
        } else "迁移尚未完成"
    }

    fun handle(key: String?): Boolean {
        val action: (() -> Unit) = when (key) {
            "agentMcp" -> ::modules
            "agentServers" -> ::servers
            "agentSkills" -> { { resources("skills") } }
            "agentModes" -> ::modes
            "agentPrompts" -> { { resources("prompts") } }
            "agentTools" -> ::toolSettings
            else -> return false
        }
        if (migrationError != null) menu("Agent 迁移失败，原数据已保留", listOf("查看错误", "原始迁移快照", "重新尝试迁移"), { selected ->
            when (selected) {
                0 -> showText("迁移错误", migrationError!!)
                1 -> migrationSnapshots()
                2 -> { migrationError = null; initialize(); changed() }
            }
        })
        else safely(action)
        return true
    }

    private fun work(action: suspend () -> Unit) {
        fragment.lifecycleScope.launch {
            try { action() } catch (error: CancellationException) { throw error
            } catch (error: Exception) { showText("操作失败", error.stackTraceToString()) }
        }
    }

    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { showText("操作失败", error.stackTraceToString()) }
    }

    private fun menu(title: String, rows: List<String>, selected: (Int) -> Unit, longPress: ((Int) -> Unit)? = null) {
        val dialog = context.alert(title) { items(rows) { _, index -> safely { selected(index) } } }
        if (longPress != null) dialog.listView?.setOnItemLongClickListener { _, _, index, _ ->
            dialog.dismiss(); safely { longPress(index) }; true
        }
    }

    private fun edit(title: String, text: String, save: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(text); setSingleLine(false); minLines = 5
            typeface = Typeface.MONOSPACE
        }
        val dialog = context.alert(title) {
            customView { input }
            negativeButton("取消")
            positiveButton("保存")
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            safely { save(input.text.toString()); dialog.dismiss(); changed() }
        }
    }

    private fun editJson(title: String, value: JSONObject, save: (JSONObject) -> Unit) = edit(title, value.toString(2)) { save(JSONObject(it)) }
    private fun showText(title: String, text: String) {
        val view = EditText(context).apply { setText(text); setSingleLine(false); typeface = Typeface.MONOSPACE }
        context.alert(title) { customView { view }; positiveButton("关闭"); neutralButton("复制") { context.sendToClip(text) } }
    }

    private fun modules() {
        val internal = AgentCapabilities.moduleNames().keys.toList()
        val external = AgentStore.dao.documents("mcp.clients")
        val rows = internal.map { id ->
            val missing = when (id) {
                "web" -> if (AgentConfig.moduleSettings(id).optString("apiKey").isBlank()) " · 缺少访问密钥" else ""
                "memory" -> if (AgentConfig.moduleSettings(id).optString("providerId").isBlank() || AgentConfig.moduleSettings(id).optString("model").isBlank()) " · 缺少嵌入配置" else ""
                else -> ""
            }
            "${if (AgentConfig.moduleEnabled(id)) "●" else "○"} ${AgentCapabilities.moduleNames()[id]}$missing"
        } + external.map {
            val value = JSONObject(it.json); val status = AgentStore.get("mcp.status", it.key)
            "${if (value.getBoolean("enabled")) "●" else "○"} ${value.getString("name")} · 外部 · ${status ?: "未发现"}"
        } + "添加外部 MCP"
        menu("MCP 管理：短按开关，长按编辑", rows, { index ->
            when {
                index < internal.size -> AgentConfig.setModuleEnabled(internal[index], !AgentConfig.moduleEnabled(internal[index]))
                index < internal.size + external.size -> external[index - internal.size].let {
                    val value = JSONObject(it.json); AgentStore.put("mcp.clients", it.key, value.put("enabled", !value.getBoolean("enabled")))
                }
                else -> { editClient(null); return@menu }
            }
            changed(); modules()
        }, { index ->
            when {
                index < internal.size -> moduleActions(internal[index])
                index < internal.size + external.size -> editClient(external[index - internal.size].key)
                else -> editClient(null)
            }
        })
    }

    private fun moduleActions(id: String) {
        menu(AgentCapabilities.moduleNames().getValue(id), listOf("模块配置", "工具开关", "查看实际 schema"), { index ->
            when (index) {
                0 -> editModule(id)
                1 -> {
                    val tools = AgentCapabilities.localTools(id)
                    menu("工具开关（清空就是无工具）", tools.map { "${if (AgentConfig.toolEnabled(id, it.toolId)) "●" else "○"} ${it.toolId}" } + listOf("关闭全部", "启用全部"), { selected ->
                        val enabled = tools.filter { AgentConfig.toolEnabled(id, it.toolId) }.map { it.toolId }.toMutableSet()
                        when (selected) {
                            tools.size -> enabled.clear()
                            tools.size + 1 -> enabled.addAll(tools.map { it.toolId })
                            else -> if (!enabled.add(tools[selected].toolId)) enabled.remove(tools[selected].toolId)
                        }
                        AgentStore.put("tool.selection", id, JSONObject().put("enabled", JSONArray(enabled.toList())))
                        moduleActions(id)
                    })
                }
                2 -> showText("$id 工具定义", JSONArray(AgentCapabilities.localTools(id).map { it.mcpDefinition() }).toString(2))
            }
        })
    }

    private fun editClient(id: String?) {
        val value = id?.let { AgentStore.get("mcp.clients", it) } ?: JSONObject().put("name", "").put("endpoint", "")
            .put("apiKey", "").put("enabled", true).put("protocolVersion", "auto")
        val actions = listOf("编辑连接", "发现工具（不执行工具）", "内部工具开关") + if (id != null) listOf("删除连接") else emptyList()
        menu("外部 MCP", actions, { index ->
            when (index) {
                0 -> editJson("连接配置：auto 或明确协议版本", value) { updated ->
                    require(updated.getString("name").isNotBlank() && updated.getString("endpoint").matches(Regex("https?://.+"))) { "名称和 HTTP(S) 端点不能为空" }
                    require(updated.getString("protocolVersion") in AgentMcpProtocol.versions + "auto") { "不支持的协议版本" }
                    updated.getBoolean("enabled")
                    AgentStore.put("mcp.clients", id ?: UUID.randomUUID().toString(), updated)
                }
                1 -> {
                    require(id != null) { "请先保存连接" }
                    work {
                        val job = currentCoroutineContext().job
                        val control = AgentControl(job) { _, _ -> }
                        val guard = launchGuard(control)
                        try {
                            val tools = withContext(Dispatchers.IO) { AgentMcpClient.discover(id, value, control) }
                            showText("发现完成", JSONArray(tools.map { it.mcpDefinition() }).toString(2))
                        } finally { guard.cancel() }
                    }
                }
                2 -> {
                    require(id != null) { "请先保存连接" }
                    work {
                        val control = AgentControl(currentCoroutineContext().job) { _, _ -> }
                        val guard = launchGuard(control)
                        try {
                            val tools = withContext(Dispatchers.IO) { AgentMcpClient.discover(id, value, control, migrateSelection = false) }
                            val module = "external.$id"
                            val available = tools.map { it.toolId }.toSet()
                            editJson("内部工具开关：enabled 为空即关闭全部", AgentStore.get("tool.selection", module)
                                ?: JSONObject().put("enabled", JSONArray(available.toList()))) { selection ->
                                val enabled = selection.getJSONArray("enabled")
                                require((0 until enabled.length()).all { enabled.getString(it) in available }) { "包含未发现的工具 ID" }
                                AgentStore.database.runInTransaction {
                                    AgentStore.put("tool.selection", module, selection)
                                    AgentStore.dao.deleteDocument("tool.selection.legacy", module)
                                }
                            }
                        } finally { guard.cancel() }
                    }
                }
                3 -> confirm("删除此外部 MCP？") {
                    AgentStore.dao.deleteDocument("mcp.clients", id!!)
                    AgentStore.dao.deleteDocument("tool.selection", "external.$id")
                    AgentStore.dao.deleteDocument("tool.selection.legacy", "external.$id")
                    changed()
                }
            }
        })
    }

    private fun launchGuard(control: AgentControl): Job = fragment.lifecycleScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (!control.job.isActive) control.cancel("管理操作已取消") }
    }

    private fun serverConfig(id: String): JSONObject = AgentStore.get("mcp.servers", id) ?: JSONObject()
        .put("enabled", false).put("address", "127.0.0.1").put("port", 18080 + AgentCapabilities.moduleNames().keys.indexOf(id))
        .put("apiKey", UUID.randomUUID().toString() + UUID.randomUUID().toString()).put("allowedHosts", JSONArray(listOf("127.0.0.1", "localhost")))
        .put("allowedOrigins", JSONArray()).put("pageSize", 64).also { AgentStore.put("mcp.servers", id, it) }

    private fun servers() {
        val ids = AgentCapabilities.moduleNames().keys.toList()
        menu("MCP Server 管理：仅本机能力，长按配置", ids.map { id ->
            "${if (serverConfig(id).getBoolean("enabled")) "●" else "○"} ${AgentCapabilities.moduleNames()[id]} · ${AgentStore.get("mcp.server.status", id) ?: "未启动"}"
        }, { index ->
            val id = ids[index]; val value = serverConfig(id)
            AgentStore.put("mcp.servers", id, value.put("enabled", !value.getBoolean("enabled")))
            AgentMcpService.refresh(); changed(); servers()
        }, { index ->
            val id = ids[index]
            menu("$id 对外服务", listOf("监听与认证配置", "复制客户端 JSON", "查看运行状态", "刷新状态"), { selected ->
                when (selected) {
                    0 -> editJson("监听配置：对外绑定请填写真实 allowedHosts", serverConfig(id)) { value ->
                        require(value.getInt("port") in 1..65535 && value.getInt("pageSize") > 0 && value.getString("apiKey").isNotBlank())
                        require(value.getJSONArray("allowedHosts").length() > 0)
                        value.getJSONArray("allowedOrigins"); value.getBoolean("enabled")
                        AgentStore.put("mcp.servers", id, value); AgentMcpService.refresh()
                    }
                    1 -> {
                        val value = serverConfig(id)
                        val client = JSONObject().put("mcpServers", JSONObject().put(id, JSONObject()
                            .put("url", "http://${value.getJSONArray("allowedHosts").getString(0)}:${value.getInt("port")}/mcp")
                            .put("headers", JSONObject().put("Authorization", "Bearer ${value.getString("apiKey")}"))))
                        context.sendToClip(client.toString(2))
                    }
                    2 -> showText("运行状态", (AgentStore.get("mcp.server.status", id) ?: JSONObject().put("state", "未启动")).toString(2))
                    3 -> servers()
                }
            })
        })
    }

    private fun toolSettings() {
        val ids = AgentCapabilities.moduleNames().keys.toList()
        menu("工具设置：配置归所属模块", ids.map { AgentCapabilities.moduleNames().getValue(it) } + "聊天交互", { index ->
            if (index < ids.size) editModule(ids[index]) else editJson("聊天交互", AgentConfig.value("ui")) {
                it.getBoolean("enterToSend"); it.getBoolean("showToolSummary"); AgentStore.put("config", "ui", it)
            }
        })
    }

    private fun editModule(id: String) {
        if (id !in setOf("web", "memory")) { moduleActionsReadOnly(id); return }
        val providerList = if (id == "memory") "\n供应商引用：" + AppConfig.aiProviderList.joinToString { "${it.name}=${it.id}" } else ""
        editJson("$id 配置$providerList", AgentConfig.moduleSettings(id)) { value ->
            if (id == "memory") {
                require(value.getString("scope") in setOf("book", "global"))
                require(value.getInt("recallCount") >= 0 && value.getDouble("contextFraction") > 0 && value.getDouble("contextFraction") <= 1)
                value.getDouble("minimumScore"); value.getBoolean("autoSave"); value.getString("model")
                val provider = value.getString("providerId")
                require(provider.isBlank() || AppConfig.aiProviderList.any { it.id == provider }) { "供应商引用不存在" }
            } else {
                require(value.getString("baseUrl").matches(Regex("https?://.+")))
                require(value.getInt("maxResults") in 1..20) { "Tavily 外部接口 max_results 范围为 1..20" }
                value.getString("apiKey"); value.getString("topic"); value.getString("searchDepth")
            }
            AgentStore.put("module.settings", id, value)
        }
    }

    private fun moduleActionsReadOnly(id: String) = showText("${AgentCapabilities.moduleNames()[id]} 配置", "该模块复用应用领域配置，没有独立连接参数。工具开关在 MCP 管理中维护。")

    private fun modes() {
        val plugins = AgentStore.dao.documents("plugins")
        menu("Agent 模式：短按切换，长按管理", plugins.map { "${if (it.key == AgentConfig.mode) "●" else "○"} ${JSONObject(it.json).getString("name")} · ${it.key}" } + listOf("导入完整 ZIP", "运行诊断", "查看随包默认配置", "原始迁移快照"), { index ->
            when (index) {
                plugins.size -> { importKind = "plugin"; importer.launch(arrayOf("application/zip", "application/octet-stream")) }
                plugins.size + 1 -> diagnostics()
                plugins.size + 2 -> showText("可查看的统一默认值", AgentConfig.defaults().toString(2))
                plugins.size + 3 -> migrationSnapshots()
                else -> { AgentConfig.mode = plugins[index].key; changed(); modes() }
            }
        }, { index -> if (index < plugins.size) pluginActions(plugins[index].key) })
    }

    private fun pluginActions(id: String) {
        val metadata = AgentStore.get("plugins", id)!!
        menu("$id 插件管理", listOf("查看 / 编辑代码", "编辑模式配置", "导出 ZIP", "复制为用户包", "选择历史修订", "启用 / 关闭", "删除用户包"), { index ->
            when (index) {
                0 -> {
                    val snapshot = AgentPlugins.snapshot(id, allowDisabled = true)
                    val files = snapshot.files.keys.sorted()
                    menu("$id @ ${snapshot.revision}", files, { selected ->
                        val path = files[selected]
                        if (metadata.getBoolean("builtin")) showText("内置文件：复制后可修改 · $path", snapshot.text(path))
                        else edit("$id/$path", snapshot.text(path)) { source ->
                            require(AgentStore.get("plugins", id)!!.getString("revision") == snapshot.revision) { "包已被其他编辑修改，请重新打开" }
                            AgentPlugins.install(snapshot.files.toMutableMap().apply { put(path, source.toByteArray(Charsets.UTF_8)) }, editingId = id)
                        }
                    })
                }
                1 -> {
                    val snapshot = AgentPlugins.snapshot(id, allowDisabled = true)
                    editJson("模式配置（不随代码包导出）", snapshot.settings) { value ->
                        snapshot.manifest.optJSONObject("settings")?.optJSONObject("schema")?.let { AgentSchema.validate(value, it, "settings") }
                        AgentStore.put("plugin.settings", id, value)
                    }
                }
                2 -> { exportBytes = ByteArrayOutputStream().also { AgentPlugins.export(id, it) }.toByteArray(); exporter.launch("$id.zip") }
                3 -> edit("新插件唯一 ID", "$id.copy") { AgentPlugins.copy(id, it.trim()) }
                4 -> {
                    val revisions = AgentStore.dao.documents("plugin.revisions").filter { it.key.startsWith("$id@") }
                    menu("手动选择修订，不自动回退", revisions.map { it.key.substringAfter('@') }, { selected ->
                        AgentPlugins.selectRevision(id, revisions[selected].key.substringAfter('@')); changed()
                    })
                }
                5 -> { AgentStore.put("plugins", id, metadata.put("enabled", !metadata.getBoolean("enabled"))); changed() }
                6 -> confirm("删除 $id？内置包不能删除。") { AgentPlugins.delete(id); changed() }
            }
        })
    }

    private fun resources(namespace: String) {
        val documents = AgentStore.dao.documents(namespace)
        val operations = if (namespace == "skills") listOf("新建 Skill", "导入 SKILL.md / ZIP") else listOf("新建提示词", "导入 JSON", "导出全部提示词")
        menu(if (namespace == "skills") "Skill 管理（知识与资源，不是工具）" else "提示词库（唯一 key）",
            documents.map { "${it.key} · ${JSONObject(it.json).optString("name")} ${if (namespace == "skills") JSONObject(it.json).optBoolean("enabled", true) else ""}" } + operations, { index ->
                if (index < documents.size) resourceActions(namespace, documents[index].key)
                else when (index - documents.size) {
                    0 -> editJson("唯一 key / 名称 / 正文", JSONObject().put("key", "").put("name", "").put("content", "").apply { if (namespace == "skills") put("enabled", true) }) { value ->
                        val key = value.getString("key"); require(key.isNotBlank() && AgentStore.get(namespace, key) == null) { "key 已存在或为空" }
                        value.getString("content"); AgentStore.put(namespace, key, value)
                    }
                    1 -> { importKind = if (namespace == "skills") "skill" else "prompts"; importer.launch(arrayOf("*/*")) }
                    2 -> {
                        exportBytes = JSONArray(documents.map { JSONObject(it.json).put("key", it.key).apply { remove("owner"); remove("path") } }).toString(2).toByteArray(Charsets.UTF_8)
                        exporter.launch("agent-prompts.json")
                    }
                }
            })
    }

    private fun resourceActions(namespace: String, key: String) {
        val value = AgentStore.get(namespace, key)!!
        val actions = listOf("编辑正文", "编辑名称", "删除") + if (namespace == "skills") listOf("启用 / 关闭", "资源文件", "导出 Skill ZIP") else emptyList()
        menu(key, actions, { index ->
            when (index) {
                0 -> edit("$key 正文", value.getString("content")) { AgentStore.put(namespace, key, value.put("content", it)) }
                1 -> edit("名称（key 保持不变）", value.optString("name")) { AgentStore.put(namespace, key, value.put("name", it)) }
                2 -> confirm("删除 $key？仍在引用它的模式将明确失败。") { AgentStore.dao.deleteDocument(namespace, key); changed() }
                3 -> { AgentStore.put(namespace, key, value.put("enabled", !value.getBoolean("enabled"))); changed() }
                4 -> {
                    val files = AgentStore.dao.documents("skill.resources.$key")
                    menu("$key 资源", files.map { it.key }, { selected ->
                        val file = files[selected]
                        editJson(file.key, JSONObject(file.json)) { AgentStore.put("skill.resources.$key", file.key, it) }
                    })
                }
                5 -> {
                    val output = ByteArrayOutputStream()
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("SKILL.md")); zip.write(value.getString("content").toByteArray(Charsets.UTF_8)); zip.closeEntry()
                        AgentStore.dao.documents("skill.resources.$key").forEach { file ->
                            zip.putNextEntry(ZipEntry(file.key)); zip.write(android.util.Base64.decode(JSONObject(file.json).getString("base64"), android.util.Base64.DEFAULT)); zip.closeEntry()
                        }
                    }
                    exportBytes = output.toByteArray(); exporter.launch("$key.zip")
                }
            }
        })
    }

    private fun importSkill(bytes: ByteArray) {
        val files = if (bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()) AgentPlugins.readZip(bytes.inputStream()) else mapOf("SKILL.md" to bytes)
        val content = AgentPlugins.text(files["SKILL.md"] ?: error("Skill ZIP 根缺少 SKILL.md"))
        val key = "skill.${UUID.randomUUID()}"
        val name = Regex("(?m)^name:\\s*(.+)$").find(content)?.groupValues?.get(1)?.trim()?.trim('"', '\'') ?: key
        if (content.startsWith("---")) require(content.indexOf("\n---", 3) >= 0) { "Skill frontmatter 没有结束标记" }
        AgentStore.database.runInTransaction {
            AgentStore.put("skills", key, JSONObject().put("name", name).put("content", content).put("enabled", true))
            files.filterKeys { it != "SKILL.md" }.forEach { (path, value) ->
                AgentStore.put("skill.resources.$key", path, JSONObject().put("base64", android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)))
            }
        }
    }

    private fun diagnostics() {
        val runs = AgentStore.dao.runs()
        menu("运行诊断（完整持久化事件）", runs.map { "${it.state} · ${it.sessionId} · ${it.id}" }, { index ->
            val run = runs[index]
            menu("${run.pluginId}@${run.revision}", listOf("事件与实际请求", "调用栈 / 变量", "暂停", "继续", "提供输入", "停止"), { action ->
                val control = AgentRuntime.controls()[run.id]
                when (action) {
                    0 -> showText("运行 ${run.id}", JSONArray(AgentStore.dao.events(run.id).map { JSONObject().put("sequence", it.sequence).put("type", it.type).put("value", JSONObject(it.json)) }).toString(2))
                    1 -> showText("暂停快照", (control?.snapshot ?: JSONObject().put("state", run.state).put("error", run.error)).toString(2))
                    2 -> (control ?: error("任务已结束或中断")).requestPause()
                    3 -> (control ?: error("任务已结束或中断")).resume()
                    4 -> edit("任务等待的输入", "") { (control ?: error("任务已结束或中断")).resume(it) }
                    5 -> (control ?: error("任务已结束或中断")).cancel()
                }
            })
        })
    }

    private fun migrationSnapshots() {
        val snapshots = AgentStore.dao.documents("migration").filter { it.key == "original" || it.key.startsWith("restore.") }
        menu("原始迁移快照（含密钥，仅供手动恢复，不自动回退）", snapshots.map { it.key }, { index ->
            val snapshot = snapshots[index]
            menu(snapshot.key, listOf("查看原始 JSON", "导出原始 JSON"), { action ->
                if (action == 0) showText("原始迁移数据", JSONObject(snapshot.json).toString(2))
                else {
                    exportBytes = snapshot.json.toByteArray(Charsets.UTF_8)
                    exporter.launch("agent-migration-${snapshot.key}.json")
                }
            })
        })
    }

    private fun confirm(message: String, action: () -> Unit) {
        context.alert(message) { negativeButton("取消"); positiveButton("确认") { safely(action) } }
    }
}
