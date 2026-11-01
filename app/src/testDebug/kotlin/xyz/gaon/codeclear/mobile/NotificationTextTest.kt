package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationTextTest {
    @Test
    fun shortOriginalAndTranslatedBodiesRemainUnchanged() {
        for (body in listOf("[DRILL] Stay indoors.", "[훈련] 실내에 머무르세요. 🚨")) {
            assertEquals(body, boundedNotificationBody(body))
        }
        assertThrows(IllegalArgumentException::class.java) { boundedNotificationBody(" \n") }
        assertThrows(IllegalArgumentException::class.java) {
            boundedNotificationBody("a".repeat(16385))
        }
    }

    @Test
    fun longBodiesKeepTheirActualPrefixWithinThePlatformPayloadBound() {
        val body = "a".repeat(5000)
        assertEquals("a".repeat(4095) + "…", boundedNotificationBody(body))
    }

    @Test
    fun truncationPreservesWholeSurrogatePairsAtBothSidesOfTheBoundary() {
        assertEquals(
            "a".repeat(4094) + "…",
            boundedNotificationBody("a".repeat(4094) + "🚨tail")
        )
        assertEquals(
            "a".repeat(4093) + "🚨…",
            boundedNotificationBody("a".repeat(4093) + "🚨tail")
        )
    }
}
