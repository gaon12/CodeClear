package xyz.gaon.codeclear.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.nl.translate.TranslateLanguage
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.capture.ProbeSession
import xyz.gaon.codeclear.nearby.NearbyBackgroundService
import xyz.gaon.codeclear.nearby.NearbySharing
import xyz.gaon.codeclear.translation.MlKitLocalEngine
import xyz.gaon.codeclear.translation.MyMemoryEngine
import xyz.gaon.codeclear.translation.SystemEngineAvailability
import xyz.gaon.codeclear.translation.SystemLocalEngine
import xyz.gaon.codeclear.translation.TranslationEngine
import xyz.gaon.codeclear.translation.TranslationEngineKind

class MobileApplication : Application() {
    lateinit var runtime: MobileRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = MobileRuntime(this)
        runtime.controller.start()
    }
}

/** The process owns capture and jobs; activity recreation never creates another translation queue. */
class MobileRuntime(context: Context) {
    private val application = context.applicationContext
    var state by mutableStateOf(MobileState())
        private set
    var preparation by mutableStateOf(PreparationState())
        private set
    var modelInventory by mutableStateOf(ModelInventoryState())
        private set
    val inventory = ModelInventory(MlKitModelInventoryBackend(), ::updateInventory)
    var modelDownload by mutableStateOf<ModelDownloadRequest?>(null)
        private set
    val detailLanguages = OriginalLanguageIdentifier()
    val speech = SpeechPlayback(context)
    val nearby: NearbySharing = NearbySharing(context, retain = ::retainNearby)
    private val notifications = ResultNotifications(context)
    private val mlKit = MlKitLocalEngine()
    private val myMemory = MyMemoryEngine(context)
    var systemAvailability by mutableStateOf(SystemEngineAvailability())
        private set
    val systemEngine = SystemLocalEngine(context) { systemAvailability = it }
    private val engine = TranslationEngine { input, download, complete ->
        when (input.engine) {
            TranslationEngineKind.ML_KIT -> mlKit.run(input, download, complete)
            TranslationEngineKind.SYSTEM -> systemEngine.run(input, download, complete)
            TranslationEngineKind.MY_MEMORY -> myMemory.run(input, false, complete)
        }
    }
    private var diagnosticOwners = 0
    private var sharingConfigured = false
    private var diagnosticGeneration = 0L
    val controller: MobileController = MobileController(
        context,
        AlertHistoryStore(context),
        MobileOptionsStorage(context),
        engine,
        changed = {
            state = it
            if (it.storageError || !it.options.translationAllowed ||
                it.options.engine != TranslationEngineKind.MY_MEMORY
            ) {
                myMemory.authorize(false)
            }
            if (it.loaded) {
                val enabled = it.options.labsEnabled && it.options.nearbyEnabled && !it.storageError
                nearby.configure(enabled)
                if (!enabled && sharingConfigured) {
                    application.stopService(
                        Intent(application, NearbyBackgroundService::class.java)
                    )
                }
                sharingConfigured = enabled
            }
        },
        translated = { id, target, producer ->
            val language = state.options.uiLanguage
            val localized = if (Build.VERSION.SDK_INT < 33 && language.isNotEmpty()) {
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        setLocales(LocaleList.forLanguageTags(language))
                    }
                )
            } else {
                context
            }
            notifications.post(id, target, localized, producer)
        },
        deleted = {
            notifications.cancel(it)
            nearby.deleted(it)
        },
        releaseEngine = {
            mlKit.close()
            systemEngine.close()
            myMemory.close()
        },
        cancelEngine = {
            systemEngine.cancel()
            myMemory.cancel()
        },
        captured = nearby::offer,
        optionsReady = {
            myMemory.authorize(
                it.engine == TranslationEngineKind.MY_MEMORY && it.translationAllowed
            )
        }
    )
    val models =
        ModelPreparation(MlKitModelPreparationBackend(), {
            inventory.preparationChanged(preparation, it)
            preparation = it
            if (it.modelLanguage != null && it.phase == PreparationPhase.READY) {
                controller.modelsPrepared()
            }
        }, { controller.modelsPrepared() })

    private fun updateInventory(value: ModelInventoryState) {
        val modelsChanged = value.loaded && !value.checking && !value.failed &&
            value.downloaded.isNotEmpty() &&
            (!modelInventory.loaded || value.downloaded != modelInventory.downloaded)
        modelInventory = value
        if (modelsChanged && state.loaded) controller.modelsPrepared()
    }

    fun requestModel(language: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(language in managedModelLanguages)
        if (models.busy) return showModelDownload()
        modelDownload = ModelDownloadRequest(setOf(language), checkUpdates = true)
        inventory.refresh(force = true)
    }

    fun requestTargetModels(
        target: xyz.gaon.codeclear.translation.TranslationTarget,
        alert: SavedAlert?
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (models.busy) return showModelDownload()
        if (alert == null) {
            if (target.modelCode == "en") return
            modelDownload = ModelDownloadRequest(setOf(target.modelCode))
        } else {
            val saved = state.detailSourceLanguage.takeIf { state.detailId == alert.id }
                ?: alert.translation?.sourceLanguage
            val source = saved?.let(TranslateLanguage::fromLanguageTag)
            val languages = if (source == target.modelCode) {
                emptySet()
            } else {
                setOfNotNull(source, target.modelCode) - "en"
            }
            modelDownload = ModelDownloadRequest(
                languages,
                alert.capture.original,
                target,
                sourceKnown = source != null
            )
        }
        inventory.refresh(force = true)
    }

    fun showModelDownload() {
        check(Looper.myLooper() == Looper.getMainLooper())
        modelDownload = modelDownload?.copy(visible = true) ?: ModelDownloadRequest(
            preparation.languages,
            started =
                models.busy || preparation.phase != PreparationPhase.IDLE
        )
    }

    fun closeModelDownload() {
        check(Looper.myLooper() == Looper.getMainLooper())
        modelDownload = if (models.busy) modelDownload?.copy(visible = false) else null
    }

    fun startModelDownload() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val request = modelDownload ?: return
        if (request.started || models.busy || !modelInventory.loaded || modelInventory.checking ||
            modelInventory.failed
        ) {
            return
        }
        modelDownload = request.copy(started = true, body = null, target = null)
        if (request.body != null) {
            models.prepare(request.body, request.target!!, true, modelInventory.downloaded)
        } else {
            val language = request.languages.singleOrNull() ?: return closeModelDownload()
            models.prepareModel(language, true, request.checkUpdates, modelInventory.downloaded)
        }
    }

    private fun retainNearby(
        original: xyz.gaon.codeclear.nearby.NearbyOriginal,
        allowed: () -> Boolean,
        complete: (Long?) -> Unit
    ) {
        controller.saveNearby(original, allowed, translate = false, complete = complete)
    }

    fun configure(options: MobileOptions) {
        if (options.serverAllowed != state.options.serverAllowed ||
            options.engine != state.options.engine ||
            options.labsEnabled != state.options.labsEnabled
        ) {
            myMemory.authorize(false)
        }
        val withdrawn = !options.localConsent && state.options.localConsent
        if (withdrawn || options.engine != state.options.engine) models.withdraw()
        if (withdrawn) notifications.cancel(null)
        controller.changeOptions(options)
    }

    fun stopNearbySharing() {
        nearby.configure(false)
        controller.stopNearbySharing()
    }

    fun pauseForDiagnostics(): AutoCloseable {
        check(BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper())
        val generation = diagnosticGeneration
        if (diagnosticOwners++ == 0) {
            controller.pauseCapture(true)
            nearby.suspendForDiagnostics(true)
        }
        var closed = false
        return AutoCloseable {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!closed && generation == diagnosticGeneration) {
                closed = true
                if (--diagnosticOwners == 0) {
                    ProbeSession.setDiagnostics(false, false)
                    controller.pauseCapture(false)
                    nearby.suspendForDiagnostics(false)
                }
            }
        }
    }

    fun diagnosticsActive(): Boolean = diagnosticOwners > 0

    fun resumeProduct() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (diagnosticOwners == 0 && !ProbeSession.state.hardwareOverride &&
            !ProbeSession.state.shellFixture
        ) {
            return
        }
        diagnosticGeneration++
        diagnosticOwners = 0
        ProbeSession.setDiagnostics(false, false)
        controller.pauseCapture(false)
        nearby.suspendForDiagnostics(false)
    }
}

val Context.mobileRuntime: MobileRuntime
    get() = (applicationContext as MobileApplication).runtime
