package xyz.gaon.codeclear.mobile

import android.app.Notification
import android.app.NotificationManager
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationEngineKind

class ResultNotificationsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun destinationsValidateIdLanguageAndAllUriParts() {
        assertNull(
            ResultNotifications.destination(
                Uri.parse("codeclear://record/" + "1".repeat(1024) + "/en")
            )
        )
        assertEquals(
            RecordDestination(42, "zh-Hant"),
            ResultNotifications.destination(ResultNotifications.destinationUri(42, "zh-Hant"))
        )
        assertEquals(
            RecordDestination(42, "en"),
            ResultNotifications.destination(Uri.parse("codeclear://record/42/en"))
        )
        assertEquals(
            RecordDestination(42, "en", TranslationEngineKind.SYSTEM),
            ResultNotifications.destination(
                ResultNotifications.destinationUri(42, "en", TranslationEngineKind.SYSTEM)
            )
        )
        listOf(
            "https://record/42/en",
            "codeclear://record/0/en",
            "codeclear://record/42/es",
            "codeclear://record/42/en?body=secret",
            "codeclear://record/42/en#fragment",
            "codeclear://record/9223372036854775808/en",
            "codeclear://other/42/en",
            "codeclear://record/42/en/unknown",
            "codeclear://record/42/en/system/extra"
        ).forEach {
            assertNull(ResultNotifications.destination(Uri.parse(it)))
        }
    }

    @Test
    fun changingTheProducerReplacesTheActionForTheSameRecord() {
        val owner = ResultNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        owner.cancel(null)
        try {
            owner.post(88, "en", engine = TranslationEngineKind.SYSTEM)
            await { manager.activeNotifications.any { it.tag == "result-88" } }
            val previous = manager.activeNotifications.first {
                it.tag == "result-88"
            }.notification.contentIntent
            owner.post(88, "en", engine = TranslationEngineKind.ML_KIT)
            await {
                manager.activeNotifications.firstOrNull { it.tag == "result-88" }
                    ?.notification?.contentIntent != previous
            }
            assertEquals(1, manager.activeNotifications.count { it.tag == "result-88" })
        } finally {
            owner.cancel(null)
            await { manager.activeNotifications.none(::isResult) }
        }
    }

    @Test
    fun aNewResultForTheSameRecordSurvivesDeferredCancellationCleanup() {
        val owner = ResultNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        owner.cancel(null)
        try {
            owner.post(89, "en", engine = TranslationEngineKind.SYSTEM)
            await { manager.activeNotifications.any { it.tag == "result-89" } }
            val previous = manager.activeNotifications.first { it.tag == "result-89" }
                .notification.contentIntent
            owner.cancel(89)
            owner.post(89, "de", engine = TranslationEngineKind.ML_KIT)
            await {
                val notice = manager.activeNotifications.firstOrNull { it.tag == "result-89" }
                notice != null && notice.notification.contentIntent != previous
            }
            SystemClock.sleep(1700)
            val current = manager.activeNotifications.single { it.tag == "result-89" }
            assertNotEquals(previous, current.notification.contentIntent)
        } finally {
            owner.cancel(null)
            await { manager.activeNotifications.none(::isResult) }
        }
    }

    @Test
    fun localizedResourcesOverrideTheDefaultNotificationCopy() {
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags("en"))
            }
        )
        val korean = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags("ko"))
            }
        )
        val owner = ResultNotifications(english)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            owner.post(71, "de", korean)
            await { manager.activeNotifications.any { it.tag == "result-71" } }
            val notice = manager.activeNotifications.first { it.tag == "result-71" }.notification
            assertEquals(
                korean.getString(R.string.result_title),
                notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            )
            assertNotEquals(
                english.getString(R.string.result_title),
                notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            )
        } finally {
            // Release this fixture owner's pending group update as well as its child.
            // A following owner cannot cancel callbacks retained by this instance.
            owner.cancel(null)
            await { manager.activeNotifications.none(::isResult) }
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
            await(timeoutMillis = 20_000) {
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
            assertEquals("translation-results", notification.group)
            await {
                manager.activeNotifications.firstOrNull {
                    it.tag == "translation-summary"
                }?.notification?.contentIntent == notification.contentIntent
            }
            val summary = manager.activeNotifications.first {
                it.tag == "translation-summary"
            }.notification
            assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            assertNull(summary.fullScreenIntent)
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
            await {
                manager.activeNotifications.firstOrNull {
                    it.tag == "translation-summary"
                }?.notification?.contentIntent == replaced.contentIntent
            }
            owner.cancel(22)
            await { manager.activeNotifications.none { it.tag == "result-22" } }
            assertFalse(manager.activeNotifications.any { it.tag == "result-22" })
            await {
                val remaining = manager.activeNotifications.firstOrNull {
                    it.tag == "translation-summary"
                }?.notification?.contentIntent
                remaining != null && remaining != replaced.contentIntent && remaining != previous
            }
            // A fresh owner must also retarget correctly using platform state after process restart.
            val restored = ResultNotifications(context)
            for (id in 3L..21L) restored.cancel(id)
            await { manager.activeNotifications.none { it.tag == "translation-summary" } }
        } finally {
            owner.cancel(null)
        }
    }

    @Test
    fun cancellingHistoryPreventsQueuedResultsFromReappearing() {
        val owner = ResultNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        owner.cancel(null)
        try {
            for (id in 101L..106L) owner.post(id, "en")
            owner.cancel(null)
            // Platform cancellation is asynchronous; first observe its completion, then
            // allow enough time for a stale dispatch callback to expose a resurrection.
            await { manager.activeNotifications.none(::isResult) }
            SystemClock.sleep(750)
            assertTrue(
                "Cancelled result tags reappeared: " +
                    manager.activeNotifications.filter(::isResult).map { it.tag },
                manager.activeNotifications.none(::isResult)
            )
        } finally {
            owner.cancel(null)
        }
    }

    private fun isResult(notice: android.service.notification.StatusBarNotification): Boolean =
        notice.tag?.startsWith("result-") == true || notice.tag == "translation-summary"

    private fun await(timeoutMillis: Long = 3000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
        assertTrue("The notification service did not finish the expected operation", predicate())
    }
}
