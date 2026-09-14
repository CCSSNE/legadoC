package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.help.review.ReviewDownloadConfig
import io.legado.app.help.review.ReviewDownloadConfig.Number
import io.legado.app.lib.dialogs.showIntegerInputDialog
import io.legado.app.lib.prefs.NameListPreference
import io.legado.app.lib.prefs.Preference
import io.legado.app.lib.prefs.PreferenceCategory
import io.legado.app.lib.prefs.SwitchPreference

/** 使用已有 Preference 与整数输入框，默认值和读取路径共用同一份定义。 */
internal fun OtherConfigFragment.addReviewDownloadPreferences() {
    val ctx = requireContext()
    fun group(title: String) = PreferenceCategory(ctx).apply {
        this.title = title
        isIconSpaceReserved = false
        preferenceScreen.addPreference(this)
    }
    fun PreferenceCategory.number(setting: Number, help: String) {
        addPreference(Preference(ctx).apply {
            key = setting.key
            setTitle(setting.title)
            setDefaultValue(setting.default)
            isIconSpaceReserved = false
            fun refresh() {
                summary = getString(R.string.review_download_numeric_summary,
                    setting.value, setting.default, help)
            }
            refresh()
            setOnPreferenceClickListener {
                showIntegerInputDialog(setting.title, setting.value,
                    setting.minimum..Int.MAX_VALUE, setting.default) {
                    setting.value = it
                    refresh()
                }
                true
            }
        })
    }
    fun PreferenceCategory.toggle(keyName: String, titleId: Int, help: String) {
        addPreference(SwitchPreference(ctx).apply {
            key = keyName
            setTitle(titleId)
            summary = help
            setDefaultValue(true)
            isIconSpaceReserved = false
        })
    }
    fun PreferenceCategory.choice(keyName: String, titleId: Int, labels: Int, values: Int,
                                  default: String, help: String? = null) {
        val pref = NameListPreference(ctx).apply {
            key = keyName
            setTitle(titleId)
            setEntries(labels)
            setEntryValues(values)
            setDefaultValue(default)
            isIconSpaceReserved = false
            fun refresh(selected: String) {
                val index = findIndexOfValue(selected)
                check(index >= 0) { "未知评论设置：$keyName=$selected" }
                summary = entries[index].toString() + if (help == null) "" else "\n$help"
            }
            setOnPreferenceChangeListener { _, newValue -> refresh(newValue.toString()); true }
        }
        addPreference(pref)
        pref.summary = pref.entry.toString() + if (help == null) "" else "\n$help"
    }
    val cfg = ReviewDownloadConfig
    group("评论下载 · 抓取引擎").choice(cfg.ENGINE, R.string.review_download_engine,
        R.array.review_download_engine_entries, R.array.review_download_engine_values, "auto",
        getString(R.string.review_download_engine_summary))
    group("评论下载 · 评论数据").apply {
        number(Number.DATA, "数据协议请求使用此配额，与回复、资源、网页分开；当前支持 idea_comment 段评，章评、书评走网页。")
        number(Number.PAGE_SIZE, "单次请求的条数参数；仍按服务端游标取完全部页面。服务端实际返回数量会记录。")
    }
    group("评论下载 · 楼中楼").apply {
        number(Number.REPLIES, "所有楼中楼数据请求共享此配额，不占用评论数据请求配额。")
        number(Number.REPLY_SIZE, "回复单页请求条数；不限制回复总量。")
        toggle(cfg.REUSE_REPLIES, R.string.review_download_reuse_replies,
            "接口内嵌回复已完整时直接保存；关闭后从回复接口重新取全。")
    }
    group("评论下载 · 网页快照").apply {
        number(Number.PAGES, "同时加载、展开的传统网页数量；结构化数据不占用此阶段。")
        number(Number.STABLE_INTERVAL, "只作用于传统网页展开和稳定检测；0 表示不主动等待。")
        number(Number.STABLE_ROUNDS, "传统网页连续多少轮内容稳定才结束；数据接口按明确完成状态结束。")
    }
    group("评论下载 · 离线资源").apply {
        number(Number.RESOURCES, "全局资源请求并发，含头像、评论图片、样式表和字体；不再按每个页面分别建配额。")
        toggle(cfg.REUSE_RESOURCES, R.string.review_download_reuse_resources,
            "按准确 URL 和图片处理要求复用资源；关闭后重新下载。失败不会当作缓存命中。")
    }
    group("评论下载 · 重处理").number(Number.HEAVY,
        "资源改写、图片压缩、HTML 序列化与快照提交；网络等待不占用此配额。")
    group("评论下载 · 超时与重试").apply {
        number(Number.PAGE_TIMEOUT, "传统网页加载和展开的总时间；0 表示不设超时。网页失败直接报告，不自动换引擎。")
        number(Number.DATA_TIMEOUT, "一次数据请求（含正文读取）的总时间；0 表示不设超时。")
        number(Number.RESOURCE_TIMEOUT, "一次资源请求（含文件传输）的总时间；0 表示不设超时。")
        number(Number.RETRIES, "数据及资源 HTTP 请求初次失败后额外重试次数；0 不重试。每次失败、重试均记录，取消不重试。")
        number(Number.RETRY_INTERVAL, "基础等待时间；0 不主动等待。")
        choice(cfg.BACKOFF, R.string.review_download_backoff,
            R.array.review_download_backoff_entries, R.array.review_download_backoff_values, "fixed")
    }
    group("评论下载 · 完整性判定").apply {
        val help = getString(R.string.review_download_requirement_summary)
        toggle(cfg.REQUIRE_AVATARS, R.string.review_download_require_avatars, help)
        toggle(cfg.REQUIRE_IMAGES, R.string.review_download_require_images, help)
        toggle(cfg.REQUIRE_CSS, R.string.review_download_require_css, help)
        toggle(cfg.REQUIRE_FONTS, R.string.review_download_require_fonts,
            "同一字体按 CSS 候选关系取可用格式；全部候选失败才触发此项。$help")
    }
}
