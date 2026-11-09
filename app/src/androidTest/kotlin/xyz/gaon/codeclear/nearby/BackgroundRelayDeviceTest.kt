package xyz.gaon.codeclear.nearby

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.mobile.AlertHistoryStore
import xyz.gaon.codeclear.mobile.CaptureSource
import xyz.gaon.codeclear.mobile.CapturedAlert
import xyz.gaon.codeclear.mobile.MobileController
import xyz.gaon.codeclear.mobile.MobileOptions
import xyz.gaon.codeclear.mobile.OptionsStorage
import xyz.gaon.codeclear.mobile.mobileRuntime
import xyz.gaon.codeclear.translation.TranslationEngine

/** Real service and BLE ownership with only synthetic content and an isolated private database. */
class BackgroundRelayDeviceTest {
    @Test
    fun backgroundSessionUsesActualAccessAndPrivateHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val began = SystemClock.elapsedRealtime()
        val role = InstrumentationRegistry.getArguments().getString("role") ?: "lifecycle"
        require(role in setOf("sender", "receiver", "lifecycle"))
        val sender = role == "sender"
        if (sender) {
            assertEquals(
                "A sender needs BLE peripheral support",
                NearbyStatus.LISTENING,
                NearbyAccess.status(context, true)
            )
        }
        val session = InstrumentationRegistry.getArguments().getString("session")?.also {
            require(it.matches(Regex("[0-9a-f]{32}")))
        }
        val ready = session?.let { File(context.cacheDir, "relay-$it-ready") }
        val go = session?.let { File(context.cacheDir, "relay-$it-go") }
        assertTrue(
            "Grant the actual Nearby devices access first",
            NearbyAccess.granted(context, sender)
        )
        assertTrue(
            "Background discovery access is required",
            NearbyAccess.supportsBackgroundSharing()
        )
        val name = "relay-background-${UUID.randomUUID()}.db"
        val store = AlertHistoryStore(context, name)
        val service = Intent(context, RelayValidationService::class.java)
        val events = ConcurrentLinkedQueue<String>()
        val activity = ActivityScenario.launch(MainActivity::class.java)
        var scope: AutoCloseable? = null
        var controller: MobileController? = null
        var owner: NearbySharing? = null
        try {
            activity.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onMain {
                scope = context.mobileRuntime.pauseForDiagnostics()
                controller = MobileController(
                    context,
                    store,
                    object : OptionsStorage {
                        override fun load() = MobileOptions()
                        override fun save(options: MobileOptions) = Unit
                    },
                    TranslationEngine { _, _, _ -> error("The fixture must not translate") },
                    changed = {}
                ).also { it.start() }
            }
            await(5000) { onMain { controller!!.state.loaded } }
            onMain {
                owner = NearbySharing(
                    context,
                    sender,
                    factory = NearbyRadioFactory { send, known, received, status ->
                        BleRelay(context, send, known, received, status) { event, code ->
                            if (events.size < 64) events.add("$event:$code")
                        }
                    }
                ).also {
                    it.configure(true)
                    it.foreground(true)
                }
                RelayValidationSession.owner = owner
                RelayValidationSession.allowed = true
            }
            activity.onActivity { ContextCompat.startForegroundService(it, service) }
            await(5000) { onMain { owner!!.state.backgroundRunning } }
            activity.moveToState(Lifecycle.State.CREATED)
            onMain { owner!!.foreground(false) }
            assertTrue(onMain { owner!!.state.backgroundRunning })
            if (role != "lifecycle" && ready != null) {
                await(40_000, { "BLE scan did not become ready; radio=${events.take(20)}" }) {
                    events.any { it == "scan-ready:0" }
                }
                ready.writeText((SystemClock.elapsedRealtime() - began).toString())
                await(90_000, { "The other fixture did not become ready" }) { go!!.exists() }
            }
            when (role) {
                "sender" -> {
                    onMain {
                        owner!!.offer(
                            901,
                            CapturedAlert(
                                "test.synthetic.receiver",
                                CaptureSource.NOTIFICATION,
                                TITLE,
                                BODY,
                                System.currentTimeMillis()
                            )
                        )
                    }
                    java.util.concurrent.CountDownLatch(1).await(55, TimeUnit.SECONDS)
                    assertTrue(
                        "Advertising did not start; radio=${events.take(20)}",
                        events.any { it == "advertise-ready:0" }
                    )
                    assertTrue(onMain { owner!!.state.status != NearbyStatus.ERROR })
                }

                "receiver" -> {
                    await(60_000, {
                        "Background receipt missing; radio=${events.take(20)}"
                    }) {
                        onMain { owner!!.state.originals.isNotEmpty() }
                    }
                    val received = onMain { owner!!.state.originals.first() }
                    val retained = java.util.concurrent.CompletableFuture<Long?>()
                    onMain {
                        controller!!.saveNearby(
                            received,
                            owner!!.saveAllowance(received.root),
                            translate = false
                        ) { retained.complete(it) }
                    }
                    val id = requireNotNull(retained.get(5, TimeUnit.SECONDS))
                    val saved = store.find(id, "en")!!
                    assertTrue("Synthetic title mismatch", TITLE == saved.capture.title)
                    assertTrue("Synthetic original mismatch", BODY == saved.capture.original)
                    assertEquals(CaptureSource.PEER, saved.capture.source)
                    assertTrue("No translation should run", saved.translation == null)
                    onMain { owner!!.configure(false) }
                    assertTrue(
                        "Retained original mismatch",
                        BODY == store.find(id, "en")!!.capture.original
                    )
                }

                else -> assertTrue(
                    onMain {
                        owner!!.state.status in
                            setOf(NearbyStatus.LISTENING, NearbyStatus.RECEIVE_ONLY)
                    }
                )
            }
            context.startService(Intent(service).setAction(NearbyBackgroundService.STOP))
            await(5000) { onMain { !owner!!.state.backgroundRunning } }
            assertFalse(onMain { RelayValidationSession.allowed })
            assertEquals(NearbyStatus.OFF, onMain { owner!!.state.status })
        } finally {
            context.stopService(service)
            onMain {
                owner?.configure(false)
                owner?.background(false)
                owner?.foreground(false)
                RelayValidationSession.allowed = false
                RelayValidationSession.owner = null
                controller?.close()
                scope?.close()
            }
            controller?.let { assertTrue(it.awaitClosed(5000)) } ?: store.close()
            context.deleteDatabase(name)
            ready?.delete()
            go?.delete()
            activity.close()
        }
    }

    private fun await(
        timeout: Long,
        message: () -> String = { "Expected background service state was not reached" },
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message())
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    companion object {
        private const val TITLE = "[DRILL] Background Unicode fixture"
        private val BODY = "[DRILL] " + "원문  두 칸\n字👩‍🚒 é 112. ".repeat(32)
    }
}
