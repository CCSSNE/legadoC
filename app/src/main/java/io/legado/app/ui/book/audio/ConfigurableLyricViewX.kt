package io.legado.app.ui.book.audio

import android.content.Context
import android.util.AttributeSet
import com.dirror.lyricviewx.LyricViewX
import io.legado.app.help.config.AppConfig

/**
 * Project adapter for LyricViewX 1.3.2.
 *
 * That dependency keeps its timeline auto-center callback at 3000 ms and does
 * not expose the value. The callback is the only delayed post in the library,
 * so the adapter maps it to the shared read-aloud follow setting at the
 * integration boundary instead of keeping a second audio-only preference.
 */
class ConfigurableLyricViewX @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LyricViewX(context, attrs, defStyleAttr) {

    override fun postDelayed(action: Runnable, delayMillis: Long): Boolean {
        val configuredDelay = if (delayMillis == UPSTREAM_TIMELINE_KEEP_TIME) {
            AppConfig.readAloudScrollFollowTimeout.toLong()
        } else {
            delayMillis
        }
        return super.postDelayed(action, configuredDelay)
    }

    private companion object {
        const val UPSTREAM_TIMELINE_KEEP_TIME = 3000L
    }
}
