package io.legado.app.model

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx

/** Shared presentation state for the reader-side read-aloud controls. */
object ReadAloudUiState {

    enum class ReaderPanelMode {
        HIDDEN,
        PLAYBACK,
        PAGE_ACTION,
    }

    @Volatile
    var readAloudDialogVisible: Boolean = false
        private set

    @Volatile
    var mainMenuVisible: Boolean = false
        private set

    val readerMenuVisible: Boolean
        get() = mainMenuVisible || readAloudDialogVisible

    @Volatile
    var readAloudFloatingVisible: Boolean = false
        private set

    @Volatile
    private var audioPlayerReturnPending = false

    fun setReadAloudDialogVisible(visible: Boolean) {
        readAloudDialogVisible = visible
    }

    fun setMainMenuVisible(visible: Boolean) {
        mainMenuVisible = visible
    }

    fun setReadAloudFloatingVisible(visible: Boolean) {
        readAloudFloatingVisible = visible
    }

    fun readerPanelMode(isRunning: Boolean, pageDetached: Boolean): ReaderPanelMode {
        if (!isRunning || readerMenuVisible || readAloudFloatingVisible) {
            return ReaderPanelMode.HIDDEN
        }
        // 按页朗读开启时，朗读只能从当前页开始（翻到哪页立刻切到哪页读），
        // 不存在“回原进度/从本页读”的概念，PAGE_ACTION 面板（悬浮窗及其页脚文字入口）
        // 整体无效化隐藏，只保留播放控制。
        if (appCtx.getPrefBoolean(PreferKey.readAloudByPage)) {
            return ReaderPanelMode.PLAYBACK
        }
        return if (pageDetached) ReaderPanelMode.PAGE_ACTION else ReaderPanelMode.PLAYBACK
    }

    fun markAudioPlayerReturn() {
        audioPlayerReturnPending = true
    }

    fun consumeAudioPlayerReturn(): Boolean {
        if (!audioPlayerReturnPending) return false
        audioPlayerReturnPending = false
        return true
    }
}
