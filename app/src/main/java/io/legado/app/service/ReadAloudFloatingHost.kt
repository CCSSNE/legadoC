package io.legado.app.service

import android.os.IBinder
import android.view.WindowManager

data class ReadAloudFloatingHost(
    val windowManager: WindowManager,
    val token: IBinder,
)

/** The one state transition that moves the floating window into or out of the dialog layer. */
data class ReadAloudDialogFloatingPresentation(
    val host: ReadAloudFloatingHost?,
)
