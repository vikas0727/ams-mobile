package com.example.digi.data.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

///*
// * The device-side half of AMS's `Utils/cryptoUtil.js`.
// *
// * The backend transparently AES-256-CBC encrypts every JSON response outside its bypass list, and
// * `/api/v1/player/*` is deliberately not in that list — the manifest carries pre-signed S3 URLs and
// * is worth keeping opaque on a station's public wifi. So the wire shape both ways is
// * `{"data":"<lowercase hex>"}` and this class is what makes it readable.
// *
// * Two details are load-bearing and easy to get wrong:
// *
// *  - The key is **SHA-256 of the secret string**, not the string's bytes. `crypto.createHash(
// *    "sha256").update(String(AES_SECRET_KEY)).digest()` on the server; the same here. Passing the
// *    raw UTF-8 bytes would only work by accident when the secret happens to be 32 bytes long, which
// *    the dev secret is — so it would appear to work in development and fail in production.
// *  - The IV is **fixed and hex-decoded from config**, not random and not prepended to the payload.
// *    A fixed IV is weaker than a per-message one, but changing it is a backend decision: the server
// *    decrypts with `Buffer.from(AES_IV, "hex")` and nothing in the payload tells it otherwise.
// */
class AesCipher(secretKey: String, ivHex: String) {

    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(secretKey.toByteArray(Charsets.UTF_8)),
        "AES"
    )
    private val iv = IvParameterSpec(ivHex.hexToBytesOrThrow())

    /** Plaintext (usually a JSON document) → lowercase hex, exactly what `encrypt()` returns. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Lowercase hex → plaintext. Throws on a wrong key, a truncated payload or bad padding. */
    fun decrypt(hex: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        return String(cipher.doFinal(hex.hexToBytesOrThrow()), Charsets.UTF_8)
    }

    /**
     * Decrypt without throwing — used by the interceptor, which meets plaintext bodies too.
     *
     * Not every response from this server is encrypted: the rate limiter, helmet and the
     * "Invalid encrypted payload" branch of the decrypt middleware all reply before the response
     * encryptor is reached. Those arrive as ordinary JSON, and treating a failed decrypt as fatal
     * would turn a readable 429 into an opaque crash.
     */
    fun decryptOrNull(hex: String): String? = runCatching { decrypt(hex) }.getOrNull()

    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

        /** Cheap shape test before committing to a decrypt: even length, hex alphabet, non-empty. */
        fun looksLikeHex(value: String): Boolean =
            value.isNotEmpty() && value.length % 2 == 0 && value.all {
                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
            }
    }
}

private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private fun String.hexToBytesOrThrow(): ByteArray {
    require(length % 2 == 0) { "Hex string must have an even length (got $length)" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Not a hex string" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private val HEX = "0123456789abcdef".toCharArray()
