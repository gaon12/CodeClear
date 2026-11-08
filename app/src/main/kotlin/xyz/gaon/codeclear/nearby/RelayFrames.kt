package xyz.gaon.codeclear.nearby

import java.nio.ByteBuffer
import xyz.gaon.codeclear.toLowerHex

data class RelayEntry(val root: String, val size: Int)
data class RelayBlockRequest(val index: Int, val offset: Int, val size: Int)

object RelayFrames {
    fun inventory(entries: List<RelayEntry>): ByteArray {
        require(entries.size <= 8)
        val output = ByteBuffer.allocate(2 + entries.size * 36)
            .put(RelayProtocol.VERSION.toByte()).put(entries.size.toByte())
        for (entry in entries) {
            require(entry.size in 64..RelayProtocol.MAX_BYTES)
            output.put(RelayProtocol.rootBytes(entry.root)).putInt(entry.size)
        }
        return output.array()
    }

    fun inventory(bytes: ByteArray): List<RelayEntry> {
        require(bytes.size in 2..290)
        val input = ByteBuffer.wrap(bytes)
        require(input.get().toInt() == RelayProtocol.VERSION)
        val count = input.get().toInt()
        require(count in 0..8 && input.remaining() == count * 36)
        val result = List(count) {
            val root = ByteArray(32).also(input::get).toLowerHex()
            val size = input.int
            require(size in 64..RelayProtocol.MAX_BYTES)
            RelayEntry(root, size)
        }
        require(result.map { it.root }.distinct().size == result.size)
        return result
    }

    fun request(index: Int, offset: Int, size: Int): ByteArray {
        require(index in 0..7 && offset >= 0 && size in 1..248)
        return ByteBuffer.allocate(
            7
        ).put(index.toByte()).putInt(offset).putShort(size.toShort()).array()
    }

    fun request(bytes: ByteArray): RelayBlockRequest {
        require(bytes.size == 7)
        val input = ByteBuffer.wrap(bytes)
        val index = input.get().toInt()
        val offset = input.int
        val size = input.short.toInt() and 65535
        request(index, offset, size)
        return RelayBlockRequest(index, offset, size)
    }

    fun block(request: RelayBlockRequest, bytes: ByteArray, mtu: Int): ByteArray {
        require(mtu >= 23 && request.offset in bytes.indices)
        val count = minOf(request.size, bytes.size - request.offset, mtu - 11, 248)
        require(count > 0)
        return ByteBuffer.allocate(8 + count).put(RelayProtocol.VERSION.toByte())
            .put(request.index.toByte()).putInt(request.offset).putShort(count.toShort())
            .put(bytes, request.offset, count).array()
    }
}

/** Sequential assembly prevents overlap, length expansion, and premature acceptance. */
class RelayAssembly(private val entry: RelayEntry, private val index: Int) {
    private val bytes = ByteArray(entry.size.also { require(it in 64..RelayProtocol.MAX_BYTES) })
    var offset = 0
        private set
    val complete: Boolean get() = offset == bytes.size

    fun append(frame: ByteArray) {
        require(frame.size in 9..256)
        val input = ByteBuffer.wrap(frame)
        require(input.get().toInt() == RelayProtocol.VERSION && input.get().toInt() == index)
        val position = input.int
        val count = input.short.toInt() and 65535
        require(count == input.remaining() && count <= bytes.size - offset && position == offset)
        input.get(bytes, offset, count)
        offset += count
    }

    fun finish(now: Long): NearbyOriginal {
        require(complete)
        val result = requireNotNull(RelayProtocol.decode(bytes, now))
        require(result.root == entry.root)
        return result
    }
}
