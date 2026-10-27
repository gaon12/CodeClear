package xyz.gaon.codeclear.probe

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceActionDeviceTest {
    @Suppress("DEPRECATION")
    @Test
    fun publicCompletionCallbackRetainsOriginalSyntheticExtras() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expected = arrayListOf(Uri.parse("codeclear-test:one"), Uri.parse("codeclear-test:two"))
        val intent = Intent(context, CaptureProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putParcelableArrayListExtra("synthetic_messages", expected)
        val pending = PendingIntent.getActivity(
            context,
            91,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        val completed = CountDownLatch(1)
        var returned: Intent? = null
        try {
            instrumentation.runOnMainSync {
                pending.send(context, 0, null, { _, original, _, _, _ ->
                    returned = original
                    completed.countDown()
                }, Handler(Looper.getMainLooper()))
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(intent.component, returned?.component)
            assertEquals(expected, returned?.getParcelableArrayListExtra<Uri>("synthetic_messages"))
        } finally {
            pending.cancel()
        }
    }
}
