package xyz.gaon.codeclear.mobile

import java.util.Locale

data class SpeechVoice(
    val name: String,
    val language: String,
    val networkRequired: Boolean,
    val quality: Int,
    val installed: Boolean = true
)

/** Never silently send an original or translation to a network-only voice. */
fun offlineSpeechVoice(voices: List<SpeechVoice>, language: String): SpeechVoice? {
    val requested = Locale.forLanguageTag(language)
    if (requested.language.isEmpty() || requested.language == "und") return null
    return voices.take(256).filter {
        !it.networkRequired && it.installed &&
            Locale.forLanguageTag(it.language).language == requested.language
    }.maxByOrNull {
        val actual = Locale.forLanguageTag(it.language)
        (if (actual.toLanguageTag() == requested.toLanguageTag()) 10_000 else 0) +
            (if (requested.script.isNotEmpty() && requested.script == actual.script) 2000 else 0) +
            it.quality.coerceIn(0, 1000)
    }
}

fun speechChunks(text: String, limit: Int = 4000): List<String> {
    require(text.isNotBlank() && text.length <= 16_384 && limit in 256..4000)
    require(Charsets.UTF_8.newEncoder().canEncode(text))
    return buildList {
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + limit, text.length)
            if (end < text.length) {
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                val boundary =
                    maxOf(text.lastIndexOf('\n', end - 1), text.lastIndexOf(' ', end - 1))
                if (boundary >= offset + limit / 2) end = boundary + 1
            }
            add(text.substring(offset, end))
            offset = end
        }
    }
}
