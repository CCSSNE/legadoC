package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 按书×按日阅读时长明细旁路（与 ReadRecordDailyHelper 同日期口径，同在
 * ReadBook.upReadTime 的 executor 内串行写入）。
 */
object ReadRecordDetailHelper {

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun record(bookName: String, readTime: Long, timestamp: Long) {
        if (readTime <= 0L || bookName.isBlank()) return
        val dateKey = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(dateFormatter)
        val device = AppConst.androidId
        val current = appDb.readRecordDetailDao.get(device, bookName, dateKey)
        val record = if (current == null) {
            ReadRecordDetail(
                deviceId = device,
                bookName = bookName,
                date = dateKey,
                readTime = readTime,
                lastRead = timestamp
            )
        } else {
            current.copy(
                readTime = current.readTime + readTime,
                lastRead = maxOf(current.lastRead, timestamp)
            )
        }
        appDb.readRecordDetailDao.insert(record)
    }
}
