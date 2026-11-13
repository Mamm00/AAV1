package com.arena.mobile.di

import com.arena.mobile.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * App-level bindings. Feature-specific dependencies live in their own modules.
 *
 * The shared OkHttpClient carries HTTP/3/QUIC hints so connections to
 * arena.ai can negotiate QUIC when the network supports it. Hints must be
 * registered before the client (and the WebView that shares its connection
 * behaviour) performs any request.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** QUIC alternate-ports advertised for arena.ai (UDP 443). */
    private const val ARENA_HOST = "arena.ai"
    private const val ARENA_PORT = 443
    private const val ARENA_ALT_PORT = 443

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // Must be added before the first request: primes the QUIC path.
            .addQuicHints(ARENA_HOST, ARENA_PORT, ARENA_ALT_PORT)
            .addQuicHints("*.arena.ai", ARENA_PORT, ARENA_ALT_PORT)

        if (BuildConfig.DEBUG) {
            // Never log bodies: prompts/responses/tokens must not leak.
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Cookie")
                    redactHeader("Authorization")
                },
            )
        }
        return builder.build()
    }
}
