package xyz.gaon.codeclear.mobile

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.TranslationTarget

class AutomaticSpeechDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val options = MobileOptions(localConsent = true, target = TranslationTarget.ENGLISH)

    @Test fun focusLossAndReturningToTheAppNeverReplayOldTranslations() = fixture { f ->
        main {
            f.owner.foreground(false)
            f.ready(1)
        }
        main { f.owner.foreground(true) }
        assertTrue(f.backend.spoken.isEmpty())
        main { f.ready(2) }
        await { f.backend.spoken.size == 1 }
        main { f.owner.foreground(false) }
        main {
            assertEquals(SpeechPhase.IDLE, f.owner.playback.state.phase)
            f.owner.speak("late-manual", "[DRILL] Stale manual request", "en")
            f.owner.foreground(true)
            f.owner.translated(2, "en", TranslationEngineKind.ML_KIT, "[DRILL] Old result")
        }
        instrumentation.waitForIdleSync()
        assertEquals(1, f.backend.spoken.size)
    }

    @Test fun burstPlaysSeriallyButAudioInterruptionCancelsTheRemainingQueue() = fixture { f ->
        main {
            f.ready(1)
            f.ready(2)
            f.ready(3)
        }
        await { f.backend.spoken.size == 1 }
        f.completed(f.backend.spoken[0].second, false)
        await { f.backend.spoken.size == 2 }
        main { f.focus.loss!!() }
        f.completed(f.backend.spoken[1].second, false)
        instrumentation.waitForIdleSync()
        assertEquals(2, f.backend.spoken.size)
        main { assertEquals(SpeechPhase.IDLE, f.owner.playback.state.phase) }
    }

    @Test fun deletionAndWithdrawalKeepManualPlaybackSeparate() = fixture { f ->
        main {
            f.ready(1, CaptureSource.PEER)
            f.ready(2)
        }
        await { f.backend.spoken.size == 1 }
        assertEquals("[DRILL] Result 2", f.backend.spoken[0].first)
        main {
            f.ready(3)
            f.owner.deleted(2)
            f.owner.authorize(null, false)
            f.ready(4)
            f.owner.speak("manual", "[DRILL] Manual", "en")
        }
        await { f.backend.spoken.size == 2 }
        assertEquals("[DRILL] Manual", f.backend.spoken[1].first)
        f.completed(f.backend.spoken[1].second, false)
        instrumentation.waitForIdleSync()
        assertEquals(2, f.backend.spoken.size)
    }

    private inner class Fixture {
        lateinit var completed: (String, Boolean) -> Unit
        val backend = Backend()
        val focus = Focus()
        lateinit var owner: AutomaticSpeech

        fun ready(id: Long, source: CaptureSource = CaptureSource.NOTIFICATION) {
            val now = System.currentTimeMillis()
            owner.captured(
                id,
                CapturedAlert(
                    "test.system.receiver",
                    source,
                    "[DRILL]",
                    "[DRILL] Original $id",
                    now,
                    postedAtMillis = now
                )
            )
            owner.translated(id, "en", TranslationEngineKind.ML_KIT, "[DRILL] Result $id")
        }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val f = Fixture()
        main {
            f.owner = AutomaticSpeech(instrumentation.targetContext) { changed, interrupted ->
                SpeechPlayback(instrumentation.targetContext, factory = { completed ->
                    f.completed = completed
                    f.backend
                }, audioFocus = f.focus, changed = changed, interrupted = interrupted)
            }
            f.owner.authorize(options, true)
            f.owner.foreground(true)
        }
        try {
            action(f)
        } finally {
            main { f.owner.close() }
            assertTrue(f.owner.playback.awaitClosed(5000))
        }
    }

    private class Backend : SpeechBackend {
        val spoken = CopyOnWriteArrayList<Pair<String, String>>()
        override fun initialize(complete: (Boolean) -> Unit) = complete(true)
        override fun voices() = listOf(SpeechVoice("fixture-offline", "en", false, 400))
        override fun selectVoice(name: String) = true
        override fun speak(text: String, id: String, flush: Boolean): Boolean {
            spoken += text to id
            return true
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class Focus : SpeechAudioFocus {
        var loss: (() -> Unit)? = null
        override fun acquire(onLoss: () -> Unit): Boolean {
            loss = onLoss
            return true
        }
        override fun release() = Unit
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Synthetic speech callback timed out" }
            Thread.sleep(5)
        }
    }
}
