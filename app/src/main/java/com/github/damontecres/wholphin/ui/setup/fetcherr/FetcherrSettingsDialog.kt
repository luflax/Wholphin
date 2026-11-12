package com.github.damontecres.wholphin.ui.setup.fetcherr

import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.ConfiguredServices
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.fetcherr.fetcherrErrorMessage
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * Add, change, test or remove the app's Fetcherr server
 */
@Composable
fun FetcherrSettingsDialog(
    onDismissRequest: () -> Unit,
    viewModel: FetcherrSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.init() }
    val state by viewModel.state.collectAsState()
    val savedMessage = stringResource(R.string.fetcherr_saved)
    LaunchedEffect(state.saved) {
        if (state.saved) {
            Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
            onDismissRequest.invoke()
        }
    }
    BasicDialog(
        onDismissRequest = onDismissRequest,
        elevation = 3.dp,
    ) {
        if (state.loaded) {
            FetcherrSettingsContent(
                initialUrl = state.initialUrl,
                initialApiKey = state.initialApiKey,
                test = state.test,
                canRemove = state.initialUrl.isNotBlank(),
                onTest = viewModel::test,
                onSave = viewModel::save,
                onRemove = viewModel::remove,
                onEdit = viewModel::resetTest,
            )
        }
    }
}

@Composable
private fun FetcherrSettingsContent(
    initialUrl: String,
    initialApiKey: String,
    test: FetcherrTestState,
    canRemove: Boolean,
    onTest: (url: String, apiKey: String) -> Unit,
    onSave: (url: String, apiKey: String) -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    val urlFocusRequester = remember { FocusRequester() }
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (initialUrl.isBlank()) urlFocusRequester.tryRequestFocus() else keyFocusRequester.tryRequestFocus()
    }
    val inputValid = url.isNotNullOrBlank() && apiKey.isNotNullOrBlank()
    val testing = test == FetcherrTestState.Testing

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .focusGroup()
                .padding(16.dp)
                .wrapContentSize(),
    ) {
        Text(
            text = stringResource(R.string.fetcherr),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        val labelWidth = 110.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.url),
                modifier = Modifier.width(labelWidth).padding(end = 8.dp),
            )
            EditTextBox(
                value = url,
                onValueChange = {
                    url = it
                    onEdit.invoke()
                },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                keyboardActions = KeyboardActions(onNext = { keyFocusRequester.tryRequestFocus() }),
                isInputValid = { true },
                modifier = Modifier.focusRequester(urlFocusRequester),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.api_key),
                modifier = Modifier.width(labelWidth).padding(end = 8.dp),
            )
            EditTextBox(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    onEdit.invoke()
                },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { if (inputValid) onSave.invoke(url, apiKey) }),
                isInputValid = { true },
                modifier = Modifier.focusRequester(keyFocusRequester),
            )
        }

        when (test) {
            FetcherrTestState.None -> {}

            FetcherrTestState.Testing -> {
                Text(
                    text = stringResource(R.string.fetcherr_testing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is FetcherrTestState.Success -> {
                Text(
                    text = stringResource(R.string.fetcherr_connected, test.status.version),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = servicesSummary(test.status.services),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is FetcherrTestState.Failed -> {
                Text(
                    text = fetcherrErrorMessage(test.exception),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            TextButton(
                stringRes = R.string.fetcherr_test,
                onClick = { onTest.invoke(url, apiKey) },
                enabled = inputValid && !testing,
            )
            TextButton(
                stringRes = R.string.save,
                onClick = { onSave.invoke(url, apiKey) },
                enabled = inputValid && !testing,
            )
            if (canRemove) {
                TextButton(
                    stringRes = R.string.remove,
                    onClick = onRemove,
                    enabled = !testing,
                )
            }
        }
    }
}

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
