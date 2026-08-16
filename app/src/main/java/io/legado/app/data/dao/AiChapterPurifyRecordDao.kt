package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiChapterPurifyRecord

@Dao
interface AiChapterPurifyRecordDao {

    @Query(
        "select * from ai_chapter_purify_records " +
            "where bookUrl = :bookUrl and chapterIndex = :chapterIndex limit 1"
    )
    fun get(bookUrl: String, chapterIndex: Int): AiChapterPurifyRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: AiChapterPurifyRecord)

    @Query("delete from ai_chapter_purify_records where bookUrl = :bookUrl")
    fun deleteByBookUrl(bookUrl: String)
}
