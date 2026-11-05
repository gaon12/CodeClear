package xyz.gaon.codeclear.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import xyz.gaon.codeclear.MainActivity
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.mobile.mobileRuntime

/** A visible, user-stoppable owner of background BLE. No boot or sticky restart is requested. */
open class NearbyBackgroundService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var leased: NearbySharing? = null
    protected open val notificationId: Int = 2001
    protected open fun notificationTitle(): String =
        getString(R.string.mobile_nearby_background_title)
    protected open fun sharing(): NearbySharing = mobileRuntime.nearby
    protected open fun permitted(): Boolean = mobileRuntime.state.let {
        it.loaded && !it.storageError && it.options.labsEnabled && it.options.nearbyEnabled
    }
    protected open fun stopSharing() {
        mobileRuntime.stopNearbySharing()
    }

    private val monitor = object : Runnable {
        override fun run() {
            val owner = leased ?: return
            if (!permitted() || !NearbyAccess.granted(this@NearbyBackgroundService, owner.sender) ||
                !NearbyAccess.supportsBackgroundSharing()
            ) {
                stopSelf()
                return
            }
            if (owner.state.status in setOf(
                    NearbyStatus.BLUETOOTH_OFF,
                    NearbyStatus.LOCATION_DISABLED,
                    NearbyStatus.PERMISSION_REQUIRED,
                    NearbyStatus.PAUSED
                )
            ) {
                owner.refresh()
            }
            main.postDelayed(this, 30_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSharing()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!permitted()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val owner = sharing()
        if (!NearbyAccess.granted(this, owner.sender) ||
            !NearbyAccess.supportsBackgroundSharing()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            leased = owner
            owner.background(true)
            main.removeCallbacks(monitor)
            main.postDelayed(monitor, 30_000)
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: IllegalStateException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        leased?.background(false)
        leased = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.mobile_nearby),
                NotificationManager.IMPORTANCE_LOW
            )
                .apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_category_sharing)
            .setContentTitle(notificationTitle())
            .setContentText(getString(R.string.mobile_nearby_background_notice))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(
                Notification.VISIBILITY_PRIVATE
            ).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.mobile_nearby_stop),
                    stop
                ).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL = "nearby_background"
        internal const val STOP = "xyz.gaon.codeclear.nearby.STOP"

        /** Call from a visible activity after the actual role grants have been checked. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NearbyBackgroundService::class.java)
            )
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
