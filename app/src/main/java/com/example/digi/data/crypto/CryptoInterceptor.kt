package com.example.digi.data.crypto

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject

/**
 * Makes the AMS envelope invisible to the rest of the app.
 *
 * Outbound: a JSON request body becomes `{"data":"<hex>"}`, which is the only shape the server's
 * decrypt middleware recognises (`if (req.body.data && typeof req.body.data === 'string')`).
 * Multipart bodies — the screenshot upload — are passed through untouched, because multer parses
 * those before body-parser ever sees a `data` field and encrypting one would break the upload.
 *
 * Inbound: `{"data":"<hex>"}` becomes the plaintext envelope. Anything that is not that shape is
 * left alone: the rate limiter, helmet and the decrypt middleware's own 400 reply all short-circuit
 * before the response encryptor runs, so plaintext error bodies are a normal thing to meet.
 */
class CryptoInterceptor(
    private val cipher: AesCipher,
    private val enabled: Boolean,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!enabled) return chain.proceed(chain.request())

        val response = chain.proceed(encryptRequest(chain.request()))
        return decryptResponse(response)
    }

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

    /**
     * @return the decrypted envelope, or null when this body was never encrypted.
     */
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
    }
}
