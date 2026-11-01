package xyz.gaon.codeclear.nearby

import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.mobile.mobileRuntime

/** Real BLE transfer of a synthetic original, independently of cellular capture and product history. */
class BleRelayDeviceTest {
    @Test
    fun exchangeSyntheticOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sender = InstrumentationRegistry.getArguments().getString("role") == "sender"
        assertTrue(
            "The owner must grant the actual role permissions in the app first",
            NearbyAccess.granted(context, sender)
        )
        val complete = CountDownLatch(1)
        val actual = AtomicReference<NearbyOriginal>()
        val error = AtomicReference<NearbyStatus>()
        val events = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val diagnostics: (String, Int) -> Unit = { event, code ->
            if (events.size < 64) events.add("$event:$code")
        }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        var scope: AutoCloseable? = null
        instrumentation.runOnMainSync { scope = context.mobileRuntime.pauseForDiagnostics() }
        val relay = if (sender) {
            BleRelay(context, true, { false }, {}, {
                if (it == NearbyStatus.ERROR ||
                    it == NearbyStatus.PERMISSION_REQUIRED
                ) {
                    error.set(it)
                }
            }, diagnostic = diagnostics)
        } else {
            null
        }
        val inbox = if (!sender) {
            onMain {
                NearbySharing(
                    context,
                    sender = false,
                    factory = NearbyRadioFactory {
                            send,
                            known,
                            received,
                            status
                        ->
                        BleRelay(context, send, known, received, status, diagnostics)
                    }
                )
            }
        } else {
            null
        }
        try {
            if (sender) {
                relay!!.start()
                relay.publish(901, TITLE, BODY, System.currentTimeMillis())
                complete.await(55, TimeUnit.SECONDS)
                assertEquals(null, error.get())
            } else {
                onMain {
                    inbox!!.configure(true)
                    inbox.foreground(true)
                }
                val deadline = SystemClock.elapsedRealtime() + 60000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val received = onMain { inbox!!.state.originals.firstOrNull() }
                    if (received != null) {
                        actual.set(received)
                        break
                    }
                    SystemClock.sleep(50)
                }
                assertTrue(
                    "No complete nearby original arrived; state=${onMain {
                        inbox!!.state.status
                    }}; " +
                        "radio=${events.take(16)}",
                    actual.get() != null
                )
                assertTrue("Synthetic title mismatch", TITLE == actual.get().title)
                assertTrue("Synthetic original mismatch", BODY == actual.get().body)
                onMain {
                    assertTrue(
                        "Receipt identity mismatch",
                        actual.get() == inbox!!.original(actual.get().root)
                    )
                    assertTrue(inbox.saveAllowance(actual.get().root)())
                    inbox.configure(false)
                    assertTrue(inbox.state.originals.isEmpty())
                    assertEquals(NearbyStatus.OFF, inbox.state.status)
                }
            }
        } finally {
            relay?.close()
            onMain {
                inbox?.configure(false)
                inbox?.foreground(false)
            }
            instrumentation.runOnMainSync { scope?.close() }
            activity.onActivity {
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity.close()
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }
    companion object {
        private const val TITLE = "[DRILL] Nearby Unicode fixture"
        private val BODY = "[DRILL] " + "원문  두 칸\n字👩‍🚒 é 112. ".repeat(32)
    }
}
