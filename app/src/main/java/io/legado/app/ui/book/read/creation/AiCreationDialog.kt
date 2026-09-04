package io.legado.app.ui.book.read.creation

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogAiCreationBinding
import io.legado.app.databinding.ItemAiPreviewBinding
import io.legado.app.help.ai.AI_CREATION_EPHEMERAL_BOOK
import io.legado.app.help.ai.AI_CREATION_LLM_INPUT_KEY
import io.legado.app.help.ai.AI_CREATION_IMAGE_COUNT_KEY
import io.legado.app.help.ai.AI_CREATION_MODE_KEY
import io.legado.app.help.ai.AiCreationConfig
import io.legado.app.help.ai.AiCreationHelper
import io.legado.app.help.ai.AiCreationImageFile
import io.legado.app.help.ai.AiCreationImageSlot
import io.legado.app.help.ai.AiCreationImageSlotState
import io.legado.app.help.ai.AiCreationImageTaskHolder
import io.legado.app.help.ai.AiCreationSessionHolder
import io.legado.app.help.ai.AiCreationVariable
import io.legado.app.help.ai.AiCreationVariableGroup
import io.legado.app.help.ai.AiCreationVariables
import io.legado.app.help.ai.CreationSectionItem
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.gone
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiCreationDialog : BaseDialogFragment(R.layout.dialog_ai_creation),
    AiCreationLibraryDialog.OnCardsAddedListener {

    companion object {
        const val ARG_BOOK_NAME = "bookName"
        const val ARG_JUMP_PREVIEW = "jumpToPreview"

        fun newInstance(bookName: String, jumpToPreview: Boolean = false): AiCreationDialog {
            return AiCreationDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK_NAME, bookName)
                    putBoolean(ARG_JUMP_PREVIEW, jumpToPreview)
                }
            }
        }
    }

    private val binding by viewBinding(DialogAiCreationBinding::bind)
    private val bookName: String by lazy { requireArguments().getString(ARG_BOOK_NAME).orEmpty() }
    private val session get() = AiCreationSessionHolder.session
    private var variableGroups: List<AiCreationVariableGroup> = emptyList()
    private var currentPage = 0
    private var generating = false
    private var suppressTextWatcher = false
    private var pendingGenerateAfterPrompt = false
    private var previewPageSize = 1
    private var previewPage = 0
    private val previewAdapter = PreviewAdapter()
    private var inDialogFloatingHost: AiCreationFloatingHost? = null

    private val cardEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val deleted = result.data?.getBooleanExtra("creationCardDeleted", false) ?: false
        val cardId = result.data?.getLongExtra("creationCardId", -1L) ?: -1L
        if (deleted && cardId > 0) {
            AiCreationConfig.sectionOrder.forEach { section ->
                session.removeCard(section, cardId)
            }
        }
        if (currentPage == 1) {
            rebuildSections()
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        AiCreationImageTaskHolder.setPreviewBlocking(currentPage == 3)
    }

    override fun onStop() {
        super.onStop()
        //预览页离开前台，全应用悬浮窗恢复显示判断
        AiCreationImageTaskHolder.setPreviewBlocking(false)
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        //系统返回键或触屏外部关闭创作界面，同样视作会话结束
        destroyEphemeralCards()
    }

    override fun onResume() {
        super.onResume()
        if (currentPage == 1) {
            rebuildSections()
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        session.bookName = bookName
        //变量区 = LLM 变量（style，控制发给 LLM 的内容）+ 供应商生图/生视频参数
        val imageVariables = AiCreationConfig.imageLlmDefinition.variables +
            AiCreationConfig.imageVariables
        val videoVariables = AiCreationConfig.videoLlmDefinition.variables +
            AiCreationConfig.videoVariables
        variableGroups = listOf(
            AiCreationVariableGroup(
                key = AiCreationVariables.GROUP_IMAGE,
                label = "图片",
                variables = imageVariables
            ),
            AiCreationVariableGroup(
                key = AiCreationVariables.GROUP_VIDEO,
                label = "视频",
                variables = videoVariables
            )
        )
        require(variableGroups.all { it.variables.isNotEmpty() }) {
            "图片和视频的 LLM 变量与供应商变量都不能为空"
        }
        //首次使用选图片；已存模式必须是当前新体系的合法值。
        val savedMode = session.paramValue(AI_CREATION_MODE_KEY)
        val initialIndex = if (savedMode == null) {
            0
        } else {
            variableGroups.indexOfFirst { it.key == savedMode }
                .also { require(it >= 0) { "AI 创作模式无效：$savedMode" } }
        }
        session.setParam(
            AI_CREATION_MODE_KEY,
            variableGroups.getOrNull(initialIndex)?.key.orEmpty()
        )
        binding.ivClose.setOnClickListener {
            destroyEphemeralCards()
            dismissAllowingStateLoss()
        }
        binding.ivBack.setOnClickListener { onBack() }
        binding.tvAction.setOnClickListener { onAction() }
        binding.tvClear.setOnClickListener {
            if (currentPage == 2) {
                copyPrompt()
            } else if (currentPage == 4) {
                copyLlmInput()
            } else {
                confirmClear()
            }
        }
        binding.btnGenerateImage.setOnClickListener { onGenerateImageClicked() }
        binding.tvGridOne.setOnClickListener {
            previewPageSize = 1
            previewPage = 0
            renderPreview()
        }
        binding.tvGridTwo.setOnClickListener {
            previewPageSize = 2
            previewPage = 0
            renderPreview()
        }
        binding.tvGridFour.setOnClickListener {
            previewPageSize = 4
            previewPage = 0
            renderPreview()
        }
        binding.tvPrevPage.setOnClickListener {
            if (previewPage > 0) {
                previewPage--
                renderPreview()
            }
        }
        binding.tvNextPage.setOnClickListener {
            if (previewPage < previewPageCount() - 1) {
                previewPage++
                renderPreview()
            }
        }
        binding.rvPreview.adapter = previewAdapter
        binding.rvPreview.layoutManager = GridLayoutManager(requireContext(), 1)
        viewLifecycleOwner.lifecycleScope.launch {
            AiCreationImageTaskHolder.slots.collect {
                if (currentPage == 3) {
                    renderPreview()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            AiCreationImageTaskHolder.notice.collect { message ->
                if (message != null) {
                    AiCreationImageTaskHolder.consumeNotice()
                    toastOnUi(message)
                }
            }
        }
        //对话框前台时悬浮窗由对话框宿主挂载在窗口顶层；预览页由 previewBlocking 拦截不显示
        inDialogFloatingHost = dialog?.window?.decorView
            ?.findViewById<ViewGroup>(android.R.id.content)
            ?.let { content ->
                AiCreationFloatingHost(
                    container = content,
                    layoutParams = {
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.END or Gravity.BOTTOM
                            marginEnd = dp(16)
                            bottomMargin = dp(64)
                        }
                    },
                    onOpen = { showPage(3) }
                )
            }
        viewLifecycleOwner.lifecycleScope.launch {
            AiCreationImageTaskHolder.floatingState.collect {
                upInDialogFloating()
            }
        }
        binding.etPrompt.addTextChangedListener { text ->
            if (!suppressTextWatcher) {
                session.prompt = text?.toString().orEmpty()
            }
        }
        binding.tvManual.setOnClickListener { showPage(4) }
        binding.etLlmInput.addTextChangedListener { text ->
            if (!suppressTextWatcher) {
                session.manualLlmInput = text?.toString().orEmpty()
            }
        }
        binding.etManualPrompt.addTextChangedListener { text ->
            if (!suppressTextWatcher) {
                session.prompt = text?.toString().orEmpty()
            }
        }
        //生成数量实时持久化：下次进入提示词页直接恢复上次值
        binding.etImageCount.addTextChangedListener { text ->
            val count = text?.toString()?.toIntOrNull()
                ?.coerceIn(1, 10) ?: 1
            session.setParam(AI_CREATION_IMAGE_COUNT_KEY, count.toString())
        }
        binding.tabLayout.setBackgroundColor(backgroundColor)
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        variableGroups.forEach { group ->
            binding.tabLayout.addTab(
                binding.tabLayout.newTab().setText(group.label.ifBlank { group.key })
            )
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val index = binding.tabLayout.selectedTabPosition
                variableGroups.getOrNull(index)?.let { group ->
                    session.setParam(AI_CREATION_MODE_KEY, group.key)
                    buildVariableControls(group)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        variableGroups.getOrNull(initialIndex)?.let { group ->
            buildVariableControls(group)
            if (binding.tabLayout.selectedTabPosition != initialIndex) {
                binding.tabLayout.getTabAt(initialIndex)?.select()
            }
        }
        if (requireArguments().getBoolean(ARG_JUMP_PREVIEW)) {
            showPage(3)
        } else {
            showPage(0)
        }
    }

    //左上角返回只在创作体系各页内回退，不退出界面；手动提示词页入口在组合素材页，返回也回组合素材页；
    //界面关闭（叉叉/系统返回键）才销毁临时卡片，回预览走生成任务悬浮窗
    private fun onBack() {
        when (currentPage) {
            0 -> Unit
            4 -> showPage(1)
            else -> showPage(currentPage - 1)
        }
    }

    private fun onAction() {
        when (currentPage) {
            0 -> showPage(1)
            1 -> generatePrompt()
            2 -> generatePrompt()
            4 -> generatePromptFromLlmInput()
        }
    }

    private fun isVideoMode(): Boolean {
        return when (val mode = session.paramValue(AI_CREATION_MODE_KEY)) {
            AiCreationVariables.GROUP_IMAGE -> false
            AiCreationVariables.GROUP_VIDEO -> true
            null -> error("AI 创作模式未设置")
            else -> error("未知 AI 创作模式：$mode")
        }
    }

    private fun showPage(page: Int) {
        currentPage = page
        binding.llModePage.visibility = if (page == 0) View.VISIBLE else View.GONE
        binding.svComposePage.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.llPromptPage.visibility = if (page == 2) View.VISIBLE else View.GONE
        binding.llManualPage.visibility = if (page == 4) View.VISIBLE else View.GONE
        binding.llPreviewPage.visibility = if (page == 3) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (page == 3) View.GONE else View.VISIBLE
        binding.ivBack.visibility = if (page > 0) View.VISIBLE else View.GONE
        binding.tvTitle.setText(
            when (page) {
                1 -> R.string.ai_creation_compose
                2 -> R.string.ai_creation_prompt_title
                3 -> R.string.ai_creation_preview_title
                4 -> R.string.ai_creation_manual_prompt
                else -> R.string.ai_creation
            }
        )
        val isVideo = isVideoMode()
        binding.etImageCount.visibility = if (page == 2 || page == 4) View.VISIBLE else View.GONE
        binding.etImageCount.setText(
            session.paramValue(AI_CREATION_IMAGE_COUNT_KEY) ?: "1"
        )
        binding.btnGenerateImage.visibility =
            if (page == 2 || page == 4) View.VISIBLE else View.GONE
        binding.btnGenerateImage.setText(
            if (isVideo) R.string.ai_creation_generate_video else R.string.ai_creation_generate_image
        )
        binding.tvClear.setText(
            when (page) {
                2 -> R.string.ai_creation_copy_prompt
                4 -> R.string.ai_creation_copy_llm_input
                else -> R.string.ai_creation_clear
            }
        )
        binding.tvClear.visibility = when {
            page == 1 -> View.VISIBLE
            page == 2 && !isVideo -> View.VISIBLE
            page == 4 -> View.VISIBLE
            else -> View.GONE
        }
        binding.tvManual.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.tvAction.setText(
            when (page) {
                1, 2, 4 -> R.string.ai_creation_generate_prompt
                else -> R.string.ai_creation_next
            }
        )
        if (page == 1) {
            rebuildSections()
        }
        if (page == 2 && session.prompt.isNotBlank()) {
            suppressTextWatcher = true
            binding.etPrompt.setText(session.prompt)
            suppressTextWatcher = false
        }
        if (page == 4) {
            suppressTextWatcher = true
            binding.etManualPrompt.setText(session.prompt)
            binding.etLlmInput.setText(session.manualLlmInput)
            suppressTextWatcher = false
            //从未手动编辑过LLM输入时按当前卡片重新汇总预填；编辑过则保留用户快照
            if (session.manualLlmInput.isBlank()) {
                prefillLlmInput()
            }
        }
        if (page == 3) {
            previewPage = 0
            renderPreview()
            binding.rvPreview.post {
                if (currentPage == 3) {
                    renderPreview()
                }
            }
        }
        AiCreationImageTaskHolder.setPreviewBlocking(page == 3)
        upInDialogFloating()
    }

    private fun upInDialogFloating() {
        val host = inDialogFloatingHost ?: return
        val state = AiCreationImageTaskHolder.floatingState.value
        host.update(show = state.shouldShow, taskRunning = state.taskRunning)
    }

    private fun isLlmVariable(variable: AiCreationVariable): Boolean {
        val definition = if (isVideoMode()) {
            AiCreationConfig.videoLlmDefinition
        } else {
            AiCreationConfig.imageLlmDefinition
        }
        return definition.variables.any { it.key == variable.key }
    }

    /** LLM 变量存 LLM 存储（不随供应商），供应商变量存供应商隔离存储 */
    private fun currentParamValue(variable: AiCreationVariable): String {
        val mode = currentGroup()?.key ?: error("AI 创作模式未选择")
        return if (isLlmVariable(variable)) {
            variable.effectiveValue(session.llmVariableValue(mode, variable.key))
        } else {
            variable.effectiveValue(session.providerVariableValue(mode, variable.key))
        }
    }

    private fun setCurrentParamValue(variable: AiCreationVariable, value: String) {
        val mode = currentGroup()?.key ?: error("AI 创作模式未选择")
        if (isLlmVariable(variable)) {
            session.setLlmVariable(mode, variable.key, value)
        } else {
            session.setProviderVariable(mode, variable.key, value)
        }
    }

    private fun buildVariableControls(group: AiCreationVariableGroup) {
        binding.llVariables.removeAllViews()
        group.variables.forEach { variable ->
            val label = AccentTextView(requireContext(), null).apply {
                text = variable.label
                textSize = 15f
                setPadding(0, dp(14), 0, dp(6))
            }
            binding.llVariables.addView(label)
            when (variable.format) {
                AiCreationVariable.FORMAT_INPUT -> addInputControl(variable)
                AiCreationVariable.FORMAT_SWITCH -> addSwitchControl(variable)
                else -> addOptionsControl(variable)
            }
        }
    }

    private fun addOptionsControl(variable: AiCreationVariable) {
        val row = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
        }
        val line = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(line)
        val current = currentParamValue(variable)
        //options 为显示文案（可带比例/横竖标注），effectiveValues 为实际存储与下发的纯值
        val optionValues = variable.effectiveValues()
        variable.options.forEachIndexed { index, option ->
            val value = optionValues.getOrNull(index) ?: option
            line.addView(
                chipView(option, value == current).apply {
                    setOnClickListener {
                        setCurrentParamValue(variable, value)
                        buildVariableControls(
                            currentGroup()
                                ?: variableGroups.firstOrNull() ?: return@setOnClickListener
                        )
                    }
                }
            )
        }
        binding.llVariables.addView(row)
    }

    private fun addSwitchControl(variable: AiCreationVariable) {
        val current = currentParamValue(variable)
        val isOn = current == variable.onValue
        binding.llVariables.addView(
            chipView(current, true).apply {
                setOnClickListener {
                    setCurrentParamValue(
                        variable,
                        if (isOn) variable.offValue else variable.onValue
                    )
                    buildVariableControls(
                        currentGroup() ?: variableGroups.firstOrNull() ?: return@setOnClickListener
                    )
                }
            }
        )
    }

    private fun addInputControl(variable: AiCreationVariable) {
        binding.llVariables.addView(
            chipView(currentParamValue(variable), true).apply {
                setOnClickListener { showVariableInputDialog(variable) }
            }
        )
    }

    private fun showVariableInputDialog(variable: AiCreationVariable) {
        val editBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setText(currentParamValue(variable))
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(title = variable.label) {
            customView { editBinding.root }
            okButton {
                setCurrentParamValue(
                    variable,
                    editBinding.editView.text?.toString()?.trim().orEmpty()
                )
                buildVariableControls(
                    currentGroup() ?: variableGroups.firstOrNull() ?: return@okButton
                )
            }
            cancelButton()
        }
    }

    private fun currentGroup(): AiCreationVariableGroup? {
        val index = binding.tabLayout.selectedTabPosition
        return variableGroups.getOrNull(index)
    }

    private fun chipView(text: String, selected: Boolean): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            val margin = dp(6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(margin, 0, margin, 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (selected) {
                    setColor(context.accentColor)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Color.parseColor("#44808080"))
                }
            }
            setTextColor(if (selected) Color.WHITE else context.primaryTextColor)
        }
    }

    override fun onCardsAddedToSection(section: String) {
        if (currentPage == 1) {
            rebuildSections()
        }
    }

    private fun rebuildSections() {
        binding.llSections.removeAllViews()
        AiCreationConfig.sectionOrder.forEach { section ->
            addSectionView(section)
        }
    }

    private fun addSectionView(section: String) {
        val label = AiCreationSessionHolder.session.sectionLabel(section)
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(6))
        }
        header.addView(
            AccentTextView(requireContext(), null).apply {
                text = label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)
                )
                //分区名即连线按钮：长按发起连线（已连线分区可选发起或取消），再点另一分区名完成连线
                setOnClickListener { onSectionLabelClick(section) }
                setOnLongClickListener {
                    onSectionLabelLongClick(section)
                    true
                }
            }
        )
        header.addView(linkStateView(section))
        header.addView(
            View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
        )
        header.addView(
            AccentTextView(requireContext(), null).apply {
                text = getString(R.string.ai_creation_add)
                textSize = 17f
                setPadding(dp(16), dp(4), dp(4), dp(4))
                setOnClickListener { openLibrary(section) }
            }
        )
        binding.llSections.addView(header)

        val items = session.itemsOf(section)
        val flow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        if (items.isEmpty()) {
            flow.addView(hintView(getString(R.string.ai_creation_empty_section)))
        } else {
            items.forEach { item ->
                flow.addView(cardTile(item))
            }
        }
        binding.llSections.addView(flow)
    }

    private fun linkStateView(section: String): TextView {
        val pending = session.pendingLink
        val group = session.linkGroupOf(section)
        val text = when {
            pending == section -> getString(R.string.ai_creation_linking)
            group != null -> getString(R.string.ai_creation_group_label, group.label)
            else -> ""
        }
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 9f
            setTextColor(context.accentColor)
            setPadding(dp(6), 0, 0, 0)
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun onSectionLabelClick(section: String) {
        val pending = session.pendingLink ?: return
        if (pending == section) {
            session.pendingLink = null
        } else {
            val groupLabel = session.toggleLink(pending, section)
            toastOnUi(
                if (groupLabel != null) {
                    getString(R.string.ai_creation_link_done, groupLabel)
                } else {
                    getString(R.string.ai_creation_link_removed)
                }
            )
            session.pendingLink = null
        }
        rebuildSections()
    }

    private fun onSectionLabelLongClick(section: String) {
        if (session.pendingLink != null) {
            onSectionLabelClick(section)
            return
        }
        if (session.isSectionLinked(section)) {
            requireContext().selector(
                AiCreationSessionHolder.session.sectionLabel(section),
                listOf(
                    getString(R.string.ai_creation_start_link),
                    getString(R.string.ai_creation_unlink)
                )
            ) { _, _, which ->
                when (which) {
                    0 -> startSectionLink(section)
                    else -> removeSectionGroup(section)
                }
            }
        } else {
            startSectionLink(section)
        }
    }

    private fun startSectionLink(section: String) {
        session.pendingLink = section
        toastOnUi(R.string.ai_creation_linking_hint)
        rebuildSections()
    }

    private fun removeSectionGroup(section: String) {
        session.linkGroups.removeAll { group ->
            group.sections.contains(section)
        }
        rebuildSections()
    }

    private fun hintView(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#80808080"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
    }

    private fun cardTile(item: CreationSectionItem): View {
        val tile = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(56)).apply {
                setMargins(0, 0, dp(8), dp(8))
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(context.backgroundColor)
                setStroke(dp(1), Color.parseColor("#33808080"))
            }
        }
        val nameView = TextView(requireContext()).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(context.primaryTextColor)
            lifecycleScope.launch {
                val card = withContext(IO) { appDb.creationCardDao.getById(item.cardId) }
                text = card?.name?.ifBlank { null }
                    ?: AiCreationSessionHolder.session.sectionLabel(item.section)
            }
        }
        tile.addView(nameView)
        tile.setOnClickListener { openCardEditor(item.cardId) }
        tile.setOnLongClickListener {
            showTileMenu(item)
            true
        }
        return tile
    }

    private fun showTileMenu(item: CreationSectionItem) {
        val actions = listOf(
            getString(R.string.edit),
            getString(R.string.ai_creation_remove)
        )
        requireContext().selector(
            AiCreationSessionHolder.session.sectionLabel(item.section),
            actions
        ) { _, _, action ->
            when (action) {
                0 -> openCardEditor(item.cardId)
                1 -> {
                    session.removeCard(item.section, item.cardId)
                    rebuildSections()
                }
            }
        }
    }

    private fun openLibrary(section: String) {
        val scope = AiCreationConfig.sectionScope(section)
        val libraryBookName = when (scope) {
            AiCreationConfig.SCOPE_GLOBAL -> ""
            AiCreationConfig.SCOPE_BOOK -> bookName
            else -> AI_CREATION_EPHEMERAL_BOOK
        }
        AiCreationLibraryDialog.newInstance(section, libraryBookName)
            .show(childFragmentManager, "creationLibrary")
    }

    private fun openCardEditor(cardId: Long) {
        val intent = Intent(requireContext(), CodeEditActivity::class.java).apply {
            putExtra("creationCardId", cardId)
        }
        cardEditLauncher.launch(intent)
    }

    private fun generatePrompt() {
        if (generating) return
        val cardIds = AiCreationConfig.sectionOrder
            .flatMap { session.itemsOf(it) }
            .map { it.cardId }
            .distinct()
        if (cardIds.isEmpty()) {
            toastOnUi(R.string.ai_creation_select_card_first)
            return
        }
        generating = true
        binding.rotateLoading.visible()
        binding.tvAction.setText(R.string.ai_creation_generating)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val cardsById = withContext(IO) {
                    cardIds.mapNotNull { appDb.creationCardDao.getById(it) }
                        .associateBy { it.cardId }
                }
                withContext(IO) {
                    AiCreationHelper.generatePrompt(session, cardsById)
                }
            }
            generating = false
            binding.rotateLoading.inVisible()
            binding.tvAction.setText(R.string.ai_creation_generate_prompt)
            result.onSuccess { prompt ->
                session.prompt = prompt
                if (pendingGenerateAfterPrompt) {
                    pendingGenerateAfterPrompt = false
                    startGeneration(prompt)
                } else {
                    showPage(2)
                }
            }.onFailure { throwable ->
                pendingGenerateAfterPrompt = false
                binding.tvAction.setText(R.string.ai_creation_generate_prompt)
                toastOnUi(throwable.message ?: throwable.javaClass.simpleName)
            }
        }
    }

    /** 手动提示词页：用上框LLM输入文本生成提示词，结果只自动填入下框，不跳页 */
    private fun generatePromptFromLlmInput() {
        if (generating) return
        val llmInput = binding.etLlmInput.text?.toString()?.trim().orEmpty()
        if (llmInput.isEmpty()) {
            toastOnUi(R.string.ai_creation_llm_input_empty)
            return
        }
        session.manualLlmInput = llmInput
        generating = true
        binding.rotateLoading.visible()
        binding.tvAction.setText(R.string.ai_creation_generating)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(IO) {
                    AiCreationHelper.generatePrompt(session, llmInput)
                }
            }
            generating = false
            binding.rotateLoading.inVisible()
            binding.tvAction.setText(R.string.ai_creation_generate_prompt)
            result.onSuccess { prompt ->
                session.prompt = prompt
                suppressTextWatcher = true
                binding.etManualPrompt.setText(prompt)
                suppressTextWatcher = false
            }.onFailure { throwable ->
                toastOnUi(throwable.message ?: throwable.javaClass.simpleName)
            }
        }
    }

    /** 手动提示词页LLM输入预填：按当前卡片重新汇总；仅在此期间用户仍未编辑时写入 */
    private fun prefillLlmInput() {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(IO) {
                val cardIds = AiCreationConfig.sectionOrder
                    .flatMap { session.itemsOf(it) }
                    .map { it.cardId }
                    .distinct()
                val cardsById = cardIds.mapNotNull { appDb.creationCardDao.getById(it) }
                    .associateBy { it.cardId }
                session.buildMaterialText(cardsById)
            }
            if (currentPage == 4 && session.manualLlmInput.isBlank()) {
                suppressTextWatcher = true
                binding.etLlmInput.setText(text)
                suppressTextWatcher = false
            }
        }
    }

    private fun onGenerateImageClicked() {
        if (currentPage == 4) {
            //手动挡：只用下框内容，为空直接报错，不自动代生成
            val prompt = binding.etManualPrompt.text?.toString()?.trim().orEmpty()
            if (prompt.isEmpty()) {
                toastOnUi(R.string.ai_creation_prompt_empty)
                return
            }
            session.prompt = prompt
            startGeneration(prompt)
            return
        }
        val prompt = binding.etPrompt.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            pendingGenerateAfterPrompt = true
            generatePrompt()
            return
        }
        startGeneration(prompt)
    }

    private fun startGeneration(prompt: String) {
        if (isVideoMode()) {
            startVideoGeneration(prompt)
        } else {
            startImageGeneration(prompt)
        }
    }

    private fun startVideoGeneration(prompt: String) {
        val count = binding.etImageCount.text?.toString()?.toIntOrNull()
            ?.coerceIn(1, 10) ?: 1
        session.setParam(AI_CREATION_IMAGE_COUNT_KEY, count.toString())
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val providerVariables = AiCreationConfig.videoVariables
                val values = AiCreationHelper.buildRequestValues(session, providerVariables)
                AiCreationImageTaskHolder.startVideo(
                    prompt,
                    count,
                    values,
                    session.paramValue(AI_CREATION_LLM_INPUT_KEY).orEmpty()
                )
            }
            result.onSuccess {
                showPage(3)
            }.onFailure { throwable ->
                toastOnUi(throwable.message ?: throwable.javaClass.simpleName)
            }
        }
    }

    private fun startImageGeneration(prompt: String) {
        val count = binding.etImageCount.text?.toString()?.toIntOrNull()
            ?.coerceIn(1, 10) ?: 1
        session.setParam(AI_CREATION_IMAGE_COUNT_KEY, count.toString())
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val providerVariables = AiCreationConfig.imageVariables
                val values = AiCreationHelper.buildRequestValues(session, providerVariables)
                AiCreationConfig.requireImageApiReady()
                AiCreationImageTaskHolder.start(
                    prompt,
                    count,
                    values,
                    session.paramValue(AI_CREATION_LLM_INPUT_KEY).orEmpty()
                )
            }
            result.onSuccess {
                showPage(3)
            }.onFailure { throwable ->
                toastOnUi(throwable.message ?: throwable.javaClass.simpleName)
            }
        }
    }

    private fun previewPageCount(): Int {
        val total = AiCreationImageTaskHolder.slots.value.size
        return maxOf(1, (total + previewPageSize - 1) / previewPageSize)
    }

    private fun renderPreview() {
        val slots = AiCreationImageTaskHolder.slots.value
        val pageCount = previewPageCount()
        previewPage = previewPage.coerceIn(0, pageCount - 1)
        binding.tvPageInfo.text = getString(
            R.string.ai_creation_page_info,
            previewPage + 1,
            pageCount
        )
        val from = previewPage * previewPageSize
        val to = minOf(slots.size, from + previewPageSize)
        previewAdapter.slots = if (from < to) slots.subList(from, to) else emptyList()
        previewAdapter.itemHeightPx = previewItemHeight()
        previewAdapter.notifyDataSetChanged()
        val span = if (previewPageSize == 4) 2 else 1
        (binding.rvPreview.layoutManager as? GridLayoutManager)?.spanCount = span
        binding.tvGridOne.setTextColor(if (previewPageSize == 1) accentColor else primaryTextColor)
        binding.tvGridTwo.setTextColor(if (previewPageSize == 2) accentColor else primaryTextColor)
        binding.tvGridFour.setTextColor(if (previewPageSize == 4) accentColor else primaryTextColor)
    }

    private fun previewItemHeight(): Int {
        val available = binding.rvPreview.height.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels * 2 / 3)
        //一宫格一页一图独占整页，2/4宫格按两行等分
        return if (previewPageSize == 1) available - dp(8) else available / 2 - dp(8)
    }

    private inner class PreviewAdapter : RecyclerView.Adapter<PreviewViewHolder>() {

        var slots: List<AiCreationImageSlot> = emptyList()

        var itemHeightPx: Int = 0

        override fun getItemCount(): Int = slots.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
            val binding = ItemAiPreviewBinding.inflate(layoutInflater, parent, false)
            return PreviewViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
            val slot = slots.getOrNull(position) ?: return
            holder.itemView.layoutParams = holder.itemView.layoutParams?.apply {
                height = itemHeightPx
            } ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                itemHeightPx
            )
            holder.bind(slot)
        }

        override fun onBindViewHolder(
            holder: PreviewViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            onBindViewHolder(holder, position)
        }
    }

    private inner class PreviewViewHolder(
        private val itemBinding: ItemAiPreviewBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(slot: AiCreationImageSlot) = itemBinding.run {
            when (slot.state) {
                AiCreationImageSlotState.LOADING -> {
                    rotateLoading.visible()
                    ivPhoto.gone()
                    tvFailed.gone()
                }

                AiCreationImageSlotState.DONE -> {
                    rotateLoading.gone()
                    tvFailed.gone()
                    if (slot.fileName.startsWith("vid_")) {
                        bindVideoResult(itemBinding, slot)
                    } else {
                        bindImageResult(itemBinding, slot)
                    }
                }

                AiCreationImageSlotState.FAILED -> {
                    rotateLoading.gone()
                    ivPhoto.gone()
                    tvFailed.visible()
                    tvFailed.text = getString(R.string.ai_creation_slot_failed, slot.error)
                    tvFailed.setOnClickListener(null)
                }
            }
        }
    }

    private fun bindImageResult(
        itemBinding: ItemAiPreviewBinding,
        slot: AiCreationImageSlot
    ) {
        val ivPhoto = itemBinding.ivPhoto
        ivPhoto.visible()
        ImageLoader.load(itemBinding.root.context, AiCreationImageFile.fileOf(slot.fileName))
            .dontTransform()
            .into(ivPhoto)
        ivPhoto.setOnClickListener {
            val doneFiles = previewAdapter.slots
                .filter { it.state == AiCreationImageSlotState.DONE }
                .map { it.fileName }
            val position = doneFiles.indexOf(slot.fileName)
            AiCreationPhotoDialog.newInstance(doneFiles, position)
                .show(childFragmentManager, "creationPhoto")
        }
        ivPhoto.setOnLongClickListener {
            showSlotSaveMenu(slot.fileName)
            true
        }
    }

    /** 视频槽位：首帧做缩略图，点击内置播放器播放，长按弹保存菜单 */
    private fun bindVideoResult(
        itemBinding: ItemAiPreviewBinding,
        slot: AiCreationImageSlot
    ) {
        val ivPhoto = itemBinding.ivPhoto
        ivPhoto.visible()
        ivPhoto.setImageBitmap(null)
        ivPhoto.setOnClickListener(null)
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(IO) {
                runCatching {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(
                            AiCreationImageFile.fileOf(slot.fileName).absolutePath
                        )
                        retriever.getFrameAtTime(0)
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }
            val stillCurrent = previewAdapter.slots.any {
                it.state == AiCreationImageSlotState.DONE && it.fileName == slot.fileName
            }
            if (stillCurrent) {
                ivPhoto.setImageBitmap(bitmap)
            }
        }
        ivPhoto.setOnClickListener {
            val doneFiles = previewAdapter.slots
                .filter { it.state == AiCreationImageSlotState.DONE }
                .map { it.fileName }
            val position = doneFiles.indexOf(slot.fileName)
            AiCreationPhotoDialog.newInstance(doneFiles, position)
                .show(childFragmentManager, "creationPhoto")
        }
        ivPhoto.setOnLongClickListener {
            showSlotSaveMenu(slot.fileName)
            true
        }
    }

    /** 生成结果长按菜单：保存到相册、保存工作流、复制工作流；图片视频同一菜单 */
    private fun showSlotSaveMenu(fileName: String) {
        requireContext().selector(
            AiCreationImageFile.fileOf(fileName).name,
            listOf(
                getString(R.string.illustration_save_to_album),
                getString(R.string.ai_creation_save_workflow),
                getString(R.string.ai_creation_copy_workflow)
            )
        ) { _, _, which ->
            when (which) {
                0 -> saveSlotToAlbum(fileName)
                1 -> exportWorkflow(fileName)
                else -> copyWorkflow(fileName)
            }
        }
    }

    private fun saveSlotToAlbum(fileName: String) {
        val ok = AiCreationImageFile.saveToAlbum(requireContext(), fileName)
        toastOnUi(
            if (ok) R.string.illustration_saved_to_album
            else R.string.illustration_save_failed
        )
    }

    private fun exportWorkflow(fileName: String) {
        val json = AiCreationImageFile.readWorkflowJson(fileName)
        if (json == null) {
            toastOnUi(R.string.ai_creation_workflow_missing)
            return
        }
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(IO) {
                AiCreationImageFile.saveWorkflowToDownloads(context, fileName, json)
            }
            toastOnUi(
                if (ok) R.string.ai_creation_workflow_saved
                else R.string.ai_creation_workflow_save_failed
            )
        }
    }

    private fun copyWorkflow(fileName: String) {
        val json = AiCreationImageFile.readWorkflowJson(fileName)
        if (json == null) {
            toastOnUi(R.string.ai_creation_workflow_missing)
            return
        }
        requireContext().sendToClip(json)
        toastOnUi(R.string.ai_creation_workflow_copied)
    }

    private fun copyPrompt() {
        val prompt = binding.etPrompt.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            toastOnUi(R.string.ai_creation_prompt_empty)
            return
        }
        session.prompt = prompt
        requireContext().sendToClip(prompt)
        toastOnUi(R.string.ai_creation_copied)
    }

    /** 手动提示词页复制的是上框LLM输入，不是下框提示词；只做复制，不做其他动作 */
    private fun copyLlmInput() {
        val llmInput = binding.etLlmInput.text?.toString()?.trim().orEmpty()
        if (llmInput.isEmpty()) {
            toastOnUi(R.string.ai_creation_llm_input_empty)
            return
        }
        session.manualLlmInput = llmInput
        requireContext().sendToClip(llmInput)
        toastOnUi(R.string.ai_creation_copied)
    }

    private fun confirmClear() {
        alert(R.string.ai_creation_clear) {
            setMessage(R.string.ai_creation_clear_confirm)
            okButton {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(IO) {
                        appDb.creationCardDao.deleteByBookName(AI_CREATION_EPHEMERAL_BOOK)
                    }
                    session.clear()
                    session.setParam(
                        AI_CREATION_MODE_KEY,
                        variableGroups.firstOrNull()?.key.orEmpty()
                    )
                    variableGroups.firstOrNull()?.let { buildVariableControls(it) }
                    binding.tabLayout.getTabAt(0)?.select()
                    showPage(0)
                }
            }
            cancelButton()
        }
    }

    /**
     * 「一次性」素材的生命周期锚点：创作界面关闭即销毁临时分区全部卡片，
     * 并把删除的卡片从当前会话摘除（分区条目、链接组、待链接引用一并清理）。
     * 先取 ID 快照再按 ID 删除，避免误删关闭动作之后才重新暂存的新卡片；
     * 销毁协程挂在宿主 Activity 上，防止界面销毁把协程一并取消。
     */
    private fun destroyEphemeralCards() {
        requireActivity().lifecycleScope.launch {
            val cardIds = withContext(IO) {
                appDb.creationCardDao.getIdsByBookName(AI_CREATION_EPHEMERAL_BOOK)
            }
            if (cardIds.isNotEmpty()) {
                withContext(IO) {
                    cardIds.forEach { appDb.creationCardDao.deleteById(it) }
                }
            }
            cardIds.forEach { cardId ->
                AiCreationConfig.sectionOrder.forEach { section ->
                    session.removeCard(section, cardId)
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
