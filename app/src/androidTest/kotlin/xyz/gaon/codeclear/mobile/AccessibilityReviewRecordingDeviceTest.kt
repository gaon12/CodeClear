package xyz.gaon.codeclear.mobile

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.MainActivity

/** Explicit emulator-only session for recording the real consent UI with synthetic alerts. */
class AccessibilityReviewRecordingDeviceTest {
    @Test
    fun recordSyntheticConsentAndCaptureReview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(BuildConfig.DEBUG && context.packageName == "xyz.gaon.codeclear.validation")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ready = File(context.cacheDir, "accessibility-review.ready")
        val stop = File(context.cacheDir, "accessibility-review.stop")
        ready.delete()
        stop.delete()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        try {
            instrumentation.runOnMainSync {
                activity.window.clearFlags(secureFlag)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ready.writeText("Synthetic emulator review session is ready.")
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (!stop.isFile && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
            }
            assertTrue("The explicit review controller must finish the recording", stop.isFile)
            assertTrue("The recorded activity must remain the same instance", !activity.isDestroyed)
        } finally {
            ready.delete()
            stop.delete()
            instrumentation.runOnMainSync {
                if (!activity.isDestroyed) {
                    activity.window.addFlags(secureFlag)
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    assertTrue(activity.window.attributes.flags and secureFlag != 0)
                    activity.finish()
                }
            }
        }
    }
}
