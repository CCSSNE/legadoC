package io.legado.app.ui.about

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemAppLogBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.*

class AppLogDialog : BaseLogDialogFragment() {

    private companion object {
        /** 评论缓存逐章详细日志前缀：仅此类日志在列表折叠为两行 */
        const val REVIEW_LOG_PREFIX = "[评论缓存]"
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy {
        LogAdapter(requireContext())
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.log)
            toolBar.inflateMenu(R.menu.app_log)
            toolBar.setOnMenuItemClickListener(this@AppLogDialog)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
        }
        adapter.setItems(visibleLogs())
    }

    override fun observeLiveBus() {
        observeEvent<Int>(io.legado.app.constant.EventBus.APP_LOG_CHANGED) {
            adapter.setItems(visibleLogs())
        }
    }

    override fun clearLogs(onCleared: () -> Unit) {
        AppLog.clear()
        onCleared()
    }

    override fun copyAllLogs() {
        requireContext().sendToClip(AppLog.formatLogs(visibleLogs()))
    }

    /** 显示与复制共用同一份数据：通用条目始终显示，其余按勾选模块过滤 */
    private fun visibleLogs(): List<AppLog.Entry> {
        return AppLog.logsForView(AppConfig.logShownModules)
    }

    inner class LogAdapter(context: Context) :
        RecyclerAdapter<AppLog.Entry, ItemAppLogBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAppLogBinding {
            return ItemAppLogBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAppLogBinding,
            item: AppLog.Entry,
            payloads: MutableList<Any>
        ) {
            binding.textTime.text = LogUtils.logTimeFormat.format(Date(item.time))
            binding.textMessage.text = item.message
            // 只有 [评论缓存] 逐章详细日志做两行折叠；普通日志保持原有显示方式
            if (item.message.startsWith(REVIEW_LOG_PREFIX)) {
                binding.textMessage.maxLines = 2
                binding.textMessage.ellipsize = android.text.TextUtils.TruncateAt.END
            } else {
                binding.textMessage.maxLines = Int.MAX_VALUE
                binding.textMessage.ellipsize = null
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAppLogBinding) {
            val showFullLog: (android.view.View) -> Unit = {
                getItem(holder.layoutPosition)?.let { item ->
                    // 点击任意一条日志：弹窗显示完整内容（列表只展示摘要）
                    val full = buildString {
                        append(item.message)
                        item.throwable?.let {
                            append("\n").append(it.stackTraceToString())
                        }
                    }
                    showDialogFragment(TextDialog("Log", full))
                }
            }
            binding.root.setOnClickListener(showFullLog)
            binding.textMessage.setOnClickListener(showFullLog)
        }

    }

}
