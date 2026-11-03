package xyz.gaon.codeclear.mobile

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import xyz.gaon.codeclear.R

@Composable
internal fun PrivacyPolicyLink() {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val korean = LocalConfiguration.current.locales[0]?.language == "ko"
    val address = "https://gaon12.github.io/CodeClear/privacy/" + if (korean) "ko.html" else ""
    TextButton(
        onClick = {
            try {
                uri.openUri(address)
            } catch (_: RuntimeException) {
                Toast.makeText(context, R.string.mobile_link_unavailable, Toast.LENGTH_LONG).show()
            }
        },
        modifier = Modifier.testTag("privacy-policy-link")
    ) { Text(stringResource(R.string.mobile_privacy_policy)) }
}
