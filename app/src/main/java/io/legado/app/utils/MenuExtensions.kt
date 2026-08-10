@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.AbsoluteSizeSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ListView
import androidx.annotation.DrawableRes
import androidx.annotation.MenuRes
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.view.menu.SubMenuBuilder
import androidx.core.view.forEach
import io.legado.app.R
import io.legado.app.constant.Theme
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import java.lang.reflect.Method

data class PopupMenuAction(
    val title: CharSequence,
    @param:DrawableRes val iconRes: Int? = null,
    val onClick: () -> Unit
)

fun View.showPopupMenu(
    actions: List<PopupMenuAction>
): Boolean {
    if (actions.isEmpty()) return false
    PopupMenu(context, this).apply {
        actions.forEachIndexed { index, action ->
            menu.add(Menu.NONE, index, index, action.title).apply {
                action.iconRes?.let(::setIcon)
            }
        }
        menu.applyUiMenuStyle(context)
        setOnMenuItemClickListener { item ->
            actions.getOrNull(item.itemId)?.onClick?.invoke()
            true
        }
        show()
        showScrollIndicators()
    }
    return true
}

fun View.showPopupMenu(
    @MenuRes menuRes: Int,
    prepare: (Menu.() -> Unit)? = null,
    onClick: (MenuItem) -> Boolean
): Boolean {
    PopupMenu(context, this).apply {
        inflate(menuRes)
        prepare?.invoke(menu)
        menu.applyUiMenuStyle(context)
        setOnMenuItemClickListener(onClick)
        show()
        showScrollIndicators()
    }
    return true
}

fun Menu.applyUiMenuStyle(context: Context, theme: Theme = Theme.Auto): Menu {
    applyUiMenuTitleSize(context)
    return applyTint(context, theme)
}

fun Menu.applyUiMenuTitleSize(context: Context? = null) {
    val textSize = context?.resources?.getDimensionPixelSize(R.dimen.menu_text_size) ?: 16
    val dip = context == null
    val typeface = context?.uiTypeface()
    for (index in 0 until size()) {
        val item = getItem(index)
        item.title = item.title.toUiMenuTitle(textSize, dip, typeface)
        item.subMenu?.applyUiMenuTitleSize(context)
    }
}

private fun CharSequence?.toUiMenuTitle(
    textSize: Int,
    dip: Boolean,
    typeface: Typeface?
): CharSequence? {
    val title = this?.toString() ?: return null
    if (title.isEmpty()) return this
    return SpannableString(title).apply {
        setSpan(AbsoluteSizeSpan(textSize, dip), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        typeface?.let {
            setSpan(MenuTypefaceSpan(it), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

private class MenuTypefaceSpan(
    private val typeface: Typeface
) : MetricAffectingSpan() {

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.typeface = typeface
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.typeface = typeface
    }
}

private fun PopupMenu.showScrollIndicators() {
    runCatching {
        val popupField = PopupMenu::class.java.getDeclaredField("mPopup")
        popupField.isAccessible = true
        val popup = popupField.get(this)
        val getPopup = popup.javaClass.getDeclaredMethod("getPopup")
        getPopup.isAccessible = true
        val listPopupWindow = getPopup.invoke(popup)
        val listView = listPopupWindow.javaClass.getMethod("getListView")
            .invoke(listPopupWindow) as? ListView
        listView?.apply {
            applyMenuScrollIndicators()
        }
    }
}

fun View.applyMenuScrollIndicators() {
    when (this) {
        is ListView -> {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            isVerticalFadingEdgeEnabled = true
        }
        is android.view.ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyMenuScrollIndicators()
            }
        }
    }
}

@SuppressLint("RestrictedApi")
@Suppress("UsePropertyAccessSyntax")
fun Menu.applyTint(context: Context, theme: Theme = Theme.Auto): Menu = this.let { menu ->
    if (menu is MenuBuilder) {
        menu.setOptionalIconsVisible(true)
    }
    val defaultTextColor = context.getCompatColor(R.color.primaryText)
    val tintColor = MenuExtensions.getMenuColor(context, theme)
    menu.forEach { item ->
        val requiresOverflow = (item as? MenuItemImpl)?.requiresOverflow() ?: true
        // overflow：展开的item
        item.icon?.setTintMutate(
            if (requiresOverflow) defaultTextColor else tintColor
        )
    }
    return menu
}

@SuppressLint("RestrictedApi")
fun Menu.applyOpenTint(context: Context, showIcon: Boolean = true) {
    //展开菜单显示图标
    if (this.javaClass.simpleName.equals("MenuBuilder", ignoreCase = true)) {
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        kotlin.runCatching {
            var method: Method =
                this.javaClass.getDeclaredMethod("setOptionalIconsVisible", java.lang.Boolean.TYPE)
            method.isAccessible = true
            method.invoke(this, showIcon)
            if (showIcon) {
                method = this.javaClass.getDeclaredMethod("getNonActionItems")
                val menuItems = method.invoke(this)
                if (menuItems is ArrayList<*>) {
                    for (menuItem in menuItems) {
                        if (menuItem is MenuItem) {
                            menuItem.icon?.setTintMutate(defaultTextColor)
                        }
                    }
                }
            }
        }
    } else if (this.javaClass.simpleName.equals("SubMenuBuilder", ignoreCase = true)) {
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        (this as? SubMenuBuilder)?.forEach { item: MenuItem ->
            item.icon?.setTintMutate(defaultTextColor)
        }
    }
}

fun Menu.iconItemOnLongClick(id: Int, function: (view: View) -> Unit) {
    findItem(id)?.let { item ->
        item.setActionView(R.layout.view_action_button)
        item.actionView?.run {
            contentDescription = item.title
            findViewById<ImageButton>(R.id.item).setImageDrawable(item.icon)
            setOnLongClickListener {
                function.invoke(this)
                true
            }
            setOnClickListener {
                performIdentifierAction(id, 0)
            }
        }
    }
}

@SuppressLint("RestrictedApi")
inline fun Menu.transaction(block: (Menu) -> Unit) {
    val menuBuilder = this as? MenuBuilder
    menuBuilder?.stopDispatchingItemsChanged()
    try {
        block(this)
    } finally {
        menuBuilder?.startDispatchingItemsChanged()
    }
}

object MenuExtensions {

    fun getMenuColor(
        context: Context,
        theme: Theme = Theme.Auto,
        requiresOverflow: Boolean = false
    ): Int {
        val defaultTextColor = context.getCompatColor(R.color.primaryText)
        if (requiresOverflow)
            return defaultTextColor
        val primaryTextColor = context.primaryTextColor
        return when (theme) {
            Theme.Dark -> context.getCompatColor(R.color.md_white_1000)
            Theme.Light -> context.getCompatColor(R.color.md_black_1000)
            else -> primaryTextColor
        }
    }

}
