package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.receiver.BluetoothReadAloudSkip
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.getPrefInt

object BluetoothKeyMapping {

    const val ACTION_NONE = 0
    const val ACTION_PAGE_BOOKMARK = 1
    const val ACTION_PREVIOUS_PAGE = 2
    const val ACTION_NEXT_PAGE = 3
    const val ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH = 4
    const val ACTION_READ_ALOUD_NEXT_PARAGRAPH = 5
    const val ACTION_PREVIOUS_CHAPTER = 6
    const val ACTION_NEXT_CHAPTER = 7
    const val ACTION_READ_ALOUD_TOGGLE = 8

    data class Key(
        val preferenceKey: String,
        val playPreferenceKey: String,
        @StringRes val label: Int,
        val keyCode: Int,
        val isMediaKey: Boolean,
        val defaultAction: Int = ACTION_NONE,
        val defaultPlayAction: Int = ACTION_NONE,
        /**
         * 无映射时是否吞掉按键：蓝牙媒体键吞（沿用旧语义），
         * 音量键放行给系统调音量。
         */
        val consumeWhenNone: Boolean = true
    )

    val keys = listOf(
        Key(
            PreferKey.bluetoothPreviousTrackAction,
            PreferKey.bluetoothPreviousTrackActionOnPlay,
            R.string.bluetooth_key_previous_track,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            true,
            defaultPlayAction = ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH
        ),
        Key(
            PreferKey.bluetoothRewindAction,
            PreferKey.bluetoothRewindActionOnPlay,
            R.string.bluetooth_key_rewind,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            true,
            ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH,
            ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH
        ),
        Key(
            PreferKey.bluetoothNextTrackAction,
            PreferKey.bluetoothNextTrackActionOnPlay,
            R.string.bluetooth_key_next_track,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            true,
            defaultPlayAction = ACTION_READ_ALOUD_NEXT_PARAGRAPH
        ),
        Key(
            PreferKey.bluetoothFastForwardAction,
            PreferKey.bluetoothFastForwardActionOnPlay,
            R.string.bluetooth_key_fast_forward,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            true,
            ACTION_READ_ALOUD_NEXT_PARAGRAPH,
            ACTION_READ_ALOUD_NEXT_PARAGRAPH
        ),
        Key(
            PreferKey.bluetoothPlayPauseAction,
            PreferKey.bluetoothPlayPauseActionOnPlay,
            R.string.bluetooth_key_play_pause,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            true
        ),
        Key(
            PreferKey.bluetoothVolumeUpAction,
            PreferKey.bluetoothVolumeUpActionOnPlay,
            R.string.bluetooth_key_volume_up,
            KeyEvent.KEYCODE_VOLUME_UP,
            false,
            ACTION_PREVIOUS_PAGE,
            ACTION_NONE,
            consumeWhenNone = false
        ),
        Key(
            PreferKey.bluetoothVolumeDownAction,
            PreferKey.bluetoothVolumeDownActionOnPlay,
            R.string.bluetooth_key_volume_down,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            false,
            ACTION_NEXT_PAGE,
            ACTION_NONE,
            consumeWhenNone = false
        )
    )

    fun keyForKeyCode(keyCode: Int): Key? =
        keys.firstOrNull { it.keyCode == keyCode }
            ?: when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> keys.first {
                    it.preferenceKey == PreferKey.bluetoothPlayPauseAction
                }

                else -> null
            }

    fun preferenceForKeyCode(keyCode: Int): String? = keyForKeyCode(keyCode)?.preferenceKey

    /**
     * 按当前是否在朗读取对应格的动作。
     * 朗读中 = [BaseReadAloudService.isPlay]，暂停了算阅读时。
     */
    fun slotAction(key: Key, context: Context): Int {
        return if (BaseReadAloudService.isPlay()) {
            context.getPrefInt(key.playPreferenceKey, key.defaultPlayAction)
        } else {
            context.getPrefInt(key.preferenceKey, key.defaultAction)
        }
    }

    /** 只有阅读界面能做的事，后台调用方传 null，做不了就返回 false。 */
    interface UiHandler {
        fun pageBookmark(): Boolean
        fun previousPage()
        fun nextPage()
        fun toggleReadAloud()
    }

    /**
     * 唯一动作执行入口，前台 Activity 与后台广播复用。
     * 返回 true 表示已消费。
     */
    fun dispatch(action: Int, context: Context, ui: UiHandler?): Boolean {
        when (action) {
            ACTION_NONE -> return false
            ACTION_PAGE_BOOKMARK -> return ui?.pageBookmark() ?: false
            ACTION_PREVIOUS_PAGE -> {
                ui ?: return false
                ui.previousPage()
                return true
            }

            ACTION_NEXT_PAGE -> {
                ui ?: return false
                ui.nextPage()
                return true
            }

            ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH -> {
                BluetoothReadAloudSkip.enqueue(context, BluetoothReadAloudSkip.Direction.PREVIOUS)
                return true
            }

            ACTION_READ_ALOUD_NEXT_PARAGRAPH -> {
                BluetoothReadAloudSkip.enqueue(context, BluetoothReadAloudSkip.Direction.NEXT)
                return true
            }

            ACTION_PREVIOUS_CHAPTER -> {
                if (BaseReadAloudService.isRun) {
                    ReadAloud.prevChapter(context)
                } else {
                    ReadBook.moveToPrevChapter(true)
                }
                return true
            }

            ACTION_NEXT_CHAPTER -> {
                if (BaseReadAloudService.isRun) {
                    ReadAloud.nextChapter(context)
                } else {
                    ReadBook.moveToNextChapter(true)
                }
                return true
            }

            ACTION_READ_ALOUD_TOGGLE -> {
                if (BaseReadAloudService.isRun) {
                    if (BaseReadAloudService.isPlay()) {
                        ReadAloud.pause(context)
                    } else {
                        ReadAloud.resume(context)
                    }
                    return true
                }
                if (ui == null) {
                    return false
                }
                ui.toggleReadAloud()
                return true
            }

            else -> error("Unknown Bluetooth key action: $action")
        }
    }
}
