package xyz.gaon.codeclear.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSourceActionRulesTest {
    @Test
    fun emptyAndOversizedBatchesAreRejected() {
        assertEquals(
            ProbeSourceActionFailure.UNSUPPORTED,
            inspectSourcePayloadTypes(emptyList()).failure
        )
        assertEquals(
            ProbeSourceActionFailure.LIMITED,
            inspectSourcePayloadTypes(
                List(41) {
                    "android.telephony.SmsCbMessage"
                }
            ).failure
        )
    }

    @Test
    fun countDoesNotImplyKnownPayloadTypes() {
        val result =
            inspectSourcePayloadTypes(listOf("android.telephony.SmsCbMessage", "android.net.Uri"))
        assertEquals(2, result.returnedMessages)
        assertFalse(result.recognizedTypes)
    }

    @Test
    fun reviewedMessageTypesAreRecognizedWithoutReadingBodies() {
        val result = inspectSourcePayloadTypes(List(3) { "android.telephony.SmsCbMessage" })
        assertEquals(3, result.returnedMessages)
        assertTrue(result.recognizedTypes)
    }
}
