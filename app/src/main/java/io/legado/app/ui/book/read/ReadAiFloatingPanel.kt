package io.legado.app.ui.book.read

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.databinding.ViewReadAiFloatingPanelBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.main.ai.AiChatActivity
import io.legado.app.ui.main.ai.AiChatAdapter
import io.legado.app.ui.main.ai.AiChatSession
import io.legado.app.ui.main.ai.AiChatViewModel
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.PopupMenuAction
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showPopupMenu
import io.legado.app.utils.toastOnUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读页问 AI 悬浮窗：与应用外大界面（[AiChatActivity]）完全同一套会话。
 * 只是在书里选一段正文点问 AI 时，把该段正文作为提示词注入进当前会话；
 * 不做任何按书隔离，历史、新对话、模型、工具卡等全部与大界面一致。
 * 本体只是悬浮外壳（拖动、关闭、全屏放大），消息渲染与请求都走 [AiChatViewModel]。
 */
class ReadAiFloatingPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    data class ReadContext(
        val bookUrl: String,
        val bookName: String,
        val author: String,
        val sourceName: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val selectedText: String,
        val snapshot: org.json.JSONObject = io.legado.app.help.agent.mcp.AgentReading.current()
    )

    data class Anchor(
        val centerX: Int,
        val topY: Int,
        val bottomY: Int
    )

    private val binding = ViewReadAiFloatingPanelBinding.inflate(LayoutInflater.from(context), this, true)
    private val messageAdapter = AiChatAdapter(context)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private var lifecycleOwner: LifecycleOwner? = null
    private var viewModel: AiChatViewModel? = null
    private var readContext: ReadContext? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f

    init {
        orientation = VERTICAL
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        binding.answerContainer.layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        binding.answerContainer.adapter = messageAdapter
        binding.btnClose.setOnClickListener { close() }
        binding.tvModel.setOnClickListener { showModelSelectorDialog() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        binding.btnFullscreen.setOnClickListener { openFullscreen() }
        binding.btnSend.setOnClickListener {
            val vm = viewModel ?: return@setOnClickListener
            if (vm.isRequesting) {
                vm.stopRequest(context.getString(R.string.ai_chat_cancelled))
            } else {
                askFromInput()
            }
        }
        binding.etQuestion.doAfterTextChanged { updateSendButtonState() }
        binding.etQuestion.setOnEditorActionListener { _, actionId, event ->
            val isSendAction = actionId == EditorInfo.IME_ACTION_SEND
            val isEnterKey = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                && event.action == android.view.KeyEvent.ACTION_DOWN
            if (AppConfig.aiEnterToSend && (isSendAction || isEnterKey)) {
                askFromInput()
                true
            } else {
                false
            }
        }
        binding.dragHandle.setOnTouchListener { _, event -> handleDrag(event) }
        applyTheme()
    }

    fun attach(lifecycleOwner: LifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner
        val storeOwner = (lifecycleOwner as? ViewModelStoreOwner)
            ?: (context as? ViewModelStoreOwner)
            ?: return
        val vm = ViewModelProvider(storeOwner)[AiChatViewModel::class.java]
        viewModel = vm
        vm.messagesLiveData.observe(lifecycleOwner) { messages ->
            messageAdapter.submitList(messages)
            val hasMessages = messages.isNotEmpty()
            binding.answerContainer.isVisible = hasMessages
            binding.emptyContainer.isVisible = !hasMessages
            if (hasMessages) {
                binding.answerContainer.post {
                    binding.answerContainer.scrollToPosition(messages.lastIndex)
                }
            }
        }
        vm.requestingLiveData.observe(lifecycleOwner) {
            updateSendButtonState()
        }
        // 从全屏大界面返回时刷新同一套会话，避免悬浮窗停留在旧快照。
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (isVisible) viewModel?.syncFromStore()
            }
        })
        updateSendButtonState()
    }

    override fun onDetachedFromWindow() {
        // View 脱离窗口时移除生命周期监听由 LifecycleOwner 自动管理，此处仅断开引用。
        lifecycleOwner = null
        super.onDetachedFromWindow()
    }

    fun open(readContext: ReadContext, anchor: Anchor? = null) {
        this.readContext = readContext
        if (viewModel == null) {
            lifecycleOwner?.let { attach(it) }
        }
        viewModel?.syncFromStore()
        updateHeader()
        updateSendButtonState()
        binding.tvContext.text = buildContextLabel(readContext)
        binding.etQuestion.setText("")
        animate().cancel()
        translationY = 0f
        if (visibility != VISIBLE) {
            alpha = 0f
            visibility = VISIBLE
        } else {
            visibility = VISIBLE
        }
        bringToFront()
        doOnLayout {
            if (anchor != null) {
                placeNearAnchor(anchor)
            }
            ensureInsideParent()
            if (alpha < 1f) {
                animate()
                    .alpha(1f)
                    .setDuration(160L)
                    .start()
            }
        }
        // 选中正文只作为本次提示词注入，不建隔离会话。
        if (readContext.selectedText.isNotBlank()) {
            ask(readContext.selectedText)
        }
    }

    fun close() {
        updateSendButtonState()
        visibility = GONE
    }

    private fun updateHeader() {
        val model = AppConfig.aiCurrentModelConfig
        binding.tvModel.text = model?.modelId ?: context.getString(R.string.ai_current_model_summary_empty)
        binding.tvModel.alpha = if (model == null) 0.72f else 1f
    }

    private fun showMoreMenu() {
        binding.btnMore.showPopupMenu(
            listOf(
                PopupMenuAction(context.getString(R.string.ai_new_chat)) {
                    startNewChatFromMenu()
                },
                PopupMenuAction(context.getString(R.string.ai_chat_history)) {
                    openHistoryFromMenu()
                },
                PopupMenuAction(context.getString(R.string.ai_setting)) {
                    openAiSettings()
                }
            )
        )
    }

    private fun startNewChatFromMenu() {
        val vm = viewModel ?: return
        if (vm.isRequesting) {
            context.toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        vm.startNewSession()
        updateHeader()
    }

    private fun openHistoryFromMenu() {
        if (viewModel?.isRequesting == true) {
            context.toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        showHistoryDialog()
    }

    private fun openAiSettings() {
        Intent(context, ConfigActivity::class.java).apply {
            putExtra("configTag", ConfigTag.AI_CONFIG)
        }.also(context::startActivity)
    }

    private fun openFullscreen() {
        context.startActivity(Intent(context, AiChatActivity::class.java))
    }

    private fun askFromInput() {
        val question = binding.etQuestion.text?.toString().orEmpty().trim()
        if (question.isBlank() || viewModel?.isRequesting == true) return
        binding.etQuestion.text?.clear()
        ask(question)
    }

    private fun ask(question: String) {
        val vm = viewModel ?: return
        if (vm.isRequesting) return
        if (AppConfig.aiCurrentProvider?.baseUrl.isNullOrBlank() || AppConfig.aiCurrentModelConfig == null) {
            context.toastOnUi(R.string.ai_missing_config)
            return
        }
        if (!AppConfig.aiAssistantEnabled) {
            context.toastOnUi(R.string.ai_not_enabled)
            return
        }
        vm.startRequest(
            userContent = question,
            thinkingText = resources.getString(R.string.ai_chat_thinking),
            cancelledText = resources.getString(R.string.ai_chat_cancelled),
            failureMessage = { resources.getString(R.string.ai_request_failed, it) },
            readingContext = buildReadingSnapshot(readContext, question)
        )
        updateSendButtonState()
    }

    /** 把当前书籍章节与选中文本显式快照进阅读上下文，选区消失后仍可追溯。 */
    private fun buildReadingSnapshot(context: ReadContext?, question: String): org.json.JSONObject {
        val base = try {
            org.json.JSONObject(context?.snapshot?.toString() ?: "{}")
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        if (context == null) return base
        base.put("open", true)
        base.put("bookUrl", context.bookUrl)
        base.put("bookName", context.bookName)
        base.put("chapterIndex", context.chapterIndex)
        base.put("chapterTitle", context.chapterTitle)
        val selected = context.selectedText.ifBlank { question }
        if (selected.isNotBlank()) base.put("selectedText", selected)
        return base
    }

    private fun showHistoryDialog() {
        val vm = viewModel ?: return
        val sessions = vm.historySessions()
        if (sessions.isEmpty()) {
            context.toastOnUi(R.string.ai_history_empty)
            return
        }
        val items = mutableListOf(context.getString(R.string.ai_history_clear_all))
        items += sessions.map { session ->
            "${session.title}\n${timeFormat.format(Date(session.updatedAt))}"
        }
        context.selector(
            context.getString(R.string.ai_chat_history),
            items
        ) { _, _, index ->
            if (index == 0) {
                confirmClearAllHistory(vm)
            } else {
                showHistorySessionActions(vm, sessions[index - 1])
            }
        }
    }

    private fun showHistorySessionActions(vm: AiChatViewModel, session: AiChatSession) {
        context.selector(
            session.title,
            listOf(
                context.getString(R.string.ai_history_open),
                context.getString(R.string.ai_history_delete)
            )
        ) { _, _, index ->
            when (index) {
                0 -> {
                    vm.loadSession(session.id)
                    updateHeader()
                }
                1 -> confirmDeleteHistorySession(vm, session)
            }
        }
    }

    private fun confirmDeleteHistorySession(vm: AiChatViewModel, session: AiChatSession) {
        context.alert(
            title = context.getString(R.string.ai_history_delete),
            message = context.getString(R.string.ai_history_delete_confirm, session.title)
        ) {
            okButton {
                vm.deleteSession(session.id)
                updateHeader()
            }
            cancelButton()
        }
    }

    private fun confirmClearAllHistory(vm: AiChatViewModel) {
        context.alert(
            title = context.getString(R.string.ai_history_clear_all),
            message = context.getString(R.string.ai_history_clear_all_confirm)
        ) {
            okButton {
                vm.clearAllSessions()
                updateHeader()
            }
            cancelButton()
        }
    }

    private fun showModelSelectorDialog() {
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            context.toastOnUi(R.string.ai_no_models)
            return
        }
        val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
        context.selector(
            context.getString(R.string.ai_current_model),
            models.map { model ->
                providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                    ?.let { "${model.modelId} · $it" }
                    ?: model.modelId
            }
        ) { _, _, index ->
            AppConfig.aiCurrentModelId = models[index].id
            updateHeader()
        }
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        val parentView = parent as? ViewGroup ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                parentView.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val targetX = startX + event.rawX - downRawX
                val targetY = startY + event.rawY - downRawY
                x = targetX.coerceIn(0f, max(0, parentView.width - width).toFloat())
                y = targetY.coerceIn(0f, max(0, parentView.height - height).toFloat())
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ensureInsideParent()
                parentView.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun ensureInsideParent() {
        val parentView = parent as? ViewGroup ?: return
        if (width <= 0 || height <= 0 || parentView.width <= 0 || parentView.height <= 0) return
        x = min(max(0f, x), max(0, parentView.width - width).toFloat())
        y = min(max(0f, y), max(0, parentView.height - height).toFloat())
    }

    private fun placeNearAnchor(anchor: Anchor) {
        val parentView = parent as? ViewGroup ?: return
        if (width <= 0 || height <= 0 || parentView.width <= 0 || parentView.height <= 0) return
        val margin = 10.dpToPx()
        val preferredX = anchor.centerX - width / 2
        val maxX = (parentView.width - width - margin).coerceAtLeast(margin)
        x = preferredX.toFloat().coerceIn(margin.toFloat(), maxX.toFloat())
        val spaceAbove = anchor.topY - margin
        val spaceBelow = parentView.height - anchor.bottomY - margin
        y = if (spaceBelow >= height || spaceBelow >= spaceAbove) {
            (anchor.bottomY + margin).toFloat()
                .coerceAtMost((parentView.height - height - margin).toFloat())
        } else {
            (anchor.topY - height - margin).toFloat()
                .coerceAtLeast(margin.toFloat())
        }
    }

    private fun applyTheme() {
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(context.accentColor)
        binding.btnSend.setColorFilter(Color.WHITE)
        binding.btnClose.imageTintList = ColorStateList.valueOf(context.secondaryTextColor)
        binding.tvModel.setTextColor(context.primaryTextColor)
        binding.btnMore.imageTintList = ColorStateList.valueOf(context.secondaryTextColor)
        binding.btnFullscreen.imageTintList = ColorStateList.valueOf(context.secondaryTextColor)
        binding.tvAiEmpty.setTextColor(context.secondaryTextColor)
        binding.ivAiEmptyIcon.setColorFilter(context.secondaryTextColor)
        binding.inputContainer.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.adjustAlpha(context.primaryTextColor, 0.06f))
        updateSendButtonState()
    }

    private fun updateSendButtonState() {
        val hasInput = binding.etQuestion.text?.isNotBlank() == true
        binding.etQuestion.isEnabled = true
        binding.btnSend.isEnabled = viewModel?.isRequesting == true || hasInput
        binding.btnSend.alpha = if (binding.btnSend.isEnabled) 1f else 0.48f
        val requesting = viewModel?.isRequesting == true
        binding.btnSend.contentDescription = resources.getString(
            if (requesting) R.string.ai_chat_stop else R.string.ai_chat_send
        )
        binding.btnSend.setImageResource(
            if (requesting) R.drawable.ic_stop_black_24dp else R.drawable.ic_arrow_right
        )
    }

    private fun buildContextLabel(context: ReadContext): String {
        return buildString {
            append(context.bookName.ifBlank { resources.getString(R.string.book_name) })
            if (context.chapterTitle.isNotBlank()) append(" · ").append(context.chapterTitle)
        }
    }
}
