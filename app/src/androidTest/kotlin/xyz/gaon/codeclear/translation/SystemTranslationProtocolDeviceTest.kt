package xyz.gaon.codeclear.translation

import android.os.Build
import android.view.translation.TranslationResponse
import android.view.translation.TranslationResponseValue
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SystemTranslationProtocolDeviceTest {
    @Test
    fun publicRequestAndResponseObjectsHonorTheSingleFinalTextContract() {
        if (Build.VERSION.SDK_INT >= 31) {
            Api31ProtocolChecks.run()
        } else {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertFalse(systemTranslationBackend(context).supported)
        }
    }
}

/** Keep generated local-method signatures out of the test class inspected on API 30. */
@RequiresApi(31)
private object Api31ProtocolChecks {
    fun run() {
        val original = "[DRILL] Leave Gate 3. Call 112."
        val request = SystemTranslationProtocol.request(original)
        assertEquals(1, request.translationRequestValues.size)
        assertEquals(original, request.translationRequestValues.single().text)
        val value = TranslationResponseValue.Builder(TranslationResponseValue.STATUS_SUCCESS)
            .setText("[훈련] 112").build()
        fun response(index: Int = 0, final: Boolean = true, status: Int = 0): TranslationResponse =
            TranslationResponse.Builder(status).setTranslationResponseValue(index, value)
                .setFinalResponse(final).build()
        assertEquals("[훈련] 112", SystemTranslationProtocol.text(response()))
        assertNull(SystemTranslationProtocol.text(response(index = 1)))
        assertNull(SystemTranslationProtocol.text(response(final = false)))
        assertNull(SystemTranslationProtocol.text(response(status = 1)))
        assertNull(
            SystemTranslationProtocol.text(
                TranslationResponse.Builder(0).setTranslationResponseValue(0, value)
                    .setTranslationResponseValue(1, value).build()
            )
        )
        assertNull(
            SystemTranslationProtocol.text(
                TranslationResponse.Builder(0)
                    .setTranslationResponseValue(0, TranslationResponseValue.forError()).build()
            )
        )
        listOf("", " ", "x".repeat(16385)).forEach { text ->
            assertNull(
                SystemTranslationProtocol.text(
                    TranslationResponse.Builder(0).setTranslationResponseValue(
                        0,
                        TranslationResponseValue.Builder(0).setText(text).build()
                    ).build()
                )
            )
        }
    }
}
