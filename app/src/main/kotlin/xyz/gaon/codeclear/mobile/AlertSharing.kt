package xyz.gaon.codeclear.mobile

import android.content.Context
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationEngineKind

/** Share the complete original, with a clearly identified ready translation when available. */
internal fun alertShareText(
    alert: SavedAlert,
    target: String,
    engine: TranslationEngineKind,
    strings: Context
): String {
    val translated = alert.translation?.takeIf {
        it.phase == TranslationPhase.READY && it.engine == engine &&
            !it.text.isNullOrBlank() && it.text != alert.capture.original
    }?.text ?: return alert.capture.original
    return strings.getString(R.string.mobile_original) + "\n" + alert.capture.original + "\n\n" +
        strings.getString(R.string.mobile_translation, languageName(target)) + "\n" + translated +
        "\n\n" + strings.getString(R.string.mobile_translation_short_notice)
}
