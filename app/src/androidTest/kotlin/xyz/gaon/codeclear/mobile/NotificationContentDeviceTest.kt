package xyz.gaon.codeclear.mobile

import android.app.Notification
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationEngineKind

class NotificationContentDeviceTest {
    @Test
    fun actualAlertContentUsesTheWarningIconAndARedactedPublicVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val body = "[DRILL] Stay indoors.\n창문에서 떨어지세요."
        val notice = ResultNotifications(context).buildNotification(
            42,
            "en",
            context,
            TranslationEngineKind.ML_KIT,
            body
        )
        assertEquals(body, notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(body, notice.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(R.drawable.ic_result, notice.smallIcon.resId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
        assertNull(notice.fullScreenIntent)
        assertEquals(
            context.getString(R.string.result_body),
            notice.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
        assertFalse(notice.publicVersion.extras.toString().contains("[DRILL]"))
    }
}
