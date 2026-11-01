package xyz.gaon.codeclear.mobile

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import xyz.gaon.codeclear.probe.LocalTarget

internal val primaryModelLanguages = LocalTarget.entries.map { it.modelCode }.toSet() - "en"
internal val managedModelLanguages = TranslateLanguage.getAllLanguages().toSet() - "en"

data class ModelInventoryState(
    val loaded: Boolean = false,
    val checking: Boolean = false,
    val failed: Boolean = false,
    val downloaded: Set<String> = emptySet(),
    val checkedAtMillis: Long? = null,
    val updateChecks: Map<String, Long> = emptyMap(),
    val failedUpdates: Set<String> = emptySet()
)

fun interface ModelInventoryBackend {
    fun downloaded(complete: (Set<String>?) -> Unit)
}

/** Inventory is read-only; download/update work belongs to the shared preparation slot. */
class ModelInventory(
    private val backend: ModelInventoryBackend,
    private val changed: (ModelInventoryState) -> Unit
) {
    var state = ModelInventoryState()
        private set
    private var inspectedAt = 0L
    private var refreshAfterUpdate = false

    fun preparationChanged(previous: PreparationState, current: PreparationState) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (previous.phase != PreparationPhase.WAITING_FOR_WIFI || current.phase !in setOf(
                PreparationPhase.READY,
                PreparationPhase.FAILED,
                PreparationPhase.IDLE
            )
        ) {
            return
        }
        val language = current.modelLanguage
        if (language == null) {
            refreshAfterPreparation()
        } else {
            updateFinished(language, current.phase == PreparationPhase.READY)
        }
    }

    fun refresh(force: Boolean = false) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (state.checking ||
            (!force && state.loaded && SystemClock.uptimeMillis() - inspectedAt < 30_000)
        ) {
            return
        }
        publish(state.copy(checking = true, failed = false))
        backend.downloaded { languages ->
            check(Looper.myLooper() == Looper.getMainLooper())
            inspectedAt = SystemClock.uptimeMillis()
            publish(
                state.copy(
                    loaded = true,
                    checking = false,
                    failed = languages == null,
                    downloaded = languages?.intersect(managedModelLanguages) ?: state.downloaded,
                    checkedAtMillis = if (languages ==
                        null
                    ) {
                        state.checkedAtMillis
                    } else {
                        System.currentTimeMillis()
                    }
                )
            )
            if (refreshAfterUpdate) {
                refreshAfterUpdate = false
                refresh(force = true)
            }
        }
    }

    fun updateFinished(language: String, success: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(language in managedModelLanguages)
        publish(
            state.copy(
                updateChecks = if (success) {
                    state.updateChecks +
                        (language to System.currentTimeMillis())
                } else {
                    state.updateChecks
                },
                failedUpdates = if (success) {
                    state.failedUpdates - language
                } else {
                    state.failedUpdates +
                        language
                }
            )
        )
        refreshAfterPreparation()
    }

    private fun refreshAfterPreparation() {
        if (state.checking) refreshAfterUpdate = true else refresh(force = true)
    }

    private fun publish(value: ModelInventoryState) {
        state = value
        changed(value)
    }
}

class MlKitModelInventoryBackend : ModelInventoryBackend {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    override fun downloaded(complete: (Set<String>?) -> Unit) {
        worker.execute {
            val result = try {
                Tasks.await(
                    RemoteModelManager.getInstance().getDownloadedModels(
                        TranslateRemoteModel::class.java
                    ),
                    15,
                    TimeUnit.SECONDS
                ).asSequence().take(256).map { it.language }.toSet()
            } catch (_: Exception) {
                null
            }
            main.post { complete(result) }
        }
    }
}
