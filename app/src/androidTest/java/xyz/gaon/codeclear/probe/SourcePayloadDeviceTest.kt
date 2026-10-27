package xyz.gaon.codeclear.probe

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourcePayloadDeviceTest {
    @Test
    fun baselineLayoutIsAcceptedAcrossAllReviewedPlatformVersions() {
        for (sdk in 30..37) {
            fixture {
                writeMessage(this, "[DRILL] Reviewed platform $sdk")
                setDataPosition(0)
                assertEquals(
                    "[DRILL] Reviewed platform $sdk",
                    ProbeBroadcastPayload.readForPlatform(this, sdk)?.body
                )
            }
        }
    }

    @Test
    fun unreviewedPlatformVersionsFailClosed() {
        for (sdk in listOf(29, 38, Int.MAX_VALUE)) {
            fixture {
                writeMessage(this, "[DRILL] Unknown platform")
                setDataPosition(0)
                assertNull(ProbeBroadcastPayload.readForPlatform(this, sdk))
            }
        }
    }

    @Test
    fun extendedCmasRequiresTheReviewedPlatformProfile() {
        for (sdk in 30..37) {
            fixture {
                writeMessage(this, "[DRILL] Platform extension", extendedCmas = true)
                setDataPosition(0)
                val decoded = ProbeBroadcastPayload.readForPlatform(this, sdk)
                if (sdk == 37) {
                    assertEquals("[DRILL] Platform extension", decoded?.body)
                } else {
                    assertNull(decoded)
                }
            }
        }
    }

    @Test
    fun extendedCmasRequiresItsReviewedLayoutAndExactConsumption() = fixture {
        writeMessage(this, "[DRILL] Extended CMAS", extendedCmas = true)
        setDataPosition(0)
        assertNull(ProbeBroadcastPayload.readReviewedParcel(this))
        setDataPosition(0)
        val decoded = ProbeBroadcastPayload.readReviewedParcel(this, extendedCmas = true)
        assertEquals("[DRILL] Extended CMAS", decoded?.body)
        assertEquals(0, decoded?.fields?.cmasClass)
    }

    @Test
    fun completeCmasBodyAndNumericFieldsSurviveIndependentlyOfDisplayLanguage() = fixture {
        val expected = "[DRILL] 강가に近づかない 🚨. 14:05, 112."
        writeMessage(this, expected)
        setDataPosition(0)
        val decoded = ProbeBroadcastPayload.readReviewedParcel(this)
        assertEquals(expected, decoded?.body)
        assertEquals(4370, decoded?.fields?.category)
        assertEquals(3, decoded?.fields?.priority)
        assertEquals(0, decoded?.fields?.cmasClass)
        assertEquals(1700000000000L, decoded?.fields?.receivedAtMillis)
    }

    @Test
    fun etwsSecurityAndLocationAreDiscarded() = fixture {
        writeMessage(this, "[DRILL] ETWS", 'E'.code)
        setDataPosition(0)
        val decoded = ProbeBroadcastPayload.readReviewedParcel(this)
        assertEquals(1, decoded?.fields?.etwsType)
        assertNull(decoded?.fields?.cmasClass)
    }

    @Test
    fun incompleteAndUnexpectedTrailingLayoutsFailClosed() = fixture {
        writeMessage(this, "[DRILL]")
        val size = dataSize()
        setDataSize(size - 4)
        setDataPosition(0)
        assertNull(ProbeBroadcastPayload.readReviewedParcel(this))
        setDataSize(size)
        setDataPosition(size)
        writeInt(123)
        setDataPosition(0)
        assertNull(ProbeBroadcastPayload.readReviewedParcel(this))
    }

    @Test
    fun oversizedBodiesAreRejectedRatherThanSilentlyShortened() = fixture {
        writeMessage(this, "a".repeat(8193))
        setDataPosition(0)
        assertNull(ProbeBroadcastPayload.readReviewedParcel(this))
    }

    @Test
    fun unknownWarningTagsAreRejected() = fixture {
        writeMessage(this, "[DRILL]", '?'.code)
        setDataPosition(0)
        assertNull(ProbeBroadcastPayload.readReviewedParcel(this))
    }

    private fun fixture(check: Parcel.() -> Unit) {
        val parcel = Parcel.obtain()
        try {
            parcel.check()
        } finally {
            parcel.recycle()
        }
    }

    private fun writeMessage(
        parcel: Parcel,
        body: String,
        tag: Int = 'C'.code,
        extendedCmas: Boolean = false
    ) = with(parcel) {
        writeInt(1)
        writeInt(0)
        writeInt(49)
        writeString("00101")
        writeInt(-1)
        writeInt(-1)
        writeInt(4370)
        writeString("ko")
        writeInt(72)
        writeString(body)
        writeInt(3)
        writeInt(tag)
        when (tag) {
            'C'.code -> {
                listOf(0, -1, -1, -1, -1, -1).forEach(::writeInt)
                if (extendedCmas) {
                    listOf(4370, 0, 0).forEach(::writeInt)
                    writeLong(1700000000100L)
                    writeInt(0)
                }
            }

            'E'.code -> {
                listOf(1, 1, 0, 0).forEach(::writeInt)
                writeByteArray(byteArrayOf(1, 2, 3))
            }
        }
        writeLong(1700000000000L)
        writeString(null)
        writeInt(0)
        writeInt(0)
        writeInt(1)
    }
}
