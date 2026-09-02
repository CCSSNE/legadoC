package io.legado.app.ui.widget.menu

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.MenuRes
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.surface.SurfaceStyles
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.SurfaceBackdrop
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.findHostWindow

/**
 * A popup whose visible shell is owned by the application.
 *
 * Unlike AppCompat PopupMenu, this class never reflects into private popup fields.
 * The same explicit root receives geometry, tint, rounded clipping and its backdrop.
 */
@SuppressLint("RestrictedApi")
class SurfacePopupMenu(
    private val context: Context,
    private val anchor: View
) {

    val menu: MenuBuilder = MenuBuilder(context)

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val scrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        addView(
            rows,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    private val surface = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = 6.dpToPx()
        setPadding(padding, padding, padding, padding)
        addView(
            scrollView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    private var surfaceGeneration = 0
    private var dismissGeneration = 0
    private var dismissAnimator: ObjectAnimator? = null
    private var dismissAction: (() -> Unit)? = null
    private var dismissListener: (() -> Unit)? = null
    private var immediateDismiss = false
    private val popupWindow: PopupWindow = object : PopupWindow(
        surface,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ) {
        override fun dismiss() {
            if (immediateDismiss) {
                super.dismiss()
            } else {
                dismissWithFade()
            }
        }
    }.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        isClippingEnabled = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        animationStyle = 0
        elevation = 12.dpToPx().toFloat()
        setOnDismissListener {
            surfaceGeneration += 1
            cancelDismissFade()
            SurfaceBackdrop.clear(surface)
            surface.alpha = 1f
            dismissListener?.invoke()
        }
    }

    private data class Level(
        val title: CharSequence?,
        val items: List<MenuItem>
    )

    private val levels = ArrayDeque<Level>()
    private var itemClickListener: ((MenuItem) -> Boolean)? = null
    private var activeMenu: Menu = menu

    fun inflate(@MenuRes menuRes: Int) {
        SupportMenuInflater(context).inflate(menuRes, menu)
    }

    fun setOnMenuItemClickListener(listener: (MenuItem) -> Boolean) {
        itemClickListener = listener
    }

    fun setOnDismissListener(listener: (() -> Unit)?) {
        dismissListener = listener
    }

    fun show() {
        show(menu, menu.visibleItemsForSurface())
    }

    /** Shows an existing menu (for example a toolbar item's submenu) in this surface. */
    fun show(sourceMenu: Menu) {
        show(sourceMenu, sourceMenu.visibleItemsForSurface())
    }

    fun show(sourceMenu: Menu, rootItems: List<MenuItem>) {
        cancelDismissFade()
        val generation = ++surfaceGeneration
        activeMenu = sourceMenu
        sourceMenu.applyUiMenuStyle(context)
        levels.clear()
        levels.addLast(Level(null, rootItems))
        renderCurrentLevel()
        SurfaceBackdrop.installStatic(surface, SurfaceStyles.popup(context))
        measurePopup()
        surface.alpha = 0f
        popupWindow.showAsDropDown(anchor, 0, 0, Gravity.END)
        prepareBackdrop(generation)
    }

    fun dismiss() = dismissWithFade()

    private fun renderCurrentLevel() {
        rows.removeAllViews()
        val level = levels.last()
        if (levels.size > 1) {
            rows.addView(createBackRow(level.title))
        }
        level.items.forEach { rows.addView(createItemRow(it)) }
    }

    private fun createBackRow(title: CharSequence?): View {
        return createRow(
            title = title ?: "",
            startIcon = ContextCompat.getDrawable(context, R.drawable.ic_arrow_back),
            endIcon = null,
            enabled = true
        ).apply {
            setOnClickListener {
                if (levels.size > 1) levels.removeLast()
                renderCurrentLevel()
                measurePopup(update = true)
                prepareBackdrop(++surfaceGeneration)
            }
        }
    }

    private fun createItemRow(item: MenuItem): View {
        val endIcon = when {
            item.hasSubMenu() -> ContextCompat.getDrawable(context, R.drawable.ic_arrow_right)
            item.isCheckable && item.isChecked -> ContextCompat.getDrawable(context, R.drawable.ic_check)
            else -> null
        }
        return createRow(item.title ?: "", item.icon, endIcon, item.isEnabled).apply {
            contentDescription = item.title
            setOnClickListener {
                if (!item.isEnabled) return@setOnClickListener
                val subMenu = item.subMenu
                if (subMenu != null) {
                    levels.addLast(Level(item.title, subMenu.visibleItemsForSurface()))
                    renderCurrentLevel()
                    measurePopup(update = true)
                    prepareBackdrop(++surfaceGeneration)
                } else {
                    dismissWithFade {
                        itemClickListener?.invoke(item) ?: activeMenu.performIdentifierAction(item.itemId, 0)
                    }
                }
            }
        }
    }

    private fun createRow(
        title: CharSequence,
        startIcon: android.graphics.drawable.Drawable?,
        endIcon: android.graphics.drawable.Drawable?,
        enabled: Boolean
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 48.dpToPx()
            val horizontal = 12.dpToPx()
            setPadding(horizontal, 0, horizontal, 0)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            resolveSelectableBackground()?.let(::setBackgroundResource)
        }
        val iconSize = 24.dpToPx()
        row.addView(ImageView(context).apply {
            setImageDrawable(startIcon)
            visibility = if (startIcon == null) View.INVISIBLE else View.VISIBLE
        }, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginEnd = 12.dpToPx()
        })
        row.addView(TextView(context).apply {
            text = title
            setTextColor(context.primaryTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.menu_text_size))
            typeface = context.uiTypeface()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(context).apply {
            setImageDrawable(endIcon)
            visibility = if (endIcon == null) View.INVISIBLE else View.VISIBLE
        }, LinearLayout.LayoutParams(20.dpToPx(), 20.dpToPx()).apply {
            marginStart = 12.dpToPx()
        })
        return row
    }

    private fun measurePopup(update: Boolean = false) {
        val metrics = context.resources.displayMetrics
        val maxWidth = minOf(metrics.widthPixels - 24.dpToPx(), 360.dpToPx())
        val maxHeight = (metrics.heightPixels * 0.68f).toInt()
        surface.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val measuredWidth = surface.measuredWidth.coerceIn(180.dpToPx(), maxWidth)
        val measuredHeight = surface.measuredHeight.coerceAtMost(maxHeight)
        popupWindow.width = measuredWidth
        popupWindow.height = measuredHeight
        if (update && popupWindow.isShowing) {
            popupWindow.update(measuredWidth, measuredHeight)
        }
    }

    private fun prepareBackdrop(generation: Int) {
        surface.alpha = 0f
        val hostWindow = context.findHostWindow()
        if (hostWindow == null) {
            if (generation == surfaceGeneration && popupWindow.isShowing) surface.alpha = 1f
            return
        }
        SurfaceBackdrop.refresh(hostWindow, surface, layerOwner = surface) {
            if (generation == surfaceGeneration && popupWindow.isShowing) {
                surface.alpha = 1f
            }
        }
    }

    private fun dismissWithFade(afterDismiss: (() -> Unit)? = null) {
        if (!popupWindow.isShowing) {
            afterDismiss?.invoke()
            return
        }
        if (dismissAnimator != null) return

        dismissAction = afterDismiss
        val generation = ++dismissGeneration
        val animator = ObjectAnimator.ofFloat(surface, View.ALPHA, surface.alpha, 0f).apply {
            duration = DISMISS_FADE_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (dismissAnimator !== animation || generation != dismissGeneration) return
                    dismissAnimator = null
                    completeDismiss()
                }
            })
        }
        dismissAnimator = animator
        animator.start()
    }

    private fun completeDismiss() {
        val afterDismiss = dismissAction
        dismissAction = null
        immediateDismiss = true
        try {
            popupWindow.dismiss()
        } finally {
            immediateDismiss = false
        }
        afterDismiss?.invoke()
    }

    private fun cancelDismissFade() {
        dismissGeneration += 1
        dismissAction = null
        dismissAnimator?.let { animator ->
            dismissAnimator = null
            animator.cancel()
        }
    }

    private fun resolveSelectableBackground(): Int? {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            value.resourceId.takeIf { it != 0 }
        } else {
            null
        }
    }

    private fun Menu.visibleItemsForSurface(): List<MenuItem> {
        return buildList {
            for (index in 0 until size()) {
                getItem(index).takeIf { it.isVisible }?.let(::add)
            }
        }
    }

    private companion object {
        const val DISMISS_FADE_DURATION_MS = 100L
    }
}
