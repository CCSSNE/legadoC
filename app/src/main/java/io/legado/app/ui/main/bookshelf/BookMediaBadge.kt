package io.legado.app.ui.main.bookshelf

import android.view.View
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isAudio

internal fun View.bindBookMediaBadge(book: Book) {
    val audioBadge = requireNotNull(findViewById<View>(R.id.iv_audio)) {
        "Book item layout must declare iv_audio"
    }
    audioBadge.alpha = if (book.isAudio) AUDIO_BADGE_ALPHA else 1f
    audioBadge.isVisible = book.isAudio
}

private const val AUDIO_BADGE_ALPHA = 0.82f
