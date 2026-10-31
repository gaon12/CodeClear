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
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.nearby.NearbyBackgroundService
import xyz.gaon.codeclear.nearby.NearbySharing
import xyz.gaon.codeclear.probe.LocalEngine
import xyz.gaon.codeclear.probe.LocalEngineKind
import xyz.gaon.codeclear.probe.MlKitLocalEngine
import xyz.gaon.codeclear.probe.ProbeSession
import xyz.gaon.codeclear.probe.SystemEngineAvailability
import xyz.gaon.codeclear.probe.SystemLocalEngine

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
    val detailLanguages = OriginalLanguageIdentifier()
    val nearby: NearbySharing = NearbySharing(context, retain = ::retainNearby)
    private val notifications = ResultNotifications(context)
    private val mlKit = MlKitLocalEngine()
    var systemAvailability by mutableStateOf(SystemEngineAvailability())
        private set
    val systemEngine = SystemLocalEngine(context) { systemAvailability = it }
    private val engine = LocalEngine { input, download, complete ->
        when (input.engine) {
            LocalEngineKind.ML_KIT -> mlKit.run(input, download, complete)
            LocalEngineKind.SYSTEM -> systemEngine.run(input, download, complete)
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
        },
        cancelEngine = systemEngine::cancel,
        captured = nearby::offer
    )
    val models =
        ModelPreparation(MlKitModelPreparationBackend(), {
            preparation = it
        }, { controller.modelsPrepared(it) })

    private fun retainNearby(
        original: xyz.gaon.codeclear.nearby.NearbyOriginal,
        allowed: () -> Boolean,
        complete: (Long?) -> Unit
    ) {
        controller.saveNearby(original, allowed, translate = false, complete = complete)
    }

    fun configure(options: MobileOptions) {
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
