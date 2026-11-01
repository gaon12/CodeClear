package xyz.gaon.codeclear.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSnapshotGateTest {
    @Test
    fun liveUpdatesSuppressTheOlderSameKeyButKeepOtherSnapshotEntries() {
        val gate = NotificationSnapshotGate()
        assertTrue(gate.begin())
        gate.observed("updated-source")
        assertFalse(gate.accepts("updated-source"))
        assertTrue(gate.accepts("unchanged-source"))
        assertFalse(gate.finish())
    }

    @Test
    fun aRemovalCannotBeReintroducedByTheInFlightSnapshot() {
        val gate = NotificationSnapshotGate()
        gate.begin()
        gate.observed("removed-source")
        assertFalse(gate.accepts("removed-source"))
        gate.finish()
        assertFalse(gate.accepts("removed-source"))
        assertTrue(gate.begin())
        assertTrue(gate.accepts("removed-source"))
    }

    @Test
    fun reconnectInvalidationWaitsForTheCurrentWorkerBeforeAllowingAFreshRead() {
        val gate = NotificationSnapshotGate()
        gate.begin()
        gate.invalidate()
        assertFalse(gate.accepts("old-connection"))
        assertFalse(gate.begin())
        assertTrue(gate.finish())
        assertTrue(gate.begin())
        assertTrue(gate.accepts("new-connection"))
    }

    @Test
    fun duplicateUpdatesDoNotConsumeTheChangedKeyLimit() {
        val gate = NotificationSnapshotGate(maximumChangedKeys = 1)
        gate.begin()
        repeat(200) { gate.observed("same-key") }
        assertTrue(gate.accepts("other-source"))
        assertFalse(gate.finish())
    }

    @Test
    fun tooManyKeysOrAnOversizedKeyInvalidateOnlyTheActiveRead() {
        val gate = NotificationSnapshotGate(maximumChangedKeys = 1)
        gate.begin()
        gate.observed("first")
        gate.observed("second")
        assertFalse(gate.accepts("untracked"))
        assertTrue(gate.finish())
        gate.begin()
        gate.observed("x".repeat(385))
        assertFalse(gate.accepts("untracked"))
        assertTrue(gate.finish())
        gate.observed("outside-a-snapshot")
        gate.begin()
        assertTrue(gate.accepts("outside-a-snapshot"))
    }
}
