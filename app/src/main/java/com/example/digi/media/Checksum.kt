package com.example.digi.media

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 helpers for checking a downloaded file against the hash the CMS published for it.
 */
object Checksum {

    /** Lower-case hex, or null when the manifest gave no usable hash (then nothing is checked). */
    fun normalise(value: String?): String? {
        val v = value?.trim()?.lowercase() ?: return null
        return if (v.length == 64 && v.all { it in '0'..'9' || it in 'a'..'f' }) v else null
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256Of(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    fun sha256Of(file: File): String = file.inputStream().use { sha256Of(it) }
}
