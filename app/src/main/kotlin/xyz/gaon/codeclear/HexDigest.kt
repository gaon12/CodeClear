package xyz.gaon.codeclear

import java.security.MessageDigest

private val lowerHexDigits = "0123456789abcdef".toCharArray()

/**
 * Encodes bytes as lowercase hexadecimal, two digits per byte. Stored alert hashes, nearby relay
 * roots, and server credential IDs use this exact format, so it must stay byte-for-byte stable.
 */
internal fun ByteArray.toLowerHex(): String {
    val digits = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        digits[index * 2] = lowerHexDigits[value ushr 4]
        digits[index * 2 + 1] = lowerHexDigits[value and 0x0f]
    }
    return String(digits)
}

/** SHA-256 of the UTF-8 text as 64 lowercase hexadecimal characters. */
internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toLowerHex()
