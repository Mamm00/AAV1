package com.arena.core.data.di

import android.content.Context
import androidx.room.Room
import com.arena.core.data.db.ArenaDatabase
import com.arena.core.data.db.AgentTaskDao
import com.arena.core.data.db.ConversationDao
import com.arena.core.data.db.LeaderboardDao
import com.arena.core.data.db.ModelDao
import com.arena.core.data.db.UsageEventDao
import com.arena.core.data.db.VoteDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ArenaDatabase =
        Room.databaseBuilder(context, ArenaDatabase::class.java, ArenaDatabase.NAME)
            // Explicit migrations only — offline data must survive upgrades.
            .addCallback(ArenaDatabase.seedCallback())
            .build()

    @Provides fun provideVoteDao(db: ArenaDatabase): VoteDao = db.voteDao()
    @Provides fun provideLeaderboardDao(db: ArenaDatabase): LeaderboardDao = db.leaderboardDao()
    @Provides fun provideModelDao(db: ArenaDatabase): ModelDao = db.modelDao()
    @Provides fun provideConversationDao(db: ArenaDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideUsageEventDao(db: ArenaDatabase): UsageEventDao = db.usageEventDao()
    @Provides fun provideAgentTaskDao(db: ArenaDatabase): AgentTaskDao = db.agentTaskDao()
}
