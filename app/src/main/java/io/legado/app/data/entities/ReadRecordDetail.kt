package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 按书×按日阅读时长明细（适配 HapeLee/legado-with-MD3 ba33e7cdd 的 readRecordDetail 模型，
 * 砍掉 readWords/firstReadTime——本仓计时段无字数与起读时间管道，诚实最小化）。
 * 计时总体系（ReadBook.upReadTime）不变，本表只旁路聚合。
 */
@Entity(tableName = "readRecordDetail", primaryKeys = ["deviceId", "bookName", "date"])
data class ReadRecordDetail(
    var deviceId: String = "",
    var bookName: String = "",
    var date: String = "",
    @ColumnInfo(defaultValue = "0")
    var readTime: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var lastRead: Long = System.currentTimeMillis()
)
