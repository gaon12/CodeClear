package xyz.gaon.codeclear.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

enum class NearbyStatus {
    OFF,
    PAUSED,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    LOCATION_DISABLED,
    UNAVAILABLE,
    LISTENING,
    RECEIVE_ONLY,
    TRANSFERRING,
    COOLDOWN,
    ERROR
}

object NearbyAccess {
    fun permissions(sender: Boolean): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (sender) add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }.toTypedArray()
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun granted(context: Context, sender: Boolean): Boolean = permissions(sender).all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun supportsBackgroundSharing(): Boolean = Build.VERSION.SDK_INT >= 31

    @SuppressLint("MissingPermission") // The exact role's runtime grants are checked first.
    fun status(context: Context, sender: Boolean): NearbyStatus {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return NearbyStatus.UNAVAILABLE
        }
        if (!granted(context, sender)) return NearbyStatus.PERMISSION_REQUIRED
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return NearbyStatus.UNAVAILABLE
            when {
                !adapter.isEnabled -> NearbyStatus.BLUETOOTH_OFF

                Build.VERSION.SDK_INT == 30 &&
                    !context.getSystemService(LocationManager::class.java).isLocationEnabled -> {
                    NearbyStatus.LOCATION_DISABLED
                }

                sender && adapter.isMultipleAdvertisementSupported -> NearbyStatus.LISTENING

                else -> NearbyStatus.RECEIVE_ONLY
            }
        } catch (_: SecurityException) {
            NearbyStatus.PERMISSION_REQUIRED
        }
    }
}
