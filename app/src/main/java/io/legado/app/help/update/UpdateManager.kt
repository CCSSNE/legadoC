package io.legado.app.help.update

import android.content.Context
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.Download
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 检查 GitHub Release 上的新版本并提示下载安装
 * Release 的 tag 需使用 versionCode 作为标签（例如 10723 或 v10723）
 */
object UpdateManager {

    private const val GITHUB_API = "https://api.github.com/repos/CCSSNE/legadoC/releases?per_page=30"
    private const val MAX_BODY_LEN = 2000

    @Keep
    data class GitHubRelease(
        val tag_name: String? = null,
        val prerelease: Boolean = false,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList()
    )

    @Keep
    data class GitHubAsset(
        val name: String? = null,
        val browser_download_url: String? = null
    )

    private data class UpdateInfo(
        val tagName: String,
        val versionCode: Long,
        val fileName: String,
        val downloadUrl: String,
        val body: String?
    )

    private data class ReleaseCandidate(
        val release: GitHubRelease,
        val versionCode: Long,
        val asset: GitHubAsset
    )

    /**
     * 检查更新，有新版则弹出更新对话框
     * @param showUpToDate 无更新时是否提示"已是最新"
     * @param showError 检查失败时是否提示
     * @return 是否发现了新版本
     */
    suspend fun checkUpdate(
        context: Context,
        showUpToDate: Boolean = false,
        showError: Boolean = false
    ): Boolean {
        return try {
            val info = withContext(Dispatchers.IO) { fetchLatestRelease(context) }
            if (info == null) {
                if (showUpToDate) withContext(Dispatchers.Main) {
                    context.toastOnUi(R.string.update_none)
                }
                return false
            }
            withContext(Dispatchers.Main) { showUpdateDialog(context, info) }
            true
        } catch (e: Exception) {
            if (showError) withContext(Dispatchers.Main) {
                context.toastOnUi(R.string.update_check_failed)
            }
            false
        }
    }

    private suspend fun fetchLatestRelease(context: Context): UpdateInfo? {
        val includePre = context.getPrefBoolean(PreferKey.updateCheckPre)
        val resp = okHttpClient.newCallStrResponse(retry = 1) {
            url(GITHUB_API)
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "LegadoC/${AppConst.appInfo.versionName}")
        }
        val body = resp.body ?: throw IOException("empty response")
        val releases = GSON.fromJson(body, Array<GitHubRelease>::class.java)?.toList() ?: emptyList()
        // pre 开关决定是否接受预发布版本
        val eligible = releases.filter { includePre || !it.prerelease }
        // versionCode 必须从 APK 资产文件名解析（legado_app_3.26.081303_10539.apk -> 10539），
        // 不能从 tag 解析：tag 是版本名格式（v3.26.081303c），里面的数字是 MMddHH，
        // 提取出来会是 81303 这种假版本号，永远大于真实 versionCode，导致误报"发现新版本"。
        val candidates = eligible.mapNotNull { release ->
            val asset = release.assets.firstOrNull { it.name?.endsWith(".apk", true) == true }
                ?: return@mapNotNull null
            val code = parseVersionCodeFromAssetName(asset.name) ?: return@mapNotNull null
            ReleaseCandidate(release, code, asset)
        }
        // 在所有可下载候选中挑 versionCode 最高者，而不是列表第一个；
        // 这样即使正式版比 Pre 版更新（版本号更高），也会正确下载正式版
        val best = candidates.maxByOrNull { it.versionCode } ?: return null
        if (best.versionCode <= AppConst.appInfo.versionCode) return null
        val originalUrl = best.asset.browser_download_url ?: return null
        return UpdateInfo(
            tagName = best.release.tag_name ?: best.versionCode.toString(),
            versionCode = best.versionCode,
            fileName = best.asset.name ?: "legado-update.apk",
            downloadUrl = resolveAcceleratedUrl(context, originalUrl),
            body = best.release.body
        )
    }

    /**
     * 从 APK 资产文件名解析 versionCode：
     * legado_app_3.26.081303_10539.apk -> 10539
     */
    private fun parseVersionCodeFromAssetName(name: String?): Long? {
        name ?: return null
        return Regex("_(\\d+)\\.apk$", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * 根据设置的加速源改写下载地址
     */
    fun resolveAcceleratedUrl(context: Context, originalUrl: String): String {
        return when (context.getPrefString(PreferKey.updateAccelerator) ?: "ghfast") {
            "ghproxy" -> "https://ghproxy.net/" + originalUrl
            "gh-proxy" -> "https://gh-proxy.com/" + originalUrl
            "ghfast" -> "https://ghfast.top/" + originalUrl
            "custom" -> {
                val prefix = context.getPrefString(PreferKey.updateAcceleratorCustom)
                    ?.trim().orEmpty()
                when {
                    prefix.isBlank() -> originalUrl
                    prefix.endsWith("/") -> prefix + originalUrl
                    else -> "$prefix/" + originalUrl
                }
            }

            else -> originalUrl
        }
    }

    private fun showUpdateDialog(context: Context, info: UpdateInfo) {
        context.alert(
            context.getString(R.string.update_dialog_title) + " v" + info.tagName,
            info.body?.trim().orEmpty().take(MAX_BODY_LEN).ifBlank {
                context.getString(R.string.update_check_on_start_desc)
            }
        ) {
            positiveButton(R.string.update_download) {
                Download.start(context, info.downloadUrl, info.fileName)
                context.toastOnUi("开始下载更新包")
            }
            cancelButton()
        }
    }

}
