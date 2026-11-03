package xyz.gaon.codeclear.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredGattCloseTest {
    @Test
    fun closeDuringRegistrationKeepsCallbackAliveUntilServiceCanBeRemoved() {
        val events = mutableListOf<String>()
        val close = DeferredGattClose { events.add("clear services, unregister, stop worker") }
        close.registrationStarted()
        close.close()
        assertFalse(close.released)
        assertTrue(events.isEmpty())
        close.registrationFinished()
        assertTrue(close.released)
        assertEquals(1, events.size)
    }

    @Test
    fun completedOrRejectedRegistrationCanCloseImmediately() {
        var releases = 0
        val close = DeferredGattClose { releases++ }
        close.registrationStarted()
        close.registrationFinished()
        assertEquals(0, releases)
        close.close()
        assertEquals(1, releases)
    }

    @Test
    fun missingCallbackHasBoundedCleanupAndLateCompletionCannotReleaseTwice() {
        var releases = 0
        val close = DeferredGattClose { releases++ }
        close.registrationStarted()
        close.close()
        close.expire()
        close.registrationFinished()
        close.close()
        close.expire()
        assertTrue(close.released)
        assertEquals(1, releases)
    }

    @Test
    fun receiveOnlyTransportReleasesWithoutAServiceRegistration() {
        var releases = 0
        val close = DeferredGattClose { releases++ }
        close.expire()
        assertFalse(close.released)
        close.close()
        close.close()
        assertEquals(1, releases)
    }
}
