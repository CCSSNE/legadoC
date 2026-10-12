package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.KeyEvent
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.config.BluetoothKeyMapping
import io.legado.app.utils.LogUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.postEvent


/**
 * Created by GKF on 2018/1/6.
 * 监听耳机键
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (handleIntent(context, intent) && isOrderedBroadcast) {
            abortBroadcast()
        }
    }

    companion object {

        private const val TAG = "MediaButtonReceiver"

        fun handleIntent(context: Context, intent: Intent): Boolean {
            val intentAction = intent.action
            if (Intent.ACTION_MEDIA_BUTTON == intentAction) {
                @Suppress("DEPRECATION")
                val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return false
                val keycode: Int = keyEvent.keyCode
                val action: Int = keyEvent.action
                if (action == KeyEvent.ACTION_DOWN) {
                    LogUtils.d(TAG, "Receive mediaButton event, keycode:$keycode")
                    if (handleReaderMediaKey(context, keycode)) {
                        return true
                    }
                    when (keycode) {
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (context.getPrefBoolean("mediaButtonPerNext", false)) {
                                if (BaseReadAloudService.isRun) {
                                    ReadAloud.prevChapter(context)
                                } else {
                                    ReadBook.moveToPrevChapter(true)
                                }
                            } else {
                                ReadAloud.prevParagraph(context)
                            }
                        }

                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            if (context.getPrefBoolean("mediaButtonPerNext", false)) {
                                if (BaseReadAloudService.isRun) {
                                    ReadAloud.nextChapter(context)
                                } else {
                                    ReadBook.moveToNextChapter(true)
                                }
                            } else {
                                ReadAloud.nextParagraph(context)
                            }
                        }

                        else -> readAloud(context)
                    }
                }
            }
            return true
        }

        fun readAloud(context: Context, isMediaKey: Boolean = true) {
            when {
                BaseReadAloudService.isRun -> {
                    if (BaseReadAloudService.isPlay()) {
                        ReadAloud.pause(context)
                    } else {
                        ReadAloud.resume(context)
                    }
                }

                isMediaKey && !AppConfig.readAloudByMediaButton -> {
                    // break
                }

                LifecycleHelp.isExistActivity(ReadBookActivity::class.java) ->
                    postEvent(EventBus.MEDIA_BUTTON, true)

                LifecycleHelp.isExistActivity(AudioPlayActivity::class.java) ->
                    postEvent(EventBus.MEDIA_BUTTON, true)

                else -> if (AppConfig.mediaButtonOnExit || LifecycleHelp.activitySize() > 0 || !isMediaKey) {
                    ReadAloud.upReadAloudClass()
                    if (ReadBook.book != null) {
                        ReadBook.readAloud()
                    } else {
                        appDb.bookDao.lastReadBook?.let {
                            ReadBook.resetData(it)
                            ReadBook.clearTextChapter()
                            ReadBook.loadContent(false) {
                                ReadBook.readAloud()
                            }
                        }
                    }
                }
            }
        }

        fun handleReaderMediaKey(context: Context, keyCode: Int): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenIsInteractive = powerManager.isInteractive
            val activity = ReadBookActivity.activeActivity()
                ?: if (!screenIsInteractive) ReadBookActivity.readerInstance() else null
            if (activity?.handleBluetoothMediaKey(keyCode) == true) return true

            val key = BluetoothKeyMapping.keyForKeyCode(keyCode) ?: return false
            return when (context.getPrefInt(key.preferenceKey, key.defaultAction)) {
                BluetoothKeyMapping.ACTION_READ_ALOUD_PREVIOUS_PARAGRAPH -> {
                    BluetoothReadAloudSkip.enqueue(
                        context,
                        BluetoothReadAloudSkip.Direction.PREVIOUS
                    )
                    true
                }

                BluetoothKeyMapping.ACTION_READ_ALOUD_NEXT_PARAGRAPH -> {
                    BluetoothReadAloudSkip.enqueue(
                        context,
                        BluetoothReadAloudSkip.Direction.NEXT
                    )
                    true
                }

                BluetoothKeyMapping.ACTION_NONE -> keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD

                else -> false
            }
        }
    }

}
