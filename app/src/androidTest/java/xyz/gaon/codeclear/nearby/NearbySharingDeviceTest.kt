package xyz.gaon.codeclear.nearby

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.mobile.CaptureSource
import xyz.gaon.codeclear.mobile.CapturedAlert

class NearbySharingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    @Test
    fun offHiddenPermissionLossAndDeletionRejectLateReceiptsAndStopEverySession() {
        val sessions = mutableListOf<FakeRadio>()
        var access = NearbyStatus.LISTENING
        val owner = onMain {
            NearbySharing(
                instrumentation.targetContext,
                sender = true,
                availability = { access },
                factory = NearbyRadioFactory { _, _, receive, _ ->
                    FakeRadio(receive).also(sessions::add)
                }
            )
        }
        try {
            onMain {
                owner.foreground(true)
                assertTrue(sessions.isEmpty())
                owner.configure(true)
                assertEquals(1, sessions.size)
                repeat(12) { index -> owner.offer(index.toLong(), capture()) }
                owner.offer(99, capture().copy(source = CaptureSource.PEER))
                assertEquals(12, sessions.last().published.size)
                val deadline = sessions.last().deadlines.getValue(11)
                val occurrence = sessions.last().occurrences.getValue(11)
                owner.foreground(false)
                assertTrue(sessions.last().closed)
                assertEquals(NearbyStatus.PAUSED, owner.state.status)
                owner.foreground(true)
                assertEquals(8, sessions.last().published.size)
                assertEquals(deadline, sessions.last().deadlines.getValue(11))
                assertEquals(occurrence, sessions.last().occurrences.getValue(11))
                val beforeDelete = sessions.last()
                owner.deleted(11)
                assertTrue(beforeDelete.closed)
                assertEquals(7, sessions.last().published.size)
                beforeDelete.receive(original())
            }
            onMain { assertTrue(owner.state.originals.isEmpty()) }
            val active = onMain { sessions.last().also { it.receive(original()) } }
            onMain { assertEquals(1, owner.state.originals.size) }
            onMain {
                access = NearbyStatus.PERMISSION_REQUIRED
                owner.refresh()
                assertTrue(active.closed)
                assertEquals(NearbyStatus.PERMISSION_REQUIRED, owner.state.status)
                owner.configure(false)
                active.receive(original())
            }
            onMain {
                assertTrue(owner.state.originals.isEmpty())
                assertEquals(NearbyStatus.OFF, owner.state.status)
                access = NearbyStatus.LISTENING
                owner.configure(true)
                assertTrue(sessions.last().published.isEmpty())
                owner.suspendForDiagnostics(true)
                assertTrue(sessions.last().closed)
                assertEquals(NearbyStatus.PAUSED, owner.state.status)
            }
        } finally {
            onMain {
                owner.configure(false)
                owner.foreground(false)
            }
        }
    }

    @Test
    fun retentionFailurePreservesTheOriginalAndTurningSharingOffClearsItsNotice() {
        lateinit var connection: FakeRadio
        val original = original()
        val owner = onMain {
            NearbySharing(
                instrumentation.targetContext,
                sender = false,
                availability = { NearbyStatus.RECEIVE_ONLY },
                factory = NearbyRadioFactory { _, _, receive, _ ->
                    FakeRadio(receive).also { connection = it }
                },
                retain = { _, _, complete -> complete(null) }
            ).also {
                it.configure(true)
                it.foreground(true)
            }
        }
        try {
            onMain { connection.receive(original) }
            onMain { /* Drain the posted receipt before checking the retention acknowledgment. */ }
            onMain {
                assertTrue(owner.state.retentionProblem)
                assertEquals(original, owner.original(original.root))
                owner.configure(false)
                assertFalse(owner.state.retentionProblem)
            }
        } finally {
            onMain {
                owner.configure(false)
                owner.foreground(false)
            }
        }
    }

    @Test
    fun acceptedReceiptIsRetainedAndLateAcknowledgmentCannotRestoreDeletedInbox() {
        lateinit var connection: FakeRadio
        var acknowledgment: ((Long?) -> Unit)? = null
        var allowance: (() -> Boolean)? = null
        val original = original()
        val owner = onMain {
            NearbySharing(
                instrumentation.targetContext,
                sender = false,
                availability = { NearbyStatus.RECEIVE_ONLY },
                factory = NearbyRadioFactory { _, _, receive, _ ->
                    FakeRadio(receive).also { connection = it }
                },
                retain = { received, allowed, complete ->
                    assertEquals(original, received)
                    allowance = allowed
                    acknowledgment = complete
                }
            ).also {
                it.configure(true)
                it.foreground(true)
            }
        }
        try {
            onMain { connection.receive(original) }
            onMain {
                assertTrue(allowance!!())
                acknowledgment!!(42)
            }
            onMain {
                assertEquals(42L, owner.state.retainedIds[original.root])
                assertTrue(connection.published.isEmpty())
                owner.deleted(null)
                assertFalse(allowance!!())
                acknowledgment!!(42)
            }
            onMain { assertTrue(owner.state.retainedIds.isEmpty()) }
        } finally {
            onMain {
                owner.configure(false)
                owner.foreground(false)
            }
        }
    }

    @Test
    fun receiptRequiresExplicitRetentionAndDismissalDoesNotAllowDuplicateDelivery() {
        lateinit var connection: FakeRadio
        val owner = onMain {
            NearbySharing(
                instrumentation.targetContext,
                sender = false,
                availability = { NearbyStatus.RECEIVE_ONLY },
                factory = NearbyRadioFactory { _, _, receive, _ ->
                    FakeRadio(receive).also { connection = it }
                }
            ).also {
                it.configure(true)
                it.foreground(true)
            }
        }
        val original = original()
        try {
            onMain { connection.receive(original) }
            onMain {
                assertEquals(listOf(original), owner.state.originals)
                assertEquals(original, owner.original(original.root))
                owner.dismiss(original.root)
                assertTrue(owner.state.originals.isEmpty())
                connection.receive(original)
            }
            onMain {
                assertTrue(owner.state.originals.isEmpty())
                assertTrue(connection.published.isEmpty())
                val allowed = owner.saveAllowance(original.root)
                assertFalse(allowed())
                owner.configure(false)
                assertFalse(allowed())
            }
        } finally {
            onMain {
                owner.configure(false)
                owner.foreground(false)
            }
        }
    }

    private fun original(): NearbyOriginal {
        val now = System.currentTimeMillis()
        return RelayProtocol.decode(
            RelayProtocol.encode("[DRILL] Test", "[DRILL] Original", now),
            now
        )!!
    }
    private fun capture() = CapturedAlert(
        "test.system.receiver",
        CaptureSource.NOTIFICATION,
        "[DRILL] Test",
        "[DRILL] Original",
        System.currentTimeMillis()
    )

    private class FakeRadio(val receive: (NearbyOriginal) -> Unit) : NearbyRadio {
        val published = mutableListOf<Long>()
        val deadlines = mutableMapOf<Long, Long>()
        val occurrences = mutableMapOf<Long, RelayOccurrence>()
        var closed = false
        override fun start() = Unit
        override fun publish(
            id: Long,
            title: String,
            body: String,
            capturedAt: Long,
            expiresAt: Long,
            occurrence: RelayOccurrence
        ) {
            check(!closed)
            published += id
            deadlines[id] = expiresAt
            occurrences[id] = occurrence
        }
        override fun close() {
            closed = true
        }
    }
}
