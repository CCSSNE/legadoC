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
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
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
 * 只是在书里选一段正文点问 AI 时，把该段正文预填进输入框等待用户补充问题后手动发送；
 * 不做任何按书隔离，历史、新对话、模型、工具卡等全部与大界面一致。
 * 本体只是悬浮外壳（顶栏拖动、四边四角拖拽调大小、关闭、全屏放大），消息渲染与请求都走 [AiChatViewModel]。
 */
class ReadAiFloatingPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val DIR_LEFT = 1
        private const val DIR_RIGHT = 1 shl 1
        private const val DIR_TOP = 1 shl 2
        private const val DIR_BOTTOM = 1 shl 3
    }

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

    private val binding = ViewReadAiFloatingPanelBinding.inflate(LayoutInflater.from(context), this)
    private val messageAdapter = AiChatAdapter(context)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private var lifecycleOwner: LifecycleOwner? = null
    private var viewModel: AiChatViewModel? = null
    private var readContext: ReadContext? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private val minPanelWidth = 200.dpToPx()
    private val minPanelHeight = 200.dpToPx()
    private var resizeDir = 0
    private var resizeDownRawX = 0f
    private var resizeDownRawY = 0f
    private var resizeStartX = 0f
    private var resizeStartY = 0f
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0

    init {
        applyUiBodyTypefaceDeep(context.uiTypeface())
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
        setupResizeHandles()
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
        // 选中正文只预填进输入框，等用户补充问题后手动发送，不直接发出去。
        if (readContext.selectedText.isNotBlank()) {
            binding.etQuestion.setText(readContext.selectedText)
            binding.etQuestion.setSelection(binding.etQuestion.text?.length ?: 0)
        }
        updateSendButtonState()
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

    private fun setupResizeHandles() {
        binding.handleTop.setOnTouchListener { _, event -> handleResize(event, DIR_TOP) }
        binding.handleBottom.setOnTouchListener { _, event -> handleResize(event, DIR_BOTTOM) }
        binding.handleLeft.setOnTouchListener { _, event -> handleResize(event, DIR_LEFT) }
        binding.handleRight.setOnTouchListener { _, event -> handleResize(event, DIR_RIGHT) }
        binding.handleTopLeft.setOnTouchListener { _, event -> handleResize(event, DIR_TOP or DIR_LEFT) }
        binding.handleTopRight.setOnTouchListener { _, event -> handleResize(event, DIR_TOP or DIR_RIGHT) }
        binding.handleBottomLeft.setOnTouchListener { _, event -> handleResize(event, DIR_BOTTOM or DIR_LEFT) }
        binding.handleBottomRight.setOnTouchListener { _, event -> handleResize(event, DIR_BOTTOM or DIR_RIGHT) }
    }

    /**
     * 按住四边或四角拖拽调整面板大小：边只改一个方向，角同时改宽高；
     * 上边/左边的缩放固定对边不动、整体跟随移动。
     */
    private fun handleResize(event: MotionEvent, dir: Int): Boolean {
        val parentView = parent as? ViewGroup ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeDir = dir
                resizeDownRawX = event.rawX
                resizeDownRawY = event.rawY
                resizeStartX = x
                resizeStartY = y
                resizeStartWidth = width
                resizeStartHeight = height
                parentView.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizeDir != dir || resizeStartWidth <= 0 || resizeStartHeight <= 0) return true
                val dx = event.rawX - resizeDownRawX
                val dy = event.rawY - resizeDownRawY
                var newWidth = resizeStartWidth
                var newHeight = resizeStartHeight
                var newX = resizeStartX
                var newY = resizeStartY
                val rightEdge = resizeStartX + resizeStartWidth
                val bottomEdge = resizeStartY + resizeStartHeight
                if (dir and DIR_LEFT != 0) {
                    newWidth = (resizeStartWidth - dx).toInt()
                        .coerceIn(minPanelWidth, rightEdge.toInt().coerceAtLeast(minPanelWidth))
                    newX = rightEdge - newWidth
                } else if (dir and DIR_RIGHT != 0) {
                    newWidth = (resizeStartWidth + dx).toInt()
                        .coerceIn(
                            minPanelWidth,
                            (parentView.width - resizeStartX).toInt().coerceAtLeast(minPanelWidth)
                        )
                }
                if (dir and DIR_TOP != 0) {
                    newHeight = (resizeStartHeight - dy).toInt()
                        .coerceIn(minPanelHeight, bottomEdge.toInt().coerceAtLeast(minPanelHeight))
                    newY = bottomEdge - newHeight
                } else if (dir and DIR_BOTTOM != 0) {
                    newHeight = (resizeStartHeight + dy).toInt()
                        .coerceIn(
                            minPanelHeight,
                            (parentView.height - resizeStartY).toInt().coerceAtLeast(minPanelHeight)
                        )
                }
                applyPanelSize(newWidth, newHeight, dir)
                x = newX
                y = newY
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

    private fun applyPanelSize(newWidth: Int, newHeight: Int, dir: Int) {
        val lp = layoutParams ?: return
        if (dir and (DIR_LEFT or DIR_RIGHT) != 0) {
            lp.width = newWidth
        }
        if (dir and (DIR_TOP or DIR_BOTTOM) != 0) {
            lp.height = newHeight
            switchToFlexHeight()
        }
        layoutParams = lp
    }

    /**
     * 纵向第一次被用户手动缩放时才切换：内容层改为撑满面板固定高度，
     * 中间消息列表改成 weight 填充剩余空间，后续纵向缩放由列表吃掉高度差。
     */
    private fun switchToFlexHeight() {
        binding.panelContent.layoutParams?.let { lp ->
            if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        (binding.answerContainer.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.height != 0 || lp.weight != 1f) {
                lp.height = 0
                lp.weight = 1f
            }
        }
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
        binding.handleBottomRight.setColorFilter(context.secondaryTextColor)
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
