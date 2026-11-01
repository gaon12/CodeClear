package xyz.gaon.codeclear.mobile

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInventoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class Backend : ModelInventoryBackend {
        var calls = 0
        var complete: ((Set<String>?) -> Unit)? = null
        override fun downloaded(complete: (Set<String>?) -> Unit) {
            calls++
            this.complete =
                complete
        }
    }

    @Test fun queriesAreSharedAndFailedRefreshDoesNotEraseThePreviousInventory() = onMain {
        val backend = Backend()
        val inventory = ModelInventory(backend) {}
        inventory.refresh()
        repeat(20) { inventory.refresh(true) }
        assertEquals(1, backend.calls)
        backend.complete!!(setOf("ko", "zh", "es", "invalid", "en"))
        assertEquals(setOf("ko", "zh", "es"), inventory.state.downloaded)
        assertNotNull(inventory.state.checkedAtMillis)
        inventory.refresh()
        assertEquals(1, backend.calls)
        inventory.refresh(true)
        backend.complete!!(null)
        assertTrue(inventory.state.failed)
        assertFalse(inventory.state.checking)
        assertEquals(setOf("ko", "zh", "es"), inventory.state.downloaded)
    }

    @Test fun updateCompletionDuringAnOlderQuerySchedulesOneFreshSnapshot() = onMain {
        val backend = Backend()
        val inventory = ModelInventory(backend) {}
        inventory.refresh()
        inventory.updateFinished("ko", true)
        inventory.updateFinished("de", false)
        assertEquals(1, backend.calls)
        backend.complete!!(emptySet())
        assertEquals(2, backend.calls)
        assertTrue(inventory.state.checking)
        backend.complete!!(setOf("ko"))
        assertFalse(inventory.state.checking)
        assertEquals(setOf("ko"), inventory.state.downloaded)
        assertNotNull(inventory.state.updateChecks["ko"])
        assertTrue("de" in inventory.state.failedUpdates)
        inventory.updateFinished("de", true)
        assertFalse("de" in inventory.state.failedUpdates)
    }

    @Test fun installedSdkInventoryReturnsOnlyLanguageMetadataWithoutDownloading() {
        val complete = CountDownLatch(1)
        val result = AtomicReference<Set<String>?>()
        val backend = MlKitModelInventoryBackend()
        onMain {
            backend.downloaded {
                result.set(it)
                complete.countDown()
            }
        }
        assertTrue(complete.await(20, TimeUnit.SECONDS))
        assertNotNull(result.get())
        assertTrue(result.get()!!.size <= 256)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("codeclear_downloaded_models", result.get()!!.size)
            }
        )
    }

    @Test fun finishedAlertDownloadsRefreshInventoryWithoutClaimingAnUpdateCheck() = onMain {
        val backend = Backend()
        val inventory = ModelInventory(backend) {}
        inventory.preparationChanged(
            PreparationState(PreparationPhase.IDENTIFYING),
            PreparationState(PreparationPhase.READY, "ko")
        )
        assertEquals(0, backend.calls)
        inventory.preparationChanged(
            PreparationState(PreparationPhase.WAITING_FOR_WIFI, "ko"),
            PreparationState(PreparationPhase.READY, "ko")
        )
        assertEquals(1, backend.calls)
        backend.complete!!(setOf("ko"))
        assertEquals(setOf("ko"), inventory.state.downloaded)
        assertTrue(inventory.state.updateChecks.isEmpty())
        inventory.refresh(force = true)
        inventory.preparationChanged(
            PreparationState(PreparationPhase.WAITING_FOR_WIFI, "fr"),
            PreparationState(PreparationPhase.IDLE, "fr")
        )
        assertEquals(2, backend.calls)
        backend.complete!!(setOf("ko"))
        assertEquals(3, backend.calls)
        backend.complete!!(setOf("ko", "fr"))
        assertEquals(setOf("ko", "fr"), inventory.state.downloaded)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
