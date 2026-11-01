package xyz.gaon.codeclear.capture

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.probe.ProbeAccessibilityService
import xyz.gaon.codeclear.probe.ProbeNotificationListener

class CaptureServiceComponentsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test
    fun grantedComponentNamesKeepTheirFrameworkTypesAndBindingPermissions() {
        val manager = context.packageManager
        val notification = manager.getServiceInfo(
            ComponentName(context, ProbeNotificationListener::class.java),
            0
        )
        val accessibility = manager.getServiceInfo(
            ComponentName(context, ProbeAccessibilityService::class.java),
            0
        )
        assertEquals("xyz.gaon.codeclear.probe.ProbeNotificationListener", notification.name)
        assertEquals("xyz.gaon.codeclear.probe.ProbeAccessibilityService", accessibility.name)
        assertEquals(
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE,
            notification.permission
        )
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, accessibility.permission)
        assertFalse(notification.exported)
        assertTrue(accessibility.exported)
        assertTrue(
            NotificationListenerService::class.java.isAssignableFrom(
                ProbeNotificationListener::class.java
            )
        )
        assertTrue(
            AccessibilityService::class.java.isAssignableFrom(ProbeAccessibilityService::class.java)
        )
        ProbeNotificationListener::class.java.getDeclaredConstructor()
        ProbeAccessibilityService::class.java.getDeclaredConstructor()
    }

    @Suppress("DEPRECATION")
    @Test
    fun systemDiscoveryDoesNotExposeAdditionalCaptureComponents() {
        val manager = context.packageManager
        for ((action, expected) in listOf(
            NotificationListenerService.SERVICE_INTERFACE to
                ProbeNotificationListener::class.java.name,
            AccessibilityService.SERVICE_INTERFACE to ProbeAccessibilityService::class.java.name
        )) {
            val discovered = manager.queryIntentServices(
                Intent(action).setPackage(context.packageName),
                0
            )
            assertEquals(setOf(expected), discovered.map { it.serviceInfo.name }.toSet())
        }
    }
}
