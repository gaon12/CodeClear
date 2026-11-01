package xyz.gaon.codeclear.mobile

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTransferDeviceTest {
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
