package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.HighlightRule
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightRuleDao {

    @Query("SELECT * FROM highlight_rules ORDER BY sortOrder ASC")
    fun flowAll(): Flow<List<HighlightRule>>

    @get:Query("SELECT * FROM highlight_rules ORDER BY sortOrder ASC")
    val all: List<HighlightRule>

    @get:Query("SELECT MIN(sortOrder) FROM highlight_rules")
    val minOrder: Int

    @get:Query("SELECT MAX(sortOrder) FROM highlight_rules")
    val maxOrder: Int

    @Query("SELECT * FROM highlight_rules WHERE id = :id")
    fun findById(id: Long): HighlightRule?

    @Query(
        """SELECT * FROM highlight_rules WHERE isEnabled = 1
        AND (scope LIKE '%' || :name || '%' or scope LIKE '%' || :origin || '%' or scope is null or scope = '')
        and (excludeScope is null or (excludeScope not LIKE '%' || :name || '%' and excludeScope not LIKE '%' || :origin || '%'))
        order by sortOrder"""
    )
    fun findEnabledByScope(name: String, origin: String): List<HighlightRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg highlightRule: HighlightRule): List<Long>

    @Update
    fun update(vararg highlightRules: HighlightRule)

    @Delete
    fun delete(vararg highlightRules: HighlightRule)
}
