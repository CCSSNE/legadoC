package io.legado.app.ui.about

import android.net.Uri
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.help.LogExporter
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseLogDialogFragment : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private var pendingLogs: List<AppLog.Entry> = emptyList()

    private val exportLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val logs = pendingLogs
        pendingLogs = emptyList()
        uri?.let { writeLogs(it, logs) }
    }

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

            R.id.menu_export -> {
                exportLogs()
                true
            }

            else -> false
        }
    }

    protected abstract fun clearLogs(onCleared: () -> Unit)

    protected abstract fun copyAllLogs()

    /** 导出当前弹窗要显示的日志（与列表显示共用同一份过滤数据） */
    protected abstract fun exportLogs()

    protected fun startExport(logs: List<AppLog.Entry>, fileNamePrefix: String) {
        if (logs.isEmpty()) {
            toastOnUi(R.string.log_empty)
            return
        }
        pendingLogs = logs
        exportLogLauncher.launch(LogExporter.fileName(fileNamePrefix))
    }

    private fun writeLogs(uri: Uri, logs: List<AppLog.Entry>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LogExporter.write(requireContext(), uri, logs) }
            }
            result.onSuccess {
                toastOnUi(R.string.log_export_success)
            }.onFailure {
                AppLog.put("导出普通日志失败\n${it.localizedMessage}", it)
                toastOnUi(getString(R.string.log_export_failed, it.localizedMessage ?: "未知错误"))
            }
        }
    }
}
