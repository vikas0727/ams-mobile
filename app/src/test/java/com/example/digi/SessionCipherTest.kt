package com.example.digi

import com.example.digi.data.crypto.SessionCipher
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The player's half of payload encryption v2. Interop with the backend was proven separately by a
 * call-for-call Java port run against the real server; these pin the pieces that must not drift.
 */
class SessionCipherTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val okm = SessionCipher.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun `a raw public key survives the round trip`() {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        repeat(20) {
            val pub = g.generateKeyPair().public as ECPublicKey
            val raw = SessionCipher.raw(pub)
            assertEquals(65, raw.size)
            assertEquals(pub.w, SessionCipher.publicKeyFromRaw(raw).w)
        }
    }

    @Test
    fun `P1363 signatures convert to DER that Java verifies`() {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = g.generateKeyPair()
        // Sign in DER, convert to P1363 the way the server sends it, and back: many runs cover the
        // high-bit and leading-zero cases.
        repeat(50) { i ->
            val msg = "message $i".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(msg); sign() }
            val p1363 = derToP1363(der)
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(pair.public); update(msg); verify(SessionCipher.p1363ToDer(p1363))
            }
            assert(ok) { "run $i" }
        }
    }

    @Test
    fun `seal produces a v2 envelope with a fresh IV every time`() {
        val cipher = SessionCipher(SessionCipher.b64(SessionCipher.raw(
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey)))
        val session = SessionCipher.Session("sid", ByteArray(32) { it.toByte() }, Long.MAX_VALUE)
        val a = JSONObject(cipher.seal(session, "POST", "/api/v1/player/heartbeat", """{"x":1}""", 1000))
        val b = JSONObject(cipher.seal(session, "POST", "/api/v1/player/heartbeat", """{"x":1}""", 1000))
        assertEquals(2, a.getInt("v"))
        assertNotEquals(a.getString("iv"), b.getString("iv"))
    }

    @Test
    fun `a message sealed as a request does not open as a response`() {
        val pinned = SessionCipher.b64(SessionCipher.raw(
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey))
        val cipher = SessionCipher(pinned)
        val session = SessionCipher.Session("sid", ByteArray(32) { 7 }, Long.MAX_VALUE)
        // A "response" made with the request direction must not open as a response.
        val sealedAsRequest = JSONObject(cipher.seal(session, "GET", "/api/v1/player/sync", """{"ok":true}""", 5))
        assertThrows(Exception::class.java) {
            cipher.open(session, "GET", "/api/v1/player/sync", 5, sealedAsRequest.getString("iv"), sealedAsRequest.getString("data"))
        }
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var i = 2
        fun read(): ByteArray {
            require(der[i] == 0x02.toByte()); val len = der[i + 1].toInt(); val v = der.copyOfRange(i + 2, i + 2 + len); i += 2 + len
            val trimmed = v.dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - trimmed.size) + trimmed
        }
        return read() + read()
    }
}
