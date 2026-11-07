package com.example.digi.data.crypto

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject

/**
 * Encrypts every player request body and decrypts every response, so the rest of the app only
 * ever sees plain JSON.
 *
 * Two envelopes:
 *
 *  - **v2 (session keys)** whenever this build pins the server's public key
 *    (the build flavor's CRYPTO_SERVER_KEY). The player negotiates a session over `/crypto/session`, seals
 *    every JSON body with it, sends `X-Crypto-Session` on every request so the response comes back
 *    sealed too, and quietly renews the session when the server answers 419 (expired, or the server
 *    lost it) or rejects a message as stale. Nothing in the APK can decrypt traffic. See
 *    [SessionCipher].
 *  - **v1 (static key)** otherwise - the AES key and IV built into the APK. Kept only so a build
 *    without the new key still works against a server with CRYPTO_LEGACY_ENABLED on.
 *
 * Common to both: multipart (the screenshot upload) is never encrypted - multer parses it before
 * anything could decrypt it - and a plaintext response (rate limiter, helmet, the crypto layer's
 * own errors) is passed through untouched rather than treated as corrupt.
 */
class CryptoInterceptor(
    private val cipher: AesCipher,
    private val enabled: Boolean,
    private val session: SessionCipher? = null,
    /** `<API_BASE_URL>crypto/session` */
    private val handshakeUrl: String? = null,
    /** A client WITHOUT this interceptor, for the handshake itself. */
    private val handshakeClient: OkHttpClient? = null,
) : Interceptor {

    private val lock = Any()
    @Volatile private var current: SessionCipher.Session? = null
    /** Server time - local time, from the last handshake. These boxes' clocks are not trustworthy. */
    @Volatile private var clockOffsetMs = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        if (session != null && handshakeUrl != null && handshakeClient != null) return interceptV2(chain, session)
        if (!enabled) return chain.proceed(chain.request())

        val response = chain.proceed(encryptRequest(chain.request()))
        return decryptResponse(response)
    }

    /* ── v2 ──────────────────────────────────────────────────────────────── */

    private fun interceptV2(chain: Interceptor.Chain, cipher: SessionCipher): Response {
        val request = chain.request()
        val plainBody = jsonBodyOf(request)

        var active = sessionFor(cipher, force = false)
        var response = chain.proceed(sealed(request, plainBody, cipher, active))

        // Renew and retry ONCE: the session expired or the server lost it (419), or our stamp was
        // stale because this box's clock moved (400 CRYPTO_INVALID) - a new handshake fixes both.
        if (response.code == 419 || (response.code == 400 && peekErrorCode(response) == "CRYPTO_INVALID")) {
            response.close()
            active = sessionFor(cipher, force = true)
            response = chain.proceed(sealed(request, plainBody, cipher, active))
        }
        return opened(response, request, cipher, active)
    }

    private fun sessionFor(cipher: SessionCipher, force: Boolean): SessionCipher.Session = synchronized(lock) {
        val now = System.currentTimeMillis() + clockOffsetMs
        current?.takeIf { !force && it.expiresAtMs - now > RENEW_BEFORE_MS }?.let { return it }
        handshake(cipher).also { current = it }
    }

    private fun handshake(cipher: SessionCipher): SessionCipher.Session {
        val pending = cipher.begin()
        val body = JSONObject().put("clientPublicKey", pending.clientPublicKey).put("client", "player").toString()
        val call = handshakeClient!!.newCall(
            Request.Builder().url(handshakeUrl!!).post(body.toRequestBody(JSON)).build()
        )
        call.execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("Encryption handshake failed (HTTP ${res.code})")
            val r = runCatching { JSONObject(text).getJSONObject("response") }.getOrNull()
                ?: throw IOException("Encryption handshake returned an unreadable reply")
            val reply = SessionCipher.HandshakeReply(
                sessionId = r.getString("sessionId"),
                serverPublicKey = r.getString("serverPublicKey"),
                salt = r.getString("salt"),
                signature = r.getString("signature"),
                expiresAt = r.getLong("expiresAt"),
            )
            val established = try {
                pending.complete(reply)
            } catch (e: SecurityException) {
                // Not our server. An IOException so callers treat it like no network - nothing is sent.
                throw IOException(e.message, e)
            }
            if (r.has("serverTime")) clockOffsetMs = r.getLong("serverTime") - System.currentTimeMillis()
            return established
        }
    }

    private fun jsonBodyOf(request: Request): String? {
        val body = request.body ?: return null
        val type = body.contentType()?.subtype?.lowercase()
        if (type == null || !type.contains("json")) return null
        return Buffer().also { body.writeTo(it) }.readUtf8().takeIf { it.isNotBlank() }
    }

    private fun sealed(request: Request, plainBody: String?, cipher: SessionCipher, active: SessionCipher.Session): Request {
        val builder = request.newBuilder().header(SESSION_HEADER, active.id)
        if (plainBody != null) {
            val envelope = cipher.seal(active, request.method, request.url.encodedPath, plainBody,
                System.currentTimeMillis() + clockOffsetMs)
            builder.method(request.method, envelope.toRequestBody(JSON))
        }
        return builder.build()
    }

    private fun opened(response: Response, request: Request, cipher: SessionCipher, active: SessionCipher.Session): Response {
        val body = response.body ?: return response
        val contentType = body.contentType()
        val raw = body.string()
        val json = raw.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val plain = if (json != null && json.optInt("v") == 2 && json.has("iv") && json.has("data") && json.has("ts")) {
            try {
                cipher.open(active, request.method, request.url.encodedPath, json.getLong("ts"), json.getString("iv"), json.getString("data"))
            } catch (e: Exception) {
                throw IOException("A response failed its integrity check and was discarded", e)
            }
        } else raw
        return response.newBuilder().body(plain.toResponseBody(contentType ?: JSON)).build()
    }

    private fun peekErrorCode(response: Response): String? =
        runCatching { JSONObject(response.peekBody(4096).string()).optString("errorCode") }.getOrNull()

    /* ── v1 (legacy) ─────────────────────────────────────────────────────── */

    private fun encryptRequest(request: Request): Request {
        val body = request.body ?: return request
        val type = body.contentType()?.subtype?.lowercase()
        // Only JSON is encrypted. `multipart/form-data` and anything else goes as-is.
        if (type == null || !type.contains("json")) return request

        val plain = Buffer().also { body.writeTo(it) }.readUtf8()
        if (plain.isBlank()) return request

        val wrapped = JSONObject().put("data", cipher.encrypt(plain)).toString()
        return request.newBuilder()
            .method(request.method, wrapped.toRequestBody(JSON))
            .build()
    }

    private fun decryptResponse(response: Response): Response {
        val body = response.body ?: return response
        val contentType = body.contentType()
        val raw = body.string()          // consumes the body; a replacement is always installed below

        val plain = unwrap(raw) ?: raw
        return response.newBuilder()
            .body(plain.toResponseBody(contentType ?: JSON))
            .build()
    }

    private fun unwrap(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        // The encrypted shape is exactly one key. Checking the count as well as the name keeps a
        // legitimate plaintext payload that happens to carry a `data` field from being mangled.
        if (json.length() != 1 || !json.has("data")) return null
        val hex = json.optString("data")
        if (!AesCipher.looksLikeHex(hex)) return null
        return cipher.decryptOrNull(hex)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val SESSION_HEADER = "X-Crypto-Session"
        const val RENEW_BEFORE_MS = 5 * 60 * 1000L
    }
}
