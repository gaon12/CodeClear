package xyz.gaon.codeclear.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationEngineKind

data class RecordDestination(
    val id: Long,
    val target: String,
    val engine: TranslationEngineKind = TranslationEngineKind.ML_KIT
)

internal fun boundedNotificationBody(body: String): String {
    require(body.length in 1..16384 && body.isNotBlank())
    if (body.length <= 4096) return body
    val end = if (body[4094].isHighSurrogate()) 4094 else 4095
    return body.take(end) + "…"
}

/** Show bounded alert contents with a redacted public version and identifier-only deep links. */
class ResultNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val posted = linkedSetOf<String>()
    private var restored = false
    private val handler = Handler(Looper.getMainLooper())
    private var summaryTask: Runnable? = null
    private var summaryRevision = 0L
    private val pending = linkedMapOf<String, Notification>()
    private var dispatchTask: Runnable? = null
    private var dispatchRevision = 0L
    private var lastDispatchAt = 0L
    private val cancelled = linkedSetOf<String>()
    private var cancellationTask: Runnable? = null
    private var cancellationRevision = 0L

    @Synchronized
    fun post(
        id: Long,
        target: String,
        strings: Context = context,
        engine: TranslationEngineKind = TranslationEngineKind.ML_KIT,
        body: String
    ) {
        require(id > 0 && target in HistoryText.targets)
        require(body.length in 1..16384 && body.isNotBlank())
        if (!allowed()) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                strings.getString(R.string.result_channel),
                NotificationManager.IMPORTANCE_LOW
            )
                .apply { setShowBadge(false) }
        )
        val tag = PREFIX + id
        cancelled.remove(tag)
        restorePosted()
        // The platform's active snapshot can lag rapid notify calls; track this process's posts too.
        posted.remove(tag)
        posted.add(tag)
        val evicted = mutableListOf<String>()
        while (posted.size > 20) {
            val oldest = posted.first()
            posted.remove(oldest)
            pending.remove(oldest)
            manager.cancel(oldest, ID)
            evicted += oldest
        }
        if (evicted.isNotEmpty()) rememberCancellation(evicted)
        val notification = buildNotification(id, target, strings, engine, body)
        pending.remove(tag)
        pending[tag] = notification
        scheduleDispatch()
    }

    internal fun buildNotification(
        id: Long,
        target: String,
        strings: Context,
        engine: TranslationEngineKind,
        body: String
    ): Notification {
        val display = boundedNotificationBody(body)
        val intent = Intent(context, MainActivity::class.java)
            .setData(destinationUri(id, target, engine))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val action = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicView = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_result)
            .setContentTitle(strings.getString(R.string.result_title))
            .setContentText(strings.getString(R.string.result_body))
            .setContentIntent(action)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_result)
            .setContentTitle(strings.getString(R.string.result_title))
            .setContentText(display)
            .setStyle(Notification.BigTextStyle().bigText(display))
            .setContentIntent(action)
            .setGroup(GROUP)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicView)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
    }

    @Synchronized
    fun cancel(id: Long?) {
        restorePosted()
        summaryRevision++
        summaryTask?.let(handler::removeCallbacks)
        summaryTask = null
        val tags = if (id == null) {
            manager.activeNotifications.mapNotNull { it.tag }
                .filter { it.startsWith(PREFIX) }.toSet() + posted
        } else {
            setOf(PREFIX + id)
        }
        if (id == null) {
            tags.forEach { manager.cancel(it, ID) }
            posted.clear()
            pending.clear()
            dispatchRevision++
            dispatchTask?.let(handler::removeCallbacks)
            dispatchTask = null
            manager.cancel(SUMMARY, ID)
        } else {
            posted.remove(PREFIX + id)
            pending.remove(PREFIX + id)
            manager.cancel(PREFIX + id, ID)
            // Active snapshots can still contain earlier cancellations from this batch.
            // Only reuse actions whose tags remain in the restored process ledger.
            val latest = manager.activeNotifications.filter {
                it.tag in posted
            }.maxByOrNull { it.postTime }?.notification
            if (latest == null) {
                manager.cancel(SUMMARY, ID)
            } else {
                try {
                    updateSummary(latest)
                } catch (_: SecurityException) {
                    manager.cancel(SUMMARY, ID)
                }
            }
        }
        rememberCancellation(tags)
    }

    private fun rememberCancellation(tags: Collection<String>) {
        cancelled.addAll(tags)
        while (cancelled.size > 64) cancelled.remove(cancelled.first())
        cancellationRevision++
        cancellationTask?.let(handler::removeCallbacks)
        checkCancellation(2)
    }

    /** Active snapshots can include enqueued posts whose first cancellation is still deferred. */
    private fun checkCancellation(remaining: Int) {
        val revision = cancellationRevision
        val task = Runnable {
            synchronized(this) {
                if (revision != cancellationRevision) return@synchronized
                cancellationTask = null
                val active = manager.activeNotifications.mapNotNull { it.tag }.toSet()
                cancelled.filter {
                    it in active && it !in posted
                }.forEach { manager.cancel(it, ID) }
                val liveChildren = active.any { it.startsWith(PREFIX) && it !in cancelled }
                // A different owner can have posted a new child since the original cancellation.
                if (!liveChildren && posted.isEmpty() &&
                    pending.isEmpty()
                ) {
                    manager.cancel(SUMMARY, ID)
                }
                if (remaining > 1) checkCancellation(remaining - 1) else cancelled.clear()
            }
        }
        cancellationTask = task
        handler.postDelayed(task, if (remaining == 2) 500 else 1000)
    }

    private fun scheduleDispatch() {
        if (dispatchTask != null || pending.isEmpty()) return
        val revision = dispatchRevision
        val task = Runnable {
            synchronized(this) {
                if (revision != dispatchRevision) return@synchronized
                dispatchTask = null
                if (!allowed()) {
                    pending.clear()
                    return@synchronized
                }
                val next = pending.entries.firstOrNull() ?: return@synchronized
                val tag = next.key
                val notification = next.value
                pending.remove(tag)
                lastDispatchAt = SystemClock.elapsedRealtime()
                if (tag in posted) {
                    try {
                        manager.notify(tag, ID, notification)
                        updateSummary(notification)
                    } catch (_: SecurityException) {
                        // Access can be withdrawn between the permission check and the platform call.
                    }
                }
                scheduleDispatch()
            }
        }
        dispatchTask = task
        // One child and at most one summary per interval stays below Android's update-rate limit.
        handler.postDelayed(
            task,
            (500 - (SystemClock.elapsedRealtime() - lastDispatchAt)).coerceAtLeast(0)
        )
    }

    private fun restorePosted() {
        if (restored) return
        manager.activeNotifications.sortedBy { it.postTime }.mapNotNull { it.tag }
            .filter { it.startsWith(PREFIX) }.forEach(posted::add)
        restored = true
    }

    private fun updateSummary(latest: Notification) {
        val summary = Notification.Builder.recoverBuilder(context, latest)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .build()
        // Coalesce rapid batches: repeated queued summaries can exhaust Android's package limit.
        summaryTask?.let(handler::removeCallbacks)
        val revision = ++summaryRevision
        val task = Runnable {
            synchronized(this) {
                if (revision != summaryRevision) return@synchronized
                summaryTask = null
                try {
                    manager.notify(SUMMARY, ID, summary)
                } catch (_: SecurityException) {
                    // Notification access can change while this bounded update is pending.
                }
            }
        }
        summaryTask = task
        handler.postDelayed(task, 100)
    }

    fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(
            CHANNEL
        )?.importance != NotificationManager.IMPORTANCE_NONE &&
        (
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            )

    companion object {
        const val CHANNEL = "local_translation_results"
        private const val PREFIX = "result-"
        private const val GROUP = "translation-results"
        private const val SUMMARY = "translation-summary"
        private const val ID = 1

        fun destinationUri(
            id: Long,
            target: String,
            engine: TranslationEngineKind = TranslationEngineKind.ML_KIT
        ): Uri {
            require(id > 0 && target in HistoryText.targets)
            return Uri.Builder().scheme(
                "codeclear"
            ).authority(
                "record"
            ).appendPath(id.toString()).appendPath(target).appendPath(engine.key)
                .build()
        }

        fun destination(uri: Uri?): RecordDestination? {
            if (uri != null && uri.toString().length > 128) return null
            if (uri == null || uri.scheme != "codeclear" || uri.authority != "record" ||
                uri.query != null ||
                uri.fragment != null
            ) {
                return null
            }
            val parts = uri.pathSegments
            if (parts.size !in 2..3 || parts[1] !in HistoryText.targets) return null
            val id = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val engine = if (parts.size == 2) {
                TranslationEngineKind.ML_KIT
            } else {
                TranslationEngineKind.entries.firstOrNull { it.key == parts[2] } ?: return null
            }
            return RecordDestination(id, parts[1], engine)
        }
    }
}
