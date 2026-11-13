package com.arena.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Callback
import androidx.sqlite.db.SupportSQLiteDatabase
import com.arena.core.data.model.ModelSeed

/**
 * Offline cache for votes, leaderboards, conversations, catalog, and usage
 * statistics. Versioned schema; destructive migration is NOT used — schema
 * changes ship with explicit migrations so offline data survives upgrades.
 */
@Database(
    entities = [
        VoteEntity::class,
        LeaderboardEntryEntity::class,
        ModelEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        UsageEventEntity::class,
        AgentTaskEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ArenaDatabase : RoomDatabase() {
    abstract fun voteDao(): VoteDao
    abstract fun leaderboardDao(): LeaderboardDao
    abstract fun modelDao(): ModelDao
    abstract fun conversationDao(): ConversationDao
    abstract fun usageEventDao(): UsageEventDao
    abstract fun agentTaskDao(): AgentTaskDao

    companion object {
        const val NAME = "arena.db"

        /**
         * Seeds the local model catalog on first create so the model picker
         * and offline fallback work before any network call. The seed is a
         * curated subset; the full 300+ catalog arrives via
         * `ModelRepository.refresh()`.
         */
        fun seedCallback(): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Room invokes onCreate on its own background query executor;
                // seed synchronously inside a single transaction.
                val stmt = db.compileStatement(
                    """
                    INSERT OR REPLACE INTO models
                    (id, displayName, provider, modality, arenaRank, votes, contextWindow, description)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                )
                db.beginTransaction()
                try {
                    ModelSeed.ALL.forEach { m ->
                        stmt.clearBindings()
                        stmt.bindString(1, m.id)
                        stmt.bindString(2, m.displayName)
                        if (m.provider != null) stmt.bindString(3, m.provider) else stmt.bindNull(3)
                        stmt.bindString(4, m.modality)
                        if (m.arenaRank != null) stmt.bindLong(5, m.arenaRank.toLong()) else stmt.bindNull(5)
                        if (m.votes != null) stmt.bindLong(6, m.votes.toLong()) else stmt.bindNull(6)
                        if (m.contextWindow != null) stmt.bindLong(7, m.contextWindow.toLong()) else stmt.bindNull(7)
                        if (m.description != null) stmt.bindString(8, m.description) else stmt.bindNull(8)
                        stmt.executeInsert()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }
}
