package io.legado.app.ui.about

import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.utils.setLayout

abstract class BaseLogDialogFragment : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    final override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_clear -> {
                clearLogs {
                    dismissAllowingStateLoss()
                }
                true
            }

            R.id.menu_copy_all -> {
                copyAllLogs()
                true
            }

            else -> false
        }
    }

    protected abstract fun clearLogs(onCleared: () -> Unit)

    protected abstract fun copyAllLogs()
}
