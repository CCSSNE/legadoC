package io.legado.app.model

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
    private var audioPlayerReturnPending = false

    fun setReadAloudDialogVisible(visible: Boolean) {
        readAloudDialogVisible = visible
    }

    fun readerPanelMode(isRunning: Boolean, pageDetached: Boolean): ReaderPanelMode {
        if (!isRunning || readAloudDialogVisible) return ReaderPanelMode.HIDDEN
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
