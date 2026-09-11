package com.example.digi.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the player token to everything except `/player/pair`.
 *
 * The token is read fresh on every request rather than captured once, because pairing and unpairing
 * both happen while this OkHttp client is alive — a cached value would leave the app sending a dead
 * token after an unpair, or no token at all for the first call after a fresh pair.
 */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.endsWith("/player/pair")) {
            return chain.proceed(request)
        }
        val token = tokenProvider()
        val authed = if (token.isNullOrBlank()) request else request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authed)
    }
}
