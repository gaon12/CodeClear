package xyz.gaon.codeclear.probe

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import xyz.gaon.codeclear.translation.TranslationTarget

object ProbeSourceTitleResources {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(40))
    private val indexes = linkedMapOf<String, ProbeHeadingIndex>()
    private val names = buildMap {
        put("cmas_presidential_level_alert", ProbeHeadingKind.NATIONAL)
        put("cmas_extreme_alert", ProbeHeadingKind.EXTREME)
        put("cmas_severe_alert", ProbeHeadingKind.SEVERE)
        put("cmas_amber_alert", ProbeHeadingKind.AMBER)
        put("public_safety_message", ProbeHeadingKind.PUBLIC_SAFETY)
        put("cmas_required_monthly_test", ProbeHeadingKind.TEST)
        put("cmas_exercise_alert", ProbeHeadingKind.TEST)
        put("cmas_operator_defined_alert", ProbeHeadingKind.OPERATOR_DEFINED)
        put("state_local_test_alert", ProbeHeadingKind.TEST)
        put("etws_test_message", ProbeHeadingKind.TEST)
        put("etws_earthquake_warning", ProbeHeadingKind.EARTHQUAKE)
        put("etws_tsunami_warning", ProbeHeadingKind.TSUNAMI)
        put("etws_earthquake_and_tsunami_warning", ProbeHeadingKind.EARTHQUAKE_AND_TSUNAMI)
        put("etws_other_emergency_type", ProbeHeadingKind.OTHER_EMERGENCY)
        put("pws_other_message_identifiers", ProbeHeadingKind.OTHER_EMERGENCY)
        put("cb_other_message_identifiers", ProbeHeadingKind.OTHER)
        for ((severity, kind) in mapOf(
            "extreme" to ProbeHeadingKind.EXTREME,
            "severe" to ProbeHeadingKind.SEVERE
        )) {
            for (urgency in listOf("immediate", "expected")) {
                for (certainty in listOf("observed", "likely")) {
                    put(
                        "cmas_${severity}_${urgency}_${certainty}_alert",
                        kind
                    )
                }
            }
        }
    }

    fun enrich(context: Context, generation: Long, recordId: Long, observation: ProbeObservation) {
        if (observation.origin != ProbeOrigin.SYSTEM_CANDIDATE || observation.title.isBlank() ||
            observation.limited || observation.bodyEvidence == ProbeBodyEvidence.UNVERIFIED
        ) {
            return
        }
        val application = context.applicationContext
        val packageName = observation.packageName
        val title = observation.title
        try {
            worker.execute {
                val hint = try {
                    loadIndex(application, packageName).infer(title)
                } catch (_: Exception) {
                    ProbeHeadingKind.UNKNOWN
                }
                if (hint != ProbeHeadingKind.UNKNOWN) {
                    main.post { ProbeSession.annotateHeading(generation, recordId, hint) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Keep the captured original. A full hint queue must never block collection.
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun loadIndex(context: Context, packageName: String): ProbeHeadingIndex {
        require(packageName in ProbeSourcePolicy.candidatePackages)
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val application = requireNotNull(info.applicationInfo)
        require(
            application.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) !=
                0
        )
        val configuration = Configuration(context.resources.configuration)
        val systemLocales = Resources.getSystem().configuration.locales
        val key = "$packageName|${info.lastUpdateTime}|$configuration|$systemLocales"
        indexes[key]?.let { return it }
        val source = context.createPackageContext(packageName, 0)
        val locales = buildSet {
            repeat(minOf(systemLocales.size(), 4)) { add(systemLocales[it]) }
            repeat(minOf(configuration.locales.size(), 4)) { add(configuration.locales[it]) }
            TranslationTarget.entries.forEach { add(Locale.forLanguageTag(it.languageTag)) }
        }
        val labels = mutableListOf<Pair<ProbeHeadingKind, String>>()
        for (locale in locales) {
            val localized = Configuration(configuration).apply { setLocales(LocaleList(locale)) }
            val resources = source.createConfigurationContext(localized).resources
            for ((name, kind) in names) {
                // Foreign package IDs are not stable across modules; resolve only these reviewed names.
                val id = resources.getIdentifier(name, "string", packageName)
                if (id != 0) labels += kind to resources.getString(id)
            }
        }
        val index = ProbeHeadingIndex(labels)
        if (indexes.size >= 3) indexes.remove(indexes.keys.first())
        indexes[key] = index
        return index
    }
}
