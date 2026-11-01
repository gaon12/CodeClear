package xyz.gaon.codeclear.translation

/** Literal separators are preserved; only bounded single-paragraph segments leave the device. */
data class RemoteSegment(val text: String, val translate: Boolean)

object MyMemoryPlan {
    const val DAILY_LIMIT = 5000
    const val MAX_RESPONSE_BYTES = 65536
    const val MAX_OUTPUT = 16384

    fun segments(body: String): List<RemoteSegment> {
        require(body.isNotBlank() && body.length <= 8192)
        require(Charsets.UTF_8.newEncoder().canEncode(body))
        val result = mutableListOf<RemoteSegment>()
        var start = 0
        while (start < body.length) {
            if (isRemoteParagraphBoundary(body[start])) {
                val end = if (body[start] == '\r' &&
                    body.getOrNull(start + 1) == '\n'
                ) {
                    start + 2
                } else {
                    start + 1
                }
                result.add(RemoteSegment(body.substring(start, end), false))
                start = end
            } else {
                val end =
                    (start until body.length).firstOrNull { isRemoteParagraphBoundary(body[it]) }
                        ?: body.length
                paragraph(body.substring(start, end), result)
                start = end
            }
            require(result.size <= 128)
        }
        require(result.count { it.translate } <= 40)
        require(result.filter { it.translate }.sumOf { it.text.length } <= DAILY_LIMIT)
        return result
    }

    private fun paragraph(text: String, result: MutableList<RemoteSegment>) {
        var start = 0
        while (start < text.length) {
            var end = start
            var bytes = 0
            var boundary = start
            while (end < text.length) {
                val point = text.codePointAt(end)
                val width = when {
                    point <= 0x7f -> 1
                    point <= 0x7ff -> 2
                    point <= 0xffff -> 3
                    else -> 4
                }
                if (bytes + width > 500) break
                bytes += width
                end += Character.charCount(point)
                if (Character.isWhitespace(point) ||
                    point in listOf(46, 33, 63, 0x3002)
                ) {
                    boundary = end
                }
            }
            if (end < text.length && boundary > start + (end - start) / 2) end = boundary
            val chunk = text.substring(start, end)
            val prefix = chunk.takeWhile(Char::isWhitespace)
            val suffix = chunk.drop(prefix.length).takeLastWhile(Char::isWhitespace)
            val content = chunk.substring(prefix.length, chunk.length - suffix.length)
            if (prefix.isNotEmpty()) result.add(RemoteSegment(prefix, false))
            if (content.isNotEmpty()) result.add(RemoteSegment(content, true))
            if (suffix.isNotEmpty()) result.add(RemoteSegment(suffix, false))
            require(result.size <= 128)
            start = end
        }
    }
}

data class RemoteBudgetSnapshot(val day: Long, val used: Int)

interface RemoteBudgetStorage {
    fun read(): RemoteBudgetSnapshot?
    fun write(value: RemoteBudgetSnapshot): Boolean
}

/** Reserve attempts before network I/O. Clock rollback cannot reset an already used budget. */
class RemoteDailyBudget(private val storage: RemoteBudgetStorage, private val day: () -> Long) {
    @Synchronized
    fun reserve(characters: Int): Boolean {
        require(characters in 1..MyMemoryPlan.DAILY_LIMIT)
        val previous = storage.read()
        require(previous == null || previous.used in 0..MyMemoryPlan.DAILY_LIMIT)
        val today = day()
        val effective = maxOf(today, previous?.day ?: today)
        val used = previous?.takeIf { it.day == effective }?.used ?: 0
        if (used + characters > MyMemoryPlan.DAILY_LIMIT) return false
        check(storage.write(RemoteBudgetSnapshot(effective, used + characters)))
        return true
    }

    @Synchronized
    fun exhaust() {
        val previous = storage.read()
        storage.write(
            RemoteBudgetSnapshot(
                maxOf(day(), previous?.day ?: Long.MIN_VALUE),
                MyMemoryPlan.DAILY_LIMIT
            )
        )
    }
}

internal fun isRemoteParagraphBoundary(value: Char): Boolean =
    value == '\r' || value == '\n' || value == '\u2028' || value == '\u2029'
