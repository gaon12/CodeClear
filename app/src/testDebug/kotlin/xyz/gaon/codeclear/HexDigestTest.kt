package xyz.gaon.codeclear

import org.junit.Assert.assertEquals
import org.junit.Test

class HexDigestTest {
    @Test
    fun hexMatchesThePreviousPerByteFormatForEveryByteValue() {
        val every = ByteArray(256) { it.toByte() }
        assertEquals(every.joinToString("") { "%02x".format(it) }, every.toLowerHex())
        assertEquals("", ByteArray(0).toLowerHex())
    }

    @Test
    fun sha256HexMatchesPublishedVectorsAndUtf8Encoding() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc")
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex("")
        )
        val korean = "[훈련] 대피하세요 112"
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(korean.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) },
            sha256Hex(korean)
        )
    }
}
