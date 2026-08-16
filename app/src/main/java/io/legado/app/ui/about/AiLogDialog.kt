package io.legado.app.ui.about

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemAiLogBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick
import java.util.Date

class AiLogDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { LogAdapter(requireContext()) }
    private var clearJob: Job? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.ai_log)
            toolBar.inflateMenu(R.menu.app_log)
            toolBar.setOnMenuItemClickListener(this@AiLogDialog)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
        }
        adapter.setItems(AppLog.aiLogs)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> {
                val menuItem = item ?: return true
                if (clearJob?.isActive == true) return true
                adapter.clearItems()
                menuItem.isEnabled = false
                clearJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        AppLog.clearAi()
                    } finally {
                        withContext(Dispatchers.Main.immediate) {
                            menuItem.isEnabled = true
                            clearJob = null
                        }
                    }
                }
            }
            R.id.menu_copy_all -> {
                requireContext().sendToClip(AppLog.formatLogs(AppLog.aiLogs))
            }
        }
        return true
    }

    inner class LogAdapter(context: Context) :
        RecyclerAdapter<Triple<Long, String, Throwable?>, ItemAiLogBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAiLogBinding {
            return ItemAiLogBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAiLogBinding,
            item: Triple<Long, String, Throwable?>,
            payloads: MutableList<Any>
        ) {
            binding.textTime.text = LogUtils.logTimeFormat.format(Date(item.first))
            binding.textMessage.text = item.second.lineSequence()
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString(" · ")
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAiLogBinding) {
            binding.root.onClick {
                getItem(holder.layoutPosition)?.let { item ->
                    showDialogFragment(
                        TextDialog(
                            getString(R.string.ai_log),
                            AppLog.formatLogs(listOf(item))
                        )
                    )
                }
            }
        }
    }
}
