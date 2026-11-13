package com.arena.mobile.di

import com.arena.mobile.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Protocols
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * App-level bindings. Feature-specific dependencies live in their own modules.
 *
 * The shared OkHttpClient advertises HTTP/2 + HTTP/1.1 and is pre-warmed
 * against arena.ai by the speculative loader before first use. QUIC/HTTP-3
 * port hints are a Cronet engine API (`CronetEngine.Builder.addQuicHint`);
 * stock OkHttp 4.x has no such builder method, so the transport hint here is
 * protocol preference plus connection pre-warm instead of a fictional hook.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // Transport hint: prefer HTTP/2, fall back to HTTP/1.1.
            .protocols(listOf(Protocols.HTTP_2, Protocols.HTTP_1_1))

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
