package xyz.gaon.codeclear.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import xyz.gaon.codeclear.probe.ProbeAccessibilityService
import xyz.gaon.codeclear.probe.ProbeNotificationListener

data class ProbeEnvironment(
    val radio: ProbeRadio,
    val systemPackages: Set<String>,
    val notificationGranted: Boolean,
    val accessibilityGranted: Boolean
) {
    companion object {
        fun read(context: Context): ProbeEnvironment {
            val packageManager = context.packageManager
            val telephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            val radioAccess = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.hasSystemFeature(
                    PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS
                )
            } else {
                null
            }
            val radio = ProbeSourcePolicy.classifyRadio(telephony, radioAccess)
            val systemPackages = ProbeSourcePolicy.candidatePackages.filterTo(mutableSetOf()) {
                try {
                    val info = packageManager.getApplicationInfo(it, 0)
                    info.flags and (
                        ApplicationInfo.FLAG_SYSTEM or
                            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                        ) != 0
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            val listener = ComponentName(context, ProbeNotificationListener::class.java)
            val accessibility = ComponentName(context, ProbeAccessibilityService::class.java)
            val enabledServices = context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val bound = enabledServices.any {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == accessibility
            }
            // The public enabled-service list contains bound services, so it can be empty during
            // startup/reconnection while the user's persisted OS grant remains enabled.
            val accessibilityGranted = try {
                hasAccessibilityGrant(
                    Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ),
                    accessibility.packageName,
                    accessibility.className
                )
            } catch (_: RuntimeException) {
                bound
            }
            return ProbeEnvironment(
                radio = radio,
                systemPackages = systemPackages,
                notificationGranted = context.getSystemService(NotificationManager::class.java)
                    .isNotificationListenerAccessGranted(listener),
                accessibilityGranted = accessibilityGranted
            )
        }
    }
}

fun Context.isUnlocked(): Boolean = getSystemService(UserManager::class.java).isUserUnlocked
