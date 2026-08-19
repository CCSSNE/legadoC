package io.legado.app.ui.book

import android.view.View
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.BookType

internal fun View.bindBookMediaBadge(bookType: Int) {
    val audioBadge = requireNotNull(findViewById<View>(R.id.iv_audio)) {
        "Book item layout must declare iv_audio"
    }
    val isAudio = bookType and BookType.audio > 0
    audioBadge.alpha = if (isAudio) AUDIO_BADGE_ALPHA else 1f
    audioBadge.isVisible = isAudio
}

private const val AUDIO_BADGE_ALPHA = 0.82f
