package com.applemusicktv.data.di

import com.applemusicktv.BuildConfig
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.data.network.ProxyApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(mutPrefs: MutPreferences, serverPrefs: ServerPreferences): OkHttpClient {
        // Rewrites the host/port of every request to the currently-configured PC server
        val serverUrlInterceptor = Interceptor { chain ->
            val base = serverPrefs.effectiveBaseUrl()
            val req = if (base == BuildConfig.PROXY_BASE_URL) {
                chain.request()
            } else {
                val newBase = base.toHttpUrl()
                val orig = chain.request().url
                val newUrl = orig.newBuilder()
                    .scheme(newBase.scheme)
                    .host(newBase.host)
                    .port(newBase.port)
                    .build()
                chain.request().newBuilder().url(newUrl).build()
            }
            chain.proceed(req)
        }

        val mutInterceptor = Interceptor { chain ->
            val mut = mutPrefs.getMUT()
            val req = if (mut.isNotEmpty())
                chain.request().newBuilder().addHeader("X-Music-User-Token", mut).build()
            else chain.request()
            chain.proceed(req)
        }

        val netLogInterceptor = Interceptor { chain ->
            val req = chain.request()
            val t0 = System.currentTimeMillis()
            val resp = chain.proceed(req)
            com.applemusicktv.data.NetworkLog.add(
                req.method, req.url.toString(), resp.code, System.currentTimeMillis() - t0)
            resp
        }

        return OkHttpClient.Builder()
            .addInterceptor(serverUrlInterceptor)
            .addInterceptor(mutInterceptor)
            .addInterceptor(netLogInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.PROXY_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideProxyApi(retrofit: Retrofit): ProxyApi = retrofit.create(ProxyApi::class.java)
}
