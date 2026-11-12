package com.github.damontecres.wholphin.ui.setup.fetcherr

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.ConfiguredServices
import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.components.QrCode
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.fetcherr.fetcherrErrorMessage
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay

/**
 * Connect the app to Fetcherr, or check, change or remove the saved server
 *
 * See [FetcherrSettingsViewModel] for the flow. Back returns to the previous screen.
 */
@Composable
fun FetcherrSettingsDialog(
    onDismissRequest: () -> Unit,
    viewModel: FetcherrSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.init() }
    val state by viewModel.state.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val removedMessage = stringResource(R.string.fetcherr_removed)
    LaunchedEffect(state.closed) {
        if (state.closed) {
            Toast.makeText(context, removedMessage, Toast.LENGTH_SHORT).show()
            onDismissRequest.invoke()
        }
    }
    val step = state.step
    BasicDialog(
        onDismissRequest = onDismissRequest,
        elevation = 3.dp,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(enabled = state.stack.size > 1) { viewModel.back() }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .focusGroup()
                    .width(if (step is FetcherrSetupStep.Pairing) 640.dp else 520.dp)
                    .padding(24.dp),
        ) {
            when (step) {
                null, FetcherrSetupStep.Searching -> {
                    SearchingContent()
                }

                FetcherrSetupStep.Saved -> {
                    SavedContent(
                        url = state.savedUrl,
                        connection = connection,
                        onPairAgain = viewModel::pairAgain,
                        onRetry = viewModel::retryConnection,
                        onChangeServer = viewModel::changeServer,
                        onRemove = viewModel::remove,
                    )
                }

                is FetcherrSetupStep.Address -> {
                    AddressContent(
                        step = step,
                        onSubmit = viewModel::submitAddress,
                    )
                }

                is FetcherrSetupStep.Pairing -> {
                    PairingContent(
                        step = step,
                        onNewCode = viewModel::newCode,
                        onChangeAddress = viewModel::useAddress,
                        onUseApiKey = viewModel::useApiKey,
                    )
                }

                is FetcherrSetupStep.ApiKey -> {
                    ApiKeyContent(
                        step = step,
                        onSubmit = viewModel::submitApiKey,
                    )
                }

                is FetcherrSetupStep.Done -> {
                    DoneContent(
                        step = step,
                        onDone = onDismissRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Title(
    text: String,
    subtitle: String? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    if (subtitle != null) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Progress(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.height(32.dp),
    ) {
        CircularProgress(Modifier.size(20.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

@Composable
private fun ColumnScope.Buttons(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
    ) {
        content()
    }
}

@Composable
private fun ColumnScope.SearchingContent() {
    Title(stringResource(R.string.fetcherr))
    Progress(
        text = stringResource(R.string.fetcherr_searching),
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}

@Composable
private fun ColumnScope.SavedContent(
    url: String,
    connection: FetcherrConnection,
    onPairAgain: () -> Unit,
    onRetry: () -> Unit,
    onChangeServer: () -> Unit,
    onRemove: () -> Unit,
) {
    Title(stringResource(R.string.fetcherr), url)
    val unauthorized = (connection as? FetcherrConnection.Error)?.isUnauthorized == true
    when (connection) {
        is FetcherrConnection.Connected -> {
            ConnectedStatus(connection.status.version, connection.status.services)
        }

        is FetcherrConnection.Error -> {
            ErrorText(
                text =
                    if (unauthorized) {
                        stringResource(R.string.fetcherr_api_key_revoked)
                    } else {
                        fetcherrErrorMessage(connection.exception)
                    },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        else -> {
            Progress(
                text = stringResource(R.string.fetcherr_testing),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
    // Lead with what fixes the problem: pairing for a rejected key, retrying for an unreachable server
    val focusRequester = remember { FocusRequester() }
    val primary =
        when {
            unauthorized -> R.string.fetcherr_pair_again
            connection is FetcherrConnection.Error -> R.string.retry
            else -> R.string.fetcherr_change_server
        }
    LaunchedEffect(primary) { focusRequester.tryRequestFocus() }
    Buttons {
        when (primary) {
            R.string.fetcherr_pair_again -> {
                TextButton(R.string.fetcherr_pair_again, onPairAgain, Modifier.focusRequester(focusRequester))
                TextButton(R.string.fetcherr_change_server, onChangeServer)
            }

            R.string.retry -> {
                TextButton(R.string.retry, onRetry, Modifier.focusRequester(focusRequester))
                TextButton(R.string.fetcherr_change_server, onChangeServer)
            }

            else -> {
                TextButton(R.string.fetcherr_change_server, onChangeServer, Modifier.focusRequester(focusRequester))
                TextButton(R.string.fetcherr_pair_again, onPairAgain)
            }
        }
        TextButton(R.string.remove, onRemove)
    }
}

@Composable
private fun ColumnScope.ConnectedStatus(
    version: String,
    services: ConfiguredServices,
) {
    Text(
        text = stringResource(R.string.fetcherr_connected, version),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        text = servicesSummary(services),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}

@Composable
private fun ColumnScope.AddressContent(
    step: FetcherrSetupStep.Address,
    onSubmit: (String) -> Unit,
) {
    Title(stringResource(R.string.fetcherr_address_title))
    Text(
        text =
            if (step.notFoundAt != null) {
                stringResource(R.string.fetcherr_address_not_found, step.notFoundAt)
            } else {
                stringResource(R.string.fetcherr_address_hint)
            },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Starts filled in with the likeliest address, so that usually only part of it needs changing
    var url by remember { mutableStateOf(step.url) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    val canSubmit = url.removePrefix("http://").removePrefix("https://").isNotBlank() && !step.checking
    EditTextBox(
        value = url,
        onValueChange = { url = it },
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
        keyboardActions = KeyboardActions(onGo = { if (canSubmit) onSubmit.invoke(url) }),
        isInputValid = { true },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
    when {
        step.checking -> Progress(stringResource(R.string.fetcherr_testing))
        step.problem == AddressProblem.NotFetcherr -> ErrorText(stringResource(R.string.fetcherr_address_not_fetcherr))
        step.problem == AddressProblem.Unreachable -> ErrorText(stringResource(R.string.fetcherr_error_unreachable))
    }
    Buttons {
        TextButton(R.string.fetcherr_continue, { onSubmit.invoke(url) }, enabled = canSubmit)
    }
}

@Composable
private fun ColumnScope.PairingContent(
    step: FetcherrSetupStep.Pairing,
    onNewCode: () -> Unit,
    onChangeAddress: () -> Unit,
    onUseApiKey: () -> Unit,
) {
    Title(stringResource(R.string.fetcherr_pair_title), step.url)
    val phase = step.phase
    val waiting = phase as? PairingPhase.Waiting
    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(R.string.fetcherr_pair_open),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${step.url}/settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.fetcherr_pair_enter_code),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.height(72.dp),
            ) {
                if (waiting != null) {
                    Text(
                        text = formatPairingCode(waiting.code),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 48.sp,
                        letterSpacing = 4.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else if (phase == PairingPhase.Requesting) {
                    Progress(stringResource(R.string.fetcherr_pair_requesting))
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Keeps its place while there is no code, so that the layout doesn't jump
            Box(Modifier.size(176.dp).alpha(if (waiting != null) 1f else 0f)) {
                if (waiting != null) QrCode(waiting.approveUrl, Modifier.size(176.dp))
            }
            Text(
                text = stringResource(R.string.fetcherr_pair_scan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(if (waiting != null) 1f else 0f),
            )
        }
    }
    when (phase) {
        is PairingPhase.Waiting -> Progress(stringResource(R.string.fetcherr_pair_waiting))
        PairingPhase.Requesting -> Spacer(Modifier.height(32.dp))
        PairingPhase.Expired -> ErrorText(stringResource(R.string.fetcherr_pair_expired))
        is PairingPhase.Failed -> ErrorText(fetcherrErrorMessage(phase.exception))
    }
    // Focus starts on a harmless button, so that an accidental OK doesn't leave the screen
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    val stopped = phase !is PairingPhase.Waiting && phase != PairingPhase.Requesting
    Buttons {
        TextButton(
            stringRes = if (stopped) R.string.retry else R.string.fetcherr_pair_new_code,
            onClick = onNewCode,
            modifier = Modifier.focusRequester(focusRequester),
        )
        TextButton(R.string.fetcherr_change_address, onChangeAddress)
        TextButton(R.string.fetcherr_use_api_key, onUseApiKey)
    }
}

/** eg "K7Q4M2" as "K7Q 4M2", which is easier to read across a room */
fun formatPairingCode(code: String): String =
    if (code.length >= 6 && code.length % 2 == 0 && ' ' !in code) {
        code.substring(0, code.length / 2) + " " + code.substring(code.length / 2)
    } else {
        code
    }

@Composable
private fun ColumnScope.ApiKeyContent(
    step: FetcherrSetupStep.ApiKey,
    onSubmit: (url: String, apiKey: String) -> Unit,
) {
    Title(stringResource(R.string.api_key))
    Text(
        text =
            stringResource(
                if (step.pairingUnavailable) R.string.fetcherr_api_key_no_pairing else R.string.fetcherr_api_key_hint,
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var url by remember { mutableStateOf(step.url) }
    var apiKey by remember { mutableStateOf("") }
    val keyFocusRequester = remember { FocusRequester() }
    // The address is already known, so start on the key
    LaunchedEffect(Unit) { keyFocusRequester.tryRequestFocus() }
    val canSubmit = url.isNotBlank() && apiKey.isNotBlank() && !step.checking
    val labelWidth = 96.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.url),
            modifier = Modifier.width(labelWidth),
        )
        EditTextBox(
            value = url,
            onValueChange = { url = it },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions = KeyboardActions(onNext = { keyFocusRequester.tryRequestFocus() }),
            isInputValid = { true },
            modifier = Modifier.weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.api_key),
            modifier = Modifier.width(labelWidth),
        )
        EditTextBox(
            value = apiKey,
            onValueChange = { apiKey = it },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                ),
            keyboardActions = KeyboardActions(onGo = { if (canSubmit) onSubmit.invoke(url, apiKey) }),
            isInputValid = { true },
            modifier = Modifier.weight(1f).focusRequester(keyFocusRequester),
        )
    }
    when {
        step.checking -> Progress(stringResource(R.string.fetcherr_testing))
        step.error != null -> ErrorText(fetcherrErrorMessage(step.error))
    }
    Buttons {
        TextButton(R.string.save, { onSubmit.invoke(url, apiKey) }, enabled = canSubmit)
    }
}

@Composable
private fun ColumnScope.DoneContent(
    step: FetcherrSetupStep.Done,
    onDone: () -> Unit,
) {
    Title(stringResource(R.string.fetcherr), step.url)
    ConnectedStatus(step.status.version, step.status.services)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.tryRequestFocus()
        // Long enough to read, without needing the remote
        delay(DONE_CLOSE_DELAY_MS)
        onDone.invoke()
    }
    Buttons {
        TextButton(R.string.fetcherr_ok, onDone, Modifier.focusRequester(focusRequester))
    }
}

private const val DONE_CLOSE_DELAY_MS = 4_000L

/**
 * eg "TMDB ✓   Jellyfin ✓   OMDb ✗ …"
 */
@Composable
private fun servicesSummary(services: ConfiguredServices): String {
    fun mark(ok: Boolean) = if (ok) "✓" else "✗"
    return listOf(
        "TMDB" to services.tmdb,
        "Jellyfin" to services.jellyfin,
        "OMDb" to services.omdb,
        "Prowlarr" to services.prowlarr,
        "qBittorrent" to services.qbittorrent,
    ).joinToString("   ") { (name, ok) -> "$name ${mark(ok)}" }
}
