package xyz.gaon.codeclear.mobile

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.probe.MlKitLocalEngine
import xyz.gaon.codeclear.probe.ProbeSession

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
    var state by mutableStateOf(MobileState())
        private set
    var preparation by mutableStateOf(PreparationState())
        private set
    private val notifications = ResultNotifications(context)
    private val engine = MlKitLocalEngine()
    private var diagnosticOwners = 0
    private var diagnosticGeneration = 0L
    val controller = MobileController(
        context,
        AlertHistoryStore(context),
        MobileOptionsStorage(context),
        engine,
        changed = { state = it },
        translated = notifications::post,
        deleted = notifications::cancel,
        releaseEngine = engine::close
    )
    val models =
        ModelPreparation(MlKitModelPreparationBackend(), {
            preparation = it
        }, controller::modelsPrepared)

    fun configure(options: MobileOptions) {
        if (!options.localConsent && state.options.localConsent) models.withdraw()
        controller.changeOptions(options)
    }

    fun pauseForDiagnostics(): AutoCloseable {
        check(BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper())
        val generation = diagnosticGeneration
        if (diagnosticOwners++ == 0) controller.pauseCapture(true)
        var closed = false
        return AutoCloseable {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!closed && generation == diagnosticGeneration) {
                closed = true
                if (--diagnosticOwners == 0) {
                    ProbeSession.setDiagnostics(false, false)
                    controller.pauseCapture(false)
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
    }
}

val Context.mobileRuntime: MobileRuntime
    get() = (applicationContext as MobileApplication).runtime
