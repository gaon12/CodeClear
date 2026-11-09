package xyz.gaon.codeclear.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import xyz.gaon.codeclear.translation.TranslationEngineKind

/** Focus-scoped optional playback; capture, translation and system warning presentation stay separate. */
internal class AutomaticSpeech(
    context: Context,
    createPlayback: (
        (SpeechState) -> Unit,
        () -> Unit
    ) -> SpeechPlayback = { changed, interrupted ->
        SpeechPlayback(context, changed = changed, interrupted = interrupted)
    }
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val queue = AutomaticSpeechQueue()
    private var options: MobileOptions? = null
    private var enabled = false
    private var selection: AutomaticSpeechSelection? = null
    private var visible = false
    private var active: AutomaticSpeechRequest? = null
    private var closed = false
    val playback = createPlayback({ state ->
        if (state.phase == SpeechPhase.UNAVAILABLE) queue.clear()
        schedule()
    }, { queue.clear() })
    private val pump = Runnable {
        if (closed) return@Runnable
        val idle = playback.state.phase in setOf(SpeechPhase.IDLE, SpeechPhase.UNAVAILABLE)
        if (idle) active = null
        val next = queue.next(idle, SystemClock.elapsedRealtime()) ?: return@Runnable
        active = next
        playback.speak(next.key, next.text, next.language)
    }

    fun authorize(value: MobileOptions?, enabled: Boolean) {
        checkMain()
        options = value
        this.enabled = enabled
        updateSelection()
    }

    fun foreground(value: Boolean) {
        checkMain()
        visible = value
        updateSelection()
        if (!value) stop()
    }

    private fun updateSelection() {
        val next = options?.takeIf { visible && enabled && it.translationAllowed }
            ?.let { AutomaticSpeechSelection(it.target.languageTag, it.engine) }
        if (selection == next) return
        selection = next
        queue.configure(next)
        if (active != null) {
            active = null
            playback.stop()
        }
    }

    fun captured(id: Long, capture: CapturedAlert) {
        checkMain()
        queue.captured(id, capture, System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }

    fun translated(id: Long, target: String, engine: TranslationEngineKind, body: String) {
        checkMain()
        queue.translated(id, target, engine, body, SystemClock.elapsedRealtime())
        schedule()
    }

    fun deleted(id: Long?) {
        checkMain()
        queue.deleted(id)
        if (id == null || active?.id == id) stop()
    }

    fun speak(key: String, text: String, language: String) {
        checkMain()
        if (!visible) return
        stop()
        playback.speak(key, text, language)
    }

    fun stop() {
        checkMain()
        queue.clear()
        active = null
        main.removeCallbacks(pump)
        playback.stop()
    }

    private fun schedule() {
        if (closed) return
        main.removeCallbacks(pump)
        main.post(pump)
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        queue.configure(null)
        active = null
        main.removeCallbacksAndMessages(null)
        playback.close()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper() && !closed)
}
