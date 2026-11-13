package com.arena.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local, offline-first data. Nothing here is ever synced off-device except
 * explicit leaderboard/model refreshes (which contain no user content).
 */

/** One Battle Mode vote, cached for offline leaderboard/history viewing. */
@Entity(tableName = "votes")
data class VoteEntity(
    @PrimaryKey val id: String,
    val battleId: String,
    /** A | B | TIE | BOTH_BAD */
    val choice: String,
    val modelAId: String?,
    val modelBId: String?,
    val generationId: String?,
    val votedAtEpochMs: Long,
)

/** Cached leaderboard row; category is TEXT | CODE | IMAGE. */
@Entity(tableName = "leaderboard_entries")
data class LeaderboardEntryEntity(
    /** Composite key "$category:$modelId". */
    @PrimaryKey val key: String,
    val category: String,
    val modelId: String,
    val displayName: String,
    val provider: String?,
    val rank: Int,
    val rating: Double,
    val votes: Int,
    val updatedAtEpochMs: Long,
)

/** Cached model catalog row (seeded locally, refreshed from /api/models). */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val provider: String?,
    /** TEXT | IMAGE | CODE | SEARCH | VIDEO */
    val modality: String,
    val arenaRank: Int?,
    val votes: Int?,
    val contextWindow: Int?,
    val description: String?,
)

/** Conversation shell for offline last-history viewing. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Battle | Direct | SideBySide | Agent */
    val mode: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** user | assistant | system */
    val role: String,
    val content: String,
    val modelId: String?,
    /** 0 = primary pane, 1 = secondary pane (battle / side-by-side). */
    val slot: Int,
    val createdAtEpochMs: Long,
)

/** Local interaction counts powering adaptive home + recommendations. */
@Entity(tableName = "usage_events")
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** BATTLE | DIRECT | SIDE_BY_SIDE | AGENT */
    val mode: String,
    val modelId: String?,
    /** Optional task hint: write | summarize | code | image | search | chat … */
    val taskType: String?,
    val atEpochMs: Long,
)

/** Long-running agent task header (steps stream in over the bridge). */
@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** PENDING | RUNNING | DONE | FAILED */
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
