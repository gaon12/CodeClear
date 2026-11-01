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
import xyz.gaon.codeclear.probe.ChineseScriptConverter
import xyz.gaon.codeclear.probe.LocalEngine
import xyz.gaon.codeclear.probe.LocalInput
import xyz.gaon.codeclear.probe.LocalResult

/** Process-owned authorization is granted only after the separate remote preference is saved. */
class MyMemoryEngine(
    context: Context,
    transportFactory: () -> MyMemoryTransport = { HttpsMyMemoryTransport() },
    suppliedBudget: RemoteDailyBudget? = null
) : LocalEngine {
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
        input: LocalInput,
        allowModelDownload: Boolean,
        complete: (LocalResult) -> Unit
    ) {
        checkMain()
        check(!closed && active == null)
        if (!authorized || input.engine != TranslationEngineKind.MY_MEMORY) {
            complete(LocalResult.Unavailable)
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
                    LocalResult.Failed
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
            complete(LocalResult.Failed)
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
