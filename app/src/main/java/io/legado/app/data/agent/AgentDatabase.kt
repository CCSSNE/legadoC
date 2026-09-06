package io.legado.app.data.agent

import androidx.room.*
import androidx.annotation.Keep
import splitties.init.appCtx

@Entity(tableName = "documents", primaryKeys = ["namespace", "key"])
@Keep
data class AgentDocument(
    val namespace: String, val key: String, val json: String,
    val revision: Long = 1, val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "runs", indices = [Index("sessionId")])
@Keep
data class AgentRun(
    @PrimaryKey val id: String, val sessionId: String, val turnId: String,
    val pluginId: String, val revision: String, val input: String,
    val state: String = "running", val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(), val error: String? = null
)

@Entity(tableName = "events", indices = [Index("runId")])
@Keep
data class AgentEvent(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val runId: String, val type: String, val json: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages", indices = [Index("sessionId"), Index("runId")])
@Keep
data class AgentMessage(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val sessionId: String, val turnId: String, val runId: String, val json: String
)

@Entity(tableName = "vectors", primaryKeys = ["namespace", "documentKey"], indices = [Index("space")])
@Keep
data class AgentVector(
    val namespace: String, val documentKey: String, val space: String,
    val dimension: Int, val contentRevision: Long, val json: String
)

@Dao
interface AgentDao {
    @Query("SELECT * FROM documents WHERE namespace = :namespace AND `key` = :key")
    fun document(namespace: String, key: String): AgentDocument?
    @Query("SELECT * FROM documents WHERE namespace = :namespace ORDER BY `key`")
    fun documents(namespace: String): List<AgentDocument>
    @Query("SELECT * FROM documents ORDER BY namespace, `key`")
    fun allDocuments(): List<AgentDocument>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(document: AgentDocument)
    @Query("DELETE FROM documents WHERE namespace = :namespace AND `key` = :key")
    fun deleteDocument(namespace: String, key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(run: AgentRun)
    @Query("SELECT * FROM runs WHERE id = :id")
    fun run(id: String): AgentRun?
    @Query("SELECT * FROM runs ORDER BY startedAt DESC")
    fun runs(): List<AgentRun>
    @Query("UPDATE runs SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    fun state(id: String, state: String, error: String?, now: Long = System.currentTimeMillis())
    @Query("SELECT * FROM runs WHERE state IN ('running', 'paused', 'waiting_input')")
    fun unfinished(): List<AgentRun>
    @Insert
    fun append(event: AgentEvent): Long
    @Query("SELECT * FROM events WHERE runId = :runId AND sequence > :after ORDER BY sequence")
    fun events(runId: String, after: Long = 0): List<AgentEvent>
    @Query("SELECT * FROM events ORDER BY sequence")
    fun allEvents(): List<AgentEvent>
    @Insert
    fun append(message: AgentMessage): Long
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY sequence")
    fun messages(sessionId: String): List<AgentMessage>
    @Query("SELECT * FROM messages ORDER BY sequence")
    fun allMessages(): List<AgentMessage>
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    fun deleteMessages(sessionId: String)
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND runId = :runId")
    fun deleteRunMessages(sessionId: String, runId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(vector: AgentVector)
    @Query("SELECT * FROM vectors WHERE namespace = :namespace")
    fun vectors(namespace: String): List<AgentVector>
    @Query("SELECT * FROM vectors")
    fun allVectors(): List<AgentVector>
    @Query("DELETE FROM vectors WHERE namespace = :namespace AND documentKey = :key")
    fun deleteVector(namespace: String, key: String)
    @Query("DELETE FROM vectors WHERE namespace = :namespace")
    fun clearVectors(namespace: String)
    @Query("DELETE FROM documents")
    fun clearDocuments()
    @Query("DELETE FROM runs")
    fun clearRuns()
    @Query("DELETE FROM events")
    fun clearEvents()
    @Query("DELETE FROM messages")
    fun clearMessages()
    @Query("DELETE FROM vectors")
    fun clearAllVectors()
}

@Database(entities = [AgentDocument::class, AgentRun::class, AgentEvent::class,
    AgentMessage::class, AgentVector::class], version = 1, exportSchema = false)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    companion object {
        val instance: AgentDatabase by lazy {
            Room.databaseBuilder(appCtx, AgentDatabase::class.java, "agent.db")
                .build()
        }
    }
}
