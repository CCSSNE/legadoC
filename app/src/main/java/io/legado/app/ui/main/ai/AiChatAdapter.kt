package io.legado.app.ui.main.ai

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.text.Spannable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.databinding.ItemAiMessageAssistantBinding
import io.legado.app.databinding.ItemAiMessageUserBinding
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.json.JSONObject

class AiChatAdapter(
    private val context: Context
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<AiChatMessage>()
    private val expandedIds = mutableSetOf<String>()
    private val markwon: Markwon by lazy {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
    }

    fun submitList(list: List<AiChatMessage>) {
        items.clear()
        items.addAll(list.filter { message ->
            when (message.kind ?: AiChatMessage.Kind.TEXT) {
                AiChatMessage.Kind.TEXT,
                AiChatMessage.Kind.THINKING,
                AiChatMessage.Kind.TOOLS,
                AiChatMessage.Kind.CONTEXT,
                AiChatMessage.Kind.STATS,
                AiChatMessage.Kind.TOTAL -> true
                AiChatMessage.Kind.STATUS ->
                    io.legado.app.help.config.AppConfig.aiShowToolSummary || !message.statusSuccess ||
                        message.statusStage in setOf("paused", "waiting_input")
            }
        })
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].role) {
            AiChatMessage.Role.USER -> TYPE_USER
            AiChatMessage.Role.ASSISTANT -> TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserViewHolder(
                ItemAiMessageUserBinding.inflate(inflater, parent, false)
            )

            else -> AssistantViewHolder(
                ItemAiMessageAssistantBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is UserViewHolder -> holder.bind(item)
            is AssistantViewHolder -> holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is AssistantViewHolder) holder.cancelSweep()
        super.onViewRecycled(holder)
    }

    private fun createBubble(
        fillColor: Int,
        strokeColor: Int,
        isUser: Boolean
    ): GradientDrawable {
        val large = 22f.dpToPx()
        val small = 8f.dpToPx()
        return GradientDrawable().apply {
            cornerRadii = if (isUser) {
                floatArrayOf(
                    large, large,
                    large, large,
                    small, small,
                    large, large
                )
            } else {
                floatArrayOf(
                    large, large,
                    large, large,
                    large, large,
                    small, small
                )
            }
            setColor(fillColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }

    private fun installSearchBookLinks(textView: TextView) {
        val spannable = textView.text as? Spannable ?: return
        val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        spans.forEach { span ->
            val url = span.url
            if (!url.startsWith(searchBookScheme)) return@forEach
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            spannable.removeSpan(span)
            spannable.setSpan(SearchBookSpan(url), start, end, flags)
        }
    }

    private fun parseMessageContent(content: String): ParsedMessage {
        val cards = mutableListOf<SearchBookCard>()
        val toolEvents = linkedMapOf<String, ToolEventCard>()
        val withoutToolEvents = toolEventBlockRegex.replace(content) { match ->
            runCatching {
                val payload = JSONObject(match.groupValues[1])
                val events = payload.optJSONArray("events") ?: return@runCatching
                for (index in 0 until events.length()) {
                    val item = events.optJSONObject(index) ?: continue
                    val stage = item.optString("stage")
                    val name = item.optString("name").ifBlank { "工具" }
                    if (stage != "result") continue
                    toolEvents[name] = ToolEventCard(
                        name = name,
                        stage = "done",
                        content = item.optString("content"),
                        success = item.optBoolean("success", true),
                        label = ""
                    )
                }
            }
            ""
        }
        val visibleContent = searchResultBlockRegex.replace(withoutToolEvents) { match ->
            runCatching {
                val payload = JSONObject(match.groupValues[1])
                val results = payload.optJSONArray("results") ?: return@runCatching
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val bookUrl = item.optString("bookUrl")
                    val origin = item.optString("origin")
                    if (bookUrl.isBlank() || origin.isBlank()) continue
                    cards += SearchBookCard(
                        name = item.optString("name").ifBlank { "未命名" },
                        author = item.optString("author"),
                        originName = item.optString("originName"),
                        kind = item.optString("kind"),
                        intro = item.optString("intro"),
                        latestChapterTitle = item.optString("latestChapterTitle"),
                        coverUrl = item.optString("coverUrl"),
                        bookUrl = bookUrl,
                        origin = origin,
                        target = item.optString("target")
                    )
                }
            }
            ""
        }.trim()
        return ParsedMessage(
            visibleContent,
            cards.distinctBy { it.bookUrl },
            toolEvents.values.toList()
        )
    }

    /** DSH 同口径：跑时摘要取最后一行（跟尾），结束后取第一行。 */
    private fun firstLine(text: String): String {
        val index = text.indexOf('\n')
        return if (index < 0) text else text.substring(0, index)
    }

    private fun latestLine(text: String): String {
        val visible = text.trimEnd()
        val index = visible.lastIndexOf('\n')
        return if (index < 0) visible else visible.substring(index + 1)
    }

    private fun bindSearchCards(binding: ItemAiMessageAssistantBinding, cards: List<SearchBookCard>) {
        val container = binding.searchCards
        container.removeAllViews()
        binding.searchCardScroller.isVisible = cards.isNotEmpty()
        cards.forEach { card ->
            container.addView(createSearchCardView(card))
        }
    }

    private fun bindInfoCard(binding: ItemAiMessageAssistantBinding, message: AiChatMessage) {
        binding.tvMessage.isVisible = false
        binding.searchCardScroller.isVisible = false
        val container = binding.toolEventContainer
        container.removeAllViews()
        container.isVisible = true
        val lines = message.content.lines()
        val title = lines.getOrNull(0).orEmpty()
        val subtitle = lines.getOrNull(1).orEmpty()
        val detail = lines.drop(2).joinToString("\n").trim()
        val expanded = message.id in expandedIds
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                val fill = ColorUtils.blendColors(context.backgroundColor, context.accentColor, 0.05f)
                cornerRadius = UiCorner.scaledDp(16f)
                setColor(UiCorner.surfaceColor(fill))
                setStroke(1.dpToPx(), UiCorner.effectStrokeColor(fill))
            }
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6.dpToPx()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(16.dpToPx(), 16.dpToPx()).apply {
                marginEnd = 8.dpToPx()
            }
            setImageResource(R.drawable.ic_settings)
            setColorFilter(context.accentColor)
        })
        header.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = title
            setTextColor(context.primaryTextColor)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val arrow = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx())
            setImageResource(R.drawable.ic_arrow_drop_down)
            setColorFilter(context.secondaryTextColor)
            rotation = if (expanded) 180f else 0f
        }
        header.addView(arrow)
        row.addView(header)
        if (subtitle.isNotBlank()) row.addView(TextView(context).apply {
            text = subtitle
            setTextColor(context.secondaryTextColor)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 4.dpToPx(), 18.dpToPx(), 0)
        })
        if (detail.isNotBlank()) row.addView(TextView(context).apply {
            text = detail
            setTextColor(context.secondaryTextColor)
            textSize = 12f
            maxLines = Int.MAX_VALUE
            isVisible = expanded
            setPadding(0, 8.dpToPx(), 0, 0)
            setTextIsSelectable(true)
        })
        row.setOnClickListener {
            if (message.id in expandedIds) expandedIds.remove(message.id)
            else expandedIds.add(message.id)
            val position = items.indexOfFirst { it.id == message.id }
            if (position >= 0) notifyItemChanged(position)
        }
        container.addView(row)
    }

    /** 用量统计卡（单轮 STATS / 会话 TOTAL）：收起只显示首行摘要，展开显示完整明细。 */
    private fun bindUsageCard(binding: ItemAiMessageAssistantBinding, message: AiChatMessage) {
        binding.tvMessage.isVisible = false
        binding.searchCardScroller.isVisible = false
        val container = binding.toolEventContainer
        container.removeAllViews()
        container.isVisible = true
        val lines = message.content.lines()
        val title = lines.getOrElse(0) { "" }
        // 末尾的 steps=/tool= 元数据行只供总计卡汇总，界面不渲染。
        val detail = lines.drop(1).filterNot { it.startsWith("steps=") }.joinToString("\n")
        val expanded = message.id in expandedIds
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                val fill = ColorUtils.blendColors(context.backgroundColor, context.accentColor, 0.05f)
                cornerRadius = UiCorner.scaledDp(16f)
                setColor(UiCorner.surfaceColor(fill))
                setStroke(1.dpToPx(), UiCorner.effectStrokeColor(fill))
            }
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6.dpToPx()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = title
            setTextColor(context.secondaryTextColor)
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val arrow = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx())
            setImageResource(R.drawable.ic_arrow_drop_down)
            setColorFilter(context.secondaryTextColor)
            rotation = if (expanded) 180f else 0f
        }
        header.addView(arrow)
        row.addView(header)
        row.addView(TextView(context).apply {
            text = detail
            setTextColor(context.secondaryTextColor)
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            maxLines = Int.MAX_VALUE
            isVisible = expanded
            setPadding(0, 8.dpToPx(), 0, 0)
            setTextIsSelectable(true)
        })
        row.setOnClickListener {
            if (message.id in expandedIds) expandedIds.remove(message.id)
            else expandedIds.add(message.id)
            val position = items.indexOfFirst { it.id == message.id }
            if (position >= 0) notifyItemChanged(position)
        }
        container.addView(row)
    }

    private fun bindToolEvents(binding: ItemAiMessageAssistantBinding, events: List<ToolEventCard>) {
        val container = binding.toolEventContainer
        container.removeAllViews()
        val showSummary = AppConfig.aiShowToolSummary && events.isNotEmpty()
        container.isVisible = showSummary
        if (showSummary) {
            container.addView(createToolSummaryView(events))
        }
    }

    private fun createToolSummaryView(events: List<ToolEventCard>): View {
        val allSuccess = events.all { it.success }
        val summary = events.joinToString("、") { it.name.ifBlank { context.getString(R.string.ai_tool_default_name) } }
        val detail = events.joinToString("\n\n") { event ->
            buildString {
                append(event.name.ifBlank { context.getString(R.string.ai_tool_default_name) })
                append('\n')
                append(
                    event.content.trim().ifBlank {
                        context.getString(
                            if (event.success) R.string.ai_tool_status_done else R.string.ai_tool_status_failed
                        )
                    }
                )
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                val fill = ColorUtils.blendColors(context.backgroundColor, context.accentColor, 0.05f)
                cornerRadius = UiCorner.scaledDp(16f)
                setColor(UiCorner.surfaceColor(fill))
                setStroke(
                    1.dpToPx(),
                    if (UiCorner.effectMode() == "solid") {
                        ColorUtils.adjustAlpha(
                            if (allSuccess) context.accentColor else ContextCompat.getColor(context, R.color.md_red_500),
                            0.14f
                        )
                    } else {
                        UiCorner.effectStrokeColor(fill)
                    }
                )
            }
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6.dpToPx()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(16.dpToPx(), 16.dpToPx()).apply {
                marginEnd = 8.dpToPx()
            }
            setImageResource(R.drawable.ic_settings)
            setColorFilter(
                if (allSuccess) context.accentColor else ContextCompat.getColor(context, R.color.md_red_500)
            )
        })
        header.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = if (events.size == 1) {
                "${summary} · 工具结果"
            } else {
                "已调用 ${events.size} 个工具"
            }
            setTextColor(context.primaryTextColor)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val arrow = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx())
            setImageResource(R.drawable.ic_arrow_drop_down)
            setColorFilter(context.secondaryTextColor)
        }
        header.addView(arrow)
        val detailView = TextView(context).apply {
            text = detail
            setTextColor(context.secondaryTextColor)
            textSize = 12f
            maxLines = Int.MAX_VALUE
            isVisible = false
            setPadding(0, 8.dpToPx(), 0, 0)
            setTextIsSelectable(true)
        }
        row.addView(header)
        row.addView(TextView(context).apply {
            text = summary
            setTextColor(context.secondaryTextColor)
            textSize = 12.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 4.dpToPx(), 18.dpToPx(), 0)
        })
        row.addView(detailView)
        row.setOnClickListener {
            val expanded = !detailView.isVisible
            detailView.isVisible = expanded
            arrow.rotation = if (expanded) 180f else 0f
        }
        return row
    }

    private fun createSearchCardView(card: SearchBookCard): View {
        val cardPaddingH = 10.dpToPx()
        val cardPaddingV = 9.dpToPx()
        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPaddingH, cardPaddingV, cardPaddingH, cardPaddingV)
            background = GradientDrawable().apply {
                val fill = ColorUtils.blendColors(context.backgroundColor, context.accentColor, 0.08f)
                cornerRadius = UiCorner.scaledDp(12f)
                setColor(UiCorner.surfaceColor(fill))
                setStroke(
                    1.dpToPx(),
                    if (UiCorner.effectMode() == "solid") {
                        ColorUtils.adjustAlpha(context.accentColor, 0.18f)
                    } else {
                        UiCorner.effectStrokeColor(fill)
                    }
                )
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                SearchBookOpenHelper.open(
                    context,
                    SearchBook(
                        name = card.name,
                        author = card.author,
                        bookUrl = card.bookUrl,
                        origin = card.origin,
                        originName = card.originName
                    ),
                    card.target == "video"
                )
            }
        }
        cardView.addView(CoverImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                126.dpToPx()
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            load(
                path = card.coverUrl,
                name = card.name,
                author = card.author,
                loadOnlyWifi = false,
                sourceOrigin = card.origin,
                preferThumb = true
            )
        })
        cardView.addView(TextView(context).apply {
            text = card.name
            setTextColor(context.primaryTextColor)
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dpToPx()
            }
        })
        val meta = listOf(card.author, card.originName, card.kind)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        cardView.addView(TextView(context).apply {
            text = meta.ifBlank { if (card.target == "video") "视频结果" else "书籍结果" }
            setTextColor(context.secondaryTextColor)
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val desc = card.latestChapterTitle.ifBlank { card.intro }
        if (desc.isNotBlank()) {
            cardView.addView(TextView(context).apply {
                text = desc.replace(Regex("\\s+"), " ").trim()
                setTextColor(context.secondaryTextColor)
                textSize = 12.5f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        cardView.layoutParams = LinearLayout.LayoutParams(
            142.dpToPx(),
            244.dpToPx()
        ).apply {
            marginEnd = 10.dpToPx()
        }
        return cardView
    }

    private inner class SearchBookSpan(
        private val url: String
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            val uri = Uri.parse(url)
            val isVideo = uri.getQueryParameter("target") == "video"
            val book = SearchBook(
                name = uri.getQueryParameter("name").orEmpty(),
                author = uri.getQueryParameter("author").orEmpty(),
                bookUrl = uri.getQueryParameter("bookUrl").orEmpty(),
                origin = uri.getQueryParameter("origin").orEmpty(),
                originName = uri.getQueryParameter("originName").orEmpty()
            )
            if (book.bookUrl.isBlank() || book.origin.isBlank()) return
            SearchBookOpenHelper.open(context, book, isVideo)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.color = context.accentColor
            ds.isUnderlineText = false
        }
    }

    private inner class UserViewHolder(
        private val binding: ItemAiMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: AiChatMessage) {
            binding.tvMessage.text = message.content
            binding.tvMessage.background = createBubble(
                USER_BUBBLE_COLOR,
                USER_BUBBLE_STROKE_COLOR,
                isUser = true
            )
            binding.tvMessage.setTextColor(CHAT_BUBBLE_TEXT_COLOR)
            binding.tvMessage.alpha = 1f
            binding.tvMessage.setTextIsSelectable(true)
            binding.tvMessage.setOnLongClickListener(null)
        }
    }

    private inner class AssistantViewHolder(
        private val binding: ItemAiMessageAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var sweepAnimator: ValueAnimator? = null
        private var boundThinkingId: String? = null
        private var thinkingTitle: TextView? = null
        private var thinkingSummary: TextView? = null
        private var thinkingDetail: TextView? = null
        private var thinkingChevron: ImageView? = null
        private var thinkingSweep: View? = null

        fun cancelSweep() {
            sweepAnimator?.cancel()
            sweepAnimator = null
            boundThinkingId = null
            thinkingTitle = null
            thinkingSummary = null
            thinkingDetail = null
            thinkingChevron = null
            thinkingSweep = null
            binding.root.contentDescription = null
        }

        private fun setFullBleed(full: Boolean) {
            val params = binding.messageContainer.layoutParams
            val target = if (full) ViewGroup.LayoutParams.MATCH_PARENT
            else ViewGroup.LayoutParams.WRAP_CONTENT
            if (params.width != target) {
                params.width = target
                binding.messageContainer.layoutParams = params
            }
        }

        fun bind(message: AiChatMessage) {
            when (message.kind) {
                AiChatMessage.Kind.TOOLS, AiChatMessage.Kind.CONTEXT -> {
                    cancelSweep()
                    setFullBleed(false)
                    bindInfoCard(binding, message)
                    return
                }
                AiChatMessage.Kind.STATS, AiChatMessage.Kind.TOTAL -> {
                    cancelSweep()
                    setFullBleed(false)
                    bindUsageCard(binding, message)
                    return
                }
                AiChatMessage.Kind.THINKING -> {
                    bindThinking(message)
                    return
                }
                else -> Unit
            }
            cancelSweep()
            setFullBleed(false)
            val parsed = parseMessageContent(message.content)
            binding.messageContainer.minimumWidth = if (message.pending) 220.dpToPx() else 0
            binding.tvMessage.background = createBubble(
                ASSISTANT_BUBBLE_COLOR,
                ASSISTANT_BUBBLE_STROKE_COLOR,
                isUser = false
            )
            binding.tvMessage.setTextColor(CHAT_BUBBLE_TEXT_COLOR)
            binding.tvMessage.alpha = if (message.pending) 0.76f else 1f
            binding.tvMessage.setOnLongClickListener(null)
            if (message.pending) {
                binding.tvMessage.isVisible = true
                binding.tvMessage.setTextIsSelectable(false)
                binding.tvMessage.movementMethod = null
                binding.tvMessage.linksClickable = false
                binding.tvMessage.text = parsed.content.ifBlank { "..." }
            } else {
                binding.tvMessage.isVisible = parsed.content.isNotBlank()
                binding.tvMessage.setTextIsSelectable(true)
                binding.tvMessage.linksClickable = true
                markwon.setMarkdown(binding.tvMessage, parsed.content.ifBlank { " " })
                installSearchBookLinks(binding.tvMessage)
                binding.tvMessage.movementMethod = LinkMovementMethod.getInstance()
            }
            bindSearchCards(binding, parsed.searchCards)
            bindToolEvents(binding, parsed.toolEvents)
        }

        /**
         * 思考横条：贯穿整行的单层折叠行。跑时单行跟尾冒字 + 扫光，点整行展开全文；
         * 结束后转为“已思考”永久保留。同 id 流式更新只刷字不重建行，扫光不中断。
         */
        private fun bindThinking(message: AiChatMessage) {
            if (boundThinkingId == message.id && thinkingSummary != null) {
                refreshThinkingTexts(message)
                return
            }
            cancelSweep()
            setFullBleed(true)
            binding.tvMessage.isVisible = false
            binding.searchCardScroller.isVisible = false
            val container = binding.toolEventContainer
            container.removeAllViews()
            container.isVisible = true
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    val fill = ColorUtils.blendColors(context.backgroundColor, context.accentColor, 0.05f)
                    cornerRadius = UiCorner.scaledDp(16f)
                    setColor(UiCorner.surfaceColor(fill))
                    setStroke(1.dpToPx(), UiCorner.effectStrokeColor(fill))
                }
                setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 6.dpToPx()
                }
            }
            val headerFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val titleView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setTextColor(context.primaryTextColor)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
            }
            val summaryView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = 8.dpToPx()
                }
                setTextColor(context.secondaryTextColor)
                textSize = 12.5f
                maxLines = 1
            }
            val chevron = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx()).apply {
                    marginStart = 8.dpToPx()
                }
                setImageResource(R.drawable.ic_arrow_drop_down)
                setColorFilter(context.secondaryTextColor)
            }
            header.addView(titleView)
            header.addView(summaryView)
            header.addView(chevron)
            val sweep = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    120.dpToPx(), FrameLayout.LayoutParams.MATCH_PARENT
                )
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        Color.TRANSPARENT,
                        ColorUtils.adjustAlpha(Color.WHITE, 0.55f),
                        Color.TRANSPARENT
                    )
                )
                isClickable = false
                isFocusable = false
            }
            headerFrame.addView(header)
            headerFrame.addView(sweep)
            val detailView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8.dpToPx()
                }
                setTextColor(context.secondaryTextColor)
                textSize = 12.5f
                maxLines = Int.MAX_VALUE
                setTextIsSelectable(true)
            }
            row.addView(headerFrame)
            row.addView(detailView)
            row.setOnClickListener {
                if (message.id in expandedIds) expandedIds.remove(message.id)
                else expandedIds.add(message.id)
                val position = items.indexOfFirst { it.id == message.id }
                if (position >= 0) notifyItemChanged(position)
            }
            container.addView(row)
            boundThinkingId = message.id
            thinkingTitle = titleView
            thinkingSummary = summaryView
            thinkingDetail = detailView
            thinkingChevron = chevron
            thinkingSweep = sweep
            refreshThinkingTexts(message)
            if (message.pending) startSweep(sweep, headerFrame, message.id)
        }

        /** 只刷字与展开态：流式更新与点开展开都走这里，不重建行。 */
        private fun refreshThinkingTexts(message: AiChatMessage) {
            val full = message.content
            val running = message.pending
            val expanded = message.id in expandedIds
            thinkingTitle?.text = context.getString(
                if (running) R.string.ai_chat_think else R.string.ai_chat_thought_done
            )
            thinkingSummary?.apply {
                text = if (running) latestLine(full) else firstLine(full)
                // 跑时掐头留尾（跟尾冒字），结束后正常省略尾部。
                ellipsize = if (running) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END
            }
            thinkingDetail?.apply {
                text = full
                isVisible = expanded
            }
            thinkingChevron?.rotation = if (expanded) 180f else 0f
            thinkingSweep?.isVisible = running
            if (running) {
                if (sweepAnimator == null) {
                    val sweep = thinkingSweep
                    (sweep?.parent as? FrameLayout)?.let { startSweep(sweep, it, message.id) }
                }
            } else {
                sweepAnimator?.cancel()
                sweepAnimator = null
            }
            binding.root.contentDescription = context.getString(
                if (running) R.string.ai_chat_thinking_collapsed else R.string.ai_chat_thinking_done
            )
        }

        /** 扫光：渐变条 2.6s 横扫一行（DSH dsh-reasoning-row-sweep 同口径），字本身不动。 */
        private fun startSweep(sweep: View, host: FrameLayout, messageId: String) {
            sweepAnimator?.cancel()
            sweepAnimator = null
            host.post {
                if (boundThinkingId != messageId) return@post
                val width = host.width
                if (width <= 0) return@post
                sweep.translationX = -width.toFloat()
                sweepAnimator = ValueAnimator.ofFloat(-width.toFloat(), width.toFloat()).apply {
                    duration = 2600L
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { sweep.translationX = it.animatedValue as Float }
                    start()
                }
            }
        }
    }

    private data class ParsedMessage(
        val content: String,
        val searchCards: List<SearchBookCard>,
        val toolEvents: List<ToolEventCard>
    )

    private data class SearchBookCard(
        val name: String,
        val author: String,
        val originName: String,
        val kind: String,
        val intro: String,
        val latestChapterTitle: String,
        val coverUrl: String,
        val bookUrl: String,
        val origin: String,
        val target: String
    )

    private data class ToolEventCard(
        val name: String,
        val stage: String,
        val content: String,
        val success: Boolean,
        val label: String
    )

    private companion object {
        const val TYPE_USER = 1
        const val TYPE_ASSISTANT = 2
        const val searchBookScheme = "legado-search-book://"
        val USER_BUBBLE_COLOR: Int = Color.rgb(149, 236, 105)
        val USER_BUBBLE_STROKE_COLOR: Int = Color.rgb(124, 212, 82)
        val ASSISTANT_BUBBLE_COLOR: Int = Color.rgb(248, 248, 248)
        val ASSISTANT_BUBBLE_STROKE_COLOR: Int = Color.rgb(226, 226, 226)
        val CHAT_BUBBLE_TEXT_COLOR: Int = Color.rgb(32, 32, 32)
        val toolEventBlockRegex = Regex(
            "```legado-tool-events\\s*\\n([\\s\\S]*?)\\n```",
            setOf(RegexOption.MULTILINE)
        )
        val searchResultBlockRegex = Regex(
            "```legado-search-results\\s*\\n([\\s\\S]*?)\\n```",
            setOf(RegexOption.MULTILINE)
        )
    }
}
