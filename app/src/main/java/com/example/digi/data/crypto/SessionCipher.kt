package com.example.digi.data.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Payload encryption v2 - the player's half of the backend's helper/SessionCrypto.js.
 *
 * Nothing in the APK decrypts anybody's traffic any more. The player makes a throwaway P-256 key
 * pair, agrees a session key with the server over ECDH, and keeps it in memory only. The one key
 * the APK carries ([pinnedServerKey], the build flavor's CRYPTO_SERVER_KEY) is the server's PUBLIC signing
 * key: it proves the handshake came from the real server, so a man in the middle on a station
 * network cannot slip in his own key, and it decrypts nothing. v1's AES key and IV, by contrast,
 * were baked into every APK and opened every screen's traffic for anyone who unzipped one.
 *
 * Every body is AES-256-GCM with a fresh IV, bound to the session, direction, method, path and a
 * timestamp. The byte layout must match the backend exactly - see that file's header.
 *
 * Plain JCA throughout, all of it available from API 24: EC key generation, ECDH, SHA256withECDSA,
 * HmacSHA256 (for HKDF) and AES/GCM/NoPadding. Base64 is Kotlin's own, because java.util.Base64
 * needs API 26 and android.util.Base64 does not exist in JVM unit tests.
 */
@OptIn(ExperimentalEncodingApi::class)
class SessionCipher(pinnedServerKey: String) {

    /** A negotiated session. [key] is the AES-256-GCM key; it never leaves memory. */
    class Session(val id: String, internal val key: ByteArray, val expiresAtMs: Long)

    /** What the server answers a handshake with (all base64 but [expiresAt]). */
    data class HandshakeReply(
        val sessionId: String,
        val serverPublicKey: String,
        val salt: String,
        val signature: String,
        val expiresAt: Long,
    )

    /** One side of an exchange in progress: our ephemeral key pair, until the reply arrives. */
    inner class Pending internal constructor(private val pair: java.security.KeyPair) {
        /** The uncompressed point to send as `clientPublicKey`. */
        val clientPublicKey: String = b64(raw(pair.public as ECPublicKey))

        /**
         * Verify the server's signature and derive the session key. Throws [SecurityException] if
         * the reply did not come from the server whose key this build pins.
         */
        fun complete(reply: HandshakeReply): Session {
            val clientPub = raw(pair.public as ECPublicKey)
            val serverPub = unb64(reply.serverPublicKey)
            val salt = unb64(reply.salt)
            val transcript = concat(
                "SO2-HS".toByteArray(), reply.sessionId.toByteArray(), clientPub, serverPub, salt,
                reply.expiresAt.toString().toByteArray(),
            )
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pinned)
            verifier.update(transcript)
            if (!verifier.verify(p1363ToDer(unb64(reply.signature)))) {
                throw SecurityException("The server could not prove its identity - refusing to connect")
            }

            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(pair.private)
            agreement.doPhase(publicKeyFromRaw(serverPub), true)
            val secret = agreement.generateSecret()
            val key = hkdfSha256(secret, salt, "SO2-KEY|${reply.sessionId}".toByteArray(), 32)
            return Session(reply.sessionId, key, reply.expiresAt)
        }
    }

    private val pinned: ECPublicKey = publicKeyFromRaw(unb64(pinnedServerKey))
    private val random = SecureRandom()

    /** Start a handshake: send [Pending.clientPublicKey], then pass the reply to [Pending.complete]. */
    fun begin(): Pending {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        return Pending(generator.generateKeyPair())
    }

    /** Seal a request body: the JSON text of the v2 envelope. */
    fun seal(session: Session, method: String, path: String, plainJson: String, nowMs: Long = System.currentTimeMillis()): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(session.key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(session.id, "req", method, path, nowMs))
        val data = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8)) // ciphertext || tag, as the server expects
        return org.json.JSONObject()
            .put("v", 2).put("ts", nowMs).put("iv", b64(iv)).put("data", b64(data))
            .toString()
    }

    /** Open a response envelope; throws if it was tampered with or is not for this request. */
    fun open(session: Session, method: String, path: String, ts: Long, iv: String, data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(session.key, "AES"), GCMParameterSpec(128, unb64(iv)))
        cipher.updateAAD(aad(session.id, "res", method, path, ts))
        return String(cipher.doFinal(unb64(data)), Charsets.UTF_8)
    }

    companion object {
        private fun aad(sid: String, direction: String, method: String, path: String, ts: Long) =
            "SO2|$sid|$direction|${method.uppercase()} $path|$ts".toByteArray(Charsets.UTF_8)

        internal fun b64(bytes: ByteArray): String = Base64.encode(bytes)
        internal fun unb64(text: String): ByteArray = Base64.decode(text)

        private fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var at = 0
            for (p in parts) { p.copyInto(out, at); at += p.size }
            return out
        }

        /** A 32-byte big-endian coordinate - BigInteger adds a sign byte or drops leading zeros. */
        private fun fixed32(n: BigInteger): ByteArray {
            val b = n.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }

        /** 0x04 || X || Y. */
        internal fun raw(key: ECPublicKey): ByteArray =
            byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

        private val P256_PARAMS by lazy {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            (generator.generateKeyPair().public as ECPublicKey).params
        }

        internal fun publicKeyFromRaw(raw: ByteArray): ECPublicKey {
            require(raw.size == 65 && raw[0] == 4.toByte()) { "expected an uncompressed P-256 point" }
            val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, P256_PARAMS)) as ECPublicKey
        }

        /** IEEE P1363 (r || s, 64 bytes), which the server and Web Crypto use, to the DER Java wants. */
        internal fun p1363ToDer(sig: ByteArray): ByteArray {
            require(sig.size == 64) { "expected a 64-byte P-256 signature" }
            fun integer(part: ByteArray): ByteArray {
                var i = 0
                while (i < part.size - 1 && part[i] == 0.toByte()) i++
                var body = part.copyOfRange(i, part.size)
                if (body[0].toInt() and 0x80 != 0) body = byteArrayOf(0) + body
                return byteArrayOf(0x02, body.size.toByte()) + body
            }
            val seq = integer(sig.copyOfRange(0, 32)) + integer(sig.copyOfRange(32, 64))
            return byteArrayOf(0x30, seq.size.toByte()) + seq
        }

        /** RFC 5869 HKDF with HMAC-SHA256. */
        internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var at = 0
            var counter = 1
            while (at < length) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val n = minOf(previous.size, length - at)
                previous.copyInto(out, at, 0, n)
                at += n
                counter++
            }
            return out
        }
    }
}
