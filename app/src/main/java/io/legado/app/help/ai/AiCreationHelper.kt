package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import org.json.JSONArray
import org.json.JSONObject

object AiCreationHelper {

    /**
     * 提示词页上框即完整LLM输入：路由提示词与模板已渲染，直接原样发给LLM，不再二次套模板。
     * 发送前先校验上框标记（重号/跳号/悬空错哪指哪）；有图时 userContent 变为多模态数组，
     * 第一块永远是上框文本，图片按标记顺序转 base64 跟在后面；返回后校验标记，不合格重新生成。
     */
    suspend fun generatePromptFromLlmInput(
        session: AiCreationSession,
        llmInput: String
    ): String {
        val refs = session.materialImageRefs
        validateMarkers(llmInput, refs)
        val imageCount = parseMarkers(llmInput).size
        val content = buildLlmUserContent(llmInput, refs, imageCount)
        val target = AiCreationConfig.requireModelTarget()
        AppLog.putAi(
            "AI_CREATION REQUEST\n" +
                "provider=${target.provider.name}\n" +
                "model=${target.modelId}\n" +
                "llmInputChars=${llmInput.length}\n" +
                "imageCount=$imageCount"
        )
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

    /** 组装 LLM userContent：无图保持字符串不动；有图时文本+条件拼接句在前，图片按标记顺序跟后 */
    private fun buildLlmUserContent(
        llmInput: String,
        refs: List<String>,
        imageCount: Int
    ): Any {
        if (imageCount == 0) return llmInput
        val text = buildString {
            append(llmInput)
            append("\n\n")
            //N 动态，写不进静态模板，由代码在有图时拼接
            append("本次共 $imageCount 张图，返回的提示词须原样保留标记、数量必须为 $imageCount")
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
        val rendered = renderLlmInput(
            template = llmDefinition.llmInputTemplate,
            prompt = promptText,
            material = materialText
        )
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
