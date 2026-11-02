package xyz.gaon.codeclear.capture

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SourceWindowVisibilityDeviceTest {
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun unknownWindowsAndAClosedConnectionCannotEstablishDisappearance() = onMain {
        var ids: Set<Int>? = null
        val connection = SourceWindowVisibility.connect { ids }
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
        val old = SourceWindowVisibility.connect { setOf(1) }
        val fresh = SourceWindowVisibility.connect { setOf(2) }
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
        val connection = SourceWindowVisibility.connect { ids }
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
        val connection = SourceWindowVisibility.connect { setOf(99) }
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
