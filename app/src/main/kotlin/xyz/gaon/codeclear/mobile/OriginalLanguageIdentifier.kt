package xyz.gaon.codeclear.mobile

import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import xyz.gaon.codeclear.sha256Hex

/** Bundled identification only: one active input, one replaceable input, and no model downloads. */
class OriginalLanguageIdentifier {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val generation = AtomicLong()
    private val cache = Collections.synchronizedMap(linkedMapOf<String, String?>())

    fun clear() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation.incrementAndGet()
        worker.queue.clear()
        cache.clear()
    }

    fun identify(body: String, complete: (String?) -> Unit): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(body.isNotBlank() && body.length <= 8192)
        val ticket = generation.incrementAndGet()
        worker.queue.clear()
        try {
            worker.execute {
                val key = sha256Hex(body)
                val source = if (cache.containsKey(key)) {
                    cache[key]
                } else {
                    val identified = try {
                        LanguageIdentification.getClient().use {
                            TranslateLanguage.fromLanguageTag(
                                Tasks.await(it.identifyLanguage(body), 15, TimeUnit.SECONDS)
                            )
                        }
                    } catch (_: Exception) {
                        null
                    }
                    originalLanguageCode(identified).also {
                        synchronized(cache) {
                            if (generation.get() == ticket) {
                                cache[key] = it
                                if (cache.size > 8) cache.remove(cache.keys.first())
                            }
                        }
                    }
                }
                main.post { if (generation.get() == ticket) complete(source) }
            }
        } catch (_: RejectedExecutionException) {
            main.post { if (generation.get() == ticket) complete(null) }
        }
        return AutoCloseable { generation.compareAndSet(ticket, ticket + 1) }
    }
}
