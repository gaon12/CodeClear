package xyz.gaon.codeclear.mobile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var tokenRevision by remember { mutableStateOf<Long?>(null) }
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
        SettingRow(
            stringResource(R.string.mobile_owned_access),
            stringResource(
                if (access.ready) {
                    R.string.mobile_owned_access_saved
                } else {
                    R.string.mobile_owned_access_missing
                }
            ),
            enabled = access.loaded && !access.busy,
            onClick = { tokenRevision = access.revision }
        )
        if (access.ready) {
            SettingRow(
                stringResource(R.string.mobile_owned_access_remove),
                enabled = ready,
                onClick = {
                    pending =
                        PendingServerDecision(
                            ServerDecision.REMOVE,
                            access.revision,
                            access.credentialId
                        )
                }
            )
        }
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
    tokenRevision?.let { revision ->
        ServerAccessDialog(
            enabled = access.loaded && !access.busy && access.revision == revision,
            dismiss = { tokenRevision = null },
            save = { value ->
                tokenRevision = null
                actions.saveServerAccess(value, revision)
            }
        )
    }
    pending?.let { decision ->
        val enabled =
            ready && access.revision == decision.revision &&
                access.credentialId == decision.credentialId
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
private fun ServerAccessDialog(enabled: Boolean, dismiss: () -> Unit, save: (String) -> Unit) {
    // Access codes deliberately do not enter saveable state or survive activity recreation.
    var code by remember { mutableStateOf("") }
    val valid = code.matches(Regex("[A-Za-z0-9._~-]{32,256}"))
    MobileConfirmationDialog(
        onDismissRequest = {
            code = ""
            dismiss()
        },
        title = { Text(stringResource(R.string.mobile_owned_access)) },
        text = {
            OutlinedTextField(
                code,
                onValueChange = { if (it.length <= 1024) code = it },
                modifier = Modifier.fillMaxWidth().testTag("owned-server-token"),
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                label = { Text(stringResource(R.string.mobile_owned_access)) },
                supportingText = { Text(stringResource(R.string.mobile_owned_access_hint)) },
                isError =
                    code.length > 256 ||
                        code.any {
                            it !in
                                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~-"
                        }
            )
        },
        confirmButton = {
            TextButton(
                enabled = enabled && valid,
                modifier = Modifier.testTag(
                    "owned-server-token-save"
                ),
                onClick = {
                    val value = code
                    code = ""
                    if (enabled && valid) save(value)
                }
            ) { Text(stringResource(R.string.mobile_owned_access_save)) }
        },
        dismissButton = {
            TextButton(onClick = {
                code = ""
                dismiss()
            }) { Text(stringResource(R.string.mobile_cancel)) }
        }
    )
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
}
