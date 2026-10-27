package xyz.gaon.codeclear.mobile

import android.app.Notification
import android.app.NotificationManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R

class ResultNotificationsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun destinationsValidateIdLanguageAndAllUriParts() {
        assertEquals(
            RecordDestination(42, "zh-Hant"),
            ResultNotifications.destination(ResultNotifications.destinationUri(42, "zh-Hant"))
        )
        listOf(
            "https://record/42/en",
            "codeclear://record/0/en",
            "codeclear://record/42/es",
            "codeclear://record/42/en?body=secret",
            "codeclear://record/42/en#fragment",
            "codeclear://record/9223372036854775808/en",
            "codeclear://other/42/en"
        ).forEach {
            assertNull(ResultNotifications.destination(Uri.parse(it)))
        }
    }

    @Test
    fun notificationsRemainGenericBoundedAndIndependentlyCancellable() {
        val owner = ResultNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("This test requires user-enabled result notifications", owner.allowed())
        owner.cancel(null)
        try {
            for (id in 1L..22L) owner.post(id, "en")
            await {
                manager.activeNotifications.count { it.tag?.startsWith("result-") == true } ==
                    20 &&
                    manager.activeNotifications.none { it.tag == "result-1" }
            }
            val active = manager.activeNotifications.filter {
                it.tag?.startsWith("result-") == true
            }
            assertEquals(20, active.size)
            assertFalse(active.any { it.tag == "result-1" })
            val notification = active.first { it.tag == "result-22" }.notification
            assertEquals(
                context.getString(R.string.result_body),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            )
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertNull(notification.fullScreenIntent)
            val previous = notification.contentIntent
            owner.post(22, "de")
            await {
                manager.activeNotifications.firstOrNull {
                    it.tag == "result-22"
                }?.notification?.contentIntent !=
                    previous
            }
            val replaced = manager.activeNotifications.first { it.tag == "result-22" }.notification
            assertNotEquals(previous, replaced.contentIntent)
            owner.cancel(22)
            await { manager.activeNotifications.none { it.tag == "result-22" } }
            assertFalse(manager.activeNotifications.any { it.tag == "result-22" })
        } finally {
            owner.cancel(null)
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
        assertTrue("The notification service did not finish the expected operation", predicate())
    }
}
