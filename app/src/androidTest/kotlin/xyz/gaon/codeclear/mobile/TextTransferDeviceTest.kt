package xyz.gaon.codeclear.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.TranslationEngineKind

class TextTransferDeviceTest {
    @Test
    fun recordSharingPreservesTheWholeOriginalAndOnlyAMatchingReadyTranslation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = "[DRILL] " + "🙂".repeat(4092)
        val translated = "[DRILL] " + "译".repeat(16_376)
        val alert = SavedAlert(
            1,
            CapturedAlert(
                "test.system.receiver",
                CaptureSource.NOTIFICATION,
                "[DRILL]",
                original,
                1700000000000
            ),
            SavedTranslation(TranslationPhase.READY, translated, "ko")
        )
        val shared = alertShareText(alert, "en", TranslationEngineKind.ML_KIT, context)
        assertTrue(shared.startsWith(context.getString(R.string.mobile_original) + "\n" + original))
        assertTrue(shared.contains(translated))
        assertTrue(shared.contains(context.getString(R.string.mobile_translation_short_notice)))
        assertEquals(shared, AlertTextTransfer.share(shared).getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(original, alertShareText(alert, "en", TranslationEngineKind.SYSTEM, context))
        assertEquals(
            original,
            alertShareText(
                alert.copy(
                    translation = alert.translation!!.copy(phase = TranslationPhase.WORKING)
                ),
                "en",
                TranslationEngineKind.ML_KIT,
                context
            )
        )
        assertEquals(
            original,
            alertShareText(
                alert.copy(translation = alert.translation!!.copy(text = original)),
                "en",
                TranslationEngineKind.ML_KIT,
                context
            )
        )
    }

    @Test
    fun invalidTransfersAreRejectedWithoutTruncatingOrCreatingAnIntent() {
        for (body in listOf("", " ", "x".repeat(32_769))) {
            assertTrue(
                runCatching { AlertTextTransfer.share(body) }.exceptionOrNull()
                    is IllegalArgumentException
            )
        }
        assertTrue(
            runCatching { AlertTextTransfer.clip("CodeClear", "x".repeat(16_385)) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun transferContainsOnlyTheCompleteSelectedBodyAndMarksClipboardPreviewsSensitive() {
        val body = "[DRILL] 창문에서 떨어지세요.\n" + "正文🙂".repeat(1_000)
        val clip = AlertTextTransfer.clip("CodeClear", body)
        assertEquals(1, clip.itemCount)
        assertEquals(body, clip.getItemAt(0).text.toString())
        assertNull(clip.getItemAt(0).uri)
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        val share = AlertTextTransfer.share(body)
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("text/plain", share.type)
        assertEquals(body, share.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), share.extras!!.keySet())
        assertNull(share.data)
        assertNull(share.component)
        assertNull(share.`package`)
    }
}
