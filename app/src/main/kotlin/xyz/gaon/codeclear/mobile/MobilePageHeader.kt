package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
internal fun MobilePageHeader(
    title: String,
    modifier: Modifier = Modifier,
    back: (() -> Unit)? = null,
    titleTag: String = "destination-title",
    backTag: String = "page-back"
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (back != null) {
            IconButton(onClick = back, modifier = Modifier.testTag(backTag)) {
                Icon(
                    painterResource(R.drawable.ic_back),
                    stringResource(R.string.mobile_back),
                    Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 12.dp).testTag(titleTag).semantics {
                heading()
            },
            style = MaterialTheme.typography.titleLarge
        )
    }
}
