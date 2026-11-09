package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.IssuerBasis
import xyz.gaon.codeclear.translation.TranslationEngineKind
import xyz.gaon.codeclear.translation.estimateIssuer
import xyz.gaon.codeclear.translation.evidencedIssuer

@Composable
fun dateTime(time: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = TimeZone.getDefault().id
    return remember(time, locale, zone) {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            locale
        ).format(Date(time))
    }
}

@Composable
fun AlertMetadata(alert: SavedAlert) {
    SettingsGroup {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp).testTag("alert-metadata")
        ) {
            Text(
                stringResource(R.string.mobile_capture_information),
                style = MaterialTheme.typography.titleMedium
            )
            alert.capture.context.country?.let { country ->
                val locale = LocalConfiguration.current.locales[0].toLanguageTag()
                Text(
                    stringResource(
                        if (country.evidence ==
                            xyz.gaon.codeclear.reception.CountryEvidence.CBS_PLMN
                        ) {
                            R.string.mobile_country_cbs
                        } else if (country.evidence ==
                            xyz.gaon.codeclear.reception.CountryEvidence.PEER
                        ) {
                            R.string.mobile_country_peer
                        } else {
                            R.string.mobile_country_network
                        },
                        country.flag + " " + country.displayName(locale)
                    ),
                    modifier = Modifier.testTag("reception-country")
                )
            }
            alert.capture.receivedAtMillis?.let {
                Text(stringResource(R.string.mobile_received, dateTime(it)))
            }
            alert.capture.postedAtMillis?.let {
                Text(stringResource(R.string.mobile_posted, dateTime(it)))
            }
            Text(
                stringResource(R.string.mobile_times_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    IssuerAttribution(alert)
}

@Composable
private fun IssuerAttribution(alert: SavedAlert) {
    val translation = alert.translation
    val original = alert.capture.original
    val claim = remember(original, translation?.issuer, translation?.phase, translation?.engine) {
        val supplied = translation?.issuer?.takeIf {
            translation.engine == TranslationEngineKind.OWNED_SERVER &&
                translation.phase == TranslationPhase.READY &&
                it.basis == IssuerBasis.EXTRACTED_FROM_TEXT
        }
        val evidenced = supplied?.let {
            evidencedIssuer(
                original,
                it.originalName,
                it.translatedName,
                it.sourceExcerpt.orEmpty(),
                it.basis
            )
                ?.takeIf { validated -> validated == supplied }
        }
        evidenced ?: estimateIssuer(original)
    }
    SettingsGroup(tag = "issuer-attribution") {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.mobile_issuer_estimate),
                style = MaterialTheme.typography.titleMedium
            )
            if (claim == null) {
                Text(
                    stringResource(R.string.mobile_issuer_unknown),
                    Modifier.testTag("issuer-unknown")
                )
            } else {
                Text(
                    listOfNotNull(
                        claim.translatedName?.takeIf {
                            it != claim.originalName
                        },
                        claim.originalName
                    )
                        .joinToString(" · "),
                    Modifier.testTag("issuer-name")
                )
                Text(
                    stringResource(
                        if (claim.basis ==
                            IssuerBasis.EXTRACTED_FROM_TEXT
                        ) {
                            R.string.mobile_issuer_ai_notice
                        } else {
                            R.string.mobile_issuer_rule_notice
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(
                        R.string.mobile_issuer_evidence,
                        historyPreview(claim.sourceExcerpt!!)
                    ),
                    Modifier.testTag("issuer-evidence"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
