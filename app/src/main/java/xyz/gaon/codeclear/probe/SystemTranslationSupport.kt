package xyz.gaon.codeclear.probe

import java.util.Locale

data class SystemTargetSupport(val readyPairs: Int = 0, val unpreparedPairs: Int = 0) {
    val available: Boolean get() = readyPairs + unpreparedPairs > 0
}

data class SystemEngineAvailability(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val platformSupported: Boolean = false,
    val pairs: List<SystemLanguagePair> = emptyList(),
    val targets: Map<LocalTarget, SystemTargetSupport> = summarizeSystemTargets(pairs),
    val queryFailed: Boolean = false
)

/** Build presentation counts on the query worker, never by parsing provider lists during a frame. */
internal fun summarizeSystemTargets(
    pairs: List<SystemLanguagePair>
): Map<LocalTarget, SystemTargetSupport> {
    val unique = linkedMapOf<Pair<String, String>, Boolean>()
    pairs.asSequence().take(4096).filter {
        validSystemLanguage(it.source) && validSystemLanguage(it.target)
    }.forEach {
        val key = Locale.forLanguageTag(it.source).toLanguageTag() to
            Locale.forLanguageTag(it.target).toLanguageTag()
        unique[key] = it.ready || unique[key] == true
    }
    val counts = LocalTarget.entries.associateWith { IntArray(2) }
    unique.forEach { (pair, ready) ->
        val locale = Locale.forLanguageTag(pair.second)
        LocalTarget.entries.forEach { target ->
            if (locale.language == target.modelCode &&
                (locale.script.isEmpty() || locale.script == targetScripts.getValue(target))
            ) {
                counts.getValue(target)[if (ready) 0 else 1]++
            }
        }
    }
    return counts.mapValues { (_, count) -> SystemTargetSupport(count[0], count[1]) }
}

private val targetScripts = LocalTarget.entries.associateWith {
    Locale.forLanguageTag(it.languageTag).script
}
private val systemLanguagePattern = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")

internal fun validSystemLanguage(tag: String): Boolean = tag.length in 2..32 &&
    systemLanguagePattern.matches(tag) && Locale.forLanguageTag(tag).language !in setOf("", "und")
