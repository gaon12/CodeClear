package xyz.gaon.codeclear.translation

/** Exact, jurisdiction-scoped names. Ambiguous aliases deliberately resolve to nothing. */
class StandardNameCatalog(entries: List<Entry>) : StandardNameResolver {
    data class Entry(
        val id: String,
        val jurisdiction: String,
        val kind: StandardSlotKind,
        val nativeLanguage: String,
        val nativeName: String,
        val aliases: Set<String> = emptySet(),
        val translations: Map<String, String>,
        val referenceId: String
    )

    private data class Key(val jurisdiction: String, val kind: StandardSlotKind, val name: String)
    private val index: Map<Key, List<Entry>>

    init {
        require(entries.size <= 10000 && entries.map { it.id }.distinct().size == entries.size)
        index = entries.flatMap { entry ->
            require(entry.kind in setOf(StandardSlotKind.AREA, StandardSlotKind.AGENCY))
            require(entry.nativeName.isNotBlank() && entry.referenceId.isNotBlank())
            val snapshot = entry.copy(
                aliases = entry.aliases.toSet(),
                translations = entry.translations.toMap()
            )
            (snapshot.aliases + snapshot.nativeName).map {
                require(it.isNotBlank() && it.length <= 120)
                Key(snapshot.jurisdiction, snapshot.kind, it) to snapshot
            }
        }.groupBy({ it.first }, { it.second })
    }

    override fun resolve(
        jurisdiction: String,
        kind: StandardSlotKind,
        original: String,
        target: String
    ): String? {
        val entry = index[Key(jurisdiction, kind, original)]?.singleOrNull() ?: return null
        return if (target == entry.nativeLanguage) entry.nativeName else entry.translations[target]
    }
}
