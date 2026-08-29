package io.legado.app.help.tts

import io.legado.app.constant.PreferKey
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import splitties.init.appCtx

/**
 * 按书保存角色发现与自动选音开关。
 * 开关只控制后续自动操作，不删除已有角色或发音人绑定。
 */
object BookTtsAutomationConfig {

    data class Settings(
        val autoCreateRoles: Boolean = true,
        val autoAssignVoices: Boolean = true
    )

    fun get(workKey: String): Settings = Settings(
        autoCreateRoles = appCtx.getPrefBoolean(key(ROLE_KEY_PREFIX, workKey), true),
        autoAssignVoices = appCtx.getPrefBoolean(key(VOICE_KEY_PREFIX, workKey), true)
    )

    fun setAutoCreateRoles(workKey: String, enabled: Boolean) {
        appCtx.putPrefBoolean(key(ROLE_KEY_PREFIX, workKey), enabled)
    }

    fun setAutoAssignVoices(workKey: String, enabled: Boolean) {
        appCtx.putPrefBoolean(key(VOICE_KEY_PREFIX, workKey), enabled)
    }

    fun workKeyOf(bookName: String, bookAuthor: String): String {
        return MD5Utils.md5Encode16("${bookName.trim()}${bookAuthor.trim()}")
    }

    private fun key(prefix: String, workKey: String): String {
        return "$prefix:$workKey"
    }

    private const val ROLE_KEY_PREFIX = "bookTtsAutoCreateRoles"
    private const val VOICE_KEY_PREFIX = "bookTtsAutoAssignVoices"
}
