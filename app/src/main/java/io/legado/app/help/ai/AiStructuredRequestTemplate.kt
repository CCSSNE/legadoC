package io.legado.app.help.ai

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

/**
 * User-editable JSON body for structured / creation / chat completion paths.
 * Provider authentication headers are deliberately kept outside this template.
 *
 * 全局默认模板是"干净通用"骨架：不带 response_format（只有章节净化需要 JSON 输出），
 * 供 AI 聊天（对话/划词/浮动面板）与 AI 创作共用。
 * {{userContent}} 独占一个字符串值时可装入数组/对象（整值原文塞），多模态内容靠它注入。
 */
object AiStructuredRequestTemplate {

    const val MODEL_TOKEN = "{{model}}"
    const val SYSTEM_PROMPT_TOKEN = "{{systemPrompt}}"
    const val USER_CONTENT_TOKEN = "{{userContent}}"

    /** 全局干净通用默认：AI 聊天与 AI 创作共用，不带 response_format，创作温度 0.7 */
    val default: String = """
        {
          "model": "$MODEL_TOKEN",
          "stream": true,
          "messages": [
            {
              "role": "system",
              "content": "$SYSTEM_PROMPT_TOKEN"
            },
            {
              "role": "user",
              "content": "$USER_CONTENT_TOKEN"
            }
          ],
          "temperature": 0.7,
          "thinking": {
            "type": "disabled"
          },
          "reasoning_effort": "low",
          "enable_thinking": false,
          "extra_body": {
            "enable_thinking": false
          }
        }
    """.trimIndent()

    /** JSON 输出专用默认：章节净化等结构化消费者的出厂模板（含 response_format，温度 0） */
    val structuredDefault: String = """
        {
          "model": "$MODEL_TOKEN",
          "stream": true,
          "messages": [
            {
              "role": "system",
              "content": "$SYSTEM_PROMPT_TOKEN"
            },
            {
              "role": "user",
              "content": "$USER_CONTENT_TOKEN"
            }
          ],
          "temperature": 0,
          "response_format": {
            "type": "json_object"
          },
          "thinking": {
            "type": "disabled"
          },
          "reasoning_effort": "low",
          "enable_thinking": false,
          "extra_body": {
            "enable_thinking": false
          }
        }
    """.trimIndent()

    /**
     * 旧版全局出厂默认（含 response_format、温度 0）：
     * 仅用于升级迁移识别"从未定制过全局模板"的存量，并作为净化固化继承快照的取值；
     * 不再作为任何出口默认。
     */
    val legacyDefault: String = """
        {
          "model": "$MODEL_TOKEN",
          "stream": true,
          "messages": [
            {
              "role": "system",
              "content": "$SYSTEM_PROMPT_TOKEN"
            },
            {
              "role": "user",
              "content": "$USER_CONTENT_TOKEN"
            }
          ],
          "temperature": 0,
          "response_format": {
            "type": "json_object"
          },
          "thinking": {
            "type": "disabled"
          },
          "reasoning_effort": "low",
          "enable_thinking": false,
          "extra_body": {
            "enable_thinking": false
          }
        }
    """.trimIndent()

    /** 全局通用请求模板存取：AI 聊天与 AI 创作共用一份（PreferKey.aiRequestTemplate） */
    var global: String
        get() = appCtx.getPrefString(PreferKey.aiRequestTemplate)
            ?.takeIf { it.isNotBlank() }
            ?: default
        set(value) = appCtx.putPrefString(PreferKey.aiRequestTemplate, value.trim())

    /**
     * 请求模板归属迁移（AI创作配置 v2 经 DefaultData 版本戳一次性执行）：
     * 旧版全局模板被聊天与净化共用（净化未配置独立模板时继承全局）；重构后全局归
     * 聊天+创作、净化独立。迁移规则：
     * - 全局存量等于旧出厂默认（或为空）＝从未定制，覆盖为新干净默认（温度 0.7、无 response_format）；
     * - 用户定制过的全局模板原样保留（其中的 response_format 是用户自己的选择）；
     * - 净化键为空时，把升级前净化实际生效的值（继承快照）固化进净化键，净化行为不变。
     * 先固化净化再覆盖全局，中途失败重试依然正确（幂等）。
     */
    fun migrateTemplateOwnership() {
        val stored = appCtx.getPrefString(PreferKey.aiRequestTemplate)
        val customized = !stored.isNullOrBlank() && stored.trim() != legacyDefault.trim()
        if (appCtx.getPrefString(PreferKey.aiChapterPurifyRequestTemplate).isNullOrBlank()) {
            val inherited = if (customized) stored!!.trim() else legacyDefault.trim()
            appCtx.putPrefString(PreferKey.aiChapterPurifyRequestTemplate, inherited)
        }
        if (!customized) {
            appCtx.putPrefString(PreferKey.aiRequestTemplate, default)
        }
    }

    fun validate(template: String) {
        val normalized = template.trim()
        require(normalized.isNotEmpty()) { "请求模板不能为空" }
        try {
            JSONObject(normalized)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "请求模板 JSON 格式错误：${throwable.message ?: throwable.javaClass.simpleName}",
                throwable
            )
        }
    }

    /**
     * 渲染请求模板。[userContent] 允许传字符串或 JSON 数组/对象：
     * 占位符独占一个字符串值时按"整值原文塞"原样放入（数组即数组，字符串即字符串）；
     * 占位符嵌在更大字符串里却拿到数组/对象时直接报错，不做静默字符串化。
     */
    fun render(
        template: String,
        model: String,
        systemPrompt: String,
        userContent: Any
    ): String {
        validate(template)
        val root = JSONObject(template.trim())
        val replacements = mapOf(
            MODEL_TOKEN to model,
            SYSTEM_PROMPT_TOKEN to systemPrompt,
            USER_CONTENT_TOKEN to userContent
        )
        replaceObject(root, replacements)
        return root.toString()
    }

    private fun replaceObject(json: JSONObject, replacements: Map<String, Any>) {
        val keys = json.keys().asSequence().toList()
        for (key in keys) {
            when (val value = json.opt(key)) {
                is JSONObject -> replaceObject(value, replacements)
                is JSONArray -> replaceArray(value, replacements)
                is String -> json.put(key, replaceString(value, replacements))
            }
        }
    }

    private fun replaceArray(array: JSONArray, replacements: Map<String, Any>) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> replaceObject(value, replacements)
                is JSONArray -> replaceArray(value, replacements)
                is String -> {
                    val exact = replacements[value]
                    array.put(index, exact ?: replaceString(value, replacements))
                }
            }
        }
    }

    private fun replaceString(value: String, replacements: Map<String, Any>): Any {
        //整值原文塞：值就是占位符本身时，替换值原样放入（可为字符串/数组/对象）
        replacements[value]?.let { return it }
        var replaced = value
        replacements.forEach { (token, replacement) ->
            if (replacement is String) {
                replaced = replaced.replace(token, replacement)
            } else if (replaced.contains(token)) {
                throw IllegalStateException(
                    "$token 必须独占一个字符串值才能装入数组或对象，不能与其他文字拼在一起"
                )
            }
        }
        return replaced
    }
}
