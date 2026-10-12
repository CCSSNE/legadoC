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

    data class Key(
        val preferenceKey: String,
        @StringRes val label: Int,
        val mediaKeyCode: Int
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
            KeyEvent.KEYCODE_MEDIA_REWIND
        ),
        Key(
            PreferKey.bluetoothNextTrackAction,
            R.string.bluetooth_key_next_track,
            KeyEvent.KEYCODE_MEDIA_NEXT
        ),
        Key(
            PreferKey.bluetoothFastForwardAction,
            R.string.bluetooth_key_fast_forward,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        ),
        Key(
            PreferKey.bluetoothPlayPauseAction,
            R.string.bluetooth_key_play_pause,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        )
    )

    fun preferenceForKeyCode(keyCode: Int): String? =
        keys.firstOrNull { it.mediaKeyCode == keyCode }?.preferenceKey
            ?: when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> PreferKey.bluetoothPlayPauseAction

                else -> null
            }
}
