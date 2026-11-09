package xyz.gaon.codeclear.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class SpeechPhase { IDLE, PREPARING, PLAYING, UNAVAILABLE }
data class SpeechState(val phase: SpeechPhase = SpeechPhase.IDLE, val key: String? = null)

interface SpeechBackend : AutoCloseable {
    fun initialize(complete: (Boolean) -> Unit)
    fun voices(): List<SpeechVoice>
    fun selectVoice(name: String): Boolean
    fun speak(text: String, id: String, flush: Boolean): Boolean
    fun stop()
}

interface SpeechAudioFocus {
    fun acquire(onLoss: () -> Unit): Boolean
    fun release()
}

/** One optional offline voice client, one worker and one replaceable playback request. */
class SpeechPlayback(
    context: Context,
    private val factory: ((String, Boolean) -> Unit) -> SpeechBackend = {
        AndroidSpeechBackend(context.applicationContext, it)
    },
    private val audioFocus: SpeechAudioFocus = AndroidSpeechAudioFocus(context),
    private val changed: (SpeechState) -> Unit = {},
    private val interrupted: () -> Unit = {}
) : AutoCloseable {
    private data class Request(
        val generation: Long,
        val key: String,
        val text: String,
        val language: String
    )
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val generation = AtomicLong()
    private val outputLock = Any()
    private var backend: SpeechBackend? = null
    private var ready = false
    private var engineEpoch = 0L
    private var working = false
    private var pending: Request? = null
    private var closed = false
    private var deadline: Runnable? = null
    private var initializationTimeout: Runnable? = null
    private var currentState by mutableStateOf(SpeechState())
    var state: SpeechState
        get() = currentState
        private set(value) {
            currentState = value
            changed(value)
        }
    private val releaseIdle = object : Runnable {
        override fun run() {
            if (working || pending != null) {
                main.postDelayed(this, 5000)
            } else {
                releaseBackend()
            }
        }
    }

    fun speak(key: String, text: String, language: String) {
        check(Looper.myLooper() == Looper.getMainLooper() && !closed)
        require(key.length in 1..80 && text.isNotBlank() && text.length <= 16_384)
        stop()
        main.removeCallbacks(releaseIdle)
        pending = Request(generation.get(), key, text, language)
        state = SpeechState(SpeechPhase.PREPARING, key)
        val ticket = generation.get()
        deadline = Runnable {
            if (!closed && generation.get() == ticket && state.key == key) unavailable(key)
        }.also { main.postDelayed(it, 180_000) }
        if (backend == null) {
            val epoch = ++engineEpoch
            val created = try {
                factory(::completed)
            } catch (_: RuntimeException) {
                unavailable(key)
                return
            }
            backend = created
            try {
                created.initialize { success ->
                    main.post {
                        if (closed || epoch != engineEpoch) return@post
                        initializationTimeout?.let(main::removeCallbacks)
                        initializationTimeout = null
                        ready = success
                        if (success) {
                            start()
                        } else {
                            val failedKey = state.key
                            releaseBackend()
                            unavailable(failedKey)
                        }
                    }
                }
            } catch (_: RuntimeException) {
                releaseBackend()
                unavailable(key)
            }
            initializationTimeout = Runnable {
                if (!closed && epoch == engineEpoch && !ready) {
                    val failedKey = state.key
                    releaseBackend()
                    if (failedKey != null) unavailable(failedKey)
                }
            }.also { main.postDelayed(it, 12_000) }
        } else {
            start()
        }
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation.incrementAndGet()
        pending = null
        deadline?.let(main::removeCallbacks)
        deadline = null
        synchronized(outputLock) { backend?.stop() }
        audioFocus.release()
        state = SpeechState()
        main.removeCallbacks(releaseIdle)
        if (!closed) main.postDelayed(releaseIdle, 30_000)
    }

    private fun start() {
        val output = backend ?: return
        if (!ready || working || closed) return
        val request = pending ?: return
        pending = null
        if (!audioFocus.acquire {
                interrupted()
                stop()
            }
        ) {
            unavailable(request.key)
            return
        }
        working = true
        state = SpeechState(SpeechPhase.PLAYING, request.key)
        worker.execute {
            val success = try {
                val voice = offlineSpeechVoice(output.voices(), request.language)
                if (voice == null || !synchronized(outputLock) {
                        generation.get() == request.generation && output.selectVoice(voice.name)
                    }
                ) {
                    false
                } else {
                    val chunks =
                        speechChunks(
                            request.text,
                            minOf(4000, TextToSpeech.getMaxSpeechInputLength())
                        )
                    chunks.withIndex().all { (index, text) ->
                        synchronized(outputLock) {
                            generation.get() == request.generation && output.speak(
                                text,
                                "${request.generation}:$index:${chunks.lastIndex}",
                                index == 0
                            )
                        }
                    }
                }
            } catch (_: RuntimeException) {
                false
            }
            main.post {
                working = false
                if (closed) return@post
                if (!success && generation.get() == request.generation) unavailable(request.key)
                start()
            }
        }
    }

    private fun completed(id: String, failed: Boolean) {
        if (id.length > 80) return
        main.post {
            val parts = id.split(':')
            if (parts.size != 3 || parts[0].toLongOrNull() != generation.get() ||
                closed
            ) {
                return@post
            }
            if (failed) {
                unavailable(state.key)
            } else if (parts[1] == parts[2]) {
                deadline?.let(main::removeCallbacks)
                deadline = null
                audioFocus.release()
                state = SpeechState()
                main.postDelayed(releaseIdle, 30_000)
            }
        }
    }

    private fun unavailable(key: String?) {
        stop()
        state = SpeechState(SpeechPhase.UNAVAILABLE, key)
    }

    private fun releaseBackend() {
        initializationTimeout?.let(main::removeCallbacks)
        initializationTimeout = null
        engineEpoch++
        ready = false
        synchronized(outputLock) {
            backend?.close()
            backend = null
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        stop()
        main.removeCallbacksAndMessages(null)
        releaseBackend()
        worker.shutdown()
    }

    fun awaitClosed(timeout: Long): Boolean =
        worker.awaitTermination(timeout, TimeUnit.MILLISECONDS)
}

private class AndroidSpeechAudioFocus(context: Context) : SpeechAudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var generation = 0L
    override fun acquire(onLoss: () -> Unit): Boolean {
        val ticket = ++generation
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 &&
                    ticket == generation
                ) {
                    onLoss()
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        request = next
        return audio.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun release() {
        generation++
        request?.let(audio::abandonAudioFocusRequest)
        request = null
    }
}

private class AndroidSpeechBackend(
    private val context: Context,
    private val finished: (String, Boolean) -> Unit
) : SpeechBackend {
    private var output: TextToSpeech? = null
    override fun initialize(complete: (Boolean) -> Unit) {
        output = TextToSpeech(context) { result -> complete(result == TextToSpeech.SUCCESS) }
        output!!.setAudioAttributes(
            AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA).build()
        )
        output!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) = finished(id, false)

            @Deprecated("Legacy platform callback")
            override fun onError(id: String) = finished(id, true)
            override fun onError(id: String, errorCode: Int) = finished(id, true)
        })
    }
    override fun voices(): List<SpeechVoice> = output?.voices?.take(256)?.map {
        SpeechVoice(
            it.name,
            it.locale.toLanguageTag(),
            it.isNetworkConnectionRequired,
            it.quality,
            it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        )
    }.orEmpty()
    override fun selectVoice(name: String): Boolean = output?.let { tts ->
        val voice = tts.voices?.firstOrNull {
            it.name == name && !it.isNetworkConnectionRequired &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }
            ?: return false
        tts.setVoice(voice) == TextToSpeech.SUCCESS
    } ?: false
    override fun speak(text: String, id: String, flush: Boolean): Boolean = output?.speak(
        text,
        if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
        null,
        id
    ) == TextToSpeech.SUCCESS
    override fun stop() {
        output?.stop()
    }
    override fun close() {
        output?.shutdown()
        output = null
    }
}
