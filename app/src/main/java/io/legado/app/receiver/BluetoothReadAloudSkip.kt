package io.legado.app.receiver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import java.util.ArrayDeque

/** Serializes repeated Bluetooth paragraph skips at the configured interval. */
object BluetoothReadAloudSkip {

    enum class Direction {
        PREVIOUS,
        NEXT
    }

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Direction>()
    private var applicationContext: Context? = null
    private var dispatchScheduled = false
    private var nextDispatchAt = 0L

    private val dispatch = Runnable { dispatchNext() }

    fun enqueue(context: Context, direction: Direction) {
        if (!BaseReadAloudService.isRun) return
        synchronized(lock) {
            applicationContext = context.applicationContext
            pending.addLast(direction)
            scheduleNextLocked()
        }
    }

    private fun scheduleNextLocked() {
        if (dispatchScheduled || pending.isEmpty()) return
        dispatchScheduled = true
        val delay = (nextDispatchAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(dispatch, delay)
    }

    private fun dispatchNext() {
        val item = synchronized(lock) {
            dispatchScheduled = false
            if (pending.isEmpty()) return
            val nextContext = applicationContext ?: return
            val direction = pending.removeFirst()
            nextDispatchAt = SystemClock.uptimeMillis() +
                AppConfig.bluetoothReadAloudSkipInterval.toLong()
            nextContext to direction
        }

        if (!BaseReadAloudService.isRun) {
            synchronized(lock) {
                pending.clear()
                applicationContext = null
                nextDispatchAt = 0L
            }
            return
        }

        when (item.second) {
            Direction.PREVIOUS -> ReadAloud.prevParagraph(item.first)
            Direction.NEXT -> ReadAloud.nextParagraph(item.first)
        }

        synchronized(lock) {
            scheduleNextLocked()
        }
    }
}
