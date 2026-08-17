package io.legado.app.utils

import android.app.Activity
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.video.VideoPlayerActivity

internal enum class BookReadingDestination {
    READER,
    MANGA,
    VIDEO,
}

internal fun Book.defaultReadingDestination(showMangaUi: Boolean): BookReadingDestination {
    return when {
        isVideo -> BookReadingDestination.VIDEO
        !isLocal && isImage && showMangaUi -> BookReadingDestination.MANGA
        else -> BookReadingDestination.READER
    }
}

fun Book.defaultReadingActivityClass(): Class<out Activity> {
    return when (defaultReadingDestination(AppConfig.showMangaUi)) {
        BookReadingDestination.READER -> ReadBookActivity::class.java
        BookReadingDestination.MANGA -> ReadMangaActivity::class.java
        BookReadingDestination.VIDEO -> VideoPlayerActivity::class.java
    }
}
