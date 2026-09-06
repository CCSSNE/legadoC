package io.legado.app.ui.config

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogAiCreationProviderEditBinding
import io.legado.app.databinding.DialogAiProviderEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.ai.AiChapterPurifyConfig
import io.legado.app.help.ai.AiStoryboardConfig
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.ai.AiCreationConfig
import io.legado.app.help.ai.AiCreationImageTaskHolder
import io.legado.app.help.ai.AiCreationProviderConfig
import io.legado.app.help.ai.AiCreationProviderModel
import io.legado.app.help.ai.AiCreationProviderStore
import io.legado.app.help.ai.AiCreationCardImages
import io.legado.app.help.ai.AiCreationVariables
import io.legado.app.help.ai.AiCreationVideoHelper
import io.legado.app.help.LogExporter
import io.legado.app.help.ai.AiLogConfig
import io.legado.app.help.ai.AiRequestTimeoutConfig
import io.legado.app.help.ai.AiStructuredRequestTemplate
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.dialogs.showIntegerInputDialog
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.about.AiLogDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var pendingAiLogs = emptyList<AppLog.Entry>()

    private val exportAiLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val logs = pendingAiLogs
        pendingAiLogs = emptyList()
        uri?.let { writeAiLogs(it, logs) }
    }

    private val agentSettings = AgentSettingsUi(this) { refreshUi(notifyMain = true) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_ai)
        configureApiRedactionPreference()
        agentSettings.initialize()
        refreshUi()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.ai_setting)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
        observeEvent<Int>(EventBus.AI_LOGS_CHANGED) { count ->
            findPreference<Preference>("aiLogs")?.summary =
                getString(R.string.ai_log_summary, count)
        }
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (agentSettings.handle(preference.key)) return true
        when (preference.key) {
            "aiManageProviders" -> showManageProvidersDialog()
            "aiTestCurrentConnection" -> testCurrentAiConnection()
            "aiManageModels" -> showManageModelsDialog()
            "aiEditRequest" -> showEditRequestDialog()
            PreferKey.aiSendImageMaxPixels -> showChapterPurifyIntDialog(
                R.string.ai_send_image_max_resolution,
                AiCreationCardImages.sendImageMaxWanPixels,
                AiCreationCardImages.MIN_SEND_IMAGE_WAN_PIXELS,
                AiCreationCardImages.MAX_SEND_IMAGE_WAN_PIXELS
            ) { AiCreationCardImages.sendImageMaxWanPixels = it }
            "aiSseIdleTimeoutSeconds" -> showChapterPurifyIntDialog(
                R.string.ai_sse_idle_timeout,
                AiRequestTimeoutConfig.sseIdleTimeoutSeconds,
                AiRequestTimeoutConfig.MIN_SSE_IDLE_TIMEOUT_SECONDS,
                AiRequestTimeoutConfig.MAX_SSE_IDLE_TIMEOUT_SECONDS
            ) { AiRequestTimeoutConfig.sseIdleTimeoutSeconds = it }
            "aiGenerationTimeoutSeconds" -> showChapterPurifyIntDialog(
                R.string.ai_generation_timeout,
                AiRequestTimeoutConfig.generationTimeoutSeconds,
                AiRequestTimeoutConfig.MIN_GENERATION_TIMEOUT_SECONDS,
                AiRequestTimeoutConfig.MAX_GENERATION_TIMEOUT_SECONDS
            ) { AiRequestTimeoutConfig.generationTimeoutSeconds = it }
            "aiThinkingInterruptSeconds" -> showOptionalAiIntDialog(
                R.string.ai_thinking_interrupt_seconds,
                AiRequestTimeoutConfig.thinkingInterruptSeconds,
                AiRequestTimeoutConfig.MIN_THINKING_INTERRUPT_SECONDS,
                AiRequestTimeoutConfig.MAX_THINKING_INTERRUPT_SECONDS
            ) { AiRequestTimeoutConfig.thinkingInterruptSeconds = it }
            "aiThinkingInterruptMaxCount" -> showChapterPurifyIntDialog(
                R.string.ai_thinking_interrupt_max_count,
                AiRequestTimeoutConfig.thinkingInterruptMaxCount,
                AiRequestTimeoutConfig.MIN_THINKING_INTERRUPT_MAX_COUNT,
                AiRequestTimeoutConfig.MAX_THINKING_INTERRUPT_MAX_COUNT
            ) { AiRequestTimeoutConfig.thinkingInterruptMaxCount = it }
            "aiLogs" -> showDialogFragment<AiLogDialog>()
            "aiExportLogs" -> exportAiLogs()
            PreferKey.aiChapterPurifyProvider -> showSelectChapterPurifyProviderDialog()
            PreferKey.aiChapterPurifyModel -> showSelectChapterPurifyModelDialog()
            "aiChapterPurifyTestConnection" -> testChapterPurifyConnection()
            PreferKey.aiChapterPurifyRequestTemplate -> showChapterPurifyRequestDialog()
            PreferKey.aiChapterPurifyPrompt -> showChapterPurifyPromptDialog()
            "aiChapterPurifyFlowInfo" -> showChapterPurifyFlowInfo()
            PreferKey.aiChapterPurifyPreprocess -> showChapterPurifyPreprocessDialog()
            PreferKey.aiChapterPurifyChapterCount -> showChapterPurifyIntDialog(
                R.string.ai_chapter_purify_chapter_count,
                AiChapterPurifyConfig.chapterCount,
                AiChapterPurifyConfig.MIN_CHAPTER_COUNT,
                AiChapterPurifyConfig.MAX_CHAPTER_COUNT
            ) { AiChapterPurifyConfig.chapterCount = it }
            PreferKey.aiChapterPurifySegmentLimit -> showChapterPurifyIntDialog(
                R.string.ai_chapter_purify_segment_limit,
                AiChapterPurifyConfig.segmentLimit,
                AiChapterPurifyConfig.MIN_SEGMENT_LIMIT,
                AiChapterPurifyConfig.MAX_SEGMENT_LIMIT
            ) { AiChapterPurifyConfig.segmentLimit = it }
            PreferKey.aiChapterPurifyRetryCount -> showChapterPurifyIntDialog(
                R.string.ai_chapter_purify_retry_count,
                AiChapterPurifyConfig.retryCount,
                AiChapterPurifyConfig.MIN_RETRY_COUNT,
                AiChapterPurifyConfig.MAX_RETRY_COUNT
            ) { AiChapterPurifyConfig.retryCount = it }
            PreferKey.aiChapterPurifyConcurrency -> showChapterPurifyIntDialog(
                R.string.ai_chapter_purify_concurrency,
                AiChapterPurifyConfig.concurrency,
                AiChapterPurifyConfig.MIN_CONCURRENCY,
                AiChapterPurifyConfig.MAX_CONCURRENCY
            ) { AiChapterPurifyConfig.concurrency = it }
            PreferKey.aiCreationProvider -> showSelectCreationProviderDialog()
            PreferKey.aiCreationModel -> showSelectCreationModelDialog()
            PreferKey.aiCreationPromptTemplate -> showCreationPromptDialog()
            PreferKey.aiCreationLlmVariables -> showCreationLlmVariablesDialog()
            "aiCreationTestConnection" -> testCreationConnection()
            PreferKey.aiCreationScope -> showCreationScopeSettingsDialog()
            "aiCreationImageManageProviders" -> showCreationManageProvidersDialog(isVideo = false)
            "aiCreationImageApiKeyJump" -> openCreationApiKeyJump()
            "aiCreationImageManageModels" -> showCreationManageModelsDialog(isVideo = false)
            "aiCreationImageTestConnection" -> testCreationImageConnection()
            "aiCreationVideoManageProviders" -> showCreationManageProvidersDialog(isVideo = true)
            "aiCreationVideoManageModels" -> showCreationManageModelsDialog(isVideo = true)
            "aiCreationVideoTestConnection" -> testCreationVideoConnection()
            PreferKey.aiCreationImageRetryCount -> showCreationImageRetryDialog()
            PreferKey.aiCreationPromptRegenerateLimit -> showCreationPromptRegenerateLimitDialog()
            PreferKey.aiStoryboardProviderId -> showSelectStoryboardProviderDialog()
            PreferKey.aiStoryboardModelId -> showSelectStoryboardModelDialog()
            PreferKey.aiStoryboardRequestTemplate -> showStoryboardRequestDialog()
            PreferKey.aiCastingRequestTemplate -> showCastingRequestDialog()
            PreferKey.aiStoryboardPreloadCount -> showStoryboardPreloadCountDialog()
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == PreferKey.aiAssistantEnabled ||
            key == PreferKey.aiAdvancedSettingsEnabled ||
            key == PreferKey.aiChapterPurifyReuseCurrentModel ||
            key == PreferKey.aiChapterPurifyRequestTemplate ||
            key == PreferKey.aiStoryboardRequestTemplate ||
            key == PreferKey.aiCastingRequestTemplate ||
            key == PreferKey.aiCreationReuseCurrentModel ||
            key == PreferKey.aiStoryboardReuseCurrentModel ||
            key == PreferKey.aiSseIdleTimeoutSeconds ||
            key == PreferKey.aiGenerationTimeoutSeconds ||
            key == PreferKey.aiThinkingInterruptSeconds ||
            key == PreferKey.aiThinkingInterruptMaxCount
        ) {
            refreshUi(notifyMain = true)
        }
    }

    private fun exportAiLogs() {
        if (AppLog.aiLogs.isEmpty()) {
            toastOnUi(R.string.ai_log_empty)
            return
        }
        pendingAiLogs = AppLog.aiLogs
        exportAiLogLauncher.launch(LogExporter.fileName("ai"))
    }

    private fun configureApiRedactionPreference() {
        val preference = findPreference<SwitchPreference>(PreferKey.aiApiRedactionEnabled)
            ?: error("Missing API redaction preference")
        preference.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean
                ?: error("API redaction preference must be Boolean")
            if (!enabled && AiLogConfig.apiRedactionEnabled) {
                showApiRedactionWarning(preference)
                false
            } else {
                AiLogConfig.apiRedactionEnabled = enabled
                true
            }
        }
    }

    private fun showApiRedactionWarning(preference: SwitchPreference) {
        alert(
            getString(R.string.ai_api_redaction_warning_title),
            getString(R.string.ai_api_redaction_warning_message)
        ) {
            okButton {
                AiLogConfig.apiRedactionEnabled = false
                preference.isChecked = false
            }
            cancelButton()
        }
    }

    private fun applyApiKeyInputPolicy(editText: EditText) {
        val masked = AiLogConfig.apiRedactionEnabled
        editText.inputType = InputType.TYPE_CLASS_TEXT or
            if (masked) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
        editText.transformationMethod = if (masked) {
            PasswordTransformationMethod.getInstance()
        } else {
            null
        }
        editText.setSelection(editText.text?.length ?: 0)
    }

    private fun writeAiLogs(uri: Uri, logs: List<AppLog.Entry>) {
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching {
                    LogExporter.write(requireContext(), uri, logs)
                }
            }
            result.onSuccess {
                toastOnUi(R.string.ai_log_export_success)
            }.onFailure {
                AppLog.put("AI 日志导出失败\n${it.localizedMessage}", it)
                toastOnUi(getString(R.string.ai_log_export_failed, it.localizedMessage ?: "未知错误"))
            }
        }
    }

    private fun showSelectChapterPurifyProviderDialog() {
        val providers = AppConfig.aiProviderList
        if (providers.isEmpty()) {
            toastOnUi(R.string.ai_no_providers)
            return
        }
        context?.selector(
            getString(R.string.ai_chapter_purify_provider),
            providers.map { it.name }
        ) { _, _, index ->
            AiChapterPurifyConfig.independentProviderId = providers[index].id
            // 切换供应商后，原模型引用不再属于新供应商，清空让用户重新选择
            AiChapterPurifyConfig.independentModelId = ""
            refreshUi()
        }
    }

    private fun showSelectChapterPurifyModelDialog() {
        val provider = AiChapterPurifyConfig.independentProvider
        if (provider == null) {
            toastOnUi(R.string.ai_chapter_purify_select_provider_first)
            return
        }
        val models = AppConfig.aiModelConfigList.filter { it.providerId == provider.id }
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_chapter_purify_provider_no_models)
            return
        }
        context?.selector(
            getString(R.string.ai_chapter_purify_model),
            models.map { it.modelId }
        ) { _, _, index ->
            AiChapterPurifyConfig.independentModelId = models[index].id
            refreshUi()
        }
    }

    private fun showChapterPurifyPromptDialog() {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_chapter_purify_prompt_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            editView.minLines = 8
            editView.setText(AiChapterPurifyConfig.prompt)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_chapter_purify_prompt) {
            customView { binding.root }
            okButton {
                AiChapterPurifyConfig.prompt = binding.editView.text?.toString().orEmpty()
                refreshUi()
            }
            neutralButton(R.string.restore_default) {
                AiChapterPurifyConfig.prompt = AiChapterPurifyConfig.defaultPrompt
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun showChapterPurifyFlowInfo() {
        alert(
            getString(R.string.ai_chapter_purify_flow_info),
            getString(R.string.ai_chapter_purify_flow_info_message)
        ) {
            okButton()
        }
    }

    private fun showSelectCreationProviderDialog() {
        val providers = AppConfig.aiProviderList
        if (providers.isEmpty()) {
            toastOnUi(R.string.ai_no_providers)
            return
        }
        context?.selector(
            getString(R.string.ai_creation_provider),
            providers.map { it.name }
        ) { _, _, index ->
            AiCreationConfig.independentProviderId = providers[index].id
            AiCreationConfig.independentModelId = ""
            refreshUi()
        }
    }

    private fun showSelectCreationModelDialog() {
        val provider = AiCreationConfig.independentProvider
        if (provider == null) {
            toastOnUi(R.string.ai_chapter_purify_select_provider_first)
            return
        }
        val models = AppConfig.aiModelConfigList.filter { it.providerId == provider.id }
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_chapter_purify_provider_no_models)
            return
        }
        context?.selector(
            getString(R.string.ai_creation_model),
            models.map { it.modelId }
        ) { _, _, index ->
            AiCreationConfig.independentModelId = models[index].id
            refreshUi()
        }
    }

    private fun showSelectStoryboardProviderDialog() {
        val providers = AppConfig.aiProviderList
        if (providers.isEmpty()) {
            toastOnUi(R.string.ai_no_providers)
            return
        }
        context?.selector(
            getString(R.string.ai_storyboard_provider),
            providers.map { it.name }
        ) { _, _, index ->
            AiStoryboardConfig.providerId = providers[index].id
            AiStoryboardConfig.modelConfigId = ""
            refreshUi()
        }
    }

    private fun showSelectStoryboardModelDialog() {
        val provider = AiStoryboardConfig.provider
        if (provider == null) {
            toastOnUi(R.string.ai_chapter_purify_select_provider_first)
            return
        }
        val models = AppConfig.aiModelConfigList.filter { it.providerId == provider.id }
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_chapter_purify_provider_no_models)
            return
        }
        context?.selector(
            getString(R.string.ai_storyboard_model),
            models.map { it.modelId }
        ) { _, _, index ->
            AiStoryboardConfig.modelConfigId = models[index].id
            refreshUi()
        }
    }

    private fun showStoryboardPreloadCountDialog() {
        showIntegerInputDialog(
            title = R.string.ai_storyboard_preload_count,
            currentValue = AiStoryboardConfig.preloadCount,
            validRange = 0..10
        ) { value ->
            AiStoryboardConfig.preloadCount = value
            refreshUi()
        }
    }

    private fun showCreationPromptDialog() {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_creation_prompt_template_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editView.minLines = 12
            editView.setText(AiCreationConfig.promptTemplateJson)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_creation_prompt_template) {
            customView { binding.root }
            okButton {
                val value = binding.editView.text?.toString().orEmpty()
                val error = runCatching {
                    AiCreationConfig.promptTemplateJson = value
                }.exceptionOrNull()
                if (error != null) {
                    toastOnUi(error.message ?: error.javaClass.simpleName)
                    return@okButton
                }
                refreshUi()
            }
            neutralButton(R.string.restore_default) {
                AiCreationConfig.promptTemplateJson = AiCreationConfig.defaultPromptTemplateJson
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun showCreationLlmVariablesDialog() {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_creation_llm_variables_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editView.minLines = 12
            editView.setText(AiCreationConfig.llmVariablesJson)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_creation_llm_variables) {
            customView { binding.root }
            okButton {
                val value = binding.editView.text?.toString().orEmpty()
                val error = runCatching {
                    AiCreationConfig.llmVariablesJson = value
                }.exceptionOrNull()
                if (error != null) {
                    toastOnUi(error.message ?: error.javaClass.simpleName)
                    return@okButton
                }
                refreshUi()
            }
            neutralButton(R.string.restore_default) {
                AiCreationConfig.llmVariablesJson = AiCreationConfig.defaultLlmVariablesJson
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun testCreationConnection() {
        val target = runCatching { AiCreationConfig.requireModelTarget() }.getOrElse {
            toastOnUi(it.message ?: it.javaClass.simpleName)
            return
        }
        //创作与聊天共用全局通用模板，连接测试也用同一份，测出来的就是真实请求形态
        testAiConnection(target.provider, target.modelId, AiStructuredRequestTemplate.global)
    }

    private fun showCreationImageRetryDialog() {
        showChapterPurifyIntDialog(
            R.string.ai_creation_image_retry_count,
            AiCreationConfig.imageRetryCount,
            AiCreationConfig.MIN_IMAGE_RETRY_COUNT,
            AiCreationConfig.MAX_IMAGE_RETRY_COUNT
        ) { AiCreationConfig.imageRetryCount = it }
    }

    private fun showCreationPromptRegenerateLimitDialog() {
        showChapterPurifyIntDialog(
            R.string.ai_creation_prompt_regenerate_limit,
            AiCreationConfig.promptRegenerateLimit,
            AiCreationConfig.MIN_PROMPT_REGENERATE_LIMIT,
            AiCreationConfig.MAX_PROMPT_REGENERATE_LIMIT
        ) { AiCreationConfig.promptRegenerateLimit = it }
    }

    // ———— AI 创作图片/视频供应商管理（参考 LLM 供应商管理：管理内设当前） ————

    private fun creationProviders(isVideo: Boolean): List<AiCreationProviderConfig> =
        if (isVideo) AiCreationProviderStore.videoProviderList
        else AiCreationProviderStore.imageProviderList

    private fun saveCreationProviders(
        isVideo: Boolean,
        providers: List<AiCreationProviderConfig>
    ) {
        if (isVideo) AiCreationProviderStore.videoProviderList = providers
        else AiCreationProviderStore.imageProviderList = providers
    }

    private fun creationModels(isVideo: Boolean): List<AiCreationProviderModel> =
        if (isVideo) AiCreationProviderStore.videoModelList
        else AiCreationProviderStore.imageModelList

    private fun saveCreationModels(isVideo: Boolean, models: List<AiCreationProviderModel>) {
        if (isVideo) AiCreationProviderStore.videoModelList = models
        else AiCreationProviderStore.imageModelList = models
    }

    private fun creationCurrentProvider(isVideo: Boolean): AiCreationProviderConfig? =
        if (isVideo) AiCreationProviderStore.videoCurrentProvider
        else AiCreationProviderStore.imageCurrentProvider

    private fun setCreationCurrentProviderId(isVideo: Boolean, id: String?) {
        if (isVideo) AiCreationProviderStore.videoCurrentProviderId = id
        else AiCreationProviderStore.imageCurrentProviderId = id
    }

    private fun setCreationCurrentModelRowId(isVideo: Boolean, id: String?) {
        if (isVideo) AiCreationProviderStore.videoCurrentModelRowId = id
        else AiCreationProviderStore.imageCurrentModelRowId = id
    }

    private fun updateCreationProvider(
        providerId: String,
        isVideo: Boolean,
        transform: (AiCreationProviderConfig) -> AiCreationProviderConfig
    ) {
        val providers = creationProviders(isVideo).toMutableList()
        val index = providers.indexOfFirst { it.id == providerId }
        if (index < 0) return
        providers[index] = transform(providers[index])
        saveCreationProviders(isVideo, providers)
    }

    private fun summarizeJsonText(json: String): String {
        val compact = json.replace(Regex("\\s+"), " ").trim()
        return if (compact.length > 40) compact.take(40) + "…" else compact.ifBlank { "—" }
    }

    private fun showCreationProviderEditDialog(
        provider: AiCreationProviderConfig?,
        isVideo: Boolean
    ) {
        //变量定义与请求体经行内点击弹窗编辑，保存到临时状态，随外层确认一并写入
        var variablesJson = provider?.variablesJson.orEmpty()
        var requestTemplate = provider?.requestTemplate.orEmpty()
        val binding = DialogAiCreationProviderEditBinding.inflate(layoutInflater).apply {
            editProviderName.setText(provider?.name.orEmpty())
            editProviderBaseUrl.setText(provider?.baseUrl.orEmpty())
            editProviderApiKey.setText(provider?.apiKey.orEmpty())
            editProviderHeaders.setText(provider?.headers.orEmpty())
            tvProviderVariablesLabel.setText(
                if (isVideo) R.string.ai_creation_video_variables
                else R.string.ai_creation_image_variables
            )
            tvProviderTemplateLabel.setText(
                if (isVideo) R.string.ai_creation_video_request_template
                else R.string.ai_creation_image_request_template
            )
            tvProviderVariables.text = summarizeJsonText(variablesJson)
            tvProviderTemplate.text = summarizeJsonText(requestTemplate)
            editProviderVariablesRow.setOnClickListener {
                showCreationTemplateEditorDialog(
                    title = if (isVideo) R.string.ai_creation_video_variables
                    else R.string.ai_creation_image_variables,
                    content = variablesJson,
                    validate = { AiCreationVariables.parse(it) }
                ) { json ->
                    variablesJson = json
                    tvProviderVariables.text = summarizeJsonText(json)
                }
            }
            editProviderTemplateRow.setOnClickListener {
                showCreationTemplateEditorDialog(
                    title = if (isVideo) R.string.ai_creation_video_request_template
                    else R.string.ai_creation_image_request_template,
                    content = requestTemplate,
                    validate = {
                        if (isVideo) AiCreationProviderStore.parseVideoRequestTemplateJson(it)
                        else AiCreationProviderStore.parseImageRequestTemplateJson(it)
                    }
                ) { json ->
                    requestTemplate = json
                    tvProviderTemplate.text = summarizeJsonText(json)
                }
            }
        }
        applyApiKeyInputPolicy(binding.editProviderApiKey)
        alert(
            title = getString(
                when {
                    provider == null && isVideo -> R.string.ai_creation_video_add_provider
                    provider == null -> R.string.ai_creation_image_add_provider
                    isVideo -> R.string.ai_creation_video_edit_provider
                    else -> R.string.ai_creation_image_edit_provider
                }
            )
        ) {
            customView { binding.root }
            //删除放最左（neutral）：取消/确定左侧，编辑态才有，含内置图片/视频供应商
            if (provider != null) {
                neutralButton(R.string.ai_remove_provider) {
                    confirmRemoveCreationProvider(provider, isVideo)
                }
            }
            okButton {
                val name = binding.editProviderName.text?.toString()?.trim().orEmpty()
                val baseUrl = binding.editProviderBaseUrl.text?.toString()?.trim().orEmpty()
                val apiKey = binding.editProviderApiKey.text?.toString()?.trim().orEmpty()
                val headers = binding.editProviderHeaders.text?.toString()?.trim().orEmpty()
                when {
                    name.isEmpty() -> {
                        toastOnUi(R.string.ai_provider_name_required)
                        return@okButton
                    }

                    baseUrl.isEmpty() -> {
                        toastOnUi(R.string.ai_provider_url_required)
                        return@okButton
                    }

                    variablesJson.isBlank() -> {
                        toastOnUi(
                            getString(
                                R.string.ai_creation_variables_invalid,
                                "变量定义为空，请先编辑变量定义"
                            )
                        )
                        return@okButton
                    }

                    requestTemplate.isBlank() -> {
                        toastOnUi(
                            getString(
                                R.string.ai_creation_request_template_invalid,
                                "请求体为空，请先编辑请求体"
                            )
                        )
                        return@okButton
                    }
                }
                val updated = (provider ?: AiCreationProviderConfig(name = name, baseUrl = baseUrl)).copy(
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    headers = headers,
                    variablesJson = variablesJson,
                    requestTemplate = requestTemplate
                )
                val providers = creationProviders(isVideo).toMutableList()
                val targetIndex = providers.indexOfFirst { it.id == updated.id }
                if (targetIndex >= 0) {
                    providers[targetIndex] = updated
                } else {
                    providers.add(updated)
                }
                saveCreationProviders(isVideo, providers)
                setCreationCurrentProviderId(isVideo, updated.id)
                refreshUi()
                toastOnUi(R.string.ai_provider_saved)
            }
            cancelButton()
        }
    }

    private fun showCreationTemplateEditorDialog(
        title: Int,
        content: String,
        validate: (String) -> Any?,
        onSaved: (String) -> Unit
    ) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editView.minLines = 12
            editView.setText(content)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = title) {
            customView { binding.root }
            okButton {
                val json = binding.editView.text?.toString()?.trim().orEmpty()
                val error = runCatching { validate(json) }.exceptionOrNull()
                if (error != null) {
                    toastOnUi(error.message ?: error.javaClass.simpleName)
                    return@okButton
                }
                onSaved(json)
            }
            cancelButton()
        }
    }

    private fun showCreationManageProvidersDialog(isVideo: Boolean) {
        val providers = creationProviders(isVideo)
        val ctx = context ?: return
        val addLabel = getString(
            if (isVideo) R.string.ai_creation_video_add_provider
            else R.string.ai_creation_image_add_provider
        )
        //短按=设为当前，长按=编辑；删除收进编辑页左下（neutral），不再二级弹窗
        val dialog = ctx.alert(
            getString(
                if (isVideo) R.string.ai_creation_video_manage_providers
                else R.string.ai_creation_image_manage_providers
            )
        ) {
            items(providers.map { it.name } + addLabel) { _, index ->
                if (index == providers.size) {
                    showCreationProviderEditDialog(null, isVideo)
                    return@items
                }
                setCreationCurrentProviderId(isVideo, providers[index].id)
                refreshUi()
            }
        }
        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            if (position == providers.size) return@setOnItemLongClickListener false
            dialog.dismiss()
            showCreationProviderEditDialog(providers[position], isVideo)
            true
        }
    }

    private fun confirmRemoveCreationProvider(
        provider: AiCreationProviderConfig,
        isVideo: Boolean
    ) {
        //内置图片/视频供应商同样允许删除：存储层已支持删空不再重种内置项
        val relatedModelCount = creationModels(isVideo).count { it.providerId == provider.id }
        alert(
            title = provider.name,
            message = getString(
                if (relatedModelCount > 0) {
                    R.string.ai_remove_provider_confirm_with_models
                } else {
                    R.string.ai_remove_provider_confirm
                },
                relatedModelCount
            )
        ) {
            okButton {
                saveCreationProviders(
                    isVideo,
                    creationProviders(isVideo).filterNot { it.id == provider.id }
                )
                refreshUi()
                toastOnUi(R.string.ai_provider_removed)
            }
            cancelButton()
        }
    }

    private fun showCreationAddModelDialog(isVideo: Boolean) {
        val provider = creationCurrentProvider(isVideo) ?: run {
            toastOnUi(R.string.ai_creation_provider_required)
            return
        }
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_model_input_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT
        }
        alert(
            title = getString(
                if (isVideo) R.string.ai_creation_video_add_model
                else R.string.ai_creation_image_add_model
            )
        ) {
            customView { binding.root }
            okButton {
                val modelId = binding.editView.text?.toString()?.trim().orEmpty()
                if (modelId.isEmpty()) {
                    return@okButton
                }
                val models = creationModels(isVideo)
                if (models.any { it.providerId == provider.id && it.modelId == modelId }) {
                    toastOnUi(R.string.ai_model_exists)
                    return@okButton
                }
                val model = AiCreationProviderModel(providerId = provider.id, modelId = modelId)
                saveCreationModels(isVideo, models + model)
                setCreationCurrentModelRowId(isVideo, model.id)
                refreshUi()
                toastOnUi(R.string.ai_model_added)
            }
            cancelButton()
        }
    }

    private fun showCreationManageModelsDialog(isVideo: Boolean) {
        val provider = creationCurrentProvider(isVideo) ?: run {
            toastOnUi(R.string.ai_creation_provider_required)
            return
        }
        val ctx = context ?: return
        val models = creationModels(isVideo).filter { it.providerId == provider.id }
        val addLabel = getString(
            if (isVideo) R.string.ai_creation_video_add_model
            else R.string.ai_creation_image_add_model
        )
        //短按=设为当前，长按=编辑；删除收进编辑页左下（neutral），不再二级弹窗
        val dialog = ctx.alert(
            getString(
                if (isVideo) R.string.ai_creation_video_manage_models
                else R.string.ai_creation_image_manage_models
            )
        ) {
            items(models.map { it.modelId } + addLabel) { _, index ->
                if (index == models.size) {
                    showCreationAddModelDialog(isVideo)
                    return@items
                }
                setCreationCurrentModelRowId(isVideo, models[index].id)
                refreshUi()
            }
        }
        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            if (position == models.size) return@setOnItemLongClickListener false
            dialog.dismiss()
            showCreationEditModelDialog(models[position], isVideo)
            true
        }
    }

    private fun showCreationEditModelDialog(model: AiCreationProviderModel, isVideo: Boolean) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_model_input_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT
            editView.setText(model.modelId)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(title = getString(R.string.ai_edit_model)) {
            customView { binding.root }
            //删除放最左（neutral）：取消/确定左侧；沿用原来图片/视频模型无二次确认直接删除
            neutralButton(R.string.ai_remove_model) {
                saveCreationModels(
                    isVideo,
                    creationModels(isVideo).filterNot { it.id == model.id }
                )
                refreshUi()
            }
            okButton {
                val modelId = binding.editView.text?.toString()?.trim().orEmpty()
                if (modelId.isEmpty()) {
                    return@okButton
                }
                val models = creationModels(isVideo).toMutableList()
                if (models.any {
                        it.providerId == model.providerId && it.modelId == modelId && it.id != model.id
                    }
                ) {
                    toastOnUi(R.string.ai_model_exists)
                    return@okButton
                }
                val index = models.indexOfFirst { it.id == model.id }
                if (index >= 0) {
                    models[index] = model.copy(modelId = modelId)
                }
                saveCreationModels(isVideo, models)
                refreshUi()
                toastOnUi(R.string.ai_model_saved)
            }
            cancelButton()
        }
    }

    private fun openCreationApiKeyJump() {
        val url = AiCreationProviderStore.imageCurrentProvider?.apiKeyUrl.orEmpty()
        if (url.isBlank()) {
            toastOnUi(R.string.ai_creation_provider_required)
            return
        }
        requireContext().openUrl(url)
    }

    private fun testCreationImageConnection() {
        val target = runCatching { AiCreationProviderStore.requireImageTarget() }.getOrElse {
            toastOnUi(it.message ?: it.javaClass.simpleName)
            return
        }
        toastOnUi(R.string.ai_creation_image_test_running)
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching { AiCreationImageTaskHolder.testConnection(target.provider, target.modelId) }
            }
            result.onSuccess {
                toastOnUi(R.string.ai_creation_image_test_success)
            }.onFailure { throwable ->
                AppLog.put(
                    "AI 创作图片测试连接失败，供应商《${target.provider.name}》，模型《${target.modelId}》\n" +
                        "${throwable.message}",
                    throwable
                )
                toastOnUi(
                    getString(
                        R.string.ai_creation_connection_test_failed,
                        throwable.message ?: throwable.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun testCreationVideoConnection() {
        val target = runCatching { AiCreationProviderStore.requireVideoTarget() }.getOrElse {
            toastOnUi(it.message ?: it.javaClass.simpleName)
            return
        }
        toastOnUi(R.string.ai_creation_video_test_running)
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching { AiCreationVideoHelper.testConnection(target.provider, target.modelId) }
            }
            result.onSuccess {
                toastOnUi(R.string.ai_creation_video_test_success)
            }.onFailure { throwable ->
                AppLog.put(
                    "AI 创作视频测试连接失败，供应商《${target.provider.name}》，模型《${target.modelId}》\n" +
                        "${throwable.message}",
                    throwable
                )
                toastOnUi(
                    getString(
                        R.string.ai_creation_connection_test_failed,
                        throwable.message ?: throwable.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun showCreationScopeDialog(section: String) {
        val values = AiCreationConfig.scopeValues
        val labels = values.map { scope ->
            creationScopeLabel(scope)
        }
        context?.selector(
            creationScopeTitle(section),
            labels
        ) { _, _, index ->
            AiCreationConfig.setSectionScope(section, values[index])
            refreshUi()
        }
    }

    private fun showCreationScopeSettingsDialog() {
        val sections = AiCreationConfig.sectionOrder
        val items = sections.map { section ->
            getString(
                R.string.ai_creation_scope_item,
                creationScopeTitle(section),
                creationScopeLabel(AiCreationConfig.sectionScope(section))
            )
        }
        context?.selector(
            getString(R.string.ai_creation_scope),
            items
        ) { _, _, index ->
            showCreationScopeDialog(sections[index])
        }
    }

    private fun creationScopeTitle(section: String): String {
        return getString(
            when (section) {
                AiCreationConfig.SECTION_SELECTED_TEXT -> R.string.ai_creation_scope_selected_text
                AiCreationConfig.SECTION_BACKGROUND -> R.string.ai_creation_scope_background
                AiCreationConfig.SECTION_SCENE -> R.string.ai_creation_scope_scene
                AiCreationConfig.SECTION_CHARACTER -> R.string.ai_creation_scope_character
                else -> R.string.ai_creation_scope_note
            }
        )
    }

    private fun creationScopeLabel(scope: String): String {
        return getString(
            when (scope) {
                AiCreationConfig.SCOPE_GLOBAL -> R.string.ai_creation_scope_global
                AiCreationConfig.SCOPE_BOOK -> R.string.ai_creation_scope_book
                else -> R.string.ai_creation_scope_session
            }
        )
    }

    private fun showChapterPurifyPreprocessDialog() {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_chapter_purify_preprocess_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editView.minLines = 18
            editView.setText(AiChapterPurifyConfig.preprocessJson)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_chapter_purify_preprocess) {
            customView { binding.root }
            okButton {
                val json = binding.editView.text?.toString()?.trim().orEmpty()
                val error = runCatching {
                    AiChapterPurifyConfig.preprocessJson = json
                }.exceptionOrNull()
                if (error != null) {
                    toastOnUi(
                        getString(
                            R.string.ai_chapter_purify_preprocess_invalid,
                            error.message.orEmpty()
                        )
                    )
                    return@okButton
                }
                refreshUi()
            }
            neutralButton(R.string.restore_default) {
                AiChapterPurifyConfig.preprocessJson = AiChapterPurifyConfig.defaultPreprocessJson
                refreshUi()
            }
            cancelButton()
        }
    }

    /** 全局通用请求体：AI 聊天（对话/划词/浮动面板）与 AI 创作共用 */
    private fun showEditRequestDialog() {
        showRequestTemplateDialog(
            titleResource = R.string.ai_edit_request,
            currentTemplate = { AiStructuredRequestTemplate.global },
            save = { AiStructuredRequestTemplate.global = it },
            restore = { AiStructuredRequestTemplate.global = AiStructuredRequestTemplate.default },
            restoreLabelResource = R.string.restore_default
        )
    }

    /** AI 分镜专用请求体：默认带 response_format=json_object */
    private fun showStoryboardRequestDialog() {
        showRequestTemplateDialog(
            titleResource = R.string.ai_storyboard_request_template,
            currentTemplate = { AiStoryboardConfig.storyboardRequestTemplate },
            save = { AiStoryboardConfig.storyboardRequestTemplate = it },
            restore = {
                AiStoryboardConfig.storyboardRequestTemplate =
                    AiStructuredRequestTemplate.structuredDefault
            },
            restoreLabelResource = R.string.restore_default
        )
    }

    /** AI 选角专用请求体：与分镜各用各的，默认带 response_format=json_object */
    private fun showCastingRequestDialog() {
        showRequestTemplateDialog(
            titleResource = R.string.ai_casting_request_template,
            currentTemplate = { AiStoryboardConfig.castingRequestTemplate },
            save = { AiStoryboardConfig.castingRequestTemplate = it },
            restore = {
                AiStoryboardConfig.castingRequestTemplate =
                    AiStructuredRequestTemplate.structuredDefault
            },
            restoreLabelResource = R.string.restore_default
        )
    }

    /** 章节净化专用请求体：净化是唯一需要 response_format=json 的消费者 */
    private fun showChapterPurifyRequestDialog() {
        showRequestTemplateDialog(
            titleResource = R.string.ai_chapter_purify_request_template,
            currentTemplate = { AiChapterPurifyConfig.requestTemplate },
            save = { AiChapterPurifyConfig.requestTemplate = it },
            restore = {
                AiChapterPurifyConfig.requestTemplate =
                    AiStructuredRequestTemplate.structuredDefault
            },
            restoreLabelResource = R.string.restore_default
        )
    }

    private fun showRequestTemplateDialog(
        titleResource: Int,
        currentTemplate: () -> String,
        save: (String) -> Unit,
        restore: () -> Unit,
        restoreLabelResource: Int
    ) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_edit_request_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editView.minLines = 16
            editView.setText(currentTemplate())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(
            titleResource = titleResource
        ) {
            customView { binding.root }
            okButton {
                val template = binding.editView.text?.toString()?.trim().orEmpty()
                save(template)
                refreshUi()
            }
            neutralButton(restoreLabelResource) {
                restore()
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun showChapterPurifyIntDialog(
        title: Int,
        current: Int,
        min: Int,
        max: Int,
        save: (Int) -> Unit
    ) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = "$min-$max"
            editView.inputType = InputType.TYPE_CLASS_NUMBER
            editView.setText(current.toString())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = title) {
            customView { binding.root }
            okButton {
                val value = binding.editView.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value !in min..max) {
                    toastOnUi(getString(R.string.ai_chapter_purify_number_invalid, min, max))
                    return@okButton
                }
                save(value)
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun showOptionalAiIntDialog(
        title: Int,
        current: Int?,
        min: Int,
        max: Int,
        save: (Int?) -> Unit
    ) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(
                R.string.ai_thinking_interrupt_number_invalid,
                min,
                max
            )
            editView.inputType = InputType.TYPE_CLASS_NUMBER
            editView.setText(current?.toString().orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = title) {
            customView { binding.root }
            okButton {
                val raw = binding.editView.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    save(null)
                    refreshUi()
                    return@okButton
                }
                val value = raw.toIntOrNull()
                if (value == null || value !in min..max) {
                    toastOnUi(getString(R.string.ai_thinking_interrupt_number_invalid, min, max))
                    return@okButton
                }
                save(value)
                refreshUi()
            }
            neutralButton(R.string.ai_thinking_interrupt_clear) {
                save(null)
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun showEditProviderDialog(provider: AiProviderConfig? = null) {
        val binding = DialogAiProviderEditBinding.inflate(layoutInflater).apply {
            editProviderName.setText(provider?.name.orEmpty())
            editProviderBaseUrl.setText(provider?.baseUrl.orEmpty())
            editProviderApiKey.setText(provider?.apiKey.orEmpty())
            editProviderHeaders.setText(provider?.headers.orEmpty())
            checkProviderVision.isChecked = provider?.supportsVision ?: true
        }
        applyApiKeyInputPolicy(binding.editProviderApiKey)
        alert(
            title = getString(
                if (provider == null) R.string.ai_add_provider else R.string.ai_edit_provider
            )
        ) {
            customView { binding.root }
            //删除放最左（neutral）：取消/确定左侧，编辑态才有
            if (provider != null) {
                neutralButton(R.string.ai_remove_provider) {
                    confirmRemoveProvider(provider)
                }
            }
            okButton {
                val name = binding.editProviderName.text?.toString()?.trim().orEmpty()
                val baseUrl = binding.editProviderBaseUrl.text?.toString()?.trim().orEmpty()
                val apiKey = binding.editProviderApiKey.text?.toString()?.trim().orEmpty()
                val headers = binding.editProviderHeaders.text?.toString()?.trim().orEmpty()
                when {
                    name.isEmpty() -> {
                        toastOnUi(R.string.ai_provider_name_required)
                        return@okButton
                    }

                    baseUrl.isEmpty() -> {
                        toastOnUi(R.string.ai_provider_url_required)
                        return@okButton
                    }
                }
                val providers = AppConfig.aiProviderList.toMutableList()
                val supportVision = binding.checkProviderVision.isChecked
                val updated = provider?.copy(
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    headers = headers,
                    supportVision = supportVision
                ) ?: AiProviderConfig(
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    headers = headers,
                    supportVision = supportVision
                )
                val targetIndex = providers.indexOfFirst { it.id == updated.id }
                if (targetIndex >= 0) {
                    providers[targetIndex] = updated
                } else {
                    providers.add(updated)
                }
                AppConfig.aiProviderList = providers
                AppConfig.aiCurrentProviderId = updated.id
                refreshUi()
                toastOnUi(R.string.ai_provider_saved)
            }
            cancelButton()
        }
    }

    private fun showManageProvidersDialog() {
        val providers = AppConfig.aiProviderList
        val ctx = context ?: return
        //短按=设为当前，长按=编辑；删除收进编辑页左下（neutral），不再二级弹窗
        val dialog = ctx.alert(getString(R.string.ai_manage_providers)) {
            items(providers.map { it.name } + getString(R.string.ai_add_provider)) { _, index ->
                if (index == providers.size) {
                    showEditProviderDialog()
                    return@items
                }
                AppConfig.aiCurrentProviderId = providers[index].id
                refreshUi()
            }
        }
        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            if (position == providers.size) return@setOnItemLongClickListener false
            dialog.dismiss()
            showEditProviderDialog(providers[position])
            true
        }
    }

    private fun confirmRemoveProvider(provider: AiProviderConfig) {
        val relatedModelCount = AppConfig.aiModelConfigList.count { it.providerId == provider.id }
        alert(
            title = provider.name,
            message = getString(
                if (relatedModelCount > 0) {
                    R.string.ai_remove_provider_confirm_with_models
                } else {
                    R.string.ai_remove_provider_confirm
                },
                relatedModelCount
            )
        ) {
            okButton {
                AppConfig.aiProviderList = AppConfig.aiProviderList.filterNot { it.id == provider.id }
                refreshUi()
                toastOnUi(R.string.ai_provider_removed)
            }
            cancelButton()
        }
    }

    private fun showEditModelDialog(model: AiModelConfig? = null) {
        val provider = AppConfig.aiCurrentProvider
        if (provider == null) {
            toastOnUi(R.string.ai_no_providers)
            return
        }
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_model_input_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT
            editView.setText(model?.modelId.orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(
            title = getString(
                if (model == null) R.string.ai_add_model else R.string.ai_edit_model
            )
        ) {
            customView { binding.root }
            //删除放最左（neutral）：取消/确定左侧，编辑态才有
            if (model != null) {
                neutralButton(R.string.ai_remove_model) {
                    confirmRemoveModel(model)
                }
            }
            okButton {
                val modelId = binding.editView.text?.toString()?.trim().orEmpty()
                if (modelId.isEmpty()) {
                    return@okButton
                }
                val models = AppConfig.aiModelConfigList.toMutableList()
                val exists = models.any {
                    it.providerId == provider.id && it.modelId == modelId && it.id != model?.id
                }
                if (exists) {
                    toastOnUi(R.string.ai_model_exists)
                    return@okButton
                }
                val updated = model?.copy(
                    providerId = provider.id,
                    modelId = modelId
                ) ?: AiModelConfig(
                    providerId = provider.id,
                    modelId = modelId
                )
                val targetIndex = models.indexOfFirst { it.id == updated.id }
                if (targetIndex >= 0) {
                    models[targetIndex] = updated
                } else {
                    models.add(updated)
                }
                AppConfig.aiModelConfigList = models
                AppConfig.aiCurrentModelId = updated.id
                refreshUi()
                toastOnUi(
                    if (model == null) R.string.ai_model_added else R.string.ai_model_saved
                )
            }
            cancelButton()
        }
    }

    private fun showAddModelOptionsDialog() {
        if (AppConfig.aiCurrentProvider == null) {
            toastOnUi(R.string.ai_no_providers)
            return
        }
        context?.selector(
            getString(R.string.ai_add_model),
            listOf(
                getString(R.string.ai_add_model_from_list),
                getString(R.string.ai_add_model_manual)
            )
        ) { _, _, index ->
            when (index) {
                0 -> fetchModelsFromCurrentProvider(showSelector = true)
                1 -> showEditModelDialog()
            }
        }
    }

    private fun showManageModelsDialog() {
        if (AppConfig.aiCurrentProvider == null) {
            toastOnUi(R.string.ai_no_providers)
            return
        }
        val ctx = context ?: return
        val models = currentProviderModels()
        //短按=设为当前，长按=编辑；删除收进编辑页左下（neutral），不再二级弹窗
        val dialog = ctx.alert(getString(R.string.ai_manage_models)) {
            items(models.map { it.modelId } + getString(R.string.ai_add_model)) { _, index ->
                if (index == models.size) {
                    showAddModelOptionsDialog()
                    return@items
                }
                AppConfig.aiCurrentModelId = models[index].id
                refreshUi()
            }
        }
        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            if (position == models.size) return@setOnItemLongClickListener false
            dialog.dismiss()
            showEditModelDialog(models[position])
            true
        }
    }

    private fun fetchModelsFromCurrentProvider(showSelector: Boolean = false) {
        val provider = AppConfig.aiCurrentProvider
        if (provider == null) {
            toastOnUi(R.string.ai_no_providers)
            return
        }
        toastOnUi(R.string.ai_fetch_models_loading)
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching { AiChatService.fetchModels(provider) }
            }
            result.onSuccess { modelIds ->
                if (modelIds.isEmpty()) {
                    toastOnUi(R.string.ai_fetch_models_empty)
                    return@onSuccess
                }
                if (showSelector) {
                    showFetchedModelSelector(provider.id, modelIds)
                } else {
                    appendFetchedModels(provider.id, modelIds)
                }
            }.onFailure {
                toastOnUi(getString(R.string.ai_fetch_models_failed, it.localizedMessage ?: "未知错误"))
            }
        }
    }

    private fun testCurrentAiConnection() {
        val provider = AppConfig.aiCurrentProvider
        if (provider == null) {
            toastOnUi(R.string.ai_connection_test_summary_missing_provider)
            return
        }
        val model = AppConfig.aiCurrentModelConfig?.modelId?.trim().orEmpty()
        if (model.isEmpty()) {
            toastOnUi(R.string.ai_connection_test_summary_missing_model)
            return
        }
        testAiConnection(provider, model, AiStructuredRequestTemplate.global)
    }

    private fun testChapterPurifyConnection() {
        val target = runCatching { AiChapterPurifyConfig.requireModelTarget() }.getOrElse {
            toastOnUi(it.message ?: it.javaClass.simpleName)
            return
        }
        testAiConnection(
            target.provider,
            target.modelId,
            AiChapterPurifyConfig.requestTemplate
        )
    }

    private fun testAiConnection(
        provider: AiProviderConfig,
        model: String,
        requestTemplate: String
    ) {
        toastOnUi(R.string.ai_connection_test_running)
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching { AiChatService.testConnection(provider, model, requestTemplate) }
            }
            result.onSuccess {
                toastOnUi(getString(R.string.ai_connection_test_success, provider.name, model))
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                AppLog.put("AI 连接测试失败，提供商《${provider.name}》，模型《$model》\n$message", throwable)
                toastOnUi(getString(R.string.ai_connection_test_failed, message))
            }
        }
    }

    private fun showFetchedModelSelector(providerId: String, modelIds: List<String>) {
        val items = buildList {
            add(getString(R.string.ai_add_all_models))
            addAll(modelIds)
        }
        context?.selector(
            getString(R.string.ai_add_model_from_list),
            items
        ) { _, _, index ->
            if (index == 0) {
                appendFetchedModels(providerId, modelIds)
            } else {
                val selectedModelId = items[index]
                val existing = AppConfig.aiModelConfigList.firstOrNull {
                    it.providerId == providerId && it.modelId == selectedModelId
                }
                if (existing != null) {
                    AppConfig.aiCurrentModelId = existing.id
                    refreshUi()
                    toastOnUi(R.string.ai_model_saved)
                } else {
                    appendFetchedModels(providerId, listOf(selectedModelId))
                }
            }
        }
    }

    private fun appendFetchedModels(providerId: String, modelIds: List<String>) {
        val oldModels = AppConfig.aiModelConfigList
        val existingIds = oldModels
            .filter { it.providerId == providerId }
            .map { it.modelId }
            .toSet()
        val newModels = modelIds
            .distinct()
            .filterNot { it in existingIds }
            .map { AiModelConfig(providerId = providerId, modelId = it) }
        if (newModels.isEmpty()) {
            toastOnUi(R.string.ai_fetch_models_no_new)
            return
        }
        AppConfig.aiModelConfigList = oldModels + newModels
        if (AppConfig.aiCurrentProviderId == providerId && AppConfig.aiCurrentModelId.isNullOrBlank()) {
            AppConfig.aiCurrentModelId = newModels.first().id
        }
        refreshUi()
        toastOnUi(getString(R.string.ai_fetch_models_success, newModels.size))
    }

    private fun confirmRemoveModel(model: AiModelConfig) {
        alert(
            title = model.modelId,
            message = getString(R.string.ai_remove_model_confirm)
        ) {
            okButton {
                AppConfig.aiModelConfigList =
                    AppConfig.aiModelConfigList.filterNot { it.id == model.id }
                refreshUi()
                toastOnUi(R.string.ai_model_removed)
            }
            cancelButton()
        }
    }

    private fun currentProviderModels(): List<AiModelConfig> {
        val providerId = AppConfig.aiCurrentProviderId ?: return emptyList()
        return AppConfig.aiModelConfigList.filter { it.providerId == providerId }
    }

    private fun refreshUi(notifyMain: Boolean = false) {
        val currentProvider = AppConfig.aiCurrentProvider
        val providerModels = currentProviderModels()
        agentSettings.refresh()
        findPreference<Preference>("aiManageProviders")?.summary =
            if (AppConfig.aiProviderList.isEmpty()) {
                getString(R.string.ai_no_providers)
            } else {
                buildString {
                    append(currentProvider?.name ?: getString(R.string.ai_current_provider_summary_empty))
                    append(" · ")
                    append(getString(R.string.ai_manage_providers_summary, AppConfig.aiProviderList.size))
                }
            }
        findPreference<Preference>("aiManageModels")?.summary =
            if (providerModels.isEmpty()) {
                getString(
                    if (currentProvider == null) {
                        R.string.ai_current_model_summary_empty
                    } else {
                        R.string.ai_current_model_summary_no_provider_models
                    }
                )
            } else {
                buildString {
                    append(AppConfig.aiCurrentModelConfig?.modelId ?: providerModels.first().modelId)
                    append(" · ")
                    append(getString(R.string.ai_manage_models_summary, providerModels.size))
                }
            }
        findPreference<Preference>("aiEditRequest")?.summary =
            getString(R.string.ai_edit_request_summary_global)
        findPreference<Preference>("aiSseIdleTimeoutSeconds")?.summary =
            getString(
                R.string.ai_sse_idle_timeout_summary,
                AiRequestTimeoutConfig.sseIdleTimeoutSeconds
            )
        findPreference<Preference>("aiGenerationTimeoutSeconds")?.summary =
            getString(
                R.string.ai_generation_timeout_summary,
                AiRequestTimeoutConfig.generationTimeoutSeconds
            )
        findPreference<Preference>("aiThinkingInterruptSeconds")?.summary =
            AiRequestTimeoutConfig.thinkingInterruptSeconds?.let {
                getString(R.string.ai_thinking_interrupt_seconds_summary_set, it)
            } ?: getString(R.string.ai_thinking_interrupt_seconds_summary_unset)
        findPreference<Preference>("aiThinkingInterruptMaxCount")?.summary =
            getString(
                R.string.ai_thinking_interrupt_max_count_summary,
                AiRequestTimeoutConfig.thinkingInterruptMaxCount
            )
        findPreference<Preference>("aiLogs")?.summary =
            getString(R.string.ai_log_summary, AppLog.aiLogs.size)
        val currentModelId = AppConfig.aiCurrentModelConfig?.modelId?.trim().orEmpty()
        findPreference<Preference>("aiTestCurrentConnection")?.summary = when {
            currentProvider == null -> getString(R.string.ai_connection_test_summary_missing_provider)
            currentModelId.isEmpty() ->
                getString(R.string.ai_connection_test_summary_missing_model)
            else -> getString(
                R.string.ai_connection_test_summary_target,
                currentProvider.name,
                currentModelId
            )
        }
        val advancedSettingsEnabled = preferenceManager.sharedPreferences
            ?.getBoolean(PreferKey.aiAdvancedSettingsEnabled, false) == true
        findPreference<SwitchPreference>(PreferKey.aiAdvancedSettingsEnabled)?.isChecked =
            advancedSettingsEnabled
        findPreference<Preference>("aiEditRequest")?.isVisible = advancedSettingsEnabled
        findPreference<Preference>(PreferKey.aiApiRedactionEnabled)?.isVisible =
            advancedSettingsEnabled
        findPreference<PreferenceGroup>("aiTimeoutCategory")?.isVisible = advancedSettingsEnabled
        findPreference<PreferenceGroup>("aiCreationCategory")?.isVisible = advancedSettingsEnabled
        listOf(
            "aiChapterPurifyFlowInfo",
            PreferKey.aiChapterPurifyReuseCurrentModel,
            PreferKey.aiChapterPurifyPrompt,
            PreferKey.aiChapterPurifyPreprocess,
            PreferKey.aiChapterPurifySummaryEnabled,
            PreferKey.aiChapterPurifyChapterCount,
            PreferKey.aiChapterPurifySegmentLimit,
            PreferKey.aiChapterPurifyRetryCount,
            PreferKey.aiChapterPurifyConcurrency
        ).forEach { key ->
            findPreference<Preference>(key)?.isVisible = advancedSettingsEnabled
        }
        val chapterPurifyReuseCurrentModel = AiChapterPurifyConfig.reuseCurrentModel
        findPreference<SwitchPreference>(PreferKey.aiChapterPurifyReuseCurrentModel)?.isChecked =
            chapterPurifyReuseCurrentModel
        findPreference<Preference>(PreferKey.aiChapterPurifyProvider)?.apply {
            isVisible = advancedSettingsEnabled && !chapterPurifyReuseCurrentModel
            val provider = AiChapterPurifyConfig.independentProvider
            summary = when {
                provider != null -> provider.name
                AiChapterPurifyConfig.independentProviderId.isNotBlank() ->
                    getString(R.string.ai_chapter_purify_reference_missing)
                else -> getString(R.string.ai_chapter_purify_provider_summary_empty)
            }
        }
        findPreference<Preference>(PreferKey.aiChapterPurifyModel)?.apply {
            isVisible = advancedSettingsEnabled && !chapterPurifyReuseCurrentModel
            val model = AiChapterPurifyConfig.independentModel
            summary = when {
                model != null -> model.modelId
                AiChapterPurifyConfig.independentModelId.isNotBlank() ->
                    getString(R.string.ai_chapter_purify_reference_missing)
                else -> getString(R.string.ai_chapter_purify_model_summary_empty)
            }
        }
        findPreference<Preference>(PreferKey.aiChapterPurifyRequestTemplate)?.apply {
            isVisible = advancedSettingsEnabled
            summary = getString(R.string.ai_chapter_purify_request_template_summary)
        }
        findPreference<Preference>("aiChapterPurifyTestConnection")?.isVisible =
            advancedSettingsEnabled && !chapterPurifyReuseCurrentModel
        findPreference<Preference>(PreferKey.aiChapterPurifyPrompt)?.summary =
            getString(R.string.ai_chapter_purify_prompt_summary)
        findPreference<Preference>(PreferKey.aiChapterPurifyPreprocess)?.summary =
            getString(R.string.ai_chapter_purify_preprocess_summary)
        findPreference<Preference>(PreferKey.aiChapterPurifyChapterCount)?.summary =
            getString(R.string.ai_chapter_purify_chapter_count_summary, AiChapterPurifyConfig.chapterCount)
        findPreference<Preference>(PreferKey.aiChapterPurifySegmentLimit)?.summary =
            getString(R.string.ai_chapter_purify_segment_limit_summary, AiChapterPurifyConfig.segmentLimit)
        findPreference<Preference>(PreferKey.aiChapterPurifyRetryCount)?.summary =
            getString(R.string.ai_chapter_purify_retry_count_summary, AiChapterPurifyConfig.retryCount)
        findPreference<Preference>(PreferKey.aiChapterPurifyConcurrency)?.summary =
            getString(R.string.ai_chapter_purify_concurrency_summary, AiChapterPurifyConfig.concurrency)
        val creationReuseCurrentModel = AiCreationConfig.reuseCurrentModel
        findPreference<SwitchPreference>(PreferKey.aiCreationReuseCurrentModel)?.isChecked =
            creationReuseCurrentModel
        findPreference<Preference>(PreferKey.aiCreationProvider)?.apply {
            isVisible = !creationReuseCurrentModel
            val provider = AiCreationConfig.independentProvider
            summary = when {
                provider != null -> provider.name
                AiCreationConfig.independentProviderId.isNotBlank() ->
                    getString(R.string.ai_chapter_purify_reference_missing)
                else -> getString(R.string.ai_creation_provider_summary_empty)
            }
        }
        findPreference<Preference>(PreferKey.aiCreationModel)?.apply {
            isVisible = !creationReuseCurrentModel
            val model = AiCreationConfig.independentModel
            summary = when {
                model != null -> model.modelId
                AiCreationConfig.independentModelId.isNotBlank() ->
                    getString(R.string.ai_chapter_purify_reference_missing)
                else -> getString(R.string.ai_creation_model_summary_empty)
            }
        }
        findPreference<Preference>(PreferKey.aiCreationPromptTemplate)?.summary =
            getString(
                R.string.ai_creation_prompt_template_summary,
                AiCreationConfig.promptTemplates.size
            )
        findPreference<Preference>(PreferKey.aiCreationLlmVariables)?.summary =
            getString(R.string.ai_creation_llm_variables_summary)
        findPreference<Preference>("aiCreationTestConnection")?.isVisible =
            !creationReuseCurrentModel
        val storyboardReuseCurrentModel = AiStoryboardConfig.reuseCurrentModel
        findPreference<SwitchPreference>(PreferKey.aiStoryboardReuseCurrentModel)?.isChecked =
            storyboardReuseCurrentModel
        findPreference<Preference>(PreferKey.aiStoryboardProviderId)?.apply {
            isVisible = !storyboardReuseCurrentModel
            val provider = AiStoryboardConfig.provider
            summary = when {
                provider != null -> provider.name
                AiStoryboardConfig.providerId.isNotBlank() ->
                    getString(R.string.ai_chapter_purify_reference_missing)
                else -> getString(R.string.ai_storyboard_provider_summary_empty)
            }
        }
        findPreference<Preference>(PreferKey.aiStoryboardModelId)?.apply {
            isVisible = !storyboardReuseCurrentModel
            val model = AiStoryboardConfig.model
            summary = when {
                model != null -> model.modelId
                AiStoryboardConfig.modelConfigId.isNotBlank() ->
                    getString(R.string.ai_chapter_purify_reference_missing)
                else -> getString(R.string.ai_storyboard_model_summary_empty)
            }
        }
        findPreference<Preference>(PreferKey.aiStoryboardPreloadCount)?.summary =
            getString(R.string.ai_storyboard_preload_count_summary, AiStoryboardConfig.preloadCount)
        findPreference<Preference>(PreferKey.aiStoryboardRequestTemplate)?.summary =
            getString(R.string.ai_storyboard_request_template_summary)
        findPreference<Preference>(PreferKey.aiCastingRequestTemplate)?.summary =
            getString(R.string.ai_casting_request_template_summary)
        findPreference<Preference>(PreferKey.aiCreationScope)?.summary =
            getString(R.string.ai_creation_scope_summary)
        // —— 图片供应商 ——
        val creationImageProvider = AiCreationProviderStore.imageCurrentProvider
        findPreference<Preference>("aiCreationImageApiKeyJump")?.apply {
            val url = creationImageProvider?.apiKeyUrl.orEmpty()
            //跳转按钮只在当前为内置供应商（有申请地址）时出现，自定义供应商隐藏
            isVisible = creationImageProvider?.builtIn == true && url.isNotBlank()
            summary = url
        }
        findPreference<Preference>("aiCreationImageManageProviders")?.summary =
            creationImageProvider?.name
                ?: getString(R.string.ai_creation_current_provider_empty)
        findPreference<Preference>("aiCreationImageManageModels")?.summary =
            AiCreationProviderStore.imageCurrentModelId.ifBlank {
                getString(R.string.ai_creation_current_model_empty)
            }
        // —— 视频供应商（与图片各自独立，始终可见） ——
        findPreference<Preference>("aiCreationVideoManageProviders")?.summary =
            AiCreationProviderStore.videoCurrentProvider?.name
                ?: getString(R.string.ai_creation_current_provider_empty)
        findPreference<Preference>("aiCreationVideoManageModels")?.summary =
            AiCreationProviderStore.videoCurrentModelId.ifBlank {
                getString(R.string.ai_creation_current_model_empty)
            }
        findPreference<Preference>(PreferKey.aiCreationImageRetryCount)?.summary =
            getString(
                R.string.ai_creation_image_retry_count_summary,
                AiCreationConfig.imageRetryCount
            )
        findPreference<Preference>(PreferKey.aiCreationPromptRegenerateLimit)?.summary =
            getString(
                R.string.ai_creation_prompt_regenerate_limit_summary,
                AiCreationConfig.promptRegenerateLimit
            )
        if (notifyMain) {
            postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }
}
