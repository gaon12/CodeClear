package xyz.gaon.codeclear.mobile

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.nearby.BleRelay
import xyz.gaon.codeclear.nearby.NearbyRadioFactory
import xyz.gaon.codeclear.nearby.NearbySharing
import xyz.gaon.codeclear.translation.TranslationTarget

/** Explicit recording session: real product UI and BLE, isolated history and synthetic text. */
class NearbyReviewRecordingDeviceTest {
    @Test
    fun recordUserControlledNearbyReview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(BuildConfig.DEBUG && context.packageName == "xyz.gaon.codeclear.validation")
        assumeTrue(InstrumentationRegistry.getArguments().getString("record-nearby") == "true")
        val role = InstrumentationRegistry.getArguments().getString("role")
        require(role in setOf("sender", "receiver", "legacy"))
        val ready = File(context.cacheDir, "nearby-review.ready")
        val send = File(context.cacheDir, "nearby-review.send")
        val stop = File(context.cacheDir, "nearby-review.stop")
        val status = File(context.cacheDir, "nearby-review.status")
        val ui = File(context.cacheDir, "nearby-review.ui")
        val files = listOf(ready, send, stop, status, ui)
        files.forEach { it.delete() }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val runtime = activity.mobileRuntime
        val radioEvents = ConcurrentLinkedQueue<String>()
        // Attach metadata-only diagnostics without changing the release runtime or BLE behavior.
        val factoryField = NearbySharing::class.java.getDeclaredField("factory").apply {
            isAccessible = true
        }
        val originalFactory = factoryField.get(runtime.nearby)
        factoryField.set(
            runtime.nearby,
            NearbyRadioFactory { sender, known, received, statusChanged ->
                BleRelay(context, sender, known, received, statusChanged) { event, code ->
                    if (radioEvents.size < 64) radioEvents.add("$event:$code")
                }
            }
        )
        var backgroundObserved = false
        var received = false
        var offered = false
        try {
            val loadDeadline = SystemClock.elapsedRealtime() + 10_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < loadDeadline) {
                instrumentation.runOnMainSync { loaded = runtime.state.loaded }
                if (!loaded) SystemClock.sleep(50)
            }
            instrumentation.runOnMainSync {
                assertTrue(loaded)
                assertTrue("Use an empty validation install", runtime.state.records.isEmpty())
                assertTrue(runtime.state.latest == null)
                runtime.controller.pauseCapture(true)
                runtime.configure(
                    MobileOptions(
                        setupComplete = true,
                        target = TranslationTarget.ENGLISH,
                        theme = ThemeChoice.LIGHT
                    )
                )
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ready.writeText(
                "Isolated synthetic review ready; ordinary capture and translation off."
            )
            val deadline = SystemClock.elapsedRealtime() + 360_000
            while (!stop.isFile && SystemClock.elapsedRealtime() < deadline) {
                var snapshot = ""
                instrumentation.runOnMainSync {
                    assertFalse(runtime.state.options.notificationConsent)
                    assertFalse(runtime.state.options.accessibilityConsent)
                    assertFalse(runtime.state.options.translationAllowed)
                    val nearby = runtime.nearby.state
                    backgroundObserved = backgroundObserved || nearby.backgroundRunning
                    if (role == "sender" && send.isFile && !offered) {
                        assertTrue(runtime.state.options.nearbyEnabled)
                        runtime.nearby.offer(
                            9_100_001,
                            CapturedAlert(
                                "test.synthetic.receiver",
                                CaptureSource.NOTIFICATION,
                                "[DRILL] Nearby service review",
                                BODY,
                                System.currentTimeMillis()
                            )
                        )
                        offered = true
                    }
                    assertTrue(
                        runtime.state.records.all {
                            it.capture.original == BODY && it.capture.source == CaptureSource.PEER
                        }
                    )
                    received = received || runtime.state.records.any { it.capture.original == BODY }
                    snapshot = "status=${nearby.status}\nbackground=${nearby.backgroundRunning}\n" +
                        "received=$received\noffered=$offered\nradio=${radioEvents.toList()}\n"
                }
                status.writeText(snapshot)
                // Reuse the runner's collection-preserving connection instead of another UI agent.
                val automation = instrumentation.uiAutomation
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                ui.writeText(reviewControls(automation.rootInActiveWindow))
                SystemClock.sleep(500)
            }
            assertTrue("The explicit recording controller must stop the session", stop.isFile)
            if (role != "legacy") assertTrue("Real foreground service must run", backgroundObserved)
            if (role ==
                "receiver"
            ) {
                assertTrue("Exact synthetic original must arrive over BLE", received)
            }
            if (role ==
                "sender"
            ) {
                assertTrue("The controller must offer the synthetic original", offered)
            }
            instrumentation.runOnMainSync { assertFalse(runtime.nearby.state.backgroundRunning) }
        } finally {
            files.forEach { it.delete() }
            instrumentation.runOnMainSync {
                runtime.stopNearbySharing()
                factoryField.set(runtime.nearby, originalFactory)
                runtime.controller.pauseCapture(false)
                if (!activity.isDestroyed) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.finish()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun reviewControls(root: AccessibilityNodeInfo?): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root?.refresh()
        val labels = mapOf(
            "settings" to context.getString(R.string.mobile_settings),
            "about" to context.getString(R.string.mobile_about),
            "version" to context.getString(R.string.mobile_version),
            "done" to context.getString(R.string.mobile_done),
            "labs" to context.getString(R.string.mobile_labs),
            "nearby" to context.getString(R.string.mobile_nearby),
            "nearby_allow" to context.getString(R.string.mobile_nearby_allow),
            "allow" to context.getString(R.string.mobile_allow),
            "history" to context.getString(R.string.mobile_history),
            "home" to context.getString(R.string.mobile_home),
            "original" to BODY,
            "os_allow" to "Allow",
            "os_allow_ko" to "허용",
            "os_foreground" to "While using the app",
            "os_background" to "Allow all the time"
        )
        val owner = root?.packageName?.toString().orEmpty()
        val allowed = owner in setOf(
            context.packageName,
            "android",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.openai.amoledblack"
        )
        val controls = JSONArray()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        if (root != null) pending.add(root)
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (allowed && owner != "com.openai.amoledblack" && visited++ < 512) {
                if (node.isVisibleToUser && node.isEnabled) {
                    val text = listOf(node.text, node.contentDescription)
                        .flatMap { it?.toString().orEmpty().split('\n', ',') }
                        .map(String::trim)
                    labels.filterValues { it in text }.keys.forEach { key ->
                        val bounds = Rect().also(node::getBoundsInScreen)
                        controls.put(
                            JSONObject().put("key", key).put("x", bounds.centerX())
                                .put("y", bounds.centerY()).put("width", bounds.width())
                                .put("height", bounds.height()).put("checked", node.isChecked)
                        )
                    }
                }
                for (index in 0 until node.childCount.coerceAtMost(128)) {
                    node.getChild(index)?.let(pending::addLast)
                }
            }
            node.recycle()
        }
        // Only reviewed static labels and geometry are exposed, never arbitrary screen text.
        return JSONObject().put("allowed", allowed).put("controls", controls).toString()
    }

    companion object {
        private const val BODY = "[DRILL] Review only. Stay indoors. Gate 3. Call 112."
    }
}
