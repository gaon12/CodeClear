package xyz.gaon.codeclear.translation

import java.net.URI
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.regex.Pattern

/** Immutable, bounded whole-message matching. Call on a translation worker, not capture callbacks. */
class StandardMessageTranslator(
    templates: List<StandardMessageTemplate>,
    private val revision: String,
    private val names: StandardNameResolver
) {
    private data class Compiled(
        val definition: StandardMessageTemplate,
        val pattern: Regex,
        val variables: List<String>
    )
    private data class Match(
        val definition: StandardMessageTemplate,
        val values: Map<String, String>
    )
    private val compiled: List<Compiled>

    init {
        StandardTranslationAttribution("catalog", revision)
        require(templates.size in 1..512)
        require(templates.map { it.id }.distinct().size == templates.size)
        compiled = templates.map { definition ->
            compile(
                definition.copy(
                    targets = definition.targets.toMap(),
                    slots = definition.slots.toMap()
                )
            )
        }
    }

    fun translate(body: String, target: TranslationTarget): StandardMessageTranslation? {
        val input = normalize(body) ?: return null
        var selected: Match? = null
        for (candidate in compiled) {
            val result = candidate.pattern.matchEntire(input) ?: continue
            val values = linkedMapOf<String, String>()
            var accepted = true
            candidate.variables.forEachIndexed { index, id ->
                val value = result.groups["s$index"]!!.value
                if ((values[id] != null && values[id] != value) ||
                    !valid(candidate.definition, id, value)
                ) {
                    accepted = false
                }
                values[id] = value
            }
            if (!accepted) continue
            // Multiple explanations are ambiguous even if one happens to have a target translation.
            if (selected != null) return null
            selected = Match(candidate.definition, values)
        }
        val match = selected ?: return null
        if (match.definition.sourceLanguage == target.languageTag) return null
        val wording = match.definition.targets[target.languageTag] ?: return null
        val rendered = linkedMapOf<String, String>()
        for ((id, value) in match.values) {
            val kind = match.definition.slots.getValue(id)
            rendered[id] = if (kind == StandardSlotKind.AREA || kind == StandardSlotKind.AGENCY) {
                if (match.definition.preserveNames) {
                    value
                } else {
                    names.resolve(match.definition.jurisdiction, kind, value, target.languageTag)
                        ?: return null
                }
            } else {
                value
            }
        }
        val output = variable.replace(wording) { rendered.getValue(it.groupValues[1]) }
        if (output.isBlank() || output.length > 16384) return null
        return StandardMessageTranslation(
            output,
            match.definition.sourceLanguage,
            StandardTranslationAttribution(match.definition.id, revision)
        )
    }

    private fun valid(template: StandardMessageTemplate, id: String, value: String): Boolean =
        when (val kind = template.slots.getValue(id)) {
            StandardSlotKind.TIME -> runCatching { LocalTime.parse(value, time) }.isSuccess

            StandardSlotKind.DATE_TIME ->
                runCatching { LocalDateTime.parse(value, dateTime) }.isSuccess

            StandardSlotKind.MAGNITUDE -> value.toDoubleOrNull()?.let { it > 0 && it <= 10 } == true

            StandardSlotKind.DISTANCE, StandardSlotKind.NUMBER -> true

            StandardSlotKind.INTENSITY -> value in intensities

            StandardSlotKind.AREA, StandardSlotKind.AGENCY ->
                names.resolve(template.jurisdiction, kind, value, template.sourceLanguage) != null

            StandardSlotKind.URL -> runCatching {
                val uri = URI(value)
                uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
                    uri.rawUserInfo == null
            }.getOrDefault(false)
        }

    private fun compile(template: StandardMessageTemplate): Compiled {
        StandardTranslationAttribution(template.id, revision)
        require(template.jurisdiction.matches(Regex("[A-Z]{2}")))
        require(template.sourceLanguage in languages)
        require(template.referenceId.matches(Regex("[a-z0-9][a-z0-9.-]{0,95}")))
        require(template.slots.size <= 12)
        val source = normalize(template.sourceText) ?: error("Invalid standard source")
        val tokens = variable.findAll(source).toList()
        val counts = tokens.groupingBy { it.groupValues[1] }.eachCount()
        require(tokens.size <= 16 && counts.keys == template.slots.keys)
        require(!source.replace(variable, "").contains(Regex("[{}]")))
        require(template.targets.isNotEmpty() && template.targets.keys.all { it in languages })
        template.targets.values.forEach {
            require(it.length in 1..8192 && normalize(it) == it)
            require(
                variable.findAll(it).groupingBy { match -> match.groupValues[1] }.eachCount() ==
                    counts
            )
            require(!it.replace(variable, "").contains(Regex("[{}]")))
        }
        val expression = StringBuilder("\\A")
        if (tokens.isEmpty()) {
            expression.append(Pattern.quote(source)).append("\\z")
        } else {
            expression.append(Pattern.quote(source.substring(0, tokens.first().range.first)))
            tokens.forEachIndexed { index, token ->
                val end = tokens.getOrNull(index + 1)?.range?.first ?: source.length
                val following = source.substring(token.range.last + 1, end)
                require(following.isNotEmpty() || index == tokens.lastIndex)
                // Commit to the first valid boundary. A later failure cannot widen an earlier slot.
                expression.append("(?>(?<s").append(index).append('>')
                    .append(slotExpression(template.slots.getValue(token.groupValues[1])))
                    .append(')').append(Pattern.quote(following))
                if (index == tokens.lastIndex) {
                    expression.append("\\z")
                } else {
                    // A space inside a registered multiword name is not its boundary before a URL or number.
                    val next = tokens[index + 1].groupValues[1]
                    expression.append(
                        "(?="
                    ).append(slotExpression(template.slots.getValue(next))).append(')')
                }
                expression.append(')')
            }
        }
        return Compiled(template, Regex(expression.toString()), tokens.map { it.groupValues[1] })
    }

    private companion object {
        val intensities = (1..12).map(Int::toString).toSet() +
            listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII") +
            ('Ⅰ'..'Ⅻ').map(Char::toString)
        val languages = TranslationTarget.entries.map { it.languageTag }.toSet()
        val variable = Regex("\\{([a-z][a-z0-9_]*)\\}")
        val time = DateTimeFormatter.ofPattern(
            "HH:mm",
            Locale.ROOT
        ).withResolverStyle(ResolverStyle.STRICT)
        val dateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)

        fun normalize(body: String): String? {
            if (body.length !in 1..8192 || body.any {
                    (it.isISOControl() && it !in "\t\r\n") ||
                        Character.getType(it) == Character.FORMAT.toInt()
                }
            ) {
                return null
            }
            val normalized = Normalizer.normalize(body, Normalizer.Form.NFC)
            val output = StringBuilder(normalized.length)
            var pendingSpace = false
            for (character in normalized) {
                if (character == ' ' || character == '\t' || character == '\r' ||
                    character == '\n'
                ) {
                    pendingSpace = output.isNotEmpty()
                } else {
                    if (pendingSpace) output.append(' ')
                    output.append(character)
                    pendingSpace = false
                }
            }
            return output.takeIf { it.isNotEmpty() }?.toString()
        }

        fun slotExpression(kind: StandardSlotKind): String = when (kind) {
            StandardSlotKind.TIME -> "(?:[01][0-9]|2[0-3]):[0-5][0-9]"

            StandardSlotKind.DATE_TIME ->
                "20[0-9]{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01]) " +
                    "(?:[01][0-9]|2[0-3]):[0-5][0-9]"

            StandardSlotKind.MAGNITUDE -> "(?:[0-9]|10)\\.[0-9]"

            StandardSlotKind.DISTANCE -> "[0-9]{1,4}(?:\\.[0-9])?"

            StandardSlotKind.NUMBER -> "[0-9]{1,4}"

            StandardSlotKind.INTENSITY -> "(?:1[0-2]|[1-9]|[IVX]{1,4}|[Ⅰ-Ⅻ])"

            StandardSlotKind.AREA, StandardSlotKind.AGENCY ->
                "[\\p{L}\\p{N}][\\p{L}\\p{N} .·’'()/-]{0,119}?"

            StandardSlotKind.URL -> "https?://[^\\s<>\\[\\]{}]{1,256}?"
        }
    }
}
