package com.arena.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vote: VoteEntity)

    @Query("SELECT * FROM votes ORDER BY votedAtEpochMs DESC")
    fun observeVotes(): Flow<List<VoteEntity>>

    @Query("SELECT COUNT(*) FROM votes WHERE choice = :choice")
    fun observeCountForChoice(choice: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM votes")
    suspend fun count(): Int
}

@Dao
interface LeaderboardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<LeaderboardEntryEntity>)

    @Query("SELECT * FROM leaderboard_entries WHERE category = :category ORDER BY rank ASC")
    fun observeCategory(category: String): Flow<List<LeaderboardEntryEntity>>

    @Query("DELETE FROM leaderboard_entries WHERE category = :category")
    suspend fun clearCategory(category: String)

    @Query("SELECT MAX(updatedAtEpochMs) FROM leaderboard_entries WHERE category = :category")
    suspend fun lastUpdated(category: String): Long?
}

@Dao
interface ModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ModelEntity>)

    @Query("SELECT * FROM models ORDER BY arenaRank IS NULL, arenaRank ASC, displayName ASC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query(
        """
        SELECT * FROM models
        WHERE (:modality IS NULL OR modality = :modality)
          AND (displayName LIKE '%' || :query || '%'
               OR provider LIKE '%' || :query || '%'
               OR id LIKE '%' || :query || '%')
        ORDER BY arenaRank IS NULL, arenaRank ASC, displayName ASC
        """,
    )
    fun search(query: String, modality: String?): Flow<List<ModelEntity>>

    @Query("SELECT COUNT(*) FROM models")
    suspend fun count(): Int

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ModelEntity?
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAtEpochMs DESC LIMIT 1")
    fun observeLatestConversation(): Flow<ConversationEntity?>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = (SELECT id FROM conversations ORDER BY updatedAtEpochMs DESC LIMIT 1)
        ORDER BY createdAtEpochMs ASC
        """,
    )
    fun observeLatestMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    suspend fun messagesSnapshot(conversationId: String): List<MessageEntity>
}

@Dao
interface UsageEventDao {
    @Insert
    suspend fun insert(event: UsageEventEntity)

    @Query("SELECT * FROM usage_events ORDER BY atEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<UsageEventEntity>>

    @Query("SELECT mode, COUNT(*) AS n FROM usage_events GROUP BY mode")
    fun observeModeFrequency(): Flow<List<ModeFrequency>>

    @Query(
        """
        SELECT modelId, COUNT(*) AS n FROM usage_events
        WHERE modelId IS NOT NULL
        GROUP BY modelId ORDER BY n DESC
        """,
    )
    fun observeModelFrequency(): Flow<List<ModelFrequency>>

    @Query("SELECT COUNT(*) FROM usage_events")
    suspend fun count(): Int
}

data class ModeFrequency(val mode: String, val n: Int)
data class ModelFrequency(val modelId: String, val n: Int)

@Dao
interface AgentTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: AgentTaskEntity)

    @Query("SELECT * FROM agent_tasks ORDER BY createdAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<AgentTaskEntity?>

    @Query("SELECT * FROM agent_tasks ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<AgentTaskEntity>>
}
