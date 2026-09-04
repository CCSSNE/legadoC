package io.legado.app.utils

import android.app.Activity
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isAudio
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

/**
 * 回退设置：音频书直进沉浸式听书页（AudioPlayActivity）的 handoff 模式。
 * [ReadBookActivity.DIRECT_AUDIO_PLAY_ALL]=所有音频书直进；
 * [ReadBookActivity.DIRECT_AUDIO_PLAY_IF_NO_SUBTITLE]=当前章节无字幕才直进；
 * null=正常进阅读页。优先级：全部 > 无字幕。
 * 判定收敛到此一处，所有打开书籍入口共用；真正的字幕有无在阅读页
 * upContent（章节显示完成）时再确认，ReadBookActivity 消费此标记。
 */
fun Book.directAudioPlayMode(): String? {
    if (!isAudio) return null
    if (AppConfig.audioBookDirectAudioPlayAll) return ReadBookActivity.DIRECT_AUDIO_PLAY_ALL
    if (AppConfig.audioBookDirectAudioPlayNoSubtitle) return ReadBookActivity.DIRECT_AUDIO_PLAY_IF_NO_SUBTITLE
    return null
}
