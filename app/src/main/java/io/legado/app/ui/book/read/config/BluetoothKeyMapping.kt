package io.legado.app.ui.book.read.config

import android.view.KeyEvent
import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.PreferKey

object BluetoothKeyMapping {

    const val ACTION_NONE = 0
    const val ACTION_PAGE_BOOKMARK = 1
    const val ACTION_PREVIOUS_PAGE = 2
    const val ACTION_NEXT_PAGE = 3
    const val ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH = 4
    const val ACTION_READ_ALOUD_NEXT_PARAGRAPH = 5

    data class Key(
        val preferenceKey: String,
        @StringRes val label: Int,
        val mediaKeyCode: Int,
        val defaultAction: Int = ACTION_NONE
    )

    val keys = listOf(
        Key(
            PreferKey.bluetoothPreviousTrackAction,
            R.string.bluetooth_key_previous_track,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS
        ),
        Key(
            PreferKey.bluetoothRewindAction,
            R.string.bluetooth_key_rewind,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH
        ),
        Key(
            PreferKey.bluetoothNextTrackAction,
            R.string.bluetooth_key_next_track,
            KeyEvent.KEYCODE_MEDIA_NEXT
        ),
        Key(
            PreferKey.bluetoothFastForwardAction,
            R.string.bluetooth_key_fast_forward,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            ACTION_READ_ALOUD_NEXT_PARAGRAPH
        ),
        Key(
            PreferKey.bluetoothPlayPauseAction,
            R.string.bluetooth_key_play_pause,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        )
    )

    fun keyForKeyCode(keyCode: Int): Key? =
        keys.firstOrNull { it.mediaKeyCode == keyCode }
            ?: when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> keys.first {
                    it.preferenceKey == PreferKey.bluetoothPlayPauseAction
                }

                else -> null
            }

    fun preferenceForKeyCode(keyCode: Int): String? = keyForKeyCode(keyCode)?.preferenceKey
}
