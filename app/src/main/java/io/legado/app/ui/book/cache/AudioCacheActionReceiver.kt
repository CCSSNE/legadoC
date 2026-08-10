package io.legado.app.ui.book.cache

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AudioCacheActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val bookUrl = intent.getStringExtra(AudioCacheTaskManager.EXTRA_BOOK_URL) ?: return
        AudioCacheTaskManager.togglePause(bookUrl)
    }

}
