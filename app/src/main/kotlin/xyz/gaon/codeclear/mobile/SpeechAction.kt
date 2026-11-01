package xyz.gaon.codeclear.mobile

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R

@Composable
internal fun SpeechAction(
    key: String,
    body: String,
    language: String?,
    tag: String,
    actions: MobileActions
) {
    var identification by remember(key, body, language) { mutableStateOf<AutoCloseable?>(null) }
    var pending by remember(key, body, language) { mutableStateOf<Any?>(null) }
    val latestActions by rememberUpdatedState(actions)
    val active = pending != null || actions.speechState.let {
        it.key == key && it.phase in setOf(SpeechPhase.PREPARING, SpeechPhase.PLAYING)
    }
    DisposableEffect(key, body, language) {
        onDispose {
            identification?.close()
            identification = null
            pending = null
            if (latestActions.speechState.key == key) latestActions.stopReading()
        }
    }
    IconButton(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag),
        onClick = {
            if (active) {
                identification?.close()
                identification = null
                pending = null
                actions.stopReading()
            } else if (language != null) {
                actions.readAloud(key, body, language)
            } else {
                val ticket = Any()
                pending = ticket
                identification = actions.identifyOriginal(body) { detected ->
                    if (pending === ticket) {
                        pending = null
                        actions.readAloud(key, body, detected ?: "und")
                    }
                }
            }
        }
    ) {
        Icon(
            painterResource(if (active) R.drawable.ic_speech_stop else R.drawable.ic_speech),
            stringResource(if (active) R.string.mobile_stop_reading else R.string.mobile_read_aloud)
        )
    }
}
