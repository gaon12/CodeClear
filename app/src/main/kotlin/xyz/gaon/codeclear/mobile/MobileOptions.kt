package xyz.gaon.codeclear.mobile

import android.content.Context
import android.content.res.Resources
import android.os.Looper
import androidx.core.app.LocaleManagerCompat
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

enum class ThemeChoice { SYSTEM, LIGHT, DARK }
enum class MessageViewMode { BOTH, SWITCH }

data class MobileOptions(
    val notificationConsent: Boolean = false,
    val accessibilityConsent: Boolean = false,
    val localConsent: Boolean = false,
    val target: TranslationTarget = defaultTarget(),
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val uiLanguage: String = "",
    val setupComplete: Boolean = false,
    val engine: TranslationEngineKind = TranslationEngineKind.ML_KIT,
    val nearbyEnabled: Boolean = false,
    val labsEnabled: Boolean = false,
    val messageViewMode: MessageViewMode = MessageViewMode.SWITCH,
    val serverAllowed: Boolean = false
) {
    val translationAllowed: Boolean get() = if (engine == TranslationEngineKind.MY_MEMORY) {
        labsEnabled && serverAllowed
    } else {
        localConsent
    }
}

fun defaultTarget(
    systemLanguages: String = Resources.getSystem().configuration.locales.toLanguageTags()
): TranslationTarget {
    // App-specific language changes must not replace the device language used for first-run defaults.
    val language = UiLanguages.normalize(systemLanguages)
    return TranslationTarget.entries.firstOrNull { it.languageTag == language }
        ?: TranslationTarget.ENGLISH
}

interface OptionsStorage {
    fun load(): MobileOptions
    fun save(options: MobileOptions)
}

/** Private preferences contain choices and disclosure versions, never alert text or credentials. */
class MobileOptionsStorage(
    private val context: Context,
    private val name: String = "mobile_options"
) : OptionsStorage {
    init {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,79}")))
    }
    override fun load(): MobileOptions {
        check(Looper.myLooper() != Looper.getMainLooper())
        val saved = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val currentDisclosure = saved.getInt("disclosure_version", 0) == 1
        val labsEnabled = saved.getBoolean("labs_enabled", false)
        val engineKey = saved.getString("local_engine", TranslationEngineKind.ML_KIT.key)
        val engine = TranslationEngineKind.entries.firstOrNull { it.key == engineKey }
            ?.takeIf { it != TranslationEngineKind.MY_MEMORY || labsEnabled }
        val target =
            TranslationTarget.entries.firstOrNull { it.name == saved.getString("target", null) }
                ?: defaultTarget(LocaleManagerCompat.getSystemLocales(context).toLanguageTags())
        val theme =
            ThemeChoice.entries.firstOrNull { it.name == saved.getString("theme", null) }
                ?: ThemeChoice.SYSTEM
        val language = saved.getString("ui_language", "").orEmpty().takeIf {
            it.isEmpty() ||
                it in HistoryText.targets
        }.orEmpty()
        return MobileOptions(
            notificationConsent =
                currentDisclosure && saved.getBoolean("notification_consent", false),
            accessibilityConsent =
                currentDisclosure && saved.getBoolean("accessibility_consent", false),
            localConsent =
                currentDisclosure && engine != null && saved.getBoolean("local_consent", false),
            target = target,
            theme = theme,
            uiLanguage = language,
            setupComplete = currentDisclosure && saved.getBoolean("setup_complete", false),
            engine = engine ?: TranslationEngineKind.ML_KIT,
            nearbyEnabled =
                currentDisclosure && labsEnabled && saved.getBoolean("nearby_enabled", false),
            labsEnabled = labsEnabled,
            serverAllowed =
                currentDisclosure && labsEnabled && engine == TranslationEngineKind.MY_MEMORY &&
                    saved.getInt("remote_disclosure_version", 0) == 1 &&
                    saved.getBoolean("server_allowed", false),
            messageViewMode = MessageViewMode.entries.firstOrNull {
                it.name == saved.getString("message_view_mode", null)
            } ?: MessageViewMode.SWITCH
        )
    }

    override fun save(options: MobileOptions) {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(options.uiLanguage.isEmpty() || options.uiLanguage in HistoryText.targets)
        val saved = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putInt("disclosure_version", 1)
            .putBoolean("notification_consent", options.notificationConsent)
            .putBoolean("accessibility_consent", options.accessibilityConsent)
            .putBoolean("local_consent", options.localConsent)
            .putInt("remote_disclosure_version", 1)
            .putBoolean("server_allowed", options.serverAllowed && options.labsEnabled)
            .putString("local_engine", options.engine.key)
            .putBoolean("nearby_enabled", options.nearbyEnabled)
            .putBoolean("labs_enabled", options.labsEnabled)
            .putString("message_view_mode", options.messageViewMode.name)
            .putBoolean("setup_complete", options.setupComplete)
            .putString("target", options.target.name).putString("theme", options.theme.name)
            .putString("ui_language", options.uiLanguage).commit()
        if (!saved) {
            // A failed preference write must not silently preserve an earlier capture opt-in.
            context.deleteSharedPreferences(name)
            error("Settings could not be saved")
        }
    }
}
