package xyz.gaon.codeclear.mobile

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings

data class PresentationAccess(
    val overlayAllowed: Boolean = false,
    val batteryExempt: Boolean = false,
    val backgroundRestricted: Boolean = false
) {
    companion object {
        fun read(context: Context): PresentationAccess = PresentationAccess(
            overlayAllowed = Settings.canDrawOverlays(context),
            batteryExempt = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            backgroundRestricted = context.getSystemService(ActivityManager::class.java)
                .isBackgroundRestricted
        )
    }
}
