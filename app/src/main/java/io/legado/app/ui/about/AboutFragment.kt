package io.legado.app.ui.about

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.AppLog
import io.legado.app.constant.LogModule
import io.legado.app.constant.PreferKey
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.update.UpdateManager
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.removePref
import io.legado.app.utils.sendMail
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File
import java.io.FileFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AboutFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.about)
        findPreference<Preference>("update_log")?.summary =
            "${getString(R.string.version)} ${appInfo.versionName}"
        findPreference<io.legado.app.lib.prefs.Preference>("telegram")?.onLongClick {
            requireContext().sendToClip(getString(R.string.qq_group_number))
            toastOnUi(R.string.qq_group_number_copied)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "contributors" -> openUrl(R.string.repo_url)
            "telegram" -> openUrl(R.string.qq_group_url)
            "update_log" -> showUpdateLog()
            "mail" -> requireContext().sendMail(getString(R.string.email))
            "license" -> showMdFile(getString(R.string.license), "LICENSE.md")
            "disclaimer" -> showMdFile(getString(R.string.disclaimer), "disclaimer.md")
            "privacyPolicy" -> showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md")
            "gzGzh" -> requireContext().sendToClip(getString(R.string.legado_gzh))
            "crashLog" -> showDialogFragment<CrashLogsDialog>()
            "saveCrashLog" -> saveCrashLog()
            "showLog" -> showDialogFragment<AppLogDialog>()
            "saveLog" -> saveLog()
            "logShownModules" -> showLogShownModulesDialog()
            "createHeapDump" -> createHeapDump()
        }
        return super.onPreferenceTreeClick(preference)
    }

    /**
     * 更新日志：优先从 GitHub 拉取最新 README（走设置的加速源），失败时回退到本地 assets
     */
    private fun showUpdateLog() {
        Coroutine.async {
            fetchReadmeFromGithub()
        }.onSuccess { text ->
            if (!isAdded) return@onSuccess
            if (text.isNullOrBlank()) {
                showMdFile(getString(R.string.update_log), "README.md")
            } else {
                showDialogFragment(TextDialog(getString(R.string.update_log), text, TextDialog.Mode.MD))
            }
        }.onError {
            AppLog.put("拉取更新日志失败\n${it.localizedMessage}", it)
            if (!isAdded) return@onError
            showMdFile(getString(R.string.update_log), "README.md")
        }
    }

    private suspend fun fetchReadmeFromGithub(): String? {
        return runCatching {
            val url = UpdateManager.resolveAcceleratedUrl(
                appCtx,
                "https://raw.githubusercontent.com/CCSSNE/legadoC/own/README.md"
            )
            okHttpClient.newCallStrResponse(retry = 1) {
                url(url)
                header("User-Agent", "LegadoC/${appInfo.versionName}")
            }.body
        }.getOrNull()
    }

    @Suppress("SameParameterValue")
    private fun openUrl(@StringRes addressID: Int) {
        requireContext().openUrl(getString(addressID))
    }

    /**
     * 显示md文件
     */
    private fun showMdFile(title: String, fileName: String) {
        if (!isAdded) return
        val ctx = context ?: return
        val mdText = String(ctx.assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }

    /** 保存普通日志：把普通日志弹窗当前勾选模块过滤后的日志导出为 TXT 到备份目录 */
    private fun saveLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            val logs = AppLog.logsForView(AppConfig.logShownModules)
            if (logs.isEmpty()) {
                appCtx.toastOnUi("当前没有可保存的日志，请先在普通日志模块中勾选")
                return@async
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            val fileName = "app-log-" + SimpleDateFormat(
                "yyyyMMdd-HHmmss", Locale.getDefault()
            ).format(Date()) + ".txt"
            doc.find(fileName)?.delete()
            doc.createFileIfNotExist(fileName).openOutputStream().getOrNull()?.use {
                it.write(AppLog.formatLogs(logs).toByteArray(Charsets.UTF_8))
            } ?: error("无法创建日志文件")
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 保存崩溃日志：把崩溃日志文件汇总导出为一个 TXT 到备份目录 */
    private fun saveCrashLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            val files = arrayListOf<FileDoc>()
            appCtx.externalCacheDir
                ?.getFile("crash")
                ?.listFiles(FileFilter { it.isFile })
                ?.forEach { files.add(FileDoc.fromFile(it)) }
            doc.find("crash")
                ?.list { !it.isDir }
                ?.let { files.addAll(it) }
            val crashLogs = files.sortedByDescending { it.name }.distinctBy { it.name }
            if (crashLogs.isEmpty()) {
                appCtx.toastOnUi("没有可保存的崩溃日志")
                return@async
            }
            val text = crashLogs.joinToString("\n\n") { file ->
                "${file.name}\n${file.readText()}"
            }
            val fileName = "crash-log-" + SimpleDateFormat(
                "yyyyMMdd-HHmmss", Locale.getDefault()
            ).format(Date()) + ".txt"
            doc.find(fileName)?.delete()
            doc.createFileIfNotExist(fileName).openOutputStream().getOrNull()?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("无法创建崩溃日志文件")
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存崩溃日志出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 勾选普通日志中显示的模块；全部模块均可勾选，全不勾选时普通日志为空 */
    private fun showLogShownModulesDialog() {
        val modules = LogModule.selectable
        val labels = modules.map { getString(it.labelRes) }.toTypedArray()
        val shown = AppConfig.logShownModules
        val checked = BooleanArray(modules.size) { modules[it].name in shown }
        alert(getString(R.string.log_shown_modules_t)) {
            multiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            okButton {
                val selected = (0 until modules.size)
                    .filter { checked[it] }
                    .mapTo(mutableSetOf()) { modules[it].name }
                putPrefStringSet(PreferKey.logShownModules, selected)
            }
            negativeButton(R.string.select_all) { dialog ->
                repeat(checked.size) { index ->
                    checked[index] = true
                    (dialog as AlertDialog).listView.setItemChecked(index, true)
                }
                putPrefStringSet(PreferKey.logShownModules, LogModule.selectableNames.toMutableSet())
            }
            neutralButton(R.string.restore_default) { dialog ->
                repeat(checked.size) { index ->
                    checked[index] = false
                    (dialog as AlertDialog).listView.setItemChecked(index, false)
                }
                removePref(PreferKey.logShownModules)
            }
            cancelButton()
        }
    }

    private fun createHeapDump() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

}
