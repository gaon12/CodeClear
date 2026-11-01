package xyz.gaon.codeclear.capture

import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.probe.CaptureProbeActivity
import xyz.gaon.codeclear.probe.ProbeNotificationListener

/** Uses only synthetic shell notifications after the owner has granted system access. */
@RunWith(AndroidJUnit4::class)
class ProbeDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<CaptureProbeActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun prepareConsentedFixtureSession() {
        // These fixtures exercise notifications with dialog collection explicitly paused.
        // Reuse Compose automation flags; reconnecting mid-suite breaks some platform versions.
        automation()
        val environment = ProbeEnvironment.read(context)
        assertTrue("Unlock the device before running capture checks", context.isUnlocked())
        assertTrue(
            "Owner must grant notification access in Settings",
            environment.notificationGranted
        )
        onMain {
            // Test consent covers shell fixtures; real-source accessibility reading stays paused.
            ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
            ProbeSession.configure(ProbeRoute.NOTIFICATION, true)
            ProbeSession.setDiagnostics(hardwareOverride = false, shell = true)
        }
        NotificationListenerService.requestRebind(
            ComponentName(context, ProbeNotificationListener::class.java)
        )
        await("Notification listener did not connect") { session().notificationConnected }
    }

    @After
    fun stopFixtureCollection() {
        onMain {
            ProbeSession.configure(ProbeRoute.NOTIFICATION, false)
            ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
            ProbeSession.setDiagnostics(hardwareOverride = false, shell = false)
        }
    }

    @Test
    fun diagnosticScreenExplainsItsScopeAndProtectsItsWindow() {
        compose.onNodeWithText(context.getString(R.string.preview_settings)).performClick()
        compose.onNodeWithText(context.getString(R.string.probe_scope)).assertIsDisplayed()
        val secure = onMain {
            compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        assertTrue("Raw diagnostic observations must not appear in screenshots", secure)
    }

    @Test
    fun expandedNotificationBodyKeepsUnicodeNegationNumbersAndTimes() {
        val body = "[DRILL] 서울 台北 東京 🚨. Do not evacuate. 14:05, 3 km, 112."
        val observation = captureFixture("Unicode fixture", body)
        assertEquals("Unicode fixture", observation.title)
        assertEquals(body, observation.body)
        assertEquals(ProbeRoute.NOTIFICATION, observation.route)
        assertEquals(ProbeOrigin.SHELL_FIXTURE, observation.origin)
        assertEquals("com.android.shell", observation.packageName)
        assertNotNull(observation.postedAtMillis)
        assertFalse(observation.limited)
        assertFalse(observation.groupSummary)
    }

    @Test
    fun longBodyMatchesThePostedFieldAndWarnsAboutKnownPlatformBoundaries() {
        val body = "[DRILL] " + "Do not enter Zone 12. Shelter at Gate 3. ".repeat(60)
        assertTrue(body.length > 2000)
        val observation = captureFixture("Long fixture", body)
        val owner = executeFixtureCommand("am get-current-user").trim()
        require(owner.matches(Regex("[0-9]+")))
        val ownerPrefix = "$owner|com.android.shell|"
        val key = executeFixtureCommand(
            "cmd notification list | grep -F '|com.android.shell|' | grep -F '|test_alert|'"
        ).lineSequence().single { it.startsWith(ownerPrefix) && it.contains("|test_alert|") }
        val storedField = executeFixtureCommand(
            "cmd notification get ${shellQuote(key)} | grep -F 'android.bigText='"
        ).lineSequence().single { it.contains("android.bigText=String (") }
            .substringAfter("android.bigText=String (").removeSuffix(")")
        assertEquals(
            "Capture must preserve the actual platform field",
            storedField,
            observation.body
        )
        assertTrue(
            "Platform field is not the synthetic original prefix",
            body.startsWith(storedField)
        )
        if (storedField.length < body.length) {
            assertTrue(
                "Upstream truncation boundary was not flagged",
                observation.platformBoundaryPossible
            )
        }
        assertFalse(observation.limited)
    }

    @Test
    fun wrongShellTagIsExcludedWithAConnectedListener() {
        captureFixture("Tag positive control", "[DRILL] Positive control.")
        onMain { ProbeSession.clear() }
        postFixture("Wrong tag", "[DRILL] Must be excluded.", "codeclear_ignored")
        assertNoFixtureDuringObservationWindow()
    }

    @Test
    fun turningOffFixturesStopsCollectionAndClearsOldObservations() {
        captureFixture("Option positive control", "[DRILL] Positive control.")
        onMain { ProbeSession.setDiagnostics(shell = false) }
        assertTrue(session().records.isEmpty())
        postFixture("Fixture option off", "[DRILL] Must be excluded.")
        assertNoFixtureDuringObservationWindow()
    }

    @Test
    fun consentWithdrawalStopsCallbacksFromRepopulatingObservations() {
        captureFixture("Consent positive control", "[DRILL] Positive control.")
        onMain { ProbeSession.configure(ProbeRoute.NOTIFICATION, false) }
        assertTrue(session().records.isEmpty())
        postFixture("Consent withdrawn", "[DRILL] Must be excluded.")
        assertNoFixtureDuringObservationWindow()
    }

    private fun captureFixture(title: String, body: String): ProbeObservation {
        postFixture(title, body)
        await("Synthetic notification was not captured: $title") {
            session().records.any {
                it.observation.origin == ProbeOrigin.SHELL_FIXTURE && it.observation.title == title
            }
        }
        return session().records.last {
            it.observation.origin == ProbeOrigin.SHELL_FIXTURE && it.observation.title == title
        }.observation
    }

    private fun postFixture(title: String, body: String, tag: String = "test_alert") {
        val command = "cmd notification post -t ${shellQuote(title)} -S bigtext " +
            "--bigtext ${shellQuote(body)} ${shellQuote(tag)} '[DRILL] Short preview.'"
        val response = executeFixtureCommand(command)
        assertFalse("Shell fixture command failed", response.contains("Exception"))
        // OEM shell acknowledgements vary; the listener's exact captured result is the oracle.
    }

    private fun assertNoFixtureDuringObservationWindow() {
        val deadline = SystemClock.elapsedRealtime() + 1500
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue(
                "Listener disconnected during negative check",
                session().notificationConnected
            )
            assertFalse(
                "A filtered fixture reached the diagnostic buffer",
                session().records.any { it.observation.origin == ProbeOrigin.SHELL_FIXTURE }
            )
            SystemClock.sleep(50)
        }
    }

    private fun session(): ProbeSessionState = onMain { ProbeSession.state }

    private fun executeFixtureCommand(command: String): String {
        if (Build.VERSION.SDK_INT >= 31) {
            // UiAutomation tokenizes commands; stdin lets sh parse quoted text without corruption.
            val descriptors = automation().executeShellCommandRw("sh")
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use {
                    it.write((command + "\nexit\n").toByteArray(Charsets.UTF_8))
                }
                return readShellOutput(descriptors[0])
            } finally {
                descriptors.forEach { it.close() }
            }
        }
        // API 30 has no shell stdin, and scoped storage prevents shell from reading app cache.
        // Runtime.exec splits whitespace: keep the script in one Base64 argument, then decode
        // it inside sh. Literal IFS expansion supplies argument separators only inside that sh.
        val encoded = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = "echo\${IFS}$encoded|base64\${IFS}-d|sh"
        return readShellOutput(automation().executeShellCommand("sh -c $script"))
    }

    private fun readShellOutput(descriptor: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            val response = StringBuilder()
            val chunk = CharArray(1024)
            while (response.length < 8192) {
                val count = reader.read(chunk, 0, minOf(chunk.size, 8192 - response.length))
                if (count < 0) return@use response.toString()
                response.append(chunk, 0, count)
            }
            error("Synthetic shell command exceeded its output budget")
        }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}

private fun await(message: String, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 10_000
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return
        SystemClock.sleep(50)
    }
    fail(message)
}

private fun <T> onMain(action: () -> T): T {
    val value = AtomicReference<T>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { value.set(action()) }
    return value.get()
}

private fun automation(): UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
