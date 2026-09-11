package com.example.digi.data.remote

import com.example.digi.BuildConfig
import com.example.digi.data.crypto.AesCipher
import com.example.digi.data.crypto.CryptoInterceptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds the one HTTP stack the app uses.
 *
 * Two clients, on purpose:
 *  - [api] talks to AMS and runs the AES interceptor.
 *  - [download] pulls media from pre-signed S3 URLs and must NOT: those bodies are binary and
 *    nothing about them is enveloped. Sharing one client would corrupt every download by trying to
 *    hex-decode an MP4.
 *
 * Timeouts are generous by app standards and mean by signage standards. A station uplink is slow
 * but the heartbeat has to fail fast enough that the player notices it is offline and starts
 * queueing rather than blocking a 60-second loop for two minutes.
 */
object ApiClient {

    val json: Json = Json {
        // The backend adds fields to the manifest faster than a deployed fleet can be updated. A
        // player that threw on an unknown key would go dark on the next CMS release.
        ignoreUnknownKeys = true
        // Mongoose writes explicit nulls for unset optional fields; without this a `null` where the
        // DTO declares a non-null default is a parse failure rather than the default.
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    private val logging = HttpLoggingInterceptor().apply {
        // BODY in debug shows the DECRYPTED payload, because the crypto interceptor is added first
        // and OkHttp runs application interceptors in order — which is the whole reason debugging
        // this API from logcat is bearable at all.
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
        else HttpLoggingInterceptor.Level.BASIC
    }

    fun create(tokenProvider: () -> String?): PlayerApi {
        val cipher = AesCipher(BuildConfig.AES_SECRET_KEY, BuildConfig.AES_IV)

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(CryptoInterceptor(cipher, BuildConfig.AES_ENABLED))
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)   // screenshot uploads on a slow uplink
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalisedBaseUrl())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PlayerApi::class.java)
    }

    /** Media transport: no auth header, no AES, long read timeout for large MP4s. */
    val download: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Retrofit demands a trailing slash; a base URL typed without one is the single most common
     *  way to get a silent 404 against `/api/v1`. */
    private fun normalisedBaseUrl(): String =
        BuildConfig.API_BASE_URL.trim().let { if (it.endsWith("/")) it else "$it/" }
}
