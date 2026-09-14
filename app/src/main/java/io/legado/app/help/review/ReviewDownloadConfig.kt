package io.legado.app.help.review

import io.legado.app.R
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx

/** 评论下载各阶段的唯一配置定义；设置界面直接使用这里的默认值。 */
object ReviewDownloadConfig {
    const val ENGINE = "reviewDownloadEngine"
    const val BACKOFF = "reviewDownloadBackoff"
    const val REUSE_REPLIES = "reviewDownloadReuseReplies"
    const val REUSE_RESOURCES = "reviewDownloadReuseResources"
    const val REQUIRE_AVATARS = "reviewDownloadRequireAvatars"
    const val REQUIRE_IMAGES = "reviewDownloadRequireImages"
    const val REQUIRE_CSS = "reviewDownloadRequireCss"
    const val REQUIRE_FONTS = "reviewDownloadRequireFonts"

    enum class Number(val key: String, val title: Int, val default: Int, val minimum: Int = 1) {
        DATA("reviewDownloadDataConcurrency", R.string.review_download_data_concurrency, 8),
        REPLIES("reviewDownloadReplyConcurrency", R.string.review_download_reply_concurrency, 8),
        RESOURCES("reviewDownloadResourceConcurrency", R.string.review_download_resource_concurrency, 8),
        PAGES("reviewDownloadPageConcurrency", R.string.review_download_page_concurrency, 2),
        HEAVY("reviewDownloadHeavyConcurrency", R.string.review_download_heavy_concurrency, 1),
        PAGE_SIZE("reviewDownloadPageSize", R.string.review_download_page_size, 20),
        REPLY_SIZE("reviewDownloadReplySize", R.string.review_download_reply_size, 20),
        STABLE_INTERVAL("reviewDownloadStableInterval", R.string.review_download_stable_interval, 800, 0),
        STABLE_ROUNDS("reviewDownloadStableRounds", R.string.review_download_stable_rounds, 3),
        PAGE_TIMEOUT("reviewDownloadPageTimeout", R.string.review_download_page_timeout, 60000, 0),
        DATA_TIMEOUT("reviewDownloadDataTimeout", R.string.review_download_data_timeout, 30000, 0),
        RESOURCE_TIMEOUT("reviewDownloadResourceTimeout", R.string.review_download_resource_timeout, 8000, 0),
        RETRIES("reviewDownloadRetries", R.string.review_download_retries, 0, 0),
        RETRY_INTERVAL("reviewDownloadRetryInterval", R.string.review_download_retry_interval, 1000, 0);

        var value: Int
            get() = appCtx.getPrefInt(key, default).also {
                require(it >= minimum) { "$key 必须不小于 $minimum，实际为 $it" }
            }
            set(value) {
                require(value >= minimum) { "$key 必须不小于 $minimum" }
                appCtx.putPrefInt(key, value)
                ReviewDownloadScheduler.settingsChanged()
            }
    }

    enum class Engine { AUTO, DATA, WEB }
    val engine: Engine get() = when (val value = appCtx.getPrefString(ENGINE, "auto")) {
        "auto" -> Engine.AUTO
        "data" -> Engine.DATA
        "web" -> Engine.WEB
        else -> error("未知评论抓取引擎：$value")
    }
    fun enabled(key: String): Boolean = appCtx.getPrefBoolean(key, true)

    /** 溢出是明确错误，不静默压低用户配置的等待时间。 */
    fun retryDelay(attempt: Int): Long {
        val base = Number.RETRY_INTERVAL.value.toLong()
        if (base == 0L) return 0L
        return when (val mode = appCtx.getPrefString(BACKOFF, "fixed")) {
            "fixed" -> base
            "linear" -> Math.multiplyExact(base, attempt.toLong())
            "exponential" -> {
                require(attempt <= 63) { "评论重试指数退避超过 Long 毫秒表示范围" }
                Math.multiplyExact(base, 1L shl (attempt - 1))
            }
            else -> error("未知评论重试退避方式：$mode")
        }
    }
}
