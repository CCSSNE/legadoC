package io.legado.app.help.agent

import com.script.rhino.RhinoScriptEngine
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Context
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class AgentPluginSnapshot(
    val id: String, val revision: String, val manifest: JSONObject,
    val files: Map<String, ByteArray>, val dependencies: Map<String, AgentPluginSnapshot>,
    val prompts: Map<String, JSONObject>, val skills: Map<String, JSONObject>, val settings: JSONObject,
    val skillResources: Map<String, Map<String, JSONObject>>
) {
    fun text(path: String): String = AgentPlugins.text(files[path] ?: error("$id@$revision 缺少文件：$path"))
}

object AgentPlugins {
    const val API_VERSION = 1
    val root: File get() = File(appCtx.filesDir, "agent/plugins")
    private val validId = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*")

    fun path(value: String): String {
        require(value.isNotBlank() && !value.startsWith('/') && !value.contains('\\') &&
            !value.contains(':') && !value.contains('\u0000') &&
            value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "非法包路径：$value" }
        return value
    }

    fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun readZip(input: InputStream): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        val names = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = path(entry.name.removeSuffix("/"))
                require(names.add(name.lowercase())) { "ZIP 内重复路径：$name" }
                if (!entry.isDirectory) files[name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return files
    }

    @Synchronized
    fun install(files: Map<String, ByteArray>, editingId: String? = null, builtin: Boolean = false): String {
        val manifest = validate(files)
        val id = manifest.getString("id")
        require(editingId == null || editingId == id) { "编辑不能更换插件 ID；请复制为新包" }
        val existing = AgentStore.get("plugins", id)
        require(existing == null || editingId == id) { "插件 ID 已存在：$id" }
        require(existing == null || !existing.getBoolean("builtin")) { "内置包只能复制后修改" }
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (name, bytes) ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
            digest.update(0.toByte())
        }
        val revision = digest.digest().joinToString("") { "%02x".format(it) }
        val directory = File(root, "$id/$revision")
        require(directory.canonicalPath.startsWith(root.canonicalPath + File.separator))
        if (!directory.exists()) {
            check(directory.mkdirs()) { "无法创建插件目录：$directory" }
            files.forEach { (name, bytes) ->
                val target = File(directory, name)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                target.writeBytes(bytes)
            }
        } else {
            val installed = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes() }
            require(installed.keys == files.keys && files.all { (name, bytes) -> installed.getValue(name).contentEquals(bytes) }) {
                "修订目录不完整或已损坏：$id@$revision；未切换到损坏代码"
            }
        }
        AgentStore.database.runInTransaction {
            publishResources(id, manifest, files, "prompts")
            publishResources(id, manifest, files, "skills")
            AgentStore.put("plugin.revisions", "$id@$revision", manifest)
            AgentStore.put("plugins", id, JSONObject().put("name", manifest.getString("name"))
                .put("revision", revision).put("builtin", builtin).put("enabled", existing?.getBoolean("enabled") ?: true))
            if (AgentStore.get("plugin.settings", id) == null) {
                AgentStore.put("plugin.settings", id, manifest.optJSONObject("settings")?.optJSONObject("defaults") ?: JSONObject())
            }
        }
        return id
    }

    private fun publishResources(id: String, manifest: JSONObject, files: Map<String, ByteArray>, category: String) {
        val entries = manifest.optJSONObject(category) ?: return
        entries.keys().forEach { key ->
            val previous = AgentStore.get(category, key)
            require(previous == null || previous.optString("owner") == id) { "$category key 冲突：$key" }
            val resourcePath = entries.getString(key)
            AgentStore.put(category, key, JSONObject().put("name", key).put("owner", id)
                .put("path", resourcePath).put("enabled", previous?.optBoolean("enabled", true) ?: true)
                .put("content", text(files.getValue(resourcePath))))
            if (category == "skills") {
                require(resourcePath.endsWith("/SKILL.md") || resourcePath == "SKILL.md") { "Skill 入口必须为 SKILL.md：$resourcePath" }
                val prefix = resourcePath.removeSuffix("SKILL.md")
                val namespace = "skill.resources.$key"
                AgentStore.dao.documents(namespace).forEach { AgentStore.dao.deleteDocument(namespace, it.key) }
                files.filterKeys { it.startsWith(prefix) && it != resourcePath }.forEach { (name, bytes) ->
                    AgentStore.put(namespace, name.removePrefix(prefix), JSONObject().put("base64",
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                }
            }
        }
    }

    fun validate(files: Map<String, ByteArray>): JSONObject {
        require(files.keys.map { path(it).lowercase() }.toSet().size == files.size) { "包内路径重复" }
        val manifest = JSONObject(text(files["manifest.json"] ?: error("缺少 manifest.json")))
        require(validId.matches(manifest.getString("id"))) { "非法插件 ID" }
        require(manifest.getString("name").isNotBlank() && manifest.getString("version").isNotBlank())
        require(manifest.getInt("hostApiVersion") == API_VERSION) { "不支持的宿主 API：${manifest.opt("hostApiVersion")}" }
        val entry = path(manifest.getString("entry"))
        require(entry.endsWith(".js") && files.containsKey(entry)) { "入口缺失或不是 .js：$entry" }
        listOf("prompts", "skills").forEach { category ->
            manifest.optJSONObject(category)?.let { entries ->
                entries.keys().forEach { key ->
                    require(key.isNotBlank() && files.containsKey(path(entries.getString(key)))) { "$category 引用缺失：$key" }
                }
            }
        }
        val dependencies = manifest.optJSONObject("dependencies") ?: JSONObject()
        val tools = manifest.optJSONArray("tools") ?: JSONArray()
        val toolIds = mutableSetOf<String>()
        for (index in 0 until tools.length()) {
            val tool = tools.getJSONObject(index)
            require(toolIds.add(tool.getString("id")) && tool.getString("id").isNotBlank()) { "插件工具 ID 为空或重复" }
            require(files.containsKey(path(tool.getString("entry"))) && tool.getString("entry").endsWith(".js")) { "插件工具入口缺失" }
            tool.getString("description")
            require(tool.getJSONObject("inputSchema").getString("type") == "object") { "工具输入 schema 必须为 object" }
        }
        manifest.optJSONObject("settings")?.let { settings ->
            io.legado.app.help.agent.mcp.AgentSchema.validate(settings.getJSONObject("defaults"), settings.getJSONObject("schema"), "settings.defaults")
        }
        listOf("prompts", "skills").forEach { category ->
            val references = dependencies.optJSONArray(category) ?: JSONArray()
            for (index in 0 until references.length()) {
                val key = references.getString(index)
                require(AgentStore.get(category, key) != null || manifest.optJSONObject(category)?.has(key) == true) {
                    "$category 依赖缺失：$key"
                }
            }
        }
        dependencies.optJSONObject("plugins")?.let { references ->
            references.keys().forEach { id -> snapshot(id, references.getString(id)) }
        }
        RhinoScriptEngine.hashCode()
        val context = Context.enter()
        try {
            files.filterKeys { it.endsWith(".js") }.forEach { (name, bytes) ->
                context.compileString("(function(require,module,exports,host){\n${text(bytes)}\n})", "${manifest.getString("id")}/$name", 0, null)
            }
        } finally {
            Context.exit()
        }
        return manifest
    }

    @Synchronized
    fun snapshot(id: String, revision: String? = null, parents: Set<String> = emptySet(), allowDisabled: Boolean = false): AgentPluginSnapshot {
        require(id !in parents) { "插件依赖循环：${parents.joinToString(" -> ")} -> $id" }
        val metadata = AgentStore.get("plugins", id) ?: error("插件不存在：$id")
        check(allowDisabled || metadata.getBoolean("enabled")) { "插件已关闭：$id" }
        val selected = revision ?: metadata.getString("revision")
        val manifest = AgentStore.get("plugin.revisions", "$id@$selected") ?: error("插件修订不存在：$id@$selected")
        val directory = File(root, "$id/$selected")
        require(directory.canonicalPath.startsWith(root.canonicalPath + File.separator))
        check(directory.isDirectory) { "插件修订文件丢失：$id@$selected" }
        val files = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (name, bytes) -> digest.update(name.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()); digest.update(bytes); digest.update(0.toByte()) }
        require(digest.digest().joinToString("") { "%02x".format(it) } == selected) { "插件修订文件完整性损坏：$id@$selected" }
        val references = manifest.optJSONObject("dependencies")?.optJSONObject("plugins") ?: JSONObject()
        val dependencies = references.keys().asSequence().associateWith { snapshot(it, references.getString(it), parents + id, allowDisabled) }
        // 不可变修订快照：全局 prompts/skills 在任务启动时冻结；本修订声明的 key 正文与 Skill 资源
        // 以本修订文件为准，不读取全局最新 DB，避免旧修订任务读到新版资源。
        val ownedPrompts = manifest.optJSONObject("prompts") ?: JSONObject()
        val ownedSkills = manifest.optJSONObject("skills") ?: JSONObject()
        fun promptSnapshot(key: String): JSONObject {
            if (ownedPrompts.has(key)) {
                val resourcePath = path(ownedPrompts.getString(key))
                val content = text(files[resourcePath] ?: error("$id@$selected 缺少提示词文件：$resourcePath"))
                val current = AgentStore.get("prompts", key)
                return JSONObject().put("name", key).put("owner", id).put("path", resourcePath)
                    .put("enabled", current?.optBoolean("enabled", true) ?: true).put("content", content)
            }
            return AgentStore.get("prompts", key) ?: error("$id@$selected 缺少 prompts 依赖：$key")
        }
        fun skillSnapshot(key: String): JSONObject {
            if (ownedSkills.has(key)) {
                val resourcePath = path(ownedSkills.getString(key))
                require(resourcePath.endsWith("/SKILL.md") || resourcePath == "SKILL.md") { "Skill 入口必须为 SKILL.md：$resourcePath" }
                val content = text(files[resourcePath] ?: error("$id@$selected 缺少 Skill 文件：$resourcePath"))
                val current = AgentStore.get("skills", key)
                return JSONObject().put("name", key).put("owner", id).put("path", resourcePath)
                    .put("enabled", current?.optBoolean("enabled", true) ?: true).put("content", content)
            }
            return AgentStore.get("skills", key) ?: error("$id@$selected 缺少 skills 依赖：$key")
        }
        fun skillResourcesSnapshot(key: String): Map<String, JSONObject> {
            if (ownedSkills.has(key)) {
                val resourcePath = path(ownedSkills.getString(key))
                val prefix = resourcePath.removeSuffix("SKILL.md")
                return files.filterKeys { it.startsWith(prefix) && it != resourcePath }.mapValues { (_, bytes) ->
                    JSONObject().put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                }
            }
            return AgentStore.dao.documents("skill.resources.$key").associate { it.key to JSONObject(it.json) }
        }
        val promptKeys = linkedSetOf<String>().apply {
            AgentStore.dao.documents("prompts").forEach { add(it.key) }
            ownedPrompts.keys().forEach(::add)
            manifest.optJSONObject("dependencies")?.optJSONArray("prompts")?.let { array ->
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }
        val skillKeys = linkedSetOf<String>().apply {
            AgentStore.dao.documents("skills").forEach { add(it.key) }
            ownedSkills.keys().forEach(::add)
            manifest.optJSONObject("dependencies")?.optJSONArray("skills")?.let { array ->
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }
        // 依赖声明的完整性仍需校验，未声明的 key 不得隐式使用。
        manifest.optJSONObject("dependencies")?.let { declared ->
            listOf("prompts", "skills").forEach { category ->
                val refs = declared.optJSONArray(category) ?: JSONArray()
                for (index in 0 until refs.length()) {
                    val key = refs.getString(index)
                    require(key in if (category == "prompts") promptKeys else skillKeys) { "$id@$selected 快照缺失 $category：$key" }
                    if (category == "prompts") promptSnapshot(key) else skillSnapshot(key)
                }
            }
        }
        val prompts = promptKeys.associateWith(::promptSnapshot)
        val skills = skillKeys.associateWith(::skillSnapshot)
        val skillResources = skillKeys.associateWith(::skillResourcesSnapshot)
        // 存量配置可能缺少新增键：manifest defaults 兜底，用户已保存的键优先，合并后再按 schema 校验。
        val stored = AgentStore.get("plugin.settings", id) ?: error("插件配置缺失：$id")
        val settings = manifest.optJSONObject("settings")?.optJSONObject("defaults")
            ?.let { defaults -> JSONObject(defaults.toString()).apply { stored.keys().forEach { key -> put(key, stored.get(key)) } } }
            ?: stored
        manifest.optJSONObject("settings")?.getJSONObject("schema")?.let { io.legado.app.help.agent.mcp.AgentSchema.validate(settings, it, "$id.settings") }
        return AgentPluginSnapshot(id, selected, manifest, files, dependencies, prompts, skills, settings, skillResources)
    }

    fun export(id: String, output: OutputStream) {
        val snapshot = snapshot(id, allowDisabled = true)
        ZipOutputStream(output).use { zip ->
            snapshot.files.toSortedMap().forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    fun copy(id: String, newId: String): String {
        val original = snapshot(id, allowDisabled = true)
        val files = original.files.toMutableMap()
        val manifest = JSONObject(original.manifest.toString()).put("id", newId)
            .put("name", original.manifest.getString("name") + " 副本")
        val dependencies = manifest.optJSONObject("dependencies") ?: JSONObject().also { manifest.put("dependencies", it) }
        listOf("prompts", "skills").forEach { category ->
            val references = dependencies.optJSONArray(category) ?: JSONArray()
            manifest.optJSONObject(category)?.keys()?.forEach { references.put(it) }
            dependencies.put(category, references)
            manifest.remove(category)
        }
        files["manifest.json"] = manifest.toString(2).toByteArray(Charsets.UTF_8)
        return install(files)
    }

    fun selectRevision(id: String, revision: String) {
        snapshot(id, revision, allowDisabled = true)
        AgentStore.put("plugins", id, AgentStore.get("plugins", id)!!.put("revision", revision))
    }

    fun delete(id: String) {
        val metadata = AgentStore.get("plugins", id) ?: error("插件不存在：$id")
        require(!metadata.getBoolean("builtin")) { "内置插件不能删除" }
        require(AgentConfig.mode != id && !AgentRuntime.usesPlugin(id)) { "请先切换模式并停止使用此插件的任务" }
        AgentStore.dao.documents("plugin.revisions").forEach { document ->
            if (!document.key.startsWith("$id@")) require(JSONObject(document.json).optJSONObject("dependencies")
                ?.optJSONObject("plugins")?.has(id) != true) { "仍有插件依赖 $id：${document.key}" }
        }
        AgentStore.dao.deleteDocument("plugins", id)
    }

    fun installBuiltin() {
        if (AgentStore.get("plugins", "legado.reader") != null) return
        val files = linkedMapOf<String, ByteArray>()
        fun read(directory: String) {
            appCtx.assets.list(directory)!!.forEach { name ->
                val resource = "$directory/$name"
                if (appCtx.assets.list(resource)!!.isNotEmpty()) read(resource)
                else files[resource.removePrefix("agent/default/")] = appCtx.assets.open(resource).use { it.readBytes() }
            }
        }
        read("agent/default")
        install(files, builtin = true)
    }
}
