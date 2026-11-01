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
            Text(
                if (alert.capture.source == CaptureSource.PEER) {
                    stringResource(R.string.mobile_nearby_unverified)
                } else {
                    stringResource(R.string.mobile_source, alert.capture.sourcePackage)
                },
                style = MaterialTheme.typography.bodyMedium
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
}
