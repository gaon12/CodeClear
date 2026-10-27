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
    val target: String? = null
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

    fun prepare(body: String, target: LocalTarget, approved: Boolean) {
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
            } else if (source == null) {
                finish(PreparationPhase.UNKNOWN_LANGUAGE)
            } else if (source == target.modelCode) {
                finish(PreparationPhase.READY)
            } else {
                publish(state.copy(phase = PreparationPhase.WAITING_FOR_WIFI))
                backend.download(setOf(source, target.modelCode) - "en") { success ->
                    if (ticket == generation) {
                        finish(if (success) PreparationPhase.READY else PreparationPhase.FAILED)
                    }
                }
            }
        }
    }

    /** Withdrawal invalidates UI/work callbacks; ML Kit exposes no download-cancellation contract. */
    fun withdraw() {
        check(Looper.myLooper() == Looper.getMainLooper())
        // Retain the active slot until its SDK task completes to prevent duplicate downloads.
        withdrawn = true
        if (active) {
            publish(PreparationState(PreparationPhase.WAITING_FOR_WIFI, state.target))
        } else {
            publish(PreparationState())
        }
    }

    private fun finish(phase: PreparationPhase) {
        active = false
        publish(state.copy(phase = if (withdrawn) PreparationPhase.IDLE else phase))
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
