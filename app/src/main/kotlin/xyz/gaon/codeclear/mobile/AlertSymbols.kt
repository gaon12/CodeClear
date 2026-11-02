package xyz.gaon.codeclear.mobile

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.capture.ProbeSourcePolicy

internal enum class AlertSymbolKind(val drawable: Int) {
    GENERAL(R.drawable.ic_message),
    CRITICAL(R.drawable.ic_alert_critical),
    URGENT(R.drawable.ic_alert_urgent),
    SAFETY(R.drawable.ic_alert_safety),
    MISSING(R.drawable.ic_alert_missing),
    TEST(R.drawable.ic_alert_test)
}

internal data class AlertSymbol(
    val kind: AlertSymbolKind,
    val official: NationalAlertType? = null,
    val estimateLabel: Int? = null
)

/** Official evidence takes precedence; reviewed title hints remain visibly separate estimates. */
internal fun alertSymbol(capture: CapturedAlert): AlertSymbol {
    nationalAlertType(capture)?.let { type ->
        val kind = when (type) {
            NationalAlertType.EXTREME_EMERGENCY -> AlertSymbolKind.CRITICAL
            NationalAlertType.EMERGENCY -> AlertSymbolKind.URGENT
            NationalAlertType.PUBLIC_SAFETY -> AlertSymbolKind.SAFETY
            NationalAlertType.MISSING_PERSON -> AlertSymbolKind.MISSING
        }
        return AlertSymbol(kind, official = type)
    }
    if (capture.source == CaptureSource.PEER ||
        capture.sourcePackage !in ProbeSourcePolicy.candidatePackages
    ) {
        return AlertSymbol(AlertSymbolKind.GENERAL)
    }
    val (kind, label) = when (capture.headingHint) {
        "NATIONAL" -> AlertSymbolKind.CRITICAL to R.string.mobile_hint_national
        "EXTREME" -> AlertSymbolKind.CRITICAL to R.string.mobile_hint_extreme
        "SEVERE" -> AlertSymbolKind.URGENT to R.string.mobile_hint_severe
        "PUBLIC_SAFETY" -> AlertSymbolKind.SAFETY to R.string.mobile_hint_safety
        "AMBER" -> AlertSymbolKind.MISSING to R.string.mobile_hint_missing
        "TEST" -> AlertSymbolKind.TEST to R.string.mobile_hint_test
        else -> return AlertSymbol(AlertSymbolKind.GENERAL)
    }
    return AlertSymbol(kind, estimateLabel = label)
}

@Composable
internal fun AlertSymbolIcon(symbol: AlertSymbol, modifier: Modifier = Modifier) {
    // The adjacent visible type label also conveys the meaning without color or duplicate speech.
    Icon(
        painterResource(symbol.kind.drawable),
        contentDescription = null,
        modifier = modifier.testTag("alert-symbol-${symbol.kind.name.lowercase()}")
    )
}

@Composable
internal fun AlertTypeLabel(symbol: AlertSymbol, expanded: Boolean = false) {
    val label = when {
        symbol.official != null -> if (expanded) {
            stringResource(R.string.mobile_alert_type, stringResource(symbol.official.label))
        } else {
            stringResource(symbol.official.label)
        }

        symbol.estimateLabel != null -> stringResource(
            R.string.mobile_title_estimate,
            stringResource(symbol.estimateLabel)
        )

        else -> return
    }
    Text(
        label,
        Modifier.testTag(
            if (symbol.official !=
                null
            ) {
                "national-alert-type"
            } else {
                "estimated-alert-type"
            }
        ),
        style = if (expanded) {
            MaterialTheme.typography.labelLarge
        } else {
            MaterialTheme.typography.labelSmall
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
