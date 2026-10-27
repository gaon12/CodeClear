package xyz.gaon.codeclear.mobile

import android.content.Context
import android.os.Looper
import java.util.Locale
import xyz.gaon.codeclear.probe.LocalTarget

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

data class MobileOptions(
    val notificationConsent: Boolean = false,
    val accessibilityConsent: Boolean = false,
    val localConsent: Boolean = false,
    val target: LocalTarget = defaultTarget(),
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val uiLanguage: String = "",
    val setupComplete: Boolean = false
)

fun defaultTarget(): LocalTarget {
    val locale = Locale.getDefault()
    return if (locale.language == "zh") {
        if (locale.script == "Hant" ||
            locale.country in setOf("TW", "HK", "MO")
        ) {
            LocalTarget.TRADITIONAL_CHINESE
        } else {
            LocalTarget.SIMPLIFIED_CHINESE
        }
    } else {
        LocalTarget.entries.firstOrNull { it.languageTag == locale.language } ?: LocalTarget.ENGLISH
    }
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
        val target =
            LocalTarget.entries.firstOrNull { it.name == saved.getString("target", null) }
                ?: defaultTarget()
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
            localConsent = currentDisclosure && saved.getBoolean("local_consent", false),
            target = target,
            theme = theme,
            uiLanguage = language,
            setupComplete = currentDisclosure && saved.getBoolean("setup_complete", false)
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
