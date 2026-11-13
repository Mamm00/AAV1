package com.arena.feature.arena.di

import com.arena.core.permissions.PermissionManager
import com.arena.feature.arena.session.ArenaSessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SessionModule {

    @Provides
    @Singleton
    fun provideArenaSession(): ArenaSessionRepository = ArenaSessionRepository()

    /**
     * One manager per process keeps denial counts and DenialStats coherent
     * across screens (image editor, agent, notifications…). Logic stays pure
     * in :core:permissions; this is just the singleton binding.
     */
    @Provides
    @Singleton
    fun providePermissionManager(): PermissionManager = PermissionManager()
}
