package com.dexcom.bgannouncer.di

import com.dexcom.bgannouncer.dexcom.DexcomClientProvider
import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(provider: DexcomClientProvider): OkHttpClient {
        return provider.createClient()
    }

    @Provides
    @Singleton
    fun provideJson(provider: DexcomClientProvider): Json {
        return provider.createJson()
    }

    @Provides
    @Singleton
    fun provideDexcomShareClient(
        okHttpClient: OkHttpClient,
        json: Json,
    ): DexcomShareClient {
        return DexcomShareClient(okHttpClient, json)
    }
}
