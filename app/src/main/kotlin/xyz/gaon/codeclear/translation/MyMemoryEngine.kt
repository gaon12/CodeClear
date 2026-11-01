package xyz.gaon.codeclear.translation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Process-owned authorization is granted only after the separate remote preference is saved. */
class MyMemoryEngine(
    context: Context,
    transportFactory: () -> MyMemoryTransport = { HttpsMyMemoryTransport() },
    suppliedBudget: RemoteDailyBudget? = null
) : TranslationEngine {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val scripts = ChineseScriptConverter()
    private val transport by lazy(transportFactory)
    private val budget =
        suppliedBudget ?: RemoteDailyBudget(PreferenceRemoteBudget(context.applicationContext)) {
            Math.floorDiv(System.currentTimeMillis(), TimeUnit.DAYS.toMillis(1))
        }

    @Volatile private var authorized = false
    private var closed = false
    private var active: RemoteCancellation? = null

    fun authorize(value: Boolean) {
        checkMain()
        authorized = value && !closed
        if (!authorized) cancel()
    }

    override fun run(
        input: TranslationInput,
        allowModelDownload: Boolean,
        complete: (TranslationResult) -> Unit
    ) {
        checkMain()
        check(!closed && active == null)
        if (!authorized || input.engine != TranslationEngineKind.MY_MEMORY) {
            complete(TranslationResult.Unavailable)
            return
        }
        val cancellation = RemoteCancellation()
        active = cancellation
        val timeout = Runnable { cancellation.cancel() }
        main.postDelayed(timeout, 45_000)
        try {
            worker.execute {
                val result = try {
                    MyMemoryOperation(
                        MyMemoryTransport { text, source, target, token ->
                            transport.translate(text, source, target, token)
                        },
                        budget,
                        ::identify,
                        scripts::convert,
                        { authorized },
                        SystemClock::elapsedRealtime
                    ).run(input, cancellation)
                } catch (_: Exception) {
                    TranslationResult.Failed
                }
                main.post {
                    main.removeCallbacks(timeout)
                    if (active === cancellation) active = null
                    if (!closed) complete(result)
                }
            }
        } catch (_: RejectedExecutionException) {
            main.removeCallbacks(timeout)
            active = null
            complete(TranslationResult.Failed)
        }
    }

    private fun identify(body: String): String? = LanguageIdentification.getClient().use {
        Tasks.await(it.identifyLanguage(body), 15, TimeUnit.SECONDS)
    }

    fun cancel() {
        checkMain()
        active?.cancel()
    }

    fun close() {
        checkMain()
        closed = true
        authorized = false
        cancel()
        worker.queue.clear()
        worker.shutdown()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}

/** Persist only day and attempted character count. Alert text never enters preferences. */
internal class PreferenceRemoteBudget(context: Context) : RemoteBudgetStorage {
    private val preferences = context.getSharedPreferences(
        "remote_daily_budget",
        Context.MODE_PRIVATE
    )
    override fun read(): RemoteBudgetSnapshot? {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (!preferences.contains("day")) return null
        return RemoteBudgetSnapshot(
            preferences.getLong("day", 0),
            preferences.getInt("used", MyMemoryPlan.DAILY_LIMIT)
        )
    }
    override fun write(value: RemoteBudgetSnapshot): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        return preferences.edit().putLong("day", value.day).putInt("used", value.used).commit()
    }
}
