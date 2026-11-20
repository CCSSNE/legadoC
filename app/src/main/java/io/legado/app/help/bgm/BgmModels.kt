package io.legado.app.help.bgm

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * 听书背景音乐设置（适配 joestar817/legado_NG BgmPlaybackController/BgmModels，
 * 砍掉平台曲库/试听/owner 机制——最小版只做本地音频文件/URL 队列）。
 * 设置持久化：bgmSettings 存曲目 JSON，bgmEnabled/bgmVolume/bgmRepeatMode 各自独立键。
 */
enum class BgmRepeatMode(val preferenceValue: String) {
    LIST("0"),
    ONE("1"),
    SHUFFLE("2");

    companion object {
        fun fromPreference(value: String?): BgmRepeatMode {
            return entries.firstOrNull { it.preferenceValue == value } ?: LIST
        }
    }
}

data class BgmSettings(
    val enabled: Boolean = false,
    val volume: Float = 0.15f,
    val repeatMode: BgmRepeatMode = BgmRepeatMode.LIST,
    val tracks: List<String> = emptyList()
)

object BgmSettingsStore {

    fun read(): BgmSettings {
        val tracks = appCtx.getPrefString(PreferKey.bgmSettings)
            ?.let { GSON.fromJsonArray<String>(it).getOrNull() }
            .orEmpty()
        return BgmSettings(
            enabled = appCtx.getPrefBoolean(PreferKey.bgmEnabled, false),
            volume = appCtx.getPrefInt(PreferKey.bgmVolume, 15) / 100f,
            repeatMode = BgmRepeatMode.fromPreference(appCtx.getPrefString(PreferKey.bgmRepeatMode)),
            tracks = tracks
        )
    }

    fun write(settings: BgmSettings) {
        appCtx.putPrefBoolean(PreferKey.bgmEnabled, settings.enabled)
        appCtx.putPrefInt(PreferKey.bgmVolume, (settings.volume.coerceIn(0f, 1f) * 100).toInt())
        appCtx.putPrefString(PreferKey.bgmRepeatMode, settings.repeatMode.preferenceValue)
        appCtx.putPrefString(PreferKey.bgmSettings, GSON.toJson(settings.tracks))
    }

    /** SAF 追加曲目：content:// 需持久化读权限 */
    fun addTracks(uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        val settings = read()
        val added = ArrayList<String>(settings.tracks)
        var addedCount = 0
        uris.forEach { uri ->
            runCatching {
                appCtx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (added.add(uri.toString())) {
                addedCount++
            }
        }
        if (addedCount > 0) {
            write(settings.copy(tracks = added))
        }
        return addedCount
    }

    fun removeTrack(index: Int): BgmSettings {
        val settings = read()
        if (index !in settings.tracks.indices) {
            return settings
        }
        val tracks = settings.tracks.toMutableList()
        val removed = tracks.removeAt(index)
        runCatching {
            appCtx.contentResolver.releasePersistableUriPermission(
                Uri.parse(removed),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val next = settings.copy(tracks = tracks)
        write(next)
        return next
    }

    fun tracksSummary(): String {
        val tracks = read().tracks
        return if (tracks.isEmpty()) {
            appCtx.getString(io.legado.app.R.string.bgm_pick_tracks_summary)
        } else {
            appCtx.getString(io.legado.app.R.string.bgm_picked_count, tracks.size)
        }
    }
}
