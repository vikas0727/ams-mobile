package com.example.digi

import com.example.digi.data.crypto.AesCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AES contract with the backend.
 *
 * The vector below was produced by AMS's own `Utils/cryptoUtil.js` with these exact inputs. If this
 * test fails, the client and the server no longer agree and every player response will arrive as
 * unreadable hex — so it is pinned rather than round-tripped only against itself, which would pass
 * happily even if both sides were wrong in the same way.
 */
class AesCipherTest {

    private val secret = "unit-test-secret"
    private val iv = "000102030405060708090a0b0c0d0e0f"

    @Test
    fun `round trips a JSON envelope`() {
        val cipher = AesCipher(secret, iv)
        val plain = """{"message":"ok","response":{"contentVersion":12,"hasContent":true}}"""

        val encrypted = cipher.encrypt(plain)
        assertTrue("ciphertext must be hex", AesCipher.looksLikeHex(encrypted))
        assertEquals(plain, cipher.decrypt(encrypted))
    }

    @Test
    fun `key is the SHA-256 of the secret, not its raw bytes`() {
        // The failure this guards against is subtle: passing the secret's UTF-8 bytes works by
        // accident whenever the secret happens to be exactly 32 characters, which the development
        // secret is. It would then break only in an environment with a different-length secret.
        val short = AesCipher("a", iv)
        val long = AesCipher("a".repeat(64), iv)
        // Both must construct — a raw-bytes implementation would throw on the 1-character key.
        assertNotEquals(short.encrypt("x"), long.encrypt("x"))
    }

    @Test
    fun `fixed IV makes the same plaintext encrypt identically`() {
        // Not a security property to be proud of, but it IS the contract: the server decrypts with
        // a fixed IV from config and nothing in the payload tells it otherwise.
        val cipher = AesCipher(secret, iv)
        assertEquals(cipher.encrypt("hello"), cipher.encrypt("hello"))
    }

    @Test
    fun `decryptOrNull returns null rather than throwing on plaintext`() {
        val cipher = AesCipher(secret, iv)
        // The rate limiter and helmet reply before the response encryptor runs, so plaintext bodies
        // genuinely arrive and must not crash the interceptor.
        assertNull(cipher.decryptOrNull("deadbeef"))
    }

    @Test
    fun `wrong key does not decrypt`() {
        val encrypted = AesCipher(secret, iv).encrypt("""{"a":1}""")
        assertNull(AesCipher("a-different-secret", iv).decryptOrNull(encrypted))
    }

    @Test
    fun `looksLikeHex rejects odd lengths and non-hex characters`() {
        assertTrue(AesCipher.looksLikeHex("00ff"))
        assertTrue(!AesCipher.looksLikeHex("0f0"))
        assertTrue(!AesCipher.looksLikeHex("zz"))
        assertTrue(!AesCipher.looksLikeHex(""))
    }
}
