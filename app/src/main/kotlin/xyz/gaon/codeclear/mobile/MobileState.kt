package xyz.gaon.codeclear.mobile

import xyz.gaon.codeclear.translation.TranslationEngineKind

data class MobileState(
    val loaded: Boolean = false,
    val options: MobileOptions = MobileOptions(),
    val records: List<SavedAlert> = emptyList(),
    val latest: SavedAlert? = null,
    val detailId: Long? = null,
    val detailTarget: String? = null,
    val detailEngine: TranslationEngineKind? = null,
    val detailSourceLanguage: String? = null,
    val detail: SavedAlert? = null,
    val query: String = "",
    val beforeId: Long = Long.MAX_VALUE,
    val loading: Boolean = false,
    val storageError: Boolean = false
)
