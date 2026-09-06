package io.legado.app.ui.config

import android.content.DialogInterface
import android.graphics.Typeface
import android.net.Uri
import android.util.Base64
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.agent.AgentDocument
import io.legado.app.help.agent.AgentConfig
import io.legado.app.help.agent.AgentControl
import io.legado.app.help.agent.AgentPluginSnapshot
import io.legado.app.help.agent.AgentPlugins
import io.legado.app.help.agent.AgentRuntime
import io.legado.app.help.agent.AgentStore
import io.legado.app.help.agent.mcp.AgentCapabilities
import io.legado.app.help.agent.mcp.AgentMcpClient
import io.legado.app.help.agent.mcp.AgentMcpServer
import io.legado.app.help.agent.mcp.AgentSchema
import io.legado.app.help.agent.memory.AgentMemory
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.service.AgentMcpService
import io.legado.app.utils.sendToClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Agent 的管理投影。数据库、包文件与连接操作只在 IO 上执行；保存成功后才改变显示状态。
 * 配置的含义与类型由配置/能力所有者校验；编辑器保留打开时的修订，避免覆盖并行编辑。
 */
class AgentSettingsUi(private val fragment: AiConfigFragment, private val changed: () -> Unit) {
    private val context get() = fragment.requireContext()
    private var ready = false
    private var initializing = false
    private var migrationError: String? = null
    private var refreshJob: Job? = null
    private var pendingImport: (suspend (ByteArray) -> Any?)? = null
    private var pendingExport: ByteArray? = null

    private val importer = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val consume = pendingImport
        pendingImport = null
        if (uri != null) work {
            check(consume != null) { "文件选择状态已丢失，请重新发起导入" }
            val bytes = io { readUri(uri) }
            consume(bytes)
            changed()
        }
    }
    private val exporter = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null) work {
            check(bytes != null) { "导出状态已丢失，请重新发起导出" }
            io { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开导出目标：$uri") }
            showText("导出完成", uri.toString())
        }
    }

    fun initialize() {
        if (initializing) return
        initializing = true
        ready = false
        refresh()
        fragment.lifecycleScope.launch {
            try {
                io { AgentConfig.initialize() }
                migrationError = null
                ready = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                migrationError = error.stackTraceToString()
                report("Agent 初始化失败，原始数据已保留", error)
            } finally {
                initializing = false
                if (fragment.isAdded) refresh()
            }
        }
    }

    fun refresh() {
        val switchSummary = migrationError
            ?: context.getString(if (ready) R.string.agent_internal_switch_summary else R.string.agent_loading)
        fragment.findPreference<TwoStatePreference>("agentEnabled")?.apply {
            isPersistent = false
            isEnabled = ready
            summary = switchSummary
            setOnPreferenceChangeListener { _, value ->
                work {
                    require(value is Boolean) { "Agent 开关必须为 Boolean" }
                    io { AgentConfig.enabled = value }
                    changed()
                }
                false
            }
        }
        if (!ready) {
            fragment.findPreference<Preference>("agentModes")?.summary = migrationError ?: fragment.getString(R.string.agent_loading)
            return
        }
        refreshJob?.cancel()
        refreshJob = work {
            val summary = io {
                val mode = AgentConfig.mode
                val plugin = AgentStore.get("plugins", mode) ?: error("当前模式不存在：$mode")
                Triple(AgentConfig.enabled, mode, plugin.getString("revision"))
            }
            fragment.findPreference<TwoStatePreference>("agentEnabled")?.isChecked = summary.first
            fragment.findPreference<Preference>("agentModes")?.summary = "${summary.second} @ ${summary.third}"
        }
    }

    fun handle(key: String?): Boolean {
        val action: () -> Unit = when (key) {
            "agentMcp" -> ::modules
            "agentServers" -> ::servers
            "agentSkills" -> { { resources("skills") } }
            "agentModes" -> ::modes
            "agentPrompts" -> { { resources("prompts") } }
            "agentTools" -> ::toolSettings
            else -> return false
        }
        when {
            initializing -> showText("Agent", fragment.getString(R.string.agent_loading))
            !ready -> menu("Agent 初始化失败", listOf("查看错误", "原始迁移快照", "重新尝试"), { index ->
                when (index) {
                    0 -> showText("完整错误", migrationError ?: "尚未初始化")
                    1 -> migrationSnapshots()
                    2 -> initialize()
                }
            })
            else -> action()
        }
        return true
    }

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) { action() }

    private fun work(action: suspend () -> Unit): Job = fragment.lifecycleScope.launch {
        try { action() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { report("操作失败", error) }
    }

    private fun report(title: String, error: Exception) {
        AppLog.put("$title\n${error.message}", error)
        if (fragment.isAdded) showText(title, error.stackTraceToString())
    }

    private fun menu(title: String, rows: List<String>, selected: suspend (Int) -> Any?, longPress: (suspend (Int) -> Any?)? = null) {
        val dialog = context.alert(title) {
            if (rows.isEmpty()) setMessage("当前没有记录")
            else items(rows) { _, index -> work { selected(index) } }
            negativeButton("关闭")
        }
        if (longPress != null) dialog.listView?.setOnItemLongClickListener { _, _, index, _ ->
            dialog.dismiss()
            work { longPress(index) }
            true
        }
    }

    private fun edit(title: String, text: String, save: suspend (String) -> Any?) {
        val input = EditText(context).apply {
            setText(text)
            setSingleLine(false)
            minLines = 5
            typeface = Typeface.MONOSPACE
            setHorizontallyScrolling(true)
        }
        val dialog = context.alert(title) {
            customView { input }
            negativeButton("取消")
            positiveButton("保存")
        }
        val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        button.setOnClickListener {
            val value = input.text.toString()
            button.isEnabled = false
            work {
                try {
                    save(value)
                    dialog.dismiss()
                    changed()
                } finally { button.isEnabled = true }
            }
        }
    }

    private fun editJson(title: String, value: JSONObject, save: suspend (JSONObject) -> Any?) =
        edit(title, value.toString(2)) { source -> save(io { JSONObject(source) }) }

    private fun showText(title: String, text: String) {
        val view = EditText(context).apply {
            setText(text)
            setSingleLine(false)
            typeface = Typeface.MONOSPACE
            keyListener = null
            setTextIsSelectable(true)
        }
        context.alert(title) {
            customView { view }
            positiveButton("关闭")
            neutralButton("复制") { context.sendToClip(text) }
        }
    }

    private fun confirm(message: String, action: suspend () -> Any?) {
        context.alert(message) {
            negativeButton("取消")
            positiveButton("确认") { work { action(); changed() } }
        }
    }

    private fun importFile(types: Array<String> = arrayOf("*/*"), consume: suspend (ByteArray) -> Any?) {
        check(pendingImport == null) { "已有文件选择正在进行" }
        pendingImport = consume
        importer.launch(types)
    }

    private fun exportFile(name: String, bytes: ByteArray) {
        check(pendingExport == null) { "已有导出正在进行" }
        pendingExport = bytes
        exporter.launch(name)
    }

    private fun readUri(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("无法读取文件：$uri")

    private suspend fun <T> controlled(title: String, action: (AgentControl) -> T): T {
        val control = AgentControl(currentCoroutineContext().job) { _, _ -> }
        val dialog = context.alert(title) {
            setMessage("正在执行；取消会停止当前请求。")
            setCancelable(false)
            negativeButton("取消") { control.cancel("用户取消管理操作") }
        }
        try { return io { action(control) } }
        finally { dialog.dismiss() }
    }

    private fun document(namespace: String, key: String): AgentDocument =
        AgentStore.dao.document(namespace, key) ?: error("记录不存在：$namespace/$key")

    private fun putDocument(old: AgentDocument, value: JSONObject) {
        AgentStore.put(old.namespace, old.key, value, old.revision)
    }

    private fun modules(): Job = work {
        val state = io {
            val internal = AgentCapabilities.moduleNames().keys.toList()
            val external = AgentStore.dao.documents("mcp.clients")
            val rows = internal.map { id ->
                val settings = AgentConfig.moduleSettings(id)
                val missing = when (id) {
                    "web" -> if (settings.getString("apiKey").isBlank()) " · 缺少访问密钥" else ""
                    "memory" -> if (settings.getString("providerId").isBlank() || settings.getString("model").isBlank()) " · 缺少嵌入供应商或模型" else ""
                    else -> ""
                }
                "${mark(AgentConfig.moduleEnabled(id))} ${AgentCapabilities.moduleNames().getValue(id)} · 内置 · ${AgentCapabilities.localTools(id).size} 个工具$missing"
            } + external.map { record ->
                val value = JSONObject(record.json)
                "${mark(value.getBoolean("enabled"))} ${value.getString("name")} · 外部 · ${AgentStore.get("mcp.status", record.key) ?: "未发现工具"}"
            } + listOf("添加外部 MCP", "刷新状态")
            Triple(internal, external, rows)
        }
        val (internal, external, rows) = state
        menu("MCP 管理：短按开关，长按编辑", rows, { index ->
            if (index == internal.size + external.size) {
                editClient(null)
            } else {
                when {
                    index < internal.size -> io {
                        val id = internal[index]
                        AgentConfig.setModuleEnabled(id, !AgentConfig.moduleEnabled(id))
                    }
                    index < internal.size + external.size -> io {
                        val old = external[index - internal.size]
                        val value = JSONObject(old.json)
                        putDocument(old, value.put("enabled", !value.getBoolean("enabled")))
                    }
                }
                changed()
                modules()
            }
        }, { index ->
            when {
                index < internal.size -> moduleActions(internal[index])
                index < internal.size + external.size -> editClient(external[index - internal.size].key)
                index == internal.size + external.size -> editClient(null)
                else -> modules()
            }
        })
    }

    private fun moduleActions(id: String): Unit = menu(AgentCapabilities.moduleNames().getValue(id),
        listOf("模块配置", "内部工具开关", "实际工具定义"), { index ->
            when (index) {
                0 -> editModule(id)
                1 -> {
                    val tools = io { AgentCapabilities.localTools(id).map { it.toolId } }
                    toolSelection(id, tools)
                }
                2 -> showText("$id 工具定义", io { JSONArray(AgentCapabilities.localTools(id).map { it.mcpDefinition() }).toString(2) })
            }
        })

    private fun toolSelection(moduleId: String, toolIds: List<String>): Job = work {
        val state = io {
            val old = AgentStore.dao.document("tool.selection", moduleId)
            val value = old?.let { JSONObject(it.json) } ?: JSONObject().put("enabled", JSONArray(toolIds))
            old to value
        }
        editJson("$moduleId 内部工具：enabled 为空即全部关闭", state.second) { value ->
            io {
                val enabled = value.getJSONArray("enabled")
                val seen = mutableSetOf<String>()
                for (index in 0 until enabled.length()) {
                    val id = enabled.get(index)
                    require(id is String && id in toolIds && seen.add(id)) { "工具 ID 不存在或重复：$id" }
                }
                AgentStore.database.runInTransaction {
                    AgentStore.put("tool.selection", moduleId, value, state.first?.revision ?: 0)
                    AgentStore.dao.deleteDocument("tool.selection.legacy", moduleId)
                }
            }
        }
    }

    private fun editClient(id: String?): Job = work {
        val old = if (id == null) null else io { document("mcp.clients", id) }
        val value = old?.let { JSONObject(it.json) } ?: JSONObject().put("name", "").put("endpoint", "")
            .put("apiKey", "").put("enabled", true).put("protocolVersion", "auto")
        if (old == null) {
            clientEditor(null, value)
        } else {
            menu("${value.getString("name")} · 外部 MCP", listOf("编辑连接", "发现工具", "内部工具开关", "删除连接"), { index ->
            when (index) {
                0 -> clientEditor(old, value)
                1 -> {
                    val tools = controlled("发现工具") { AgentMcpClient.discover(old.key, value, it) }
                    showText("实际工具定义", io { JSONArray(tools.map { it.mcpDefinition() }).toString(2) })
                }
                2 -> {
                    val tools = controlled("读取实际工具目录") { AgentMcpClient.discover(old.key, value, it, migrateSelection = false) }
                    toolSelection("external.${old.key}", tools.map { it.toolId })
                }
                3 -> confirm("删除此外部 MCP 连接？") {
                    io {
                        AgentStore.database.runInTransaction {
                            require(document(old.namespace, old.key).revision == old.revision) { "连接已被修改，请重新打开" }
                            AgentStore.dao.deleteDocument(old.namespace, old.key)
                            listOf("tool.selection", "tool.selection.legacy").forEach { AgentStore.dao.deleteDocument(it, "external.${old.key}") }
                            AgentStore.dao.deleteDocument("mcp.status", old.key)
                        }
                    }
                    modules()
                }
            }
            })
        }
    }

    private fun clientEditor(old: AgentDocument?, value: JSONObject): Unit = editJson("外部连接：protocolVersion 为 auto 或明确版本", value) { updated ->
        io {
            AgentMcpClient.validateConfig(updated)
            AgentStore.put("mcp.clients", old?.key ?: UUID.randomUUID().toString(), updated, old?.revision ?: 0)
        }
        modules()
    }

    private fun serverConfig(id: String): JSONObject = AgentStore.get("mcp.servers", id) ?: JSONObject()
        .put("enabled", false).put("address", "127.0.0.1")
        .put("port", 18080 + AgentCapabilities.moduleNames().keys.indexOf(id))
        .put("apiKey", UUID.randomUUID().toString() + UUID.randomUUID().toString())
        .put("allowedHosts", JSONArray(listOf("127.0.0.1", "localhost")))
        .put("allowedOrigins", JSONArray()).put("pageSize", 64)

    private fun servers(): Job = work {
        val state = io {
            val ids = AgentCapabilities.moduleNames().keys.toList()
            ids to ids.map { id ->
                "${mark(serverConfig(id).getBoolean("enabled"))} ${AgentCapabilities.moduleNames().getValue(id)} · ${AgentStore.get("mcp.server.status", id) ?: "未启动"}"
            }
        }
        menu("本机 MCP Server：短按开关，长按配置", state.second + "刷新运行状态", { index ->
            if (index < state.first.size) {
                io {
                    val id = state.first[index]
                    val old = AgentStore.dao.document("mcp.servers", id)
                    val value = serverConfig(id)
                    value.put("enabled", !value.getBoolean("enabled"))
                    AgentMcpServer.validateConfig(id, value)
                    AgentStore.put("mcp.servers", id, value, old?.revision ?: 0)
                }
                AgentMcpService.refresh()
                changed()
            }
            servers()
        }, { index -> if (index < state.first.size) serverActions(state.first[index]) else servers() })
    }

    private fun serverActions(id: String): Unit = menu("$id 对外服务", listOf("监听与认证配置", "复制客户端 JSON", "运行地址与错误", "应用配置 / 重试启动"), { index ->
        when (index) {
            0 -> {
                val state = io { AgentStore.dao.document("mcp.servers", id) to serverConfig(id) }
                editJson("监听配置：address / port / apiKey / allowedHosts / allowedOrigins", state.second) { value ->
                    io {
                        AgentMcpServer.validateConfig(id, value)
                        AgentStore.put("mcp.servers", id, value, state.first?.revision ?: 0)
                    }
                    AgentMcpService.refresh()
                }
            }
            1 -> {
                val text = io {
                    val value = serverConfig(id)
                    val host = value.getJSONArray("allowedHosts").getString(0)
                    val authority = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
                    JSONObject().put("mcpServers", JSONObject().put(id, JSONObject()
                        .put("url", "http://$authority:${value.getInt("port")}/mcp")
                        .put("headers", JSONObject().put("Authorization", "Bearer ${value.getString("apiKey")}")))).toString(2)
                }
                context.sendToClip(text)
                showText("已复制客户端 JSON", text)
            }
            2 -> showText("$id 运行状态", io { (AgentStore.get("mcp.server.status", id) ?: JSONObject().put("state", "stopped")).toString(2) })
            3 -> { AgentMcpService.refresh(); servers() }
        }
    })

    private fun toolSettings() {
        val ids = AgentCapabilities.moduleNames().keys.toList()
        menu("工具设置", ids.map { AgentCapabilities.moduleNames().getValue(it) } + "聊天交互", { index ->
            if (index < ids.size) editModule(ids[index])
            else {
                val old = io { document("config", "ui") }
                editJson("聊天交互", JSONObject(old.json)) { io { AgentConfig.saveValue("ui", it, old.revision) } }
            }
        })
    }

    private fun editModule(id: String): Job = work {
        if (id == "memory") {
            menu("记忆：共享供应商，模型仅作参数", listOf("自动召回 / 保存 / 作用域 / 检索配置", "选择嵌入供应商", "设置嵌入模型", "重建全部向量索引", "查看记忆文档"), { index ->
                when (index) {
                    0 -> moduleEditor(id)
                    1 -> memoryProvider()
                    2 -> memoryModel()
                    3 -> {
                        val result = controlled("按当前嵌入配置重建索引") { AgentMemory.rebuild(it) }
                        showText("索引重建完成", result.toString())
                    }
                    4 -> memoryDocuments()
                }
            })
        } else if (id == "web") moduleEditor(id)
        else showText("${AgentCapabilities.moduleNames().getValue(id)} 配置", "复用应用中的领域配置。内部工具选择在 MCP 管理中维护。")
    }

    private fun moduleEditor(id: String): Job = work {
        val state = io { AgentStore.dao.document("module.settings", id) to AgentConfig.moduleSettings(id) }
        editJson(if (id == "memory") "记忆配置：嵌入配置为空时不会伪装成向量成功" else "Tavily 联网配置", state.second) { value ->
            io { AgentConfig.saveModuleSettings(id, value, state.first?.revision ?: 0) }
        }
    }

    private fun memoryProvider(): Job = work {
        val providers = io { AppConfig.aiProviderList }
        menu("选择已有供应商（切换后需确认模型并重建索引）", providers.map { "${it.name} · ${it.id}" }, { index ->
            io {
                val old = document("module.settings", "memory")
                AgentConfig.saveModuleSettings("memory", JSONObject(old.json).put("providerId", providers[index].id).put("model", ""), old.revision)
            }
            changed()
            memoryModel()
        })
    }

    private fun memoryModel(): Job = work {
        val old = io { document("module.settings", "memory") }
        val settings = JSONObject(old.json)
        val models = io { AppConfig.aiModelConfigList.filter { it.providerId == settings.getString("providerId") } }
        menu("嵌入模型（须为供应商支持的 embeddings 模型）", models.map { it.modelId } + "直接填写模型参数", { index ->
            if (index < models.size) {
                io { AgentConfig.saveModuleSettings("memory", settings.put("model", models[index].modelId), old.revision) }
                changed()
            } else edit("嵌入模型参数", settings.getString("model")) { model ->
                require(model.isNotBlank()) { "嵌入模型不能为空" }
                io { AgentConfig.saveModuleSettings("memory", settings.put("model", model.trim()), old.revision) }
            }
        })
    }

    private fun memoryDocuments(): Job = work {
        val records = io { AgentStore.dao.documents("memory") }
        menu("记忆文档及来源", records.map { record ->
            val value = JSONObject(record.json)
            "${record.key} · ${value.getString("scope")} · ${value.getString("type")}"
        }, { index -> showText("记忆 ${records[index].key}", JSONObject(records[index].json).toString(2)) })
    }

    private fun modes(): Job = work {
        val state = io { AgentStore.dao.documents("plugins") to AgentConfig.mode }
        val records = state.first
        menu("Agent 模式：短按选择，长按管理", records.map {
            val value = JSONObject(it.json)
            "${mark(it.key == state.second)} ${value.getString("name")} · ${if (value.getBoolean("enabled")) "启用" else "关闭"} · ${it.key}"
        } + listOf("导入完整插件 ZIP", "运行诊断", "随包默认配置", "原始迁移快照"), { index ->
            when (index) {
                records.size -> importFile(arrayOf("application/zip", "application/octet-stream")) { bytes ->
                    val id = io { AgentPlugins.install(AgentPlugins.readZip(bytes.inputStream())) }
                    pluginActions(id)
                }
                records.size + 1 -> diagnostics()
                records.size + 2 -> showText("随包默认配置", io { AgentConfig.defaults().toString(2) })
                records.size + 3 -> migrationSnapshots()
                else -> { io { AgentConfig.mode = records[index].key }; changed(); modes() }
            }
        }, { index -> if (index < records.size) pluginActions(records[index].key) })
    }

    private fun pluginActions(id: String): Job = work {
        val old = io { document("plugins", id) }
        val metadata = JSONObject(old.json)
        val actions = listOf("包内文件", "模式配置与 schema", "导出 ZIP", "复制为用户包", "选择历史修订", "启用 / 关闭") +
            if (metadata.getBoolean("builtin")) emptyList() else listOf("删除用户包")
        menu("$id 插件管理", actions, { index ->
            when (index) {
                0 -> pluginFiles(id)
                1 -> {
                    val state = io { AgentPlugins.snapshot(id, allowDisabled = true) to document("plugin.settings", id) }
                    val schema = state.first.manifest.optJSONObject("settings")?.getJSONObject("schema")
                    menu("模式配置：保存后用于新运行", listOf("编辑配置", "查看配置 schema"), { selected ->
                        if (selected == 1) showText("$id settings schema", schema?.toString(2) ?: "此插件未声明可配置项")
                        else editJson("$id 模式配置", JSONObject(state.second.json)) { value ->
                            io {
                                require(AgentStore.get("plugins", id)?.getString("revision") == state.first.revision) { "插件修订已变化，请重新打开配置" }
                                if (schema == null) require(value.length() == 0) { "此插件未声明可配置项" }
                                else AgentSchema.validate(value, schema, "$id.settings")
                                putDocument(state.second, value)
                            }
                        }
                    })
                }
                2 -> exportFile("$id.zip", io { ByteArrayOutputStream().also { AgentPlugins.export(id, it) }.toByteArray() })
                3 -> edit("新插件唯一 ID", "$id.copy") { newId ->
                    val copied = io { AgentPlugins.copy(id, newId.trim()) }
                    pluginActions(copied)
                }
                4 -> {
                    val revisions = io { AgentStore.dao.documents("plugin.revisions").filter { it.key.startsWith("$id@") } }
                    menu("手动选择完整修订", revisions.map { it.key.substringAfter('@') }, { selected ->
                        io { AgentPlugins.selectRevision(id, revisions[selected].key.substringAfter('@')) }
                        changed()
                        pluginActions(id)
                    })
                }
                5 -> { io { putDocument(old, metadata.put("enabled", !metadata.getBoolean("enabled"))) }; changed(); pluginActions(id) }
                6 -> confirm("删除用户插件 $id？") { io { AgentPlugins.delete(id) }; modes() }
            }
        })
    }

    private fun pluginFiles(id: String): Job = work {
        val state = io { AgentPlugins.snapshot(id, allowDisabled = true) to document("plugins", id) }
        val snapshot = state.first
        val builtin = JSONObject(state.second.json).getBoolean("builtin")
        val files = snapshot.files.keys.sorted()
        val operations = if (builtin) emptyList() else listOf("新增文本文件", "导入文件", "替换整个修订 ZIP")
        menu("$id @ ${snapshot.revision}", files + operations, { index ->
            if (index < files.size) pluginFile(snapshot, files[index], builtin)
            else when (index - files.size) {
                0 -> edit("包内相对文件路径", "lib/new.js") { name ->
                    io { AgentPlugins.path(name); require(name !in snapshot.files) { "文件已存在：$name" } }
                    edit(name, "") { source -> savePluginFiles(snapshot, snapshot.files + (name to source.toByteArray(Charsets.UTF_8))) }
                }
                1 -> edit("导入到包内相对路径", "assets/resource.bin") { name ->
                    io { AgentPlugins.path(name) }
                    importFile { bytes -> savePluginFiles(snapshot, snapshot.files + (name to bytes)) }
                }
                2 -> importFile(arrayOf("application/zip", "application/octet-stream")) { bytes ->
                    savePluginFiles(snapshot, io { AgentPlugins.readZip(bytes.inputStream()) })
                }
            }
        })
    }

    private fun pluginFile(snapshot: AgentPluginSnapshot, path: String, builtin: Boolean) {
        val actions = listOf("查看 UTF-8 内容", "导出原文件") + if (builtin) emptyList() else listOf("编辑 UTF-8 内容", "导入替换文件", "删除文件")
        menu(path, actions, { index ->
            when (index) {
                0 -> showText(path, io { snapshot.text(path) })
                1 -> exportFile(path.substringAfterLast('/'), snapshot.files.getValue(path))
                2 -> edit(path, io { snapshot.text(path) }) { source -> savePluginFiles(snapshot, snapshot.files + (path to source.toByteArray(Charsets.UTF_8))) }
                3 -> importFile { bytes -> savePluginFiles(snapshot, snapshot.files + (path to bytes)) }
                4 -> confirm("删除包内文件 $path？清单引用仍需完整。") { savePluginFiles(snapshot, snapshot.files - path) }
            }
        })
    }

    private suspend fun savePluginFiles(snapshot: AgentPluginSnapshot, files: Map<String, ByteArray>) {
        io {
            require(AgentStore.get("plugins", snapshot.id)?.getString("revision") == snapshot.revision) { "插件已被其他编辑修改，请重新打开" }
            AgentPlugins.install(files, editingId = snapshot.id)
        }
        pluginFiles(snapshot.id)
    }

    private fun resources(namespace: String): Job = work {
        val records = io { AgentStore.dao.documents(namespace) }
        val skills = namespace == "skills"
        val actions = if (skills) listOf("新建 Skill", "导入 SKILL.md / ZIP") else listOf("新建提示词", "导入 JSON", "导出全部提示词")
        menu(if (skills) "Skill：短按开关，长按编辑" else "提示词库：唯一 key", records.map {
            val value = JSONObject(it.json)
            "${if (skills) mark(value.getBoolean("enabled")) + " " else ""}${it.key} · ${value.getString("name")}"
        } + actions, { index ->
            if (index < records.size) {
                if (skills) {
                    io { val old = records[index]; val value = JSONObject(old.json); putDocument(old, value.put("enabled", !value.getBoolean("enabled"))) }
                    changed(); resources(namespace)
                } else resourceActions(namespace, records[index].key)
            } else when (index - records.size) {
                0 -> editJson("唯一 key / 名称 / 正文", JSONObject().put("key", "").put("name", "").put("content", "").apply { if (skills) put("enabled", true) }) { value ->
                    io {
                        val key = value.getString("key")
                        AgentStore.put(namespace, key, value, 0)
                    }
                    resources(namespace)
                }
                1 -> if (skills) importFile { bytes -> val key = io { importSkill(bytes) }; resourceActions("skills", key) }
                    else importFile { bytes -> io { importPrompts(bytes) }; resources("prompts") }
                2 -> exportFile("agent-prompts.json", io {
                    JSONArray(records.map { JSONObject(it.json).put("key", it.key).apply { remove("owner"); remove("path") } }).toString(2).toByteArray(Charsets.UTF_8)
                })
            }
        }, { index -> if (index < records.size) resourceActions(namespace, records[index].key) })
    }

    private fun resourceActions(namespace: String, key: String): Job = work {
        val old = io { document(namespace, key) }
        val value = JSONObject(old.json)
        val actions = listOf("编辑正文", "编辑名称", "删除") + if (namespace == "skills") listOf("资源文件", "导出 Skill ZIP") else emptyList()
        menu(key, actions, { index ->
            when (index) {
                0 -> edit("$key 正文", value.getString("content")) { io { putDocument(old, value.put("content", it)) } }
                1 -> edit("名称（key 保持不变）", value.getString("name")) { io { putDocument(old, value.put("name", it)) } }
                2 -> confirm("删除 $key？依赖该 key 的运行会直接报告缺失。") {
                    io {
                        AgentStore.database.runInTransaction {
                            require(document(namespace, key).revision == old.revision) { "资源已被修改，请重新打开" }
                            AgentStore.dao.deleteDocument(namespace, key)
                            if (namespace == "skills") AgentStore.dao.documents("skill.resources.$key").forEach { AgentStore.dao.deleteDocument(it.namespace, it.key) }
                        }
                    }
                    resources(namespace)
                }
                3 -> skillFiles(key)
                4 -> exportFile("$key.zip", io { exportSkill(key) })
            }
        })
    }

    private fun skillFiles(key: String): Job = work {
        val files = io { AgentStore.dao.documents("skill.resources.$key") }
        menu("$key 支持资源", files.map { it.key } + listOf("新增文本资源", "导入资源文件"), { index ->
            if (index < files.size) skillFile(files[index])
            else edit("资源相对路径（正文由 SKILL.md 管理）", "references/reference.md") { name ->
                io { AgentPlugins.path(name); require(name != "SKILL.md") { "请在 Skill 正文中编辑 SKILL.md" } }
                if (index == files.size) edit(name, "") { content ->
                    io { saveSkillFile(key, name, content.toByteArray(Charsets.UTF_8), 0) }; skillFiles(key)
                } else importFile { bytes -> io { saveSkillFile(key, name, bytes, 0) }; skillFiles(key) }
            }
        })
    }

    private fun skillFile(old: AgentDocument): Unit = menu(old.key, listOf("查看 UTF-8 内容", "编辑 UTF-8 内容", "导出原文件", "导入替换", "删除资源"), { index ->
        val bytes = io { Base64.decode(JSONObject(old.json).getString("base64"), Base64.DEFAULT) }
        val skillKey = old.namespace.removePrefix("skill.resources.")
        when (index) {
            0 -> showText(old.key, io { AgentPlugins.text(bytes) })
            1 -> edit(old.key, io { AgentPlugins.text(bytes) }) { content -> io { saveSkillFile(skillKey, old.key, content.toByteArray(Charsets.UTF_8), old.revision) } }
            2 -> exportFile(old.key.substringAfterLast('/'), bytes)
            3 -> importFile { replacement -> io { saveSkillFile(skillKey, old.key, replacement, old.revision) }; skillFiles(skillKey) }
            4 -> confirm("删除资源 ${old.key}？") {
                io {
                    AgentStore.database.runInTransaction {
                        require(document(old.namespace, old.key).revision == old.revision) { "资源已被修改" }
                        AgentStore.dao.deleteDocument(old.namespace, old.key)
                    }
                }
                skillFiles(skillKey)
            }
        }
    })

    private fun saveSkillFile(key: String, path: String, bytes: ByteArray, revision: Long) {
        AgentPlugins.path(path)
        require(path != "SKILL.md") { "SKILL.md 由 Skill 正文管理" }
        document("skills", key)
        AgentStore.put("skill.resources.$key", path, JSONObject().put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)), revision)
    }

    private fun importSkill(bytes: ByteArray): String {
        val zip = bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()
        val files = if (zip) AgentPlugins.readZip(bytes.inputStream()) else mapOf("SKILL.md" to bytes)
        val content = AgentPlugins.text(files["SKILL.md"] ?: error("Skill ZIP 根目录缺少 SKILL.md"))
        val key = "skill.${UUID.randomUUID()}"
        val name = Regex("(?m)^name:\\s*(.+)$").find(content)?.groupValues?.get(1)?.trim()?.trim('"', '\'') ?: key
        AgentStore.database.runInTransaction {
            AgentStore.put("skills", key, JSONObject().put("name", name).put("content", content).put("enabled", true), 0)
            files.filterKeys { it != "SKILL.md" }.forEach { (path, value) -> saveSkillFile(key, path, value, 0) }
        }
        return key
    }

    private fun importPrompts(bytes: ByteArray) {
        val entries = JSONArray(AgentPlugins.text(bytes))
        AgentStore.database.runInTransaction {
            for (index in 0 until entries.length()) {
                val value = entries.getJSONObject(index)
                require(!value.has("owner") && !value.has("path")) { "独立提示词导入不能声明插件归属" }
                AgentStore.put("prompts", value.getString("key"), value, 0)
            }
        }
    }

    private fun exportSkill(key: String): ByteArray {
        val value = AgentStore.get("skills", key) ?: error("Skill 不存在：$key")
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("SKILL.md"))
                zip.write(value.getString("content").toByteArray(Charsets.UTF_8)); zip.closeEntry()
                AgentStore.dao.documents("skill.resources.$key").forEach { file ->
                    AgentPlugins.path(file.key)
                    require(file.key != "SKILL.md") { "Skill 支持资源覆盖了正文" }
                    zip.putNextEntry(ZipEntry(file.key))
                    zip.write(Base64.decode(JSONObject(file.json).getString("base64"), Base64.DEFAULT)); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun diagnostics(): Job = work {
        val runs = io { AgentStore.dao.runs() }
        menu("运行诊断", runs.map { "${it.state} · ${it.sessionId} · ${it.id}" } + "刷新", { index ->
            if (index == runs.size) diagnostics() else runActions(runs[index].id)
        })
    }

    private fun runActions(id: String): Job = work {
        val run = io { AgentStore.dao.run(id) ?: error("运行记录不存在：$id") }
        menu("${run.state} · ${run.pluginId}@${run.revision}", listOf("完整事件与实际请求", "导出完整事件", "调用栈 / 变量 / 等待输入", "暂停", "继续", "提供输入", "停止", "刷新状态"), { index ->
            when (index) {
                0 -> showText("运行 $id", io { runEvents(id).toString(2) })
                1 -> exportFile("agent-run-$id.json", io { runEvents(id).toString(2).toByteArray(Charsets.UTF_8) })
                2 -> showText("运行快照", io {
                    val latest = AgentStore.dao.run(id) ?: error("运行记录不存在")
                    JSONObject().put("state", latest.state).put("error", latest.error)
                        .put("snapshot", AgentRuntime.controls()[id]?.snapshot)
                        .put("waitingInput", AgentRuntime.controls()[id]?.waitingInput)
                        .put("latestPauseOrInput", AgentStore.dao.events(id).lastOrNull { it.type == "waiting_input" || it.type == "paused" }?.let { JSONObject(it.json) }).toString(2)
                })
                3 -> { io { activeControl(id).requestPause() }; runActions(id) }
                4 -> { io { activeControl(id).resume() }; runActions(id) }
                5 -> {
                    val prompt = io { AgentStore.dao.events(id).lastOrNull { it.type == "waiting_input" }?.json ?: error("任务没有等待输入提示") }
                    showText("任务等待的内容", prompt)
                    edit("输入内容", "") { answer ->
                        io {
                            val control = activeControl(id)
                            require(control.waitingInput) { "任务当前未等待输入" }
                            control.resume(answer)
                        }
                        runActions(id)
                    }
                }
                6 -> { io { activeControl(id).cancel() }; runActions(id) }
                7 -> runActions(id)
            }
        })
    }

    private fun activeControl(id: String): AgentControl = AgentRuntime.controls()[id] ?: error("任务已结束或中断：$id")

    private fun runEvents(id: String): JSONArray = JSONArray(AgentStore.dao.events(id).map {
        JSONObject().put("sequence", it.sequence).put("createdAt", it.createdAt).put("type", it.type).put("value", JSONObject(it.json))
    })

    private fun migrationSnapshots(): Job = work {
        val snapshots = io { AgentStore.dao.documents("migration").filter { it.key == "original" || it.key.startsWith("restore.") } }
        menu("原始迁移快照（含密钥）", snapshots.map { it.key }, { index ->
            val snapshot = snapshots[index]
            menu(snapshot.key, listOf("查看 JSON", "导出 JSON"), { selected ->
                if (selected == 0) showText("原始迁移数据", io { JSONObject(snapshot.json).toString(2) })
                else exportFile("agent-migration-${snapshot.key}.json", snapshot.json.toByteArray(Charsets.UTF_8))
            })
        })
    }

    private fun mark(enabled: Boolean): String = if (enabled) "●" else "○"
}
