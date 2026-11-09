package xyz.gaon.codeclear.mobile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.translation.ServerCacheClearResult

private enum class ServerDecision { ALLOW, REMOVE, CLEAR }
private data class PendingServerDecision(
    val kind: ServerDecision,
    val revision: Long,
    val credentialId: String
)

@Composable
internal fun OwnedServerSettings(options: MobileOptions, actions: MobileActions) {
    val access = actions.serverAccess
    if (!access.configured || !options.labsEnabled) return
    var pending by remember { mutableStateOf<PendingServerDecision?>(null) }
    val ready = access.loaded && access.ready && !access.busy
    val permitted = options.ownedServerAllowed && options.ownedServerCredentialId.isNotEmpty()
    SettingsGroup {
        SettingNote(stringResource(R.string.mobile_owned_description))
        SettingSwitchRow(
            stringResource(R.string.mobile_owned_permission),
            stringResource(if (permitted) R.string.mobile_on else R.string.mobile_off),
            checked = permitted,
            enabled = ready || (access.loaded && !access.busy && permitted),
            onCheckedChange = {
                if (it) {
                    pending =
                        PendingServerDecision(
                            ServerDecision.ALLOW,
                            access.revision,
                            access.credentialId
                        )
                } else {
                    actions.configure(
                        options.copy(ownedServerAllowed = false, ownedServerCredentialId = "")
                    )
                }
            }
        )
        SettingDivider()
        SettingNote(
            stringResource(
                if (access.ready) {
                    R.string.mobile_owned_access_saved
                } else {
                    R.string.mobile_owned_access_missing
                }
            )
        )
        SettingNote(stringResource(R.string.mobile_remote_priority))
        SettingRow(
            stringResource(R.string.mobile_owned_access_remove),
            enabled = access.loaded && !access.busy,
            onClick = {
                pending =
                    PendingServerDecision(
                        ServerDecision.REMOVE,
                        access.revision,
                        access.credentialId
                    )
            }
        )
        SettingDivider()
        SettingRow(
            stringResource(R.string.mobile_owned_cache_clear),
            enabled = ready,
            onClick = {
                pending =
                    PendingServerDecision(
                        ServerDecision.CLEAR,
                        access.revision,
                        access.credentialId
                    )
            }
        )
        if (access.busy || !access.loaded) SettingNote(stringResource(R.string.mobile_working))
        if (access.failed) SettingNote(stringResource(R.string.mobile_owned_access_failed))
        access.cacheResult?.let {
            SettingNote(
                stringResource(
                    if (it == ServerCacheClearResult.CLEARED) {
                        R.string.mobile_owned_cache_cleared
                    } else {
                        R.string.mobile_owned_cache_failed
                    }
                )
            )
        }
        SettingNote(stringResource(R.string.mobile_server_translation_notice))
        OwnedProviderTermsLink()
    }
    pending?.let { decision ->
        val enabled =
            access.loaded && !access.busy && access.revision == decision.revision &&
                (
                    decision.kind == ServerDecision.REMOVE ||
                        (ready && access.credentialId == decision.credentialId)
                    )
        val title = when (decision.kind) {
            ServerDecision.ALLOW -> R.string.mobile_owned_server
            ServerDecision.REMOVE -> R.string.mobile_owned_access_remove
            ServerDecision.CLEAR -> R.string.mobile_owned_cache_clear
        }
        val message = when (decision.kind) {
            ServerDecision.ALLOW -> R.string.mobile_owned_disclosure
            ServerDecision.REMOVE -> R.string.mobile_owned_access_remove_review
            ServerDecision.CLEAR -> R.string.mobile_owned_cache_review
        }
        MobileConfirmationDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).testTag("owned-server-review"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(message))
                    if (decision.kind == ServerDecision.ALLOW) OwnedProviderTermsLink()
                }
            },
            confirmButton = {
                TextButton(
                    enabled = enabled,
                    modifier = Modifier.testTag(
                        "owned-server-confirm"
                    ),
                    onClick = {
                        pending = null
                        if (enabled) {
                            when (decision.kind) {
                                ServerDecision.ALLOW -> actions.allowServer(
                                    decision.revision,
                                    decision.credentialId
                                )

                                ServerDecision.REMOVE -> actions.removeServerAccess(
                                    decision.revision
                                )

                                ServerDecision.CLEAR -> actions.clearServerCache(decision.revision)
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (decision.kind ==
                                ServerDecision.ALLOW
                            ) {
                                R.string.mobile_allow
                            } else {
                                R.string.mobile_delete
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                }) { Text(stringResource(R.string.mobile_cancel)) }
            }
        )
    }
}

@Composable
internal fun OwnedProviderTermsLink() {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    TextButton(onClick = {
        try {
            uri.openUri("https://console.groq.com/docs/your-data")
        } catch (_: RuntimeException) {
            Toast.makeText(context, R.string.mobile_link_unavailable, Toast.LENGTH_LONG).show()
        }
    }) { Text(stringResource(R.string.mobile_owned_terms)) }
    PrivacyPolicyLink()
}
