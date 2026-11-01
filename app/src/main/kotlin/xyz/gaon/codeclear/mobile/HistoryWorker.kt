package xyz.gaon.codeclear.mobile

import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Serializes history access and delivers outcomes on the main thread while its owner is open. */
internal class HistoryWorker(private val release: () -> Unit, private val failed: () -> Unit) :
    AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128))

    @Volatile private var closed = false

    fun <T> submit(block: () -> T, onFailure: () -> Unit = {}, success: (T) -> Unit) {
        if (closed) return
        try {
            executor.execute {
                if (closed) return@execute
                try {
                    val result = block()
                    main.post { if (!closed) success(result) }
                } catch (_: OperationCanceledException) {
                    // A newer view owns the UI; cancellation is not a storage failure.
                } catch (_: Exception) {
                    main.post { deliverFailure(onFailure) }
                }
            }
        } catch (_: RejectedExecutionException) {
            deliverFailure(onFailure)
        }
    }

    private fun deliverFailure(onFailure: () -> Unit) {
        if (closed) return
        failed()
        // Reporting failure can close the owner synchronously through a state observer.
        if (!closed) onFailure()
    }

    fun awaitClosed(timeoutMillis: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        return executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        executor.queue.clear()
        // Release on the same worker after the running operation stops using the store.
        executor.execute { release() }
        executor.shutdown()
    }
}
