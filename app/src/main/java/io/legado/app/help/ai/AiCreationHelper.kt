package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import org.json.JSONArray
import org.json.JSONObject

object AiCreationHelper {

    /**
     * 提示词页上框即完整LLM输入：路由提示词与模板已渲染，直接原样发给LLM，不再二次套模板。
     * 发送前先校验上框标记（重号/跳号/悬空错哪指哪）；供应商支持多模态时 userContent 变为
     * 多模态数组，第一块永远是上框文本，图片按标记顺序转 base64 跟在后面；
     * 供应商不支持时图片就地转成文字占位嵌回数组（DSH 同款），文字照发、请求不报错；
     * 标记校验只看用户带没带图（上框有没有标记），跟模型长没长眼睛无关：
     * 标记是给下游生图步骤的路由，LLM 看不见图也必须原样保留，否则下游收不到图；
     * 返回后校验标记，不合格重新生成。
     */
    suspend fun generatePromptFromLlmInput(
        session: AiCreationSession,
        llmInput: String
    ): String {
        val refs = session.materialImageRefs
        validateMarkers(llmInput, refs)
        val imageCount = parseMarkers(llmInput).size
        val target = AiCreationConfig.requireModelTarget()
        val vision = target.provider.supportsVision
        //有图规则路由：本节 markerRule 点名的提示词库条目，只要用户带了图就追加，
        //跟供应商支不支持多模态无关（规则管的是返回，不是眼睛）
        val mode = session.paramValue(AI_CREATION_MODE_KEY)
        val markerRule = when (mode) {
            AiCreationVariables.GROUP_IMAGE -> AiCreationConfig.imageLlmDefinition.markerRule
            AiCreationVariables.GROUP_VIDEO -> AiCreationConfig.videoLlmDefinition.markerRule
            else -> error("未知 AI 创作模式：$mode")
        }
        val content = buildLlmUserContent(llmInput, refs, imageCount, vision, markerRule)
        AppLog.putAi(
            "AI_CREATION REQUEST\n" +
                "provider=${target.provider.name}\n" +
                "model=${target.modelId}\n" +
                "llmInputChars=${llmInput.length}\n" +
                "imageCount=$imageCount\n" +
                "supportVision=$vision"
        )
        //标记校验只看用户带没带图：带了图，返回必须保留标记给下游生图用；
        //眼睛看不见不是理由，规则照发、校验照跑
        val response = sendWithMarkerValidation(target, content, imageCount)
        //工作流溯源：记录本次发给 LLM 的完整输入
        session.setParam(AI_CREATION_LLM_INPUT_KEY, llmInput)
        return response
    }

    /**
     * 标记校验（上框发送与下框生成图片共用）：标记必须是 1..M 的连续集合且各出现一次，
     * 每个标记都要能对应到集合里的现存图片文件；标记数小于集合数表示删了标记（对应图片不发）。
     */
    fun validateMarkers(text: String, refs: List<String>) {
        val markers = parseMarkers(text)
        if (markers.isEmpty()) return
        val duplicated = markers.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.sorted()
        require(duplicated.isEmpty()) {
            "图片标记重复：${duplicated.joinToString("、") { AiCreationImageMarkers.markerOf(it) }}"
        }
        val dangling = markers.filter { it > refs.size }.sorted()
        require(dangling.isEmpty()) {
            "图片标记没有对应的图片：${dangling.joinToString("、") { AiCreationImageMarkers.markerOf(it) }}"
        }
        val missing = (1..markers.max()).filterNot(markers::contains)
        require(missing.isEmpty()) {
            "图片标记跳号：缺少 ${missing.joinToString("、") { AiCreationImageMarkers.markerOf(it) }}"
        }
        val missingFiles = markers
            .map { it to AiCreationCardImages.fileOf(refs[it - 1]) }
            .filter { it.second == null }
            .map { it.first }
        require(missingFiles.isEmpty()) {
            "图片文件不存在：${missingFiles.joinToString("、") { AiCreationImageMarkers.markerOf(it) }}"
        }
    }

    /** 解析文本里的图片标记（按出现顺序；异常大编号视为悬空，不静默丢弃） */
    fun parseMarkers(text: String): List<Int> =
        AiCreationImageMarkers.REGEX.findAll(text)
            .map { it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE }
            .toList()

    /**
     * 生图/生视频链路的图片解析：按下框提示词里的标记取对应图片转 base64 data URL，
     * 顺序与 LLM 链路一致（1..N 数字序）；无标记返回空表（纯文生）；
     * 标记与集合不一致或文件缺失直接报错，不静默丢图。
     */
    fun resolvePromptImageDataUrls(prompt: String, refs: List<String>): List<String> {
        val markers = parseMarkers(prompt)
        if (markers.isEmpty()) return emptyList()
        validateMarkers(prompt, refs)
        return markers.distinct().sorted().map { number ->
            AiCreationCardImages.dataUrlOf(refs[number - 1])
        }
    }

    /**
     * LLM 输入份图片溯源：按上框（llmInput）标记解析对应图片 base64 data URL，
     * 只要涉及图片就 100% 记录，不管这次有没有实际请求 LLM；
     * 编号超出图片集合的悬空标记跳过（llmInput 文本本身仍如实记录该标记），
     * 文件缺失由 dataUrlOf 直接抛错，不静默丢图。
     */
    fun resolveLlmInputImageDataUrls(llmInput: String, refs: List<String>): List<String> {
        val markers = parseMarkers(llmInput)
        if (markers.isEmpty()) return emptyList()
        return markers.distinct().sorted()
            .filter { it in 1..refs.size }
            .map { number -> AiCreationCardImages.dataUrlOf(refs[number - 1]) }
    }

    /**
     * 组装 LLM userContent：无图保持字符串不动（预填带进来的规则句同步摘掉，删标记=纯文生，
     * 不给模型留复活标记的借口）；有图时文本在前、本节 markerRule 点名的提示词库规则紧随其后
     * （预填已带就不重复缀；校验要模型保留标记，规则必须先告诉模型，
     * 眼睛支不支持都一样：标记是下游生图的路由，不是给眼睛看的），图片按标记顺序跟后；
     * 库里没有该条目直接报错；本节没点名就不追加，校验照跑；
     * 供应商不支持时图片转成文字占位嵌回数组（图没发出去，但位置和意图留下了）。
     */
    private fun buildLlmUserContent(
        llmInput: String,
        refs: List<String>,
        imageCount: Int,
        supportVision: Boolean,
        markerRule: String?
    ): Any {
        val ruleText = markerRule?.takeIf { it.isNotBlank() }?.let { ruleName ->
            if (imageCount == 0) {
                //纯文生用不上规则：库里有没有条目都无所谓，不报错
                runCatching { AiCreationConfig.promptTextOf(ruleName) }.getOrNull()
            } else {
                //有标记必须有规则：库里没条目直接报错
                AiCreationConfig.promptTextOf(ruleName)
            }
        }
        if (imageCount == 0) {
            //用户删光标记=纯文生：预填带进来的规则句同步摘掉，不给模型留复活标记的借口
            if (ruleText == null) return llmInput
            return llmInput.replace("\n\n$ruleText", "").replace(ruleText, "").trim()
        }
        if (!supportVision) {
            val array = JSONArray()
            val firstText = buildString {
                append(llmInput)
                //预填已带规则就不重复缀；图片后挂载的不经过预填，发送时补上
                if (ruleText != null && !llmInput.contains(ruleText)) {
                    append("\n\n")
                    append(ruleText)
                }
            }
            array.put(JSONObject().put("type", "text").put("text", firstText))
            for (index in 1..imageCount) {
                array.put(
                    JSONObject().put("type", "text").put(
                        "text",
                        "${AiCreationImageMarkers.markerOf(index)}已省略：当前供应商不支持图片输入"
                    )
                )
            }
            return array
        }
        val text = buildString {
            append(llmInput)
            if (ruleText != null && !llmInput.contains(ruleText)) {
                append("\n\n")
                append(ruleText)
            }
        }
        val array = JSONArray()
        array.put(JSONObject().put("type", "text").put("text", text))
        for (index in 1..imageCount) {
            val ref = refs[index - 1]
            array.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", AiCreationCardImages.dataUrlOf(ref)))
            )
        }
        return array
    }

    /**
     * 发送并校验返回标记：返回的提示词必须原样保留全部标记（数量一致、编号连续），
     * 不合格按 AI 设置里的重新生成上限重发；仍不合格如实报错，不静默吞。
     */
    private suspend fun sendWithMarkerValidation(
        target: AiCreationModelTarget,
        content: Any,
        imageCount: Int
    ): String {
        val limit = AiCreationConfig.promptRegenerateLimit
        var lastProblem = ""
        for (attempt in 0..limit) {
            if (attempt > 0) {
                AppLog.putAi(
                    "AI_CREATION PROMPT_REGENERATE\n" +
                        "attempt=$attempt\n" +
                        "limit=$limit\n" +
                        "problem=$lastProblem"
                )
            }
            val response = AiChatService.generatePlainText(
                provider = target.provider,
                model = target.modelId,
                userContent = content,
                temperature = 0.7
            )
            val problem = markerProblem(response, imageCount) ?: return response
            lastProblem = problem
        }
        throw IllegalStateException(
            "提示词标记校验未通过（已生成 ${limit + 1} 次）：$lastProblem"
        )
    }

    /** 返回文本的标记校验：无图不校验；有图时数量必须一致且编号为 1..N 连续 */
    private fun markerProblem(text: String, expected: Int): String? {
        if (expected <= 0) return null
        val markers = parseMarkers(text)
        val first = AiCreationImageMarkers.markerOf(1)
        val last = AiCreationImageMarkers.markerOf(expected)
        if (markers.isEmpty()) {
            return "返回内容没有保留图片标记（应为 $first 到 $last 共 $expected 个）"
        }
        val duplicated = markers.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.sorted()
        if (duplicated.isNotEmpty()) {
            return "返回的图片标记重复：${duplicated.joinToString("、") { AiCreationImageMarkers.markerOf(it) }}"
        }
        if (markers.size != expected || !markers.containsAll((1..expected).toList())) {
            return "返回的图片标记数量或编号不符：应为 $first 到 $last 共 $expected 个，实际 ${markers.size} 个"
        }
        return null
    }

    /** 统一渲染入口：用LLM输入模板组合路由提示词与素材，得到发给LLM的完整输入；提示词页预填用 */
    fun buildLlmInput(
        session: AiCreationSession,
        materialText: String
    ): String {
        //LLM 变量与输入模板来自全局 LLM 变量设置，与供应商无关
        val mode = session.paramValue(AI_CREATION_MODE_KEY)
            ?: error("AI 创作模式未设置")
        val llmDefinition = when (mode) {
            AiCreationVariables.GROUP_IMAGE -> AiCreationConfig.imageLlmDefinition
            AiCreationVariables.GROUP_VIDEO -> AiCreationConfig.videoLlmDefinition
            else -> error("未知 AI 创作模式：$mode")
        }
        val routeParams = llmDefinition.variables.associate {
            it.key to it.effectiveValue(session.llmVariableValue(mode, it.key))
        }
        val promptName = AiCreationConfig.resolvePromptName(llmDefinition, routeParams)
        val promptText = AiCreationConfig.promptTextOf(promptName)
        var rendered = renderLlmInput(
            template = llmDefinition.llmInputTemplate,
            prompt = promptText,
            material = materialText
        )
        //预填就把规则带上框：素材里有标记才缀，用户在上框看得见、改得掉、删得掉；
        //库里没该条目直接报错，不留到发送时
        if (parseMarkers(rendered).isNotEmpty()) {
            llmDefinition.markerRule?.takeIf { it.isNotBlank() }?.let { ruleName ->
                rendered += "\n\n" + AiCreationConfig.promptTextOf(ruleName)
            }
        }
        AppLog.putAi(
            "AI_CREATION LLM_INPUT\n" +
                "route=$promptName\n" +
                "routeParams=${routeParams.entries.joinToString("，") { "${it.key}=${it.value}" }}\n" +
                "llmInputChars=${rendered.length}"
        )
        return rendered
    }

    /** 供应商变量只服务于路由与最终图片/视频请求，不进入 LLM 输入。 */
    fun buildRequestValues(
        session: AiCreationSession,
        variables: List<AiCreationVariable>
    ): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val mode = session.paramValue(AI_CREATION_MODE_KEY)
            ?: error("AI 创作模式未设置")
        variables.forEach { variable ->
            values[variable.key] = variable.effectiveValue(
                session.providerVariableValue(mode, variable.key)
            )
        }
        return values
    }

    /** 用 LLM 输入模板组合路由提示词与素材，得到发给 LLM 的完整输入。 */
    private fun renderLlmInput(
        template: String,
        prompt: String,
        material: String
    ): String {
        val rendered = template
            .replace("\${prompt}", prompt)
            .replace("\${素材}", material)
        val unresolved = TEMPLATE_VARIABLE.findAll(rendered)
            .map { it.groupValues[1] }
            .toSet()
        require(unresolved.isEmpty()) {
            "LLM 输入模板包含未定义变量：${unresolved.joinToString("、")}"
        }
        return rendered
    }

    private val TEMPLATE_VARIABLE = Regex("\\$\\{([^{}]+)\\}")
}
