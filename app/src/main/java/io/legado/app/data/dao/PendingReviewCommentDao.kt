package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.PendingReviewComment

@Dao
interface PendingReviewCommentDao {

    @Insert
    suspend fun insert(item: PendingReviewComment): Long

    @Update
    suspend fun update(item: PendingReviewComment)

    @Delete
    suspend fun delete(item: PendingReviewComment)

    @Query("select * from pending_review_comments order by id")
    suspend fun all(): List<PendingReviewComment>

    @Query("select * from pending_review_comments where status in (0, 3) order by id")
    suspend fun sendable(): List<PendingReviewComment>

    @Query(
        """select * from pending_review_comments
        where status = 1 limit 1"""
    )
    suspend fun firstSending(): PendingReviewComment?

    @Query("select count(*) from pending_review_comments where status = :status")
    suspend fun countByStatus(status: Int): Int

    @Query("select count(*) from pending_review_comments")
    suspend fun countAll(): Int

    @Query("delete from pending_review_comments")
    suspend fun clearAll()

}
