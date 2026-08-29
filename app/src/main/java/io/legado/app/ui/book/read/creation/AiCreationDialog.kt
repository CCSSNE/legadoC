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
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogAiCreationBinding
import io.legado.app.help.ai.AiCreationConfig.AI_CREATION_EPHEMERAL_BOOK
import io.legado.app.help.ai.AiCreationConfig
import io.legado.app.help.ai.AiCreationHelper
import io.legado.app.help.ai.AiCreationSessionHolder
import io.legado.app.help.ai.AiCreationVariable
import io.legado.app.help.ai.AiCreationVariableGroup
import io.legado.app.help.ai.AiCreationVariables
import io.legado.app.help.ai.CreationSectionItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiCreationDialog : BaseDialogFragment(R.layout.dialog_ai_creation) {

    companion object {
        const val ARG_BOOK_NAME = "bookName"

        fun newInstance(bookName: String): AiCreationDialog {
            return AiCreationDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK_NAME, bookName)
                }
            }
        }
    }

    private val binding by viewBinding(DialogAiCreationBinding::bind)
    private val bookName: String by lazy { requireArguments().getString(ARG_BOOK_NAME).orEmpty() }
    private val session get() = AiCreationSessionHolder.session
    private var variables: List<AiCreationVariable> = emptyList()
    private var variableGroups: List<AiCreationVariableGroup> = emptyList()
    private var currentPage = 0
    private var generating = false
    private var suppressPromptWatcher = false

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
    }

    override fun onResume() {
        super.onResume()
        if (currentPage == 1) {
            rebuildSections()
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        session.bookName = bookName
        variables = try {
            AiCreationConfig.variables
        } catch (throwable: Throwable) {
            toastOnUi(throwable.message ?: throwable.javaClass.simpleName)
            AiCreationVariables.parse(AiCreationVariables.defaultJson)
        }
        variableGroups = variables.map { it.group }.distinct().mapNotNull { groupKey ->
            val groupVariables = variables.filter { it.group == groupKey }
            if (groupVariables.isEmpty()) {
                null
            } else {
                AiCreationVariableGroup(
                    key = groupKey,
                    label = groupKey,
                    variables = groupVariables
                )
            }
        }.map { it.copy(label = AiCreationVariables.groupLabelOf(it)) }
        binding.ivClose.setOnClickListener { dismissAllowingStateLoss() }
        binding.ivBack.setOnClickListener { onBack() }
        binding.tvAction.setOnClickListener { onAction() }
        binding.tvClear.setOnClickListener { confirmClear() }
        binding.etPrompt.addTextChangedListener { text ->
            if (!suppressPromptWatcher) {
                session.prompt = text?.toString().orEmpty()
            }
        }
        binding.tabLayout.setBackgroundColor(backgroundColor)
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
        variableGroups.forEach { group ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(group.label))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val index = binding.tabLayout.selectedTabPosition
                variableGroups.getOrNull(index)?.let { buildVariableControls(it) }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        variableGroups.firstOrNull()?.let { buildVariableControls(it) }
        showPage(0)
    }

    private fun onBack() {
        if (currentPage > 0) {
            showPage(currentPage - 1)
        }
    }

    private fun onAction() {
        when (currentPage) {
            0 -> showPage(1)
            1 -> generatePrompt()
            2 -> copyPrompt()
        }
    }

    private fun showPage(page: Int) {
        currentPage = page
        binding.llModePage.visibility = if (page == 0) View.VISIBLE else View.GONE
        binding.svComposePage.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.llPromptPage.visibility = if (page == 2) View.VISIBLE else View.GONE
        binding.ivBack.visibility = if (page > 0) View.VISIBLE else View.GONE
        binding.tvClear.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.tvTitle.setText(
            when (page) {
                1 -> R.string.ai_creation_compose
                2 -> R.string.ai_creation_prompt_title
                else -> R.string.ai_creation
            }
        )
        binding.tvAction.setText(
            when (page) {
                1 -> R.string.ai_creation_generate_prompt
                2 -> R.string.ai_creation_copy_prompt
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
            binding.tvAction.setText(R.string.ai_creation_copy_prompt)
            result.onSuccess { prompt ->
                session.prompt = prompt
                showPage(2)
            }.onFailure { throwable ->
                binding.tvAction.setText(
                    if (currentPage == 1) {
                        R.string.ai_creation_generate_prompt
                    } else {
                        R.string.ai_creation_copy_prompt
                    }
                )
                toastOnUi(throwable.message ?: throwable.javaClass.simpleName)
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
