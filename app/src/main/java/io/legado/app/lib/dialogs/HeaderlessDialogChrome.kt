package io.legado.app.lib.dialogs

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.children
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import io.legado.app.lib.theme.applyUiBodyTypeface
import io.legado.app.ui.widget.menu.SurfacePopupMenu
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.dpToPx

/**
 * The common modal-content policy: a dialog has no title bar.
 *
 * Legacy dialog layouts still declare [Toolbar]s.  They are treated only as a
 * temporary action registry while the dialog is being migrated: the toolbar is
 * removed from the visible hierarchy and each registered navigation/menu action
 * is exposed in a standard bottom action row.  The original menu callback is
 * invoked unchanged, so content code never needs a second action implementation.
 */
internal fun View.applyHeaderlessDialogChrome() {
    findToolbars().forEach(::moveToolbarActionsToBottom)
}

private fun View.findToolbars(): List<Toolbar> {
    val result = mutableListOf<Toolbar>()

    fun visit(view: View) {
        if (view is Toolbar) result += view
        if (view is ViewGroup) view.children.forEach(::visit)
    }

    visit(this)
    return result
}

private fun moveToolbarActionsToBottom(toolbar: Toolbar) {
    if (toolbar.visibility == View.GONE) return
    val host = findActionHost(toolbar) ?: run {
        // Do not leave a title bar visible merely because an unsupported legacy
        // container was encountered. The missing host is a layout issue to fix,
        // while the global no-header rule still applies.
        toolbar.visibility = View.GONE
        return
    }
    val actions = mutableListOf<Pair<CharSequence, (View) -> Unit>>()
    toolbar.children.filterIsInstance<ImageButton>().firstOrNull()?.let { navigation ->
        val label = toolbar.navigationContentDescription?.takeIf { it.isNotBlank() }
            ?: toolbar.context.getString(android.R.string.cancel)
        actions += label to { _ -> navigation.performClick() }
    }
    toolbar.menu.let { menu ->
        repeat(menu.size()) { index ->
            val item = menu.getItem(index)
            val label = item.title?.takeIf { it.isNotBlank() }
            if (item.isVisible && item.isEnabled && label != null) {
                val subMenu = item.subMenu
                if (subMenu == null) {
                    actions += label to { _ -> menu.performIdentifierAction(item.itemId, 0) }
                } else {
                    actions += label to { anchor ->
                        SurfacePopupMenu(anchor.context, anchor).apply {
                            setOnMenuItemClickListener { child ->
                                menu.performIdentifierAction(child.itemId, 0)
                            }
                            show(subMenu)
                        }
                    }
                }
            }
        }
    }
    toolbar.visibility = View.GONE
    if (actions.isEmpty()) return

    val actionRow = FlexboxLayout(host.context).apply {
        flexWrap = FlexWrap.WRAP
        justifyContent = JustifyContent.FLEX_END
        alignItems = AlignItems.CENTER
        setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
        actions.forEach { (label, action) ->
            val actionView = AccentTextView(context, null).apply {
                text = label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minHeight = 48.dpToPx()
                setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
                applyUiBodyTypeface(context)
                setOnClickListener { action(this) }
            }
            addView(
                actionView,
                FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    flexShrink = 0f
                }
            )
        }
        addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            val availableWidth = (
                right - left - view.paddingLeft - view.paddingRight
            ).coerceAtLeast(1)
            (view as? ViewGroup)?.children?.filterIsInstance<TextView>()?.forEach { child ->
                if (child.maxWidth != availableWidth) child.maxWidth = availableWidth
            }
        }
    }
    host.addBottomActionRow(actionRow)
}

private fun findActionHost(toolbar: Toolbar): ViewGroup? {
    var current: View? = toolbar
    while (current != null) {
        when (current) {
            is LinearLayout, is ConstraintLayout -> return current
        }
        current = current.parent as? View
    }
    return null
}

private fun ViewGroup.addBottomActionRow(actionRow: View) {
    when (this) {
        is LinearLayout -> addView(
            actionRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        is ConstraintLayout -> {
            val bottomAnchoredChildren = children.toList().filter { child ->
                (child.layoutParams as? ConstraintLayout.LayoutParams)?.bottomToBottom ==
                    ConstraintSet.PARENT_ID
            }
            val actionId = View.generateViewId()
            actionRow.id = actionId
            addView(
                actionRow,
                ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            ConstraintSet().apply {
                clone(this@addBottomActionRow)
                connect(actionId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                connect(actionId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                connect(actionId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                bottomAnchoredChildren.forEach { child ->
                    if (child.id == View.NO_ID) child.id = View.generateViewId()
                    clear(child.id, ConstraintSet.BOTTOM)
                    connect(child.id, ConstraintSet.BOTTOM, actionId, ConstraintSet.TOP)
                }
                applyTo(this@addBottomActionRow)
            }
        }
    }
}
