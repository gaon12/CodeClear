package xyz.gaon.codeclear.mobile

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val owners = mutableListOf<SpeechPlayback>()
    private class Backend : SpeechBackend {
        var initialized: ((Boolean) -> Unit)? = null
        val spoken = CopyOnWriteArrayList<Pair<String, String>>()
        var installed = listOf(SpeechVoice("offline", "en-US", false, 300))
        var closed = false
        var voicesEntered: CountDownLatch? = null
        var voicesRelease: CountDownLatch? = null
        override fun initialize(complete: (Boolean) -> Unit) {
            initialized = complete
        }
        override fun voices(): List<SpeechVoice> {
            voicesEntered?.countDown()
            voicesRelease?.await(5, TimeUnit.SECONDS)
            return installed
        }
        override fun selectVoice(name: String): Boolean = true
        override fun speak(text: String, id: String, flush: Boolean): Boolean {
            spoken.add(text to id)
            return true
        }
        override fun stop() = Unit
        override fun close() {
            closed = true
        }
    }
    private class Focus : SpeechAudioFocus {
        var loss: (() -> Unit)? = null
        override fun acquire(onLoss: () -> Unit): Boolean {
            loss = onLoss
            return true
        }
        override fun release() = Unit
    }

    @After fun closeWorkers() {
        owners.forEach {
            onMain { it.close() }
            assertTrue(it.awaitClosed(5000))
        }
    }

    @Test fun installedOfflineVoiceSynthesizesSyntheticTextWithoutPlayback() {
        val context = instrumentation.targetContext
        val initialized = CountDownLatch(1)
        val done = CountDownLatch(1)
        var ready = false
        var successful = false
        lateinit var tts: TextToSpeech
        val file = File.createTempFile("synthetic-speech-", ".wav", context.cacheDir)
        try {
            onMain {
                tts =
                    TextToSpeech(context) {
                        ready = it == TextToSpeech.SUCCESS
                        initialized.countDown()
                    }
            }
            assertTrue(initialized.await(15, TimeUnit.SECONDS))
            assertTrue(ready)
            val voice = tts.voices?.firstOrNull {
                it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                    it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putBoolean(
                        "codeclear_offline_speech_voice",
                        voice != null
                    )
                }
            )
            if (voice == null) return
            assertEquals(TextToSpeech.SUCCESS, tts.setVoice(voice))
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = Unit
                override fun onDone(id: String) {
                    successful = true
                    done.countDown()
                }

                @Deprecated("Legacy platform callback")
                override fun onError(id: String) {
                    done.countDown()
                }
            })
            assertEquals(
                TextToSpeech.SUCCESS,
                tts.synthesizeToFile(
                    "[DRILL] Synthetic voice check.",
                    Bundle(),
                    file,
                    "voice-check"
                )
            )
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertTrue(successful && file.length() > 44)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putBoolean("codeclear_offline_speech_synthesized", true)
                }
            )
        } finally {
            onMain {
                tts.stop()
                tts.shutdown()
            }
            assertTrue(file.delete())
        }
    }

    @Test fun stopBeforeInitializationNeverQueuesSpeech() {
        val backend = Backend()
        val owner = owner(backend)
        onMain {
            owner.speak("first", "[DRILL] Synthetic one.", "en")
            owner.stop()
            backend.initialized!!(true)
        }
        instrumentation.waitForIdleSync()
        assertTrue(backend.spoken.isEmpty())
        onMain { assertEquals(SpeechPhase.IDLE, owner.state.phase) }
    }

    @Test fun replacementDuringInitializationOnlySpeaksTheLatestText() {
        val backend = Backend()
        val owner = owner(backend)
        onMain {
            owner.speak("first", "[DRILL] Old.", "en")
            owner.speak("second", "[DRILL] Latest.", "en")
            backend.initialized!!(true)
        }
        await { backend.spoken.size == 1 }
        assertEquals("[DRILL] Latest.", backend.spoken.single().first)
    }

    @Test fun cancellationDuringVoiceQueryAndLateCompletionCannotRestorePlayback() {
        val backend = Backend().apply {
            voicesEntered = CountDownLatch(1)
            voicesRelease = CountDownLatch(1)
        }
        var finished: ((String, Boolean) -> Unit)? = null
        val owner = owner(backend) { finished = it }
        onMain {
            owner.speak("first", "[DRILL] Old.", "en")
            backend.initialized!!(true)
        }
        assertTrue(backend.voicesEntered!!.await(5, TimeUnit.SECONDS))
        onMain { owner.stop() }
        backend.voicesRelease!!.countDown()
        onMain { owner.speak("new", "[DRILL] New.", "en") }
        await { backend.spoken.size == 1 }
        finished!!("1:0:0", false)
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals("new", owner.state.key)
            assertEquals(SpeechPhase.PLAYING, owner.state.phase)
        }
        assertEquals("[DRILL] New.", backend.spoken.single().first)
    }

    @Test fun missingOrNetworkVoicesNeverSpeakAndInitializationFailureCanRetry() {
        val backend = Backend().apply {
            installed = listOf(SpeechVoice("network", "en", true, 500))
        }
        val owner = owner(backend)
        onMain {
            owner.speak("first", "[DRILL] Synthetic.", "en")
            backend.initialized!!(false)
        }
        instrumentation.waitForIdleSync()
        assertTrue(backend.closed)
        onMain {
            assertEquals(SpeechPhase.UNAVAILABLE, owner.state.phase)
            owner.speak("retry", "[DRILL] Retry.", "en")
            backend.initialized!!(true)
        }
        awaitMain { owner.state.phase == SpeechPhase.UNAVAILABLE }
        assertTrue(backend.spoken.isEmpty())
    }

    @Test fun focusLossAndCompletionReleaseTheCurrentRequest() {
        val backend = Backend()
        val focus = Focus()
        var finished: ((String, Boolean) -> Unit)? = null
        val owner = owner(backend, focus) { finished = it }
        onMain {
            owner.speak("first", "[DRILL] Synthetic.", "en")
            backend.initialized!!(true)
        }
        await { backend.spoken.isNotEmpty() }
        finished!!(backend.spoken.single().second, false)
        awaitMain { owner.state.phase == SpeechPhase.IDLE }
        onMain { owner.speak("next", "[DRILL] Next.", "en") }
        await { backend.spoken.size == 2 }
        onMain {
            focus.loss!!.invoke()
            assertEquals(SpeechPhase.IDLE, owner.state.phase)
        }
    }

    @Test fun unicodeChunksPreserveTheWholeTextAndVoiceSelectionRequiresInstalledOfflineData() {
        val text = ("[DRILL] 日本語 한국어 中文 😀 " + "x".repeat(300)).repeat(30)
        val chunks = speechChunks(text, 256)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 256 && Charsets.UTF_8.newEncoder().canEncode(it) })
        assertFalse(chunks.isEmpty())
        assertNotNull(offlineSpeechVoice(listOf(SpeechVoice("en", "en-US", false, 300)), "en"))
        assertEquals(
            null,
            offlineSpeechVoice(listOf(SpeechVoice("missing", "en", false, 500, false)), "en")
        )
        assertEquals(null, offlineSpeechVoice(listOf(SpeechVoice("remote", "en", true, 500)), "en"))
        assertEquals(null, offlineSpeechVoice(listOf(SpeechVoice("en", "en", false, 500)), "und"))
    }

    private fun owner(
        backend: Backend,
        focus: Focus = Focus(),
        listener: (
            ((String, Boolean) -> Unit) -> Unit
        )? = null
    ): SpeechPlayback {
        lateinit var owner: SpeechPlayback
        onMain {
            owner =
                SpeechPlayback(instrumentation.targetContext, {
                    listener?.invoke(it)
                    backend
                }, focus)
            owners.add(owner)
        }
        return owner
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun awaitMain(condition: () -> Boolean) = await {
        var complete = false
        onMain { complete = condition() }
        complete
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(condition())
    }
}
