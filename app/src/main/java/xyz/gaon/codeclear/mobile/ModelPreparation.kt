package xyz.gaon.codeclear.mobile

import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import xyz.gaon.codeclear.probe.LocalTarget

enum class PreparationPhase { IDLE, IDENTIFYING, WAITING_FOR_WIFI, READY, UNKNOWN_LANGUAGE, FAILED }

data class PreparationState(
    val phase: PreparationPhase = PreparationPhase.IDLE,
    val target: String? = null,
    val modelLanguage: String? = null,
    val languages: Set<String> = emptySet(),
    val completedLanguages: Set<String> = emptySet(),
    val activeLanguage: String? = null,
    val checkUpdates: Boolean = false
)

interface ModelPreparationBackend {
    fun identify(body: String, complete: (String?) -> Unit)
    fun download(languages: Set<String>, complete: (Boolean) -> Unit)
}

/** Keeps at most one explicitly approved preparation active, including SDK-managed Wi-Fi waits. */
class ModelPreparation(
    private val backend: ModelPreparationBackend,
    private val changed: (PreparationState) -> Unit,
    private val ready: (String) -> Unit
) {
    var state = PreparationState()
        private set
    private var generation = 0L
    private var active = false
    private var withdrawn = false
    val busy: Boolean get() = active

    fun prepareModel(
        language: String,
        approved: Boolean,
        checkUpdates: Boolean = true,
        installed: Set<String> = emptySet()
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(language in managedModelLanguages)
        if (!approved || active) return false
        active = true
        withdrawn = false
        val ticket = ++generation
        val complete = if (checkUpdates) emptySet() else installed.intersect(setOf(language))
        publish(
            PreparationState(
                PreparationPhase.WAITING_FOR_WIFI,
                modelLanguage = language,
                languages = setOf(language),
                completedLanguages = complete,
                checkUpdates = checkUpdates
            )
        )
        downloadNext(listOf(language).filterNot { it in complete }, ticket)
        return true
    }

    fun prepare(
        body: String,
        target: LocalTarget,
        approved: Boolean,
        installed: Set<String> = emptySet()
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!approved || active) return
        require(body.isNotBlank() && body.length <= 8192)
        active = true
        withdrawn = false
        val ticket = ++generation
        publish(PreparationState(PreparationPhase.IDENTIFYING, target.languageTag))
        backend.identify(body) { source ->
            if (ticket != generation) return@identify
            if (withdrawn) {
                finish(PreparationPhase.IDLE)
            } else if (source == null || (source != "en" && source !in managedModelLanguages)) {
                finish(PreparationPhase.UNKNOWN_LANGUAGE)
            } else if (source == target.modelCode) {
                finish(PreparationPhase.READY)
            } else {
                val required = setOf(source, target.modelCode) - "en"
                val complete = required.intersect(installed)
                publish(
                    state.copy(
                        phase = PreparationPhase.WAITING_FOR_WIFI,
                        languages = required,
                        completedLanguages = complete
                    )
                )
                downloadNext((required - complete).toList(), ticket)
            }
        }
    }

    private fun downloadNext(remaining: List<String>, ticket: Long) {
        if (ticket != generation) return
        if (withdrawn) return finish(PreparationPhase.IDLE)
        val language = remaining.firstOrNull() ?: return finish(PreparationPhase.READY)
        publish(state.copy(activeLanguage = language))
        backend.download(setOf(language)) { success ->
            if (ticket != generation) return@download
            if (success) {
                publish(
                    state.copy(completedLanguages = state.completedLanguages + language)
                )
            }
            when {
                withdrawn -> finish(PreparationPhase.IDLE)
                !success -> finish(PreparationPhase.FAILED)
                else -> downloadNext(remaining.drop(1), ticket)
            }
        }
    }

    /** Withdrawal invalidates UI/work callbacks; ML Kit exposes no download-cancellation contract. */
    fun withdraw() {
        check(Looper.myLooper() == Looper.getMainLooper())
        // Explicit model management remains independent of the automatic translation choice.
        if (active && state.modelLanguage != null) return
        // Retain the active slot until its SDK task completes to prevent duplicate downloads.
        withdrawn = true
        if (active) {
            publish(state.copy(phase = PreparationPhase.WAITING_FOR_WIFI))
        } else {
            publish(PreparationState())
        }
    }

    private fun finish(phase: PreparationPhase) {
        active = false
        publish(
            state.copy(
                phase = if (withdrawn) PreparationPhase.IDLE else phase,
                activeLanguage = if (phase ==
                    PreparationPhase.FAILED
                ) {
                    state.activeLanguage
                } else {
                    null
                }
            )
        )
        if (!withdrawn && phase == PreparationPhase.READY) state.target?.let(ready)
    }

    private fun publish(value: PreparationState) {
        state = value
        changed(value)
    }
}

/** Alert text is used only for bundled language identification and never sent to model downloads. */
class MlKitModelPreparationBackend : ModelPreparationBackend {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))

    override fun identify(body: String, complete: (String?) -> Unit) {
        worker.execute {
            val source = try {
                LanguageIdentification.getClient().use {
                    TranslateLanguage.fromLanguageTag(
                        Tasks.await(it.identifyLanguage(body), 15, TimeUnit.SECONDS)
                    )
                }
            } catch (_: Exception) {
                null
            }
            main.post { complete(source) }
        }
    }

    override fun download(languages: Set<String>, complete: (Boolean) -> Unit) {
        require(languages.size <= 2)
        try {
            val manager = RemoteModelManager.getInstance()
            val conditions = DownloadConditions.Builder().requireWifi().build()
            val tasks = languages.map {
                manager.download(TranslateRemoteModel.Builder(it).build(), conditions)
            }
            Tasks.whenAllComplete(tasks).addOnCompleteListener {
                complete(tasks.all { task -> task.isSuccessful })
            }
        } catch (_: RuntimeException) {
            main.post { complete(false) }
        }
    }
}
