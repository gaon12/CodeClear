package xyz.gaon.codeclear.probe

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.gaon.codeclear.mobile.mobileRuntime

@RunWith(AndroidJUnit4::class)
class SourceTitleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var diagnosticScope: AutoCloseable? = null

    @Before
    fun keepSyntheticSourceTitlesOutOfPersistentProductHistory() {
        onMain { diagnosticScope = context.mobileRuntime.pauseForDiagnostics() }
    }

    @After
    fun clearSyntheticObservations() {
        onMain {
            ProbeSession.clear()
            diagnosticScope?.close()
        }
    }

    @Test
    @SuppressLint("DiscouragedApi")
    fun differentSourceLocalesYieldTheSameExplicitlyInferredType() {
        val packageName = requireNotNull(
            ProbeEnvironment.read(context).systemPackages.firstOrNull { candidate ->
                context.createPackageContext(candidate, 0).resources.getIdentifier(
                    "cmas_presidential_level_alert",
                    "string",
                    candidate
                ) != 0
            }
        ) { "No installed reviewed source exposes the localized title fixture" }
        val source = context.createPackageContext(packageName, 0)
        fun title(language: String): String {
            val configuration = Configuration(source.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language))
            }
            val resources = source.createConfigurationContext(configuration).resources
            val id = resources.getIdentifier("cmas_presidential_level_alert", "string", packageName)
            assertTrue("Reviewed source title resource is absent", id != 0)
            return resources.getString(id)
        }
        val english = title("en")
        val french = title("fr")
        assertNotEquals("This locale fixture did not exercise a translated title", english, french)
        for (label in listOf(english, french)) {
            val observation = ProbeObservation(
                packageName,
                ProbeRoute.NOTIFICATION,
                ProbeOrigin.SYSTEM_CANDIDATE,
                label,
                "[DRILL] Source-resource fixture.",
                1,
                bodyEvidence = ProbeBodyEvidence.EXPANDED_NOTIFICATION_FIELD
            )
            val ticket = onMain {
                ProbeSession.configure(ProbeRoute.NOTIFICATION, false)
                ProbeSession.configure(ProbeRoute.ACCESSIBILITY, false)
                val generation = ProbeSession.currentGeneration()
                generation to requireNotNull(ProbeSession.publish(generation, observation))
            }
            ProbeSourceTitleResources.enrich(context, ticket.first, ticket.second, observation)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (onMain { ProbeSession.state.records.single().observation.headingHint } ==
                ProbeHeadingKind.UNKNOWN && SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(50)
            }
            assertEquals(
                ProbeHeadingKind.NATIONAL,
                onMain { ProbeSession.state.records.single().observation.headingHint }
            )
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }
}
