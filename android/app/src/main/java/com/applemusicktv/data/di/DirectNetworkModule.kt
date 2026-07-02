package com.applemusicktv.data.di

import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.NetworkLog
import com.applemusicktv.data.network.DirectAppleApi
import com.applemusicktv.media.AppleDirectClient
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DirectNetworkModule {

    @Provides
    @Singleton
    @Named("direct")
    fun provideDirectOkHttpClient(
        mutPrefs: MutPreferences,
        appleClient: AppleDirectClient,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            // Bearer is fetched lazily and cached inside AppleDirectClient.
            val bearer = runBlocking { appleClient.getBearer() }
            val mut = mutPrefs.getMUT()
            val req = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $bearer")
                .apply { if (mut.isNotEmpty()) addHeader("Music-User-Token", mut) }
                .addHeader("Origin", "https://music.apple.com")
                .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                .build()
            val t0 = System.currentTimeMillis()
            val resp = chain.proceed(req)
            val ms = System.currentTimeMillis() - t0
            NetworkLog.add(req.method, req.url.toString(), resp.code, ms)
            resp
        }
        .build()

    @Provides
    @Singleton
    fun provideDirectAppleApi(
        @Named("direct") client: OkHttpClient,
        moshi: Moshi,
    ): DirectAppleApi = Retrofit.Builder()
        .baseUrl("https://amp-api-edge.music.apple.com/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(DirectAppleApi::class.java)
}
