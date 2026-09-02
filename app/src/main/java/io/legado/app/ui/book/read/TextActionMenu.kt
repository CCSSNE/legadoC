package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.RequiresApi
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ItemMenuEditBinding
import io.legado.app.databinding.ItemTextBinding
import io.legado.app.databinding.PopupActionMenuBinding
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.surface.SurfaceStyles
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.dpToPx
import io.legado.app.utils.SurfaceBackdrop
import io.legado.app.utils.findHostWindow
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi

@SuppressLint("RestrictedApi")
class TextActionMenu(private val context: Context, private val callBack: CallBack) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    private val binding = PopupActionMenuBinding.inflate(LayoutInflater.from(context))
    private val adapter = Adapter(context).apply {
        setHasStableIds(true)
    }
    private val allMenuItems: List<MenuItemImpl>
    private var blurGeneration = 0
    var illustrationEnabled: Boolean = false
        set(value) {
            // 每次赋值都重建菜单项，保证"配图"只按当前选区状态出现/隐藏，
            // 不依赖前后两次值是否变化，避免旧状态残留导致闪现
            field = value
            upMenu()
        }
    var reviewEnabled: Boolean = false
        set(value) {
            field = value
            upMenu()
        }

    private val configuredActionIds: Set<String>
        get() = context.getPrefStringSet(
            PreferKey.contentSelectActions,
            mutableSetOf("web_search", "replace", "copy", "bookmark", "paragraph_bookmark", "aloud", "ai_create", "stage")
        )?.filterNot { it == "generate_image" }?.toSet() ?: emptySet()

    private val defaultOpenActionId: String
        get() = context.getPrefString(PreferKey.contentSelectDefaultOpen, "").orEmpty()

    private val actionOrder: List<String>
        get() = context.getPrefString(PreferKey.contentSelectActionsOrder, "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun menuItemToActionId(itemId: Int): String? = when (itemId) {
        R.id.menu_replace -> "replace"
        R.id.menu_copy -> "copy"
        R.id.menu_web_search -> ContentSelectConfig.ACTION_WEB_SEARCH
        R.id.menu_bookmark -> "bookmark"
        R.id.menu_paragraph_bookmark -> "paragraph_bookmark"
        R.id.menu_aloud -> "aloud"
        R.id.menu_dict -> "dict"
        R.id.menu_ask_ai -> "ask_ai"
        R.id.menu_ai_create -> "ai_create"
        R.id.menu_stage -> "stage"
        else -> null
    }

    init {
        @SuppressLint("InflateParams")
        contentView = binding.root
        binding.root.applyUiBodyTypefaceDeep(context.uiTypeface())
        SurfaceBackdrop.installStatic(contentView, SurfaceStyles.popup(context))

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = 14f.dpToPx()
        }

        val myMenu = MenuBuilder(context)
        val otherMenu = MenuBuilder(context)
        SupportMenuInflater(context).inflate(R.menu.content_select_action, myMenu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            onInitializeMenu(otherMenu)
        }
        allMenuItems = myMenu.visibleItems + otherMenu.visibleItems
        adapter.addFooterView { parent ->
            ItemMenuEditBinding.inflate(adapter.inflater, parent, false).apply {
                root.setOnClickListener {
                    callBack.onMenuConfigRequested()
                }
            }
        }
        binding.recyclerView.adapter = adapter
        setOnDismissListener {
            blurGeneration++
            SurfaceBackdrop.cancel(contentView)
            contentView.alpha = 1f
        }
        upMenu()
    }

    private fun filteredMenuItems(): List<MenuItemImpl> {
        val filtered = allMenuItems.filter { item ->
            when (item.itemId) {
                R.id.menu_illustration -> illustrationEnabled
                R.id.menu_review -> reviewEnabled
                else -> menuItemToActionId(item.itemId)?.let { configuredActionIds.contains(it) } ?: false
            }
        }
        val order = actionOrder
        if (order.isEmpty()) return filtered
        return filtered.sortedBy { item ->
            menuItemToActionId(item.itemId)
                ?.let { actionId -> order.indexOf(actionId).takeIf { it >= 0 } }
                ?: Int.MAX_VALUE
        }
    }

    fun upMenu() {
        adapter.setItems(filteredMenuItems())
    }

    fun show(
        view: View,
        windowHeight: Int,
        startX: Int,
        startTopY: Int,
        startTextBottomY: Int,
        startBottomY: Int,
        endX: Int,
        endBottomY: Int
    ) {
        val defaultActionId = defaultOpenActionId
        if (defaultActionId.isNotEmpty() && configuredActionIds.contains(defaultActionId)) {
            val defaultItem = filteredMenuItems().firstOrNull { menuItemToActionId(it.itemId) == defaultActionId }
            if (defaultItem != null) {
                if (!callBack.onMenuItemSelected(defaultItem.itemId)) {
                    onMenuItemSelected(defaultItem)
                }
                callBack.onMenuActionFinally()
                return
            }
        }
        val margin = 4.dpToPx()
        // 弹窗宽度固定：只由窗口宽度决定，不随可见菜单项数量变化，
        // 避免按内容测量出不同宽度导致弹窗反复改尺寸、按钮跨行跳动。
        // 内容区 RecyclerView 为 match_parent，预测量与窗口布局两趟拿到的都是
        // EXACTLY 同一宽度，Flexbox 换行行数两趟必然一致；wrap_content 宽度下
        // 测量上报的内容宽度会溢出父容器约束，两趟宽度漂移导致行数判定不一致，
        // 弹窗高度按多出的行创建、底部出现整行空白
        val windowWidth = if (view.width > 0) view.width else context.resources.displayMetrics.widthPixels
        val popupWidth = (windowWidth - 2 * margin).coerceAtLeast(margin)
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        val popupHeight = contentView.measuredHeight
        val textHeight = (startTextBottomY - startTopY).coerceAtLeast(0)
        val textTopY = (startTopY - textHeight).coerceAtLeast(0)
        val selectionBottomY = maxOf(startBottomY, endBottomY)
        val spaceAbove = textTopY
        val spaceBelow = windowHeight - selectionBottomY
        val showAbove = spaceAbove >= popupHeight + margin || (
                spaceBelow < popupHeight + margin && spaceAbove > spaceBelow
                )
        val preferredX = ((startX + endX) / 2f - popupWidth / 2f).toInt()
        val maxX = (view.width - popupWidth - margin).coerceAtLeast(margin)
        val x = preferredX.coerceIn(margin, maxX)
        val y = if (showAbove) {
            (textTopY - popupHeight - margin).coerceAtLeast(margin)
        } else {
            (selectionBottomY + margin).coerceAtMost((windowHeight - popupHeight - margin).coerceAtLeast(margin))
        }
        val originalAlpha = contentView.alpha.takeIf { it > 0f } ?: 1f
        val generation = ++blurGeneration
        SurfaceBackdrop.installStatic(contentView, SurfaceStyles.popup(context))
        contentView.alpha = 0f
        if (isShowing) {
            // 已显示时原地更新位置与尺寸，避免重建窗口造成闪动
            update(x, y, popupWidth, popupHeight)
        } else {
            // 首次弹出也使用与预测量相同的固定尺寸，避免窗口内按内容自适应
            // 在首帧后改宽改高、按钮跨行跳动
            width = popupWidth
            height = popupHeight
            // 重新弹出前清掉上次布局残留的子项，避免首帧闪出旧菜单内容
            binding.recyclerView.removeAllViews()
            showAtLocation(view, Gravity.TOP or Gravity.START, x, y)
        }
        context.findHostWindow()?.let { hostWindow ->
            SurfaceBackdrop.refresh(
                hostWindow = hostWindow,
                target = contentView,
                layerOwner = contentView,
                onReady = {
                    if (generation == blurGeneration && isShowing) {
                        contentView.alpha = originalAlpha
                    }
                }
            )
        } ?: run {
            if (generation == blurGeneration) contentView.alpha = originalAlpha
        }
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<MenuItemImpl, ItemTextBinding>(context) {

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewBinding(parent: ViewGroup): ItemTextBinding {
            return ItemTextBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTextBinding,
            item: MenuItemImpl,
            payloads: MutableList<Any>
        ) {
            with(binding) {
                textView.text = item.title
                textView.typeface = context.uiTypeface()
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTextBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    if (!callBack.onMenuItemSelected(it.itemId)) {
                        onMenuItemSelected(it)
                    }
                }
                callBack.onMenuActionFinally()
            }
        }
    }

    private fun onMenuItemSelected(item: MenuItemImpl) {
        when (item.itemId) {
            R.id.menu_copy -> context.sendToClip(callBack.selectedText)
            R.id.menu_share_str -> context.share(callBack.selectedText)
            R.id.menu_browser -> {
                kotlin.runCatching {
                    val intent = if (callBack.selectedText.isAbsUrl()) {
                        Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(callBack.selectedText)
                        }
                    } else {
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, callBack.selectedText)
                        }
                    }
                    context.startActivity(intent)
                }.onFailure {
                    context.toastOnUi(it.localizedMessage ?: "ERROR")
                }
            }

            else -> item.intent?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    kotlin.runCatching {
                        it.putExtra(Intent.EXTRA_PROCESS_TEXT, callBack.selectedText)
                        context.startActivity(it)
                    }.onFailure { e ->
                        AppLog.put("执行文本菜单操作出错\n$e", e, true)
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntent(): Intent {
        return Intent()
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getSupportedActivities(): List<ResolveInfo> {
        return context.packageManager
            .queryIntentActivities(createProcessTextIntent(), 0)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntentForResolveInfo(info: ResolveInfo): Intent {
        return createProcessTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

    /**
     * Start with a menu Item order value that is high enough
     * so that your "PROCESS_TEXT" menu items appear after the
     * standard selection menu items like Cut, Copy, Paste.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun onInitializeMenu(menu: Menu) {
        kotlin.runCatching {
            var menuItemOrder = 100
            for (resolveInfo in getSupportedActivities()) {
                menu.add(
                    Menu.NONE, Menu.NONE,
                    menuItemOrder++, resolveInfo.loadLabel(context.packageManager)
                ).intent = createProcessTextIntentForResolveInfo(resolveInfo)
            }
        }.onFailure {
            context.toastOnUi("获取文字操作菜单出错:${it.localizedMessage}")
        }
    }

    interface CallBack {
        val selectedText: String

        fun onMenuItemSelected(itemId: Int): Boolean

        fun onMenuActionFinally()

        fun onMenuConfigRequested()
    }
}
