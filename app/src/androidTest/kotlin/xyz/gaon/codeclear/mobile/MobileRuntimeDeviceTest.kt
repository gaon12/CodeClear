package xyz.gaon.codeclear.mobile

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.probe.ProbeSession

class MobileRuntimeDeviceTest {
    @Test
    fun resumingProductInvalidatesOldLeasesAndClearsDebugCaptureOptions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val runtime = instrumentation.targetContext.mobileRuntime
            val old = runtime.pauseForDiagnostics()
            ProbeSession.setDiagnostics(hardwareOverride = true, shell = true)
            runtime.resumeProduct()
            assertFalse(runtime.diagnosticsActive())
            assertFalse(ProbeSession.state.hardwareOverride)
            assertFalse(ProbeSession.state.shellFixture)
            val newer = runtime.pauseForDiagnostics()
            try {
                old.close()
                assertTrue(runtime.diagnosticsActive())
            } finally {
                newer.close()
            }
            assertFalse(runtime.diagnosticsActive())
        }
    }
}
