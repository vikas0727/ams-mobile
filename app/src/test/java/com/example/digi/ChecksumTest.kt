package com.example.digi

import com.example.digi.media.Checksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ChecksumTest {

    @Test
    fun `hashes known input`() {
        // sha256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksum.sha256Of("abc".byteInputStream()),
        )
    }

    @Test
    fun `a changed file has a different hash`() {
        val file = File.createTempFile("ams-checksum", ".bin")
        try {
            file.writeText("the published video")
            val published = Checksum.sha256Of(file)
            file.writeText("a swapped video")
            assertNotEquals(published, Checksum.sha256Of(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `normalise accepts a 64 char hex and rejects anything else`() {
        val ok = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertEquals(ok.lowercase(), Checksum.normalise(" $ok "))
        assertNull(Checksum.normalise(null))
        assertNull(Checksum.normalise(""))
        assertNull(Checksum.normalise("not-a-hash"))
        assertNull(Checksum.normalise("ba78"))
    }
}
