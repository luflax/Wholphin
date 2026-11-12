package com.github.damontecres.wholphin.ui.fetcherr.request

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * Confirm removing a request, choosing whether its downloaded & imported files go too
 */
@Composable
fun RemoveRequestDialog(
    title: String,
    onRemove: (deleteFiles: Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    BasicDialog(onDismissRequest = onDismissRequest, elevation = 3.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.fetcherr_remove_request, title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.fetcherr_remove_request_files),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(
                    stringRes = R.string.fetcherr_keep_files,
                    onClick = {
                        onRemove.invoke(false)
                        onDismissRequest.invoke()
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                )
                TextButton(
                    stringRes = R.string.fetcherr_delete_files,
                    onClick = {
                        onRemove.invoke(true)
                        onDismissRequest.invoke()
                    },
                )
                TextButton(stringRes = R.string.cancel, onClick = onDismissRequest)
            }
        }
    }
}
