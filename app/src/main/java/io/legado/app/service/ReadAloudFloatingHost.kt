package io.legado.app.service

import android.os.IBinder
import android.view.WindowManager

data class ReadAloudFloatingHost(
    val windowManager: WindowManager,
    val token: IBinder,
)
