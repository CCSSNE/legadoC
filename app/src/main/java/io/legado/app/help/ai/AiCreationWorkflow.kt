package io.legado.app.help.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 创作工作流：一次生成请求的完整溯源快照，落盘时写入 PNG/MP4 元数据随文件保存。
 * 术语约定（与 llmInputTemplate 模板严格区分）：
 * - llmInput：渲染后发给 LLM 的完整输入（LLM 输入；路由选中的提示词模板文本 + 素材组合），
 *   提示词页直接生成时为当时上框内容，测试连接不经过 LLM 时为空串
 * - llmImages：LLM 输入份图片——按上框（llmInput）标记解析的图片 base64 data URL，
 *   只要涉及图片就 100% 记录，不管这次有没有实际请求 LLM；与 images 相互独立
 * - llmOutput：最近一次 LLM 返回（中间大段；只由 LLM 调用写入，下框二次编辑或手填不覆盖它，
 *   本次生产没调过 LLM 时为空串）
 * - prompt：下框终稿，LLM 产出填入后经二次编辑或手填、经 {{prompt}} 填入请求模板发给生图/生视频 API 的最终文本
 * - images：随生图/生视频请求实际发出的图片 data URL（按提示词标记顺序解析；无图为空表）
 * - request：渲染后的完整请求体（全部占位符已替换为实际值；引用图占位的模板此处即含图片 data URL）
 * 只做溯源：不写 API Key 与自定义请求头，避免元数据随文件外泄密钥。
 */
data class AiCreationWorkflow(
    val type: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val variables: Map<String, String>,
    val llmInput: String,
    val llmOutput: String = "",
    val prompt: String,
    val request: String,
    val images: List<String> = emptyList(),
    val llmImages: List<String> = emptyList()
) {

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("type", type)
        root.put("createdAt", System.currentTimeMillis())
        root.put(
            "provider",
            JSONObject().put("name", providerName).put("baseUrl", baseUrl)
        )
        root.put("model", model)
        root.put("variables", JSONObject(variables))
        root.put("llmInput", llmInput)
        root.put("llmImages", JSONArray(llmImages))
        root.put("llmOutput", llmOutput)
        root.put("prompt", prompt)
        root.put("images", JSONArray(images))
        root.put("request", runCatching { JSONObject(request) }.getOrNull() ?: request)
        return root.toString()
    }

    companion object {
        const val APP_TAG = "legadoC"

        /** PNG 文本块 key（与 ComfyUI 的 workflow chunk 同名，通用查看工具也能看到） */
        const val PNG_TEXT_KEY = "workflow"

        /** MP4 mdta keys 元数据 key */
        const val MP4_META_KEY = "com.legado.aiworkflow"

        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"

        /** 文本出口的图片占位：data URL 原字节只住在文件里，拿出来的一律换成这个 */
        const val IMAGE_PLACEHOLDER = "图片"

        /**
         * 文本出口脱敏：递归走一遍，把所有 data URL 原字节换成图片标记；
         * 数组计数组，个数对得上；不是 JSON 原样返回。存库文件本身不动。
         */
        fun redactDataUrls(text: String): String {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return text
            redactObject(root)
            return root.toString()
        }

        private fun redactObject(json: JSONObject) {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = json.opt(key)) {
                    is JSONObject -> redactObject(value)
                    is JSONArray -> redactArray(value)
                    is String -> if (isDataUrl(value)) json.put(key, IMAGE_PLACEHOLDER)
                }
            }
        }

        private fun redactArray(array: JSONArray) {
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is JSONObject -> redactObject(value)
                    is JSONArray -> redactArray(value)
                    is String -> if (isDataUrl(value)) array.put(index, IMAGE_PLACEHOLDER)
                }
            }
        }

        private fun isDataUrl(value: String): Boolean =
            value.startsWith("data:") && value.contains(";base64,")

        /** 解析我们写入的工作流 JSON；非本应用格式（如 ComfyUI 原生图）返回 null */
        fun fromJsonString(text: String): AiCreationWorkflow? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (root.optString("app") != APP_TAG) return null
            val provider = root.optJSONObject("provider")
            val variables = root.optJSONObject("variables")
            //images/llmImages 是后加的溯源项：旧文件没有该数组时按空表读，不视为损坏
            val images = root.optJSONArray("images")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        add(array.optString(index))
                    }
                }
            }.orEmpty()
            val llmImages = root.optJSONArray("llmImages")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        add(array.optString(index))
                    }
                }
            }.orEmpty()
            return AiCreationWorkflow(
                type = root.optString("type"),
                providerName = provider?.optString("name").orEmpty(),
                baseUrl = provider?.optString("baseUrl").orEmpty(),
                model = root.optString("model"),
                variables = variables?.let { json ->
                    buildMap {
                        json.keys().forEach { key -> put(key, json.optString(key)) }
                    }
                }.orEmpty(),
                llmInput = root.optString("llmInput"),
                llmOutput = root.optString("llmOutput"),
                prompt = root.optString("prompt"),
                request = root.opt("request")?.toString().orEmpty(),
                images = images,
                llmImages = llmImages
            )
        }
    }
}
