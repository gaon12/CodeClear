package xyz.gaon.codeclear.nearby

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom

/** Integrity and occurrence identity only; a digest never authenticates an issuing authority. */
data class NearbyOriginal(
    val root: String,
    val title: String,
    val body: String,
    val capturedAt: Long
)

/** An event keeps its random identity across radio restarts; generation runs on the radio worker. */
class RelayOccurrence {
    internal val nonce: ByteArray by lazy {
        ByteArray(16).also { SecureRandom().nextBytes(it) }
    }
}

object RelayProtocol {
    const val VERSION = 1
    const val MAX_BYTES = 32768
    const val LIFETIME = 15 * 60 * 1000L
    private const val HEADER = 31

    fun encode(
        title: String,
        body: String,
        capturedAt: Long,
        occurrence: RelayOccurrence = RelayOccurrence()
    ): ByteArray {
        require(title.length <= 256 && body.isNotBlank() && body.length <= 8192)
        require(capturedAt > 0)
        val heading = strictEncode(title)
        val original = strictEncode(body)
        val size = HEADER + heading.size + original.size + 32
        require(size <= MAX_BYTES)
        val payload = ByteBuffer.allocate(size - 32)
            .put(VERSION.toByte()).put(occurrence.nonce)
            .putLong(capturedAt).putShort(heading.size.toShort()).putInt(original.size)
            .put(heading).put(original).array()
        return payload + digest(payload)
    }

    fun decode(bytes: ByteArray, now: Long): NearbyOriginal? {
        if (bytes.size !in (HEADER + 33)..MAX_BYTES) return null
        val payloadSize = bytes.size - 32
        if (!MessageDigest.isEqual(
                digest(bytes.copyOf(payloadSize)),
                bytes.copyOfRange(payloadSize, bytes.size)
            )
        ) {
            return null
        }
        return try {
            val input = ByteBuffer.wrap(bytes, 0, payloadSize)
            require(input.get().toInt() == VERSION)
            input.position(17)
            val captured = input.long
            require(captured > 0 && captured >= now - LIFETIME && captured <= now + 300000)
            val headingSize = input.short.toInt() and 65535
            val bodySize = input.int
            require(headingSize <= 1024 && bodySize in 1..MAX_BYTES)
            require(headingSize + bodySize == input.remaining())
            val heading = strictDecode(ByteArray(headingSize).also(input::get))
            val body = strictDecode(ByteArray(bodySize).also(input::get))
            require(heading.length <= 256 && body.isNotBlank() && body.length <= 8192)
            NearbyOriginal(root(bytes), heading, body, captured)
        } catch (_: Exception) {
            null
        }
    }

    fun root(bytes: ByteArray): String {
        require(bytes.size in (HEADER + 33)..MAX_BYTES)
        return bytes.takeLast(32).toByteArray().joinToString("") { "%02x".format(it) }
    }

    fun rootBytes(root: String): ByteArray {
        require(root.matches(Regex("[a-f0-9]{64}")))
        return root.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
    private fun strictEncode(value: String): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }
    private fun strictDecode(value: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value)).toString()
}
