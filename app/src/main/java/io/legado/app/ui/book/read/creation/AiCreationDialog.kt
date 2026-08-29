package io.legado.app.ui.book.read.creation

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

class AiCreationDialog : BaseDialogFragment(R.layout.dialog_ai_creation) {

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
    private var suppressPromptWatcher = false
    private var pendingImageAfterPrompt = false
    private var previewPageSize = 2
    private var previewPage = 0
    private val previewAdapter = PreviewAdapter()

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
        AiCreationImageTaskHolder.setUiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        AiCreationImageTaskHolder.setUiVisible(false)
    }

    override fun onResume() {
        super.onResume()
        if (currentPage == 1) {
            rebuildSections()
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        session.bookName = bookName
        val definition = try {
            AiCreationConfig.definition
        } catch (throwable: Throwable) {
            toastOnUi(throwable.message ?: throwable.javaClass.simpleName)
            AiCreationVariables.parse(AiCreationVariables.defaultJson)
        }
        variableGroups = definition.groups.filter { it.variables.isNotEmpty() }
        session.params[AI_CREATION_MODE_KEY] = variableGroups.firstOrNull()?.key.orEmpty()
        binding.ivClose.setOnClickListener { dismissAllowingStateLoss() }
        binding.ivBack.setOnClickListener { onBack() }
        binding.tvAction.setOnClickListener { onAction() }
        binding.tvClear.setOnClickListener {
            if (currentPage == 2) {
                copyPrompt()
            } else {
                confirmClear()
            }
        }
        binding.btnGenerateImage.setOnClickListener { onGenerateImageClicked() }
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
        binding.etPrompt.addTextChangedListener { text ->
            if (!suppressPromptWatcher) {
                session.prompt = text?.toString().orEmpty()
            }
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
                    session.params[AI_CREATION_MODE_KEY] = group.key
                    buildVariableControls(group)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        variableGroups.firstOrNull()?.let { buildVariableControls(it) }
        if (requireArguments().getBoolean(ARG_JUMP_PREVIEW)) {
            showPage(3)
        } else {
            showPage(0)
        }
    }

    private fun onBack() {
        if (currentPage >= 3) {
            dismissAllowingStateLoss()
            return
        }
        if (currentPage > 0) {
            showPage(currentPage - 1)
        }
    }

    private fun onAction() {
        when (currentPage) {
            0 -> showPage(1)
            1 -> generatePrompt()
            2 -> generatePrompt()
        }
    }

    private fun isVideoMode(): Boolean {
        val mode = session.params[AI_CREATION_MODE_KEY].orEmpty()
        return mode == AiCreationVariables.GROUP_VIDEO
    }

    private fun showPage(page: Int) {
        currentPage = page
        binding.llModePage.visibility = if (page == 0) View.VISIBLE else View.GONE
        binding.svComposePage.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.llPromptPage.visibility = if (page == 2) View.VISIBLE else View.GONE
        binding.llPreviewPage.visibility = if (page == 3) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (page == 3) View.GONE else View.VISIBLE
        binding.ivBack.visibility = if (page > 0) View.VISIBLE else View.GONE
        binding.tvTitle.setText(
            when (page) {
                1 -> R.string.ai_creation_compose
                2 -> R.string.ai_creation_prompt_title
                3 -> R.string.ai_creation_preview_title
                else -> R.string.ai_creation
            }
        )
        val isVideo = isVideoMode()
        binding.etImageCount.visibility =
            if (page == 2 && !isVideo) View.VISIBLE else View.GONE
        binding.btnGenerateImage.visibility =
            if (page == 2 && !isVideo) View.VISIBLE else View.GONE
        binding.tvClear.setText(
            if (page == 2) R.string.ai_creation_copy_prompt else R.string.ai_creation_clear
        )
        binding.tvClear.visibility = when {
            page == 1 -> View.VISIBLE
            page == 2 && !isVideo -> View.VISIBLE
            else -> View.GONE
        }
        binding.tvAction.setText(
            when (page) {
                1 -> R.string.ai_creation_generate_prompt
                2 -> R.string.ai_creation_generate_prompt
                else -> R.string.ai_creation_next
            }
        )
        if (page == 1) {
            rebuildSections()
        }
        if (page == 2 && session.prompt.isNotBlank()) {
            suppressPromptWatcher = true
            binding.etPrompt.setText(session.prompt)
            suppressPromptWatcher = false
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
    }

    private fun currentParamValue(variable: AiCreationVariable): String {
        return session.params[variable.key] ?: variable.defaultValue
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
        variable.options.forEach { option ->
            line.addView(
                chipView(option, option == current).apply {
                    setOnClickListener {
                        session.params[variable.key] = option
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
        val next = if (current == "关") "开" else "关"
        binding.llVariables.addView(
            chipView(current, true).apply {
                setOnClickListener {
                    session.params[variable.key] = next
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
                session.params[variable.key] =
                    editBinding.editView.text?.toString()?.trim().orEmpty()
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
                layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
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

    private fun hintView(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#80808080"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
    }

    private fun cardTile(item: CreationSectionItem): View {
        val pending = session.pendingLink
        val isPendingTarget = pending != null &&
            pending.cardId == item.cardId && pending.section == item.section
        val linked = session.isLinked(item.section, item.cardId)
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
                when {
                    isPendingTarget -> setStroke(dp(2), context.accentColor)
                    linked -> setStroke(dp(2), Color.parseColor("#66808080"))
                    else -> setStroke(dp(1), Color.parseColor("#33808080"))
                }
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
        if (linked || isPendingTarget) {
            tile.addView(
                TextView(requireContext()).apply {
                    text = getString(
                        if (isPendingTarget) {
                            R.string.ai_creation_linking
                        } else {
                            R.string.ai_creation_linked
                        }
                    )
                    textSize = 9f
                    setTextColor(context.accentColor)
                }
            )
        }
        tile.setOnClickListener { onTileClick(item) }
        tile.setOnLongClickListener {
            showTileMenu(item)
            true
        }
        return tile
    }

    private fun onTileClick(item: CreationSectionItem) {
        val pending = session.pendingLink
        if (pending != null && (pending.cardId != item.cardId || pending.section != item.section)) {
            if (pending.section == item.section) {
                toastOnUi(R.string.ai_creation_link_same_section)
                session.pendingLink = null
            } else {
                val linked = session.toggleLink(pending, item)
                toastOnUi(
                    getString(
                        if (linked) R.string.ai_creation_link_done else R.string.ai_creation_link_removed
                    )
                )
            }
            rebuildSections()
            return
        }
        session.pendingLink = null
        openCardEditor(item.cardId)
    }

    private fun showTileMenu(item: CreationSectionItem) {
        val linked = session.isLinked(item.section, item.cardId)
        val actions = listOf(
            getString(R.string.edit),
            getString(if (linked) R.string.ai_creation_unlink else R.string.ai_creation_link),
            getString(R.string.ai_creation_remove)
        )
        requireContext().selector(
            AiCreationSessionHolder.session.sectionLabel(item.section),
            actions
        ) { _, _, action ->
            when (action) {
                0 -> openCardEditor(item.cardId)
                1 -> {
                    if (linked) {
                        session.linkGroups.removeAll { group ->
                            group.refs.contains(item)
                        }
                        rebuildSections()
                    } else {
                        session.pendingLink = item
                        toastOnUi(R.string.ai_creation_linking_hint)
                        rebuildSections()
                    }
                }
                2 -> {
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
                if (pendingImageAfterPrompt) {
                    pendingImageAfterPrompt = false
                    startImageGeneration(prompt)
                } else {
                    showPage(2)
                }
            }.onFailure { throwable ->
                pendingImageAfterPrompt = false
                binding.tvAction.setText(R.string.ai_creation_generate_prompt)
                toastOnUi(throwable.message ?: throwable.javaClass.simpleName)
            }
        }
    }

    private fun onGenerateImageClicked() {
        val prompt = binding.etPrompt.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            pendingImageAfterPrompt = true
            generatePrompt()
            return
        }
        startImageGeneration(prompt)
    }

    private fun startImageGeneration(prompt: String) {
        if (AiCreationImageTaskHolder.running) {
            toastOnUi(R.string.ai_creation_task_running)
            return
        }
        val count = binding.etImageCount.text?.toString()?.toIntOrNull()
            ?.coerceIn(1, 10) ?: 1
        val cardIds = AiCreationConfig.sectionOrder
            .flatMap { session.itemsOf(it) }
            .map { it.cardId }
            .distinct()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val definition = AiCreationConfig.definition
                val cardsById = withContext(IO) {
                    cardIds.mapNotNull { appDb.creationCardDao.getById(it) }
                        .associateBy { it.cardId }
                }
                val values = withContext(IO) {
                    AiCreationHelper.buildValues(session, cardsById, definition.variables)
                }
                AiCreationConfig.requireImageApiReady()
                AiCreationImageTaskHolder.start(prompt, count, values)
            }
            result.onSuccess { started ->
                if (started) {
                    showPage(3)
                }
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
        binding.tvGridTwo.setTextColor(if (previewPageSize == 2) accentColor else primaryTextColor)
        binding.tvGridFour.setTextColor(if (previewPageSize == 4) accentColor else primaryTextColor)
    }

    private fun previewItemHeight(): Int {
        val available = binding.rvPreview.height.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels * 2 / 3)
        return available / 2 - dp(8)
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
                    ivPhoto.visible()
                    ImageLoader.load(itemView.context, AiCreationImageFile.fileOf(slot.fileName))
                        .dontTransform()
                        .into(ivPhoto)
                    ivPhoto.setOnClickListener {
                        val doneFiles = slots
                            .filter { it.state == AiCreationImageSlotState.DONE }
                            .map { it.fileName }
                        val position = doneFiles.indexOf(slot.fileName)
                        AiCreationPhotoDialog.newInstance(doneFiles, position)
                            .show(childFragmentManager, "creationPhoto")
                    }
                    ivPhoto.setOnLongClickListener {
                        val ok = AiCreationImageFile.saveToAlbum(requireContext(), slot.fileName)
                        toastOnUi(
                            if (ok) R.string.illustration_saved_to_album
                            else R.string.illustration_save_failed
                        )
                        true
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

    private fun confirmClear() {
        alert(R.string.ai_creation_clear) {
            setMessage(R.string.ai_creation_clear_confirm)
            okButton {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(IO) {
                        appDb.creationCardDao.deleteByBookName(AI_CREATION_EPHEMERAL_BOOK)
                    }
                    session.clear()
                    session.params[AI_CREATION_MODE_KEY] =
                        variableGroups.firstOrNull()?.key.orEmpty()
                    variableGroups.firstOrNull()?.let { buildVariableControls(it) }
                    binding.tabLayout.getTabAt(0)?.select()
                    showPage(0)
                }
            }
            cancelButton()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
