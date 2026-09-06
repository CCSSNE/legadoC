package io.legado.app.help.agent

import com.script.rhino.RhinoContext
import com.script.rhino.RhinoScriptEngine
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.debug.DebugFrame
import org.mozilla.javascript.debug.DebuggableScript
import org.mozilla.javascript.debug.Debugger
import java.util.IdentityHashMap

class AgentScript(private val execution: AgentExecution) : AutoCloseable {
    private val context: RhinoContext
    private val scope: ScriptableObject
    private val descriptorGetter: Function
    private val modules = mutableMapOf<String, Scriptable>()
    private val frames = mutableListOf<JSONObject>()
    private val hosts = mutableMapOf<String, Scriptable>()

    init {
        RhinoScriptEngine.hashCode()
        context = Context.enter() as RhinoContext
        context.coroutineContext = execution.control.job
        context.allowScriptRun = true
        try {
            context.isGeneratingDebug = true
            context.setDebugger(object : Debugger {
                override fun handleCompilationDone(context: Context, script: DebuggableScript, source: String) = Unit
                override fun getFrame(context: Context, script: DebuggableScript): DebugFrame = frame(script)
            }, null)
            scope = context.initSafeStandardObjects()
            val objectConstructor = ScriptableObject.getProperty(scope, "Object") as Scriptable
            descriptorGetter = ScriptableObject.getProperty(objectConstructor, "getOwnPropertyDescriptor") as Function
        } catch (error: Throwable) {
            context.setDebugger(null, null)
            context.coroutineContext = null
            context.allowScriptRun = false
            Context.exit()
            throw error
        }
    }

    private fun host(snapshot: AgentPluginSnapshot): Scriptable = hosts.getOrPut("${snapshot.id}@${snapshot.revision}") {
        val bridge = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                execution.control.checkpoint()
                val operation = Context.toString(args[0])
                val value = JSONObject(Context.toString(args[1]))
                val callback = args.getOrNull(2) as? Function
                val result = execution.invoke(operation, value, snapshot) { event, data ->
                    callback?.call(context, this@AgentScript.scope, this@AgentScript.scope, arrayOf(event, data)) == true
                }
                return JSONObject().put("value", result ?: JSONObject.NULL).toString()
            }
        }
        ScriptableObject.putProperty(scope, "__agentBridge", bridge)
        val result = context.evaluateString(scope, """
            (function(bridge) {
                return Object.freeze({
                    call: function(operation, value, callback) {
                        return JSON.parse(bridge(operation, JSON.stringify(value || {}), callback)).value;
                    }
                });
            })(__agentBridge)
        """.trimIndent(), "agent:host-api", 1, null) as Scriptable
        ScriptableObject.deleteProperty(scope, "__agentBridge")
        result
    }

    fun execute(snapshot: AgentPluginSnapshot, entry: String, export: String, input: JSONObject): Any? {
        val module = load(snapshot, AgentPlugins.path(entry))
        val exports = ScriptableObject.getProperty(module, "exports") as? Scriptable ?: error("模块必须导出对象：$entry")
        val function = ScriptableObject.getProperty(exports, export) as? Function ?: error("模块缺少函数：$entry#$export")
        val jsonParser = ScriptableObject.getProperty(scope, "JSON") as Scriptable
        val parse = ScriptableObject.getProperty(jsonParser, "parse") as Function
        val inputObject = parse.call(context, scope, jsonParser, arrayOf(input.toString()))
        val result = function.call(context, scope, scope, arrayOf(inputObject, host(snapshot)))
        if (result == null || result is Undefined) return null
        val json = ScriptableObject.getProperty(scope, "JSON") as Scriptable
        val stringify = ScriptableObject.getProperty(json, "stringify") as Function
        val encoded = stringify.call(context, scope, json, arrayOf(result))
        return if (encoded is Undefined) null else org.json.JSONTokener(Context.toString(encoded)).nextValue()
    }

    private fun load(snapshot: AgentPluginSnapshot, file: String): Scriptable {
        val key = "${snapshot.id}@${snapshot.revision}/$file"
        modules[key]?.let { return it }
        val module = context.newObject(scope)
        val exports = context.newObject(scope)
        ScriptableObject.putProperty(module, "exports", exports)
        modules[key] = module
        val requireFunction = object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val requested = Context.toString(args[0])
                val target: AgentPluginSnapshot
                val targetPath: String
                if (requested.startsWith('@')) {
                    val dependencyId = requested.substringAfter('@').substringBefore('/')
                    target = snapshot.dependencies[dependencyId] ?: error("未声明插件依赖：$dependencyId")
                    targetPath = AgentPlugins.path(requested.substringAfter('/'))
                } else {
                    target = snapshot
                    targetPath = AgentPlugins.path(requested)
                }
                require(targetPath.endsWith(".js")) { "require 使用包根相对完整 .js 路径：$requested" }
                return ScriptableObject.getProperty(load(target, targetPath), "exports")
            }
        }
        val factory = context.evaluateString(scope,
            "(function(require,module,exports,host){\n${snapshot.text(file)}\n})", key, 0, null) as Function
        factory.call(context, scope, scope, arrayOf(requireFunction, module, exports, host(snapshot)))
        return module
    }

    private fun frame(script: DebuggableScript): DebugFrame = object : DebugFrame {
        private var activation: Scriptable? = null
        private val location = JSONObject().put("file", script.sourceName).put("function", script.functionName)
        override fun onEnter(context: Context, activation: Scriptable, thisObj: Scriptable, args: Array<out Any?>) {
            this.activation = activation
            frames.add(location)
        }
        private fun capture(): JSONObject {
            val variables = JSONObject()
            activation?.let { active ->
                active.ids.forEach { id ->
                    val key = id.toString()
                    val value = if (id is Number) active.get(id.toInt(), active) else active.get(key, active)
                    variables.put(key, variable(value, IdentityHashMap()))
                }
            }
            return JSONObject().put("stack", JSONArray(frames)).put("variables", variables)
        }
        override fun onLineChange(context: Context, lineNumber: Int) {
            location.put("line", lineNumber)
            execution.control.check()
            if (execution.control.paused) execution.control.checkpoint(capture())
        }
        override fun onExceptionThrown(context: Context, error: Throwable) {
            execution.lastStack = capture().put("error", error.toString())
        }
        override fun onExit(context: Context, byThrow: Boolean, resultOrException: Any?) {
            frames.remove(location)
        }
        override fun onDebuggerStatement(context: Context) {
            execution.control.checkpoint(capture(), force = true)
        }
    }

    private fun variable(value: Any?, visited: IdentityHashMap<ScriptableObject, Boolean>): Any = when (value) {
        null, is Undefined -> JSONObject.NULL
        is String, is Number, is Boolean -> value
        is Function -> "[function]"
        is ScriptableObject -> {
            if (visited.put(value, true) != null) "[circular reference]"
            else JSONObject().apply {
                value.ids.forEach { id ->
                    val descriptor = descriptorGetter.call(context, scope, scope, arrayOf(value, id)) as? Scriptable
                    val data = if (descriptor?.has("value", descriptor) == true) descriptor.get("value", descriptor) else Scriptable.NOT_FOUND
                    put(id.toString(), if (data == Scriptable.NOT_FOUND) "[accessor]" else variable(data, visited))
                }
            }
        }
        is Scriptable -> "[${value.className}]"
        else -> "[${value.javaClass.simpleName}]"
    }

    override fun close() {
        context.setDebugger(null, null)
        context.coroutineContext = null
        context.allowScriptRun = false
        Context.exit()
    }
}
