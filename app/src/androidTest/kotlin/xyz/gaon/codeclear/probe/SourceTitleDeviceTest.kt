package xyz.gaon.codeclear.probe

import android.annotation.SuppressLint
import android.content.Context
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
        data class TitleFixture(val source: Context, val name: String, val kind: ProbeHeadingKind)
        fun title(fixture: TitleFixture, language: String): String? {
            val source = fixture.source
            val configuration = Configuration(source.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language))
            }
            val resources = source.createConfigurationContext(configuration).resources
            val id = resources.getIdentifier(
                fixture.name,
                "string",
                source.packageName
            )
            return id.takeIf { it != 0 }?.let(resources::getString)
        }
        val names = listOf(
            "cmas_presidential_level_alert" to ProbeHeadingKind.NATIONAL,
            "cmas_extreme_alert" to ProbeHeadingKind.EXTREME,
            "etws_tsunami_warning" to ProbeHeadingKind.TSUNAMI
        )
        // Carrier overrides can leave one heading untranslated; retain a genuinely localized fixture.
        val fixture = requireNotNull(
            ProbeEnvironment.read(context).systemPackages.asSequence()
                .map { context.createPackageContext(it, 0) }
                .flatMap { source ->
                    names.asSequence().map { (name, kind) -> TitleFixture(source, name, kind) }
                }
                .firstOrNull {
                    val english = title(it, "en")
                    val french = title(it, "fr")
                    english != null && french != null && english != french
                }
        ) { "No installed reviewed source exposes two distinct localized title fixtures" }
        val packageName = fixture.source.packageName
        val english = requireNotNull(title(fixture, "en"))
        val french = requireNotNull(title(fixture, "fr"))
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
                fixture.kind,
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
