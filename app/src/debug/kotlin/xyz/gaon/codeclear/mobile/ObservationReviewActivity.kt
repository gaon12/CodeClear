package xyz.gaon.codeclear.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.R

/** Manual, memory-only UI review; collection and provider work stay outside this activity. */
class ObservationReviewActivity : ComponentActivity() {
    private var diagnosticScope: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || packageName != "xyz.gaon.codeclear.validation") {
            finish()
            return
        }
        diagnosticScope = mobileRuntime.pauseForDiagnostics()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val alert = reviewAlert()
        val actions = MobileActions(
            configure = {}, search = {}, detail = {}, delete = {}, retry = { _, _, _ -> },
            prepare = { _, _, _ -> }, settings = {}, openSource = {}, notificationPermission = {},
            notificationsAllowed = false, older = {}, newest = {}, retryStorage = {}
        )
        setContent {
            var theme by rememberSaveable { mutableStateOf(ThemeChoice.SYSTEM) }
            MobileTheme(theme) {
                androidx.compose.material3.Scaffold(
                    containerColor = MaterialTheme.colorScheme.background
                ) { insets ->
                    Column(Modifier.fillMaxSize().padding(insets)) {
                        MobilePageHeader(
                            stringResource(R.string.mobile_details),
                            back = ::finish,
                            backTag = "observation-review-back"
                        )
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                stringResource(R.string.observation_review_notice),
                                Modifier.testTag("observation-review-notice"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(ThemeChoice.LIGHT, ThemeChoice.DARK).forEach { choice ->
                                    FilterChip(
                                        selected = theme == choice,
                                        onClick = { theme = choice },
                                        label = {
                                            Text(
                                                stringResource(
                                                    if (choice ==
                                                        ThemeChoice.LIGHT
                                                    ) {
                                                        R.string.mobile_light
                                                    } else {
                                                        R.string.mobile_dark
                                                    }
                                                )
                                            )
                                        },
                                        modifier = Modifier.testTag(
                                            "observation-review-${choice.name.lowercase()}"
                                        )
                                    )
                                }
                            }
                            ObservationHistory(alert, MobileState(loaded = true), actions)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        diagnosticScope?.close()
        super.onDestroy()
    }

    private fun reviewAlert() = SavedAlert(
        id = 9_100_001,
        capture = CapturedAlert(
            "test.synthetic.receiver",
            CaptureSource.NOTIFICATION,
            "[DRILL] UI review",
            "[DRILL] Synthetic collection history.",
            1700000002000
        ),
        translation = null,
        observationCount = 4,
        observations = listOf(
            HistoryObservation(
                12,
                CaptureSource.NOTIFICATION,
                1700000002000,
                1700000001000,
                1699999999000
            ),
            HistoryObservation(11, CaptureSource.DIALOG, 1700000000000, null, null),
            HistoryObservation(
                10,
                CaptureSource.ORIGINAL_NOTIFICATION,
                1699913600000,
                null,
                1699913599000
            ),
            HistoryObservation(9, CaptureSource.NOTIFICATION, 1699913599000, 1699913598000, null)
        )
    )
}
