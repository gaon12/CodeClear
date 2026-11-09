package xyz.gaon.codeclear.capture

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SourceWindowVisibilityDeviceTest {
    @Test
    fun onlyReviewedDefaultDisplayAlertWindowsCanCorrelateNotificationCandidates() = onMain {
        var windows = mapOf(10 to 0, 11 to 1, 12 to 0, 99 to 0)
        val connection = SourceWindowVisibility.connect { windows }
        val subscription = SourceWindowVisibility.observe {}
        try {
            SourceWindowVisibility.sourceOpened(10, "test.system.receiver")
            SourceWindowVisibility.sourceOpened(11, "test.system.receiver")
            SourceWindowVisibility.sourceOpened(12)
            assertEquals(
                mapOf(10 to "test.system.receiver"),
                SourceWindowVisibility.snapshot()?.alertPackages
            )
            assertEquals(setOf(10, 11, 12), SourceWindowVisibility.snapshot()?.sourceIds)
            SourceWindowVisibility.sourceOpened(10)
            assertEquals(emptyMap<Int, String>(), SourceWindowVisibility.snapshot()?.alertPackages)
            SourceWindowVisibility.sourceOpened(10, "test.system.receiver")
            windows = mapOf(99 to 0)
            assertEquals(emptyMap<Int, String>(), SourceWindowVisibility.snapshot()?.alertPackages)
            windows = mapOf(10 to 0, 99 to 0)
            assertEquals(emptyMap<Int, String>(), SourceWindowVisibility.snapshot()?.alertPackages)
        } finally {
            subscription.close()
            connection.close()
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun unknownWindowsAndAClosedConnectionCannotEstablishDisappearance() = onMain {
        var ids: Set<Int>? = null
        val connection = SourceWindowVisibility.connect { ids?.associateWith { 0 } }
        val changes = mutableListOf<SourceWindowChange>()
        val subscription = SourceWindowVisibility.observe(changes::add)
        try {
            assertNull(SourceWindowVisibility.snapshot())
            SourceWindowVisibility.sourceOpened(10)
            ids = setOf(10, 99)
            assertEquals(setOf(10), SourceWindowVisibility.snapshot()?.sourceIds)
            ids = setOf(99)
            assertEquals(emptySet<Int>(), SourceWindowVisibility.snapshot()?.sourceIds)
            assertEquals(listOf(SourceWindowChange.OPENED), changes)
            connection.close()
            assertEquals(SourceWindowChange.UNAVAILABLE, changes.last())
            assertNull(SourceWindowVisibility.snapshot())
        } finally {
            subscription.close()
            connection.close()
        }
    }

    @Test
    fun staleUnbindCannotRemoveANewerInspector() = onMain {
        val old = SourceWindowVisibility.connect { mapOf(1 to 0) }
        val fresh = SourceWindowVisibility.connect { mapOf(2 to 0) }
        try {
            old.close()
            assertEquals(setOf(2), SourceWindowVisibility.snapshot()?.visibleIds)
        } finally {
            fresh.close()
            old.close()
        }
    }

    @Test
    fun oversizedOrUnidentifiableWindowStateFailsClosed() = onMain {
        var ids = (1..65).toSet()
        val connection = SourceWindowVisibility.connect { ids.associateWith { 0 } }
        val subscription = SourceWindowVisibility.observe {}
        try {
            assertNull(SourceWindowVisibility.snapshot())
            ids = setOf(1)
            assertNotNull(SourceWindowVisibility.snapshot())
            SourceWindowVisibility.sourceOpened(-1)
            assertNull(SourceWindowVisibility.snapshot())
        } finally {
            subscription.close()
            connection.close()
        }
    }

    @Test
    fun aPresentationObserverFailureDoesNotEscapeTheCaptureCallback() = onMain {
        val connection = SourceWindowVisibility.connect { mapOf(99 to 0) }
        val failing = SourceWindowVisibility.observe { error("Synthetic presentation failure") }
        var signals = 0
        val healthy = SourceWindowVisibility.observe { signals++ }
        try {
            SourceWindowVisibility.sourceOpened(10)
            SourceWindowVisibility.sourceOpened(11)
            assertEquals(2, signals)
        } finally {
            failing.close()
            healthy.close()
            connection.close()
        }
    }
}
