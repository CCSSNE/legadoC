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

    /**
     * 面板模式判定（纯派生，无存储）：
     * - 强制追页 ON：翻页即双击换段，视角永远在朗读页，
     *   “回原进度/从本页读”入口整体无效，只保留播放控制。
     * - viewBehindAloud（显示页≠朗读页，由调用方现算）：
     *   显示与朗读脱节，提供 PAGE_ACTION 面板（回原进度/从本页读）。
     */
    fun readerPanelMode(isRunning: Boolean, viewBehindAloud: Boolean): ReaderPanelMode {
        if (!isRunning || readerMenuVisible || readAloudFloatingVisible) {
            return ReaderPanelMode.HIDDEN
        }
        if (appCtx.getPrefBoolean(PreferKey.forcePageFollow)) {
            return ReaderPanelMode.PLAYBACK
        }
        return if (viewBehindAloud) ReaderPanelMode.PAGE_ACTION else ReaderPanelMode.PLAYBACK
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
