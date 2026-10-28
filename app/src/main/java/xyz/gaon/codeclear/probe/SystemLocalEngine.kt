package xyz.gaon.codeclear.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One bounded worker owns system queries, identification, sessions, and script conversion. */
class SystemLocalEngine(
    private val backend: SystemTranslationBackend,
    private val availabilityChanged: (SystemEngineAvailability) -> Unit = {}
) : LocalEngine {
    constructor(context: Context, changed: (SystemEngineAvailability) -> Unit = {}) :
        this(systemTranslationBackend(context), changed)

    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val scripts = ChineseScriptConverter()
    private var active: SystemTranslationOperation? = null
    private var closed = false
    private var inspecting = false
    private var inspectAfterWork = false
    private var inspectedAt = 0L
    private var requestingSettings = false
    var availability = SystemEngineAvailability(platformSupported = backend.supported)
        private set

    override fun run(
        input: LocalInput,
        allowModelDownload: Boolean,
        complete: (LocalResult) -> Unit
    ) {
        requireMain()
        check(!closed && active == null)
        val operation = SystemTranslationOperation(backend, scripts::convert)
        active = operation
        try {
            worker.execute {
                val result = operation.run(input)
                main.post {
                    if (active === operation) active = null
                    if (!closed) {
                        complete(result)
                        if (inspectAfterWork) {
                            inspectAfterWork = false
                            refreshCapabilities(force = true)
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            active = null
            main.post { if (!closed) complete(LocalResult.Failed) }
        }
    }

    fun refreshCapabilities(force: Boolean = false) {
        requireMain()
        if (closed || inspecting) return
        if (!force && availability.loaded &&
            SystemClock.uptimeMillis() - inspectedAt < 30_000
        ) {
            return
        }
        if (active != null || requestingSettings) {
            inspectAfterWork = true
            return
        }
        inspecting = true
        publish(availability.copy(loading = true))
        try {
            worker.execute {
                val pairs = try {
                    if (backend.supported) {
                        backend.capabilities().asSequence().supportedSystemPairs()
                    } else {
                        emptyList()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                val snapshot = SystemEngineAvailability(true, false, backend.supported, pairs)
                main.post {
                    inspecting = false
                    inspectedAt = SystemClock.uptimeMillis()
                    if (!closed) {
                        publish(snapshot)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            inspecting = false
            publish(availability.copy(loading = false))
        }
    }

    /** Resolve provider settings in the background; the caller opens them only while foreground. */
    fun requestSettings(complete: ((() -> Boolean)?) -> Unit) {
        requireMain()
        if (closed || requestingSettings || inspecting || active != null) {
            complete(null)
            return
        }
        requestingSettings = true
        try {
            worker.execute {
                val action = try {
                    backend.settingsAction()
                } catch (_: Exception) {
                    null
                }
                main.post {
                    requestingSettings = false
                    if (!closed) complete(action)
                    if (inspectAfterWork && !closed) {
                        inspectAfterWork = false
                        refreshCapabilities(force = true)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            requestingSettings = false
            complete(null)
        }
    }

    fun cancel() {
        requireMain()
        active?.cancel()
    }

    fun close() {
        requireMain()
        if (closed) return
        closed = true
        active?.cancel()
        worker.queue.clear()
        worker.shutdown()
    }

    fun awaitClosed(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        return worker.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun publish(state: SystemEngineAvailability) {
        availability = state
        availabilityChanged(state)
    }

    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
