package xyz.gaon.codeclear.mobile

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeEnvironment
import xyz.gaon.codeclear.capture.ProbeRadio
import xyz.gaon.codeclear.capture.ProbeSessionState

/** Connection indicators describe synthetic access state, never successful physical capture. */
class HomeCaptureStatusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MobileFixtureActivity>()
    private var scope: AutoCloseable? = null
    private var options by mutableStateOf(MobileOptions(notificationConsent = true))
    private var environment by mutableStateOf(
        ProbeEnvironment(ProbeRadio.PRESENT, setOf("test.system.receiver"), true, true)
    )
    private var session by mutableStateOf(ProbeSessionState(notificationConnected = true))
    private var languageTag by mutableStateOf("en")
    private var largeText by mutableStateOf(false)
    private val localized
        get() = compose.activity.createConfigurationContext(
            Configuration(compose.activity.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(languageTag))
            }
        )

    @Before
    fun showSyntheticConnections() {
        compose.runOnUiThread { scope = compose.activity.mobileRuntime.pauseForDiagnostics() }
        compose.setContent {
            val context = localized
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalResources provides context.resources,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(density.density, if (largeText) 1.8f else 1f)
            ) {
                MobileTheme(ThemeChoice.LIGHT) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        HomeOverview(MobileState(options = options), environment, session, {}, {})
                    }
                }
            }
        }
    }

    @After
    fun releaseSyntheticConnections() {
        compose.runOnUiThread { scope?.close() }
    }

    private fun status(id: Int) = compose.onNodeWithText(localized.getString(id))

    @Test
    fun connectionRequiresConsentAccessAndASourceWithoutClaimingCapture() {
        status(R.string.mobile_home_capture_connected).assertIsDisplayed()
        compose.onNodeWithTag("home-capture-notice").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { options = options.copy(notificationConsent = false) }
        status(R.string.mobile_home_setup).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { options = options.copy(accessibilityConsent = true) }
        status(R.string.mobile_home_setup).assertIsDisplayed()
        compose.runOnIdle { session = session.copy(accessibilityConnected = true) }
        status(R.string.mobile_home_capture_connected).assertIsDisplayed()
        compose.runOnIdle { environment = environment.copy(accessibilityGranted = false) }
        status(R.string.mobile_home_setup).assertIsDisplayed()
        compose.runOnIdle { environment = environment.copy(systemPackages = emptySet()) }
        status(R.string.mobile_home_check_source).assertIsDisplayed()
        compose.runOnIdle { environment = environment.copy(radio = ProbeRadio.ABSENT) }
        status(R.string.mobile_home_no_radio).assertIsDisplayed()
        compose.onNodeWithTag("home-capture-notice").assertDoesNotExist()
    }

    @Test
    fun everyLocaleKeepsTheCaptureLimitationReachableAtLargeText() {
        compose.runOnIdle { largeText = true }
        for (tag in listOf("en", "ko", "ja", "fr", "de", "zh-Hans", "zh-Hant")) {
            compose.runOnIdle { languageTag = tag }
            status(R.string.mobile_home_capture_connected).performScrollTo().assertIsDisplayed()
            status(R.string.mobile_home_capture_notice).performScrollTo().assertIsDisplayed()
        }
    }
}
