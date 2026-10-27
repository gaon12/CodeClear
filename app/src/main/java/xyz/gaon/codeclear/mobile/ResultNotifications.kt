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
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.R

data class RecordDestination(val id: Long, val target: String)

/** No raw alert, translated text, or source heading enters the notification payload. */
class ResultNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val posted = linkedSetOf<String>()
    private var restored = false

    @Synchronized
    fun post(id: Long, target: String) {
        require(id > 0 && target in HistoryText.targets)
        if (!allowed()) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.result_channel),
                NotificationManager.IMPORTANCE_LOW
            )
                .apply { setShowBadge(false) }
        )
        val tag = PREFIX + id
        if (!restored) {
            manager.activeNotifications.sortedBy { it.postTime }.mapNotNull { it.tag }
                .filter { it.startsWith(PREFIX) }.forEach(posted::add)
            restored = true
        }
        // The platform's active snapshot can lag rapid notify calls; track this process's posts too.
        posted.remove(tag)
        posted.add(tag)
        while (posted.size > 20) {
            val oldest = posted.first()
            posted.remove(oldest)
            manager.cancel(oldest, ID)
        }
        val intent = Intent(context, MainActivity::class.java)
            .setData(destinationUri(id, target))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val action = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_result)
            .setContentTitle(context.getString(R.string.result_title))
            .setContentText(context.getString(R.string.result_body))
            .setContentIntent(action)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(tag, ID, notification)
        } catch (_: SecurityException) {
            // Access can be withdrawn between the permission check and the platform call.
        }
    }

    @Synchronized
    fun cancel(id: Long?) {
        if (id == null) {
            val tags = manager.activeNotifications.mapNotNull { it.tag }
                .filter { it.startsWith(PREFIX) }.toSet() + posted
            tags.forEach { manager.cancel(it, ID) }
            posted.clear()
        } else {
            posted.remove(PREFIX + id)
            manager.cancel(PREFIX + id, ID)
        }
    }

    fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            )

    companion object {
        const val CHANNEL = "local_translation_results"
        private const val PREFIX = "result-"
        private const val ID = 1

        fun destinationUri(id: Long, target: String): Uri {
            require(id > 0 && target in HistoryText.targets)
            return Uri.Builder().scheme(
                "codeclear"
            ).authority("record").appendPath(id.toString()).appendPath(target).build()
        }

        fun destination(uri: Uri?): RecordDestination? {
            if (uri == null || uri.scheme != "codeclear" || uri.authority != "record" ||
                uri.query != null ||
                uri.fragment != null
            ) {
                return null
            }
            val parts = uri.pathSegments
            if (parts.size != 2 || parts[1] !in HistoryText.targets) return null
            val id = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return RecordDestination(id, parts[1])
        }
    }
}
