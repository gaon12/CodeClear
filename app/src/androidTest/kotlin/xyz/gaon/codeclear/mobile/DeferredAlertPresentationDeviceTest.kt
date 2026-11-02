package xyz.gaon.codeclear.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeRoute
import xyz.gaon.codeclear.capture.ProbeSession
import xyz.gaon.codeclear.capture.SourceWindowVisibility
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

class DeferredAlertPresentationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val options = MobileOptions(
        notificationConsent = true,
        accessibilityConsent = true,
        deferredOverlayEnabled = true,
        target = TranslationTarget.ENGLISH
    )
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun alert() = CapturedAlert(
        "test.system.receiver",
        CaptureSource.NOTIFICATION,
        "[DRILL]",
        "[DRILL] Original: Gate 3, 112.",
        System.currentTimeMillis(),
        postedAtMillis = System.currentTimeMillis()
    )

    private class Surface : DeferredAlertSurface {
        override val foreground = true
        var current: DeferredAlert? = null
        var dismissed: (() -> Unit)? = null
        var failShow = false
        var failedCalls = 0
        override fun show(candidate: DeferredAlert, options: MobileOptions, dismissed: () -> Unit) {
            if (failShow) {
                failedCalls++
                error("Synthetic window failure")
            }
            current = candidate
            this.dismissed = dismissed
        }
        override fun hide() {
            current = null
        }
    }

    @Test
    fun sourcePriorityAndWithdrawalRemoveOptionalWindowsWithoutReplaying() {
        val surface = Surface()
        val presenter = DeferredAlertPresenter(context, surface)
        var connection: AutoCloseable? = null
        val original = ProbeSession.state
        var visible = setOf(10, 20)
        try {
            main {
                for (route in ProbeRoute.entries) {
                    ProbeSession.configure(route, true)
                    ProbeSession.setConnected(route, true)
                }
                connection = SourceWindowVisibility.connect { visible }
                presenter.authorize(options)
                SourceWindowVisibility.sourceOpened(10)
                val capture = alert()
                presenter.observed(StoredObservation(1, true), capture, null, null)
                presenter.observed(
                    StoredObservation(1, false),
                    capture.copy(source = CaptureSource.DIALOG),
                    10,
                    0
                )
            }
            SystemClock.sleep(1000)
            main {
                assertNull(surface.current)
                visible = setOf(20)
            }
            waitUntil { surface.current?.id == 1L }
            main {
                presenter.translated(1, "en", TranslationEngineKind.ML_KIT, "[DRILL] Translation")
            }
            waitUntil { surface.current?.translation == "[DRILL] Translation" }
            main {
                visible = setOf(20, 30)
                SourceWindowVisibility.sourceOpened(30)
                assertNull(surface.current)
                presenter.authorize(null)
                visible = setOf(20)
            }
            SystemClock.sleep(1200)
            main {
                assertNull(surface.current)
                presenter.authorize(options)
            }
            SystemClock.sleep(1200)
            main {
                assertNull(surface.current)
                surface.failShow = true
                val capture = alert()
                presenter.observed(StoredObservation(2, true), capture, null, null)
                presenter.observed(
                    StoredObservation(2, false),
                    capture.copy(source = CaptureSource.DIALOG),
                    10,
                    0
                )
            }
            waitUntil { surface.failedCalls == 1 }
            SystemClock.sleep(1200)
            main {
                assertEquals(1, surface.failedCalls)
                assertNull(surface.current)
            }
        } finally {
            main {
                presenter.authorize(null)
                connection?.close()
                ProbeSession.configure(ProbeRoute.NOTIFICATION, original.notificationConsent)
                ProbeSession.configure(ProbeRoute.ACCESSIBILITY, original.accessibilityConsent)
                ProbeSession.setConnected(ProbeRoute.NOTIFICATION, original.notificationConnected)
                ProbeSession.setConnected(ProbeRoute.ACCESSIBILITY, original.accessibilityConnected)
            }
        }
    }

    @Test
    fun attachedWindowPreservesOriginalSwitchesReadyTranslationAndClosesOnce() {
        ActivityScenario.launch(MobileFixtureActivity::class.java).use { scenario ->
            val overlay = InstrumentationRegistry.getArguments()
                .getString("codeclear.overlayFixture") == "true"
            lateinit var host: DeferredAlertWindow
            var closed = 0
            val candidate = DeferredAlert(1, alert(), "en", TranslationEngineKind.ML_KIT, 0)
            val windowOptions = options.copy(theme = ThemeChoice.LIGHT)
            scenario.onActivity {
                host = DeferredAlertWindow { it }
                if (!overlay) {
                    host.started(it)
                    host.focus(it, true)
                }
            }
            if (overlay) {
                assertTrue(
                    "Grant validation overlay access through normal Android settings first",
                    Settings.canDrawOverlays(context)
                )
                scenario.moveToState(Lifecycle.State.CREATED)
            }
            main {
                host.show(candidate, windowOptions) { closed++ }
            }
            instrumentation.waitForIdleSync()
            main {
                val root = rootContaining(candidate.capture.original)
                val params = root.layoutParams as WindowManager.LayoutParams
                assertTrue(
                    Color.luminance(find(root, candidate.capture.original).currentTextColor) < 0.1
                )
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals(
                    overlay,
                    params.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                )
                if (overlay) {
                    assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                    assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
                assertFalse(
                    find(root, context.getString(R.string.mobile_translation, "English")).isEnabled
                )
                host.show(candidate.copy(translation = "[DRILL] Translation"), windowOptions) {
                    closed++
                }
                find(root, context.getString(R.string.mobile_translation, "English")).performClick()
                assertEquals("[DRILL] Translation", find(root, "[DRILL] Translation").text)
                find(root, context.getString(R.string.mobile_original)).let { button ->
                    allText(root).last { it.text == button.text }.performClick()
                }
                assertTrue(allText(root).any { it.text == candidate.capture.original })
                saveSynthetic(root, "deferred-popup-synthetic.png")
                find(root, context.getString(R.string.mobile_close)).performClick()
            }
            instrumentation.waitForIdleSync()
            main {
                assertEquals(1, closed)
                host.hide()
                assertEquals(1, closed)
            }
        }
    }

    @Test
    fun darkWindowKeepsLongOriginalScrollableAndActionsVisible() {
        ActivityScenario.launch(MobileFixtureActivity::class.java).use { scenario ->
            lateinit var host: DeferredAlertWindow
            var closed = 0
            val original = "[DRILL] Original: Gate 3, 112.\n".repeat(100)
            val candidate = DeferredAlert(
                2,
                alert().copy(original = original),
                "en",
                TranslationEngineKind.ML_KIT,
                0
            )
            scenario.onActivity {
                host = DeferredAlertWindow { it }
                host.started(it)
                host.focus(it, true)
                host.show(candidate, options.copy(theme = ThemeChoice.DARK)) { closed++ }
            }
            instrumentation.waitForIdleSync()
            main {
                val root = rootContaining(original)
                assertTrue(Color.luminance(find(root, original).currentTextColor) > 0.7)
                assertTrue(
                    "Long original content must remain scrollable",
                    allViews(root).filterIsInstance<ScrollView>().first { it.isShown }
                        .canScrollVertically(1)
                )
                assertTrue(find(root, context.getString(R.string.mobile_close)).isShown)
                assertTrue(find(root, context.getString(R.string.mobile_details)).isShown)
                saveSynthetic(root, "deferred-popup-dark-synthetic.png")
                find(root, context.getString(R.string.mobile_details)).performClick()
            }
            instrumentation.waitForIdleSync()
            main {
                assertEquals(1, closed)
                host.hide()
                assertEquals(1, closed)
            }
        }
    }

    private fun saveSynthetic(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(context.cacheDir, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        var satisfied = false
        while (!satisfied && SystemClock.elapsedRealtime() < deadline) {
            main { satisfied = condition() }
            if (!satisfied) SystemClock.sleep(50)
        }
        assertTrue("Expected optional presentation state", satisfied)
    }

    private fun rootContaining(text: String): View = WindowInspector.getGlobalWindowViews()
        .single { root -> allText(root).any { it.text == text } }
    private fun find(root: View, text: String): TextView = allText(root).first { it.text == text }
    private fun allText(root: View): List<TextView> = allViews(root).filterIsInstance<TextView>()
    private fun allViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                addAll(
                    allViews(root.getChildAt(index))
                )
            }
        }
    }
}
