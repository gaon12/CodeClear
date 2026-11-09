package xyz.gaon.codeclear.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.gaon.codeclear.translation.TranslationEngineKind

class ResultNotificationChannelDeviceTest {
    @Test
    fun newChannelsAllowBannersButExistingQuietChannelsAndGroupingRemainUserControlled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = "test-result-banner-${UUID.randomUUID()}"
        try {
            val initial = prepareResultChannel(context, id)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, initial.importance)
            assertFalse(initial.canBypassDnd())
            manager.createNotificationChannel(
                NotificationChannel(
                    id,
                    "Synthetic quiet channel",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            assertEquals(
                NotificationManager.IMPORTANCE_LOW,
                prepareResultChannel(context, id).importance
            )
            val owner = ResultNotifications(context)
            val notice = owner.buildNotification(
                1,
                "en",
                context,
                TranslationEngineKind.ML_KIT,
                "[DRILL] Original: Gate 3, 112."
            )
            assertEquals(Notification.GROUP_ALERT_CHILDREN, notice.groupAlertBehavior)
            assertFalse(notice.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            assertEquals(
                Notification.FLAG_ONLY_ALERT_ONCE,
                notice.flags and Notification.FLAG_ONLY_ALERT_ONCE
            )
            assertNull(notice.fullScreenIntent)
        } finally {
            manager.deleteNotificationChannel(id)
        }
    }
}
