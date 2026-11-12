package com.github.damontecres.wholphin.ui.fetcherr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.toFetcherrException
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.tryRequestFocus
import java.io.IOException

/**
 * A user-facing message for an error from talking to Fetcherr
 */
@Composable
fun fetcherrErrorMessage(ex: Throwable): String {
    val fetcherr = ex.toFetcherrException()
    return when {
        fetcherr?.isUnauthorized == true -> stringResource(R.string.fetcherr_error_api_key)
        fetcherr?.isNotConfigured == true -> stringResource(R.string.fetcherr_error_not_configured, fetcherr.message.orEmpty())
        fetcherr != null -> fetcherr.message.orEmpty()
        ex is IOException -> stringResource(R.string.fetcherr_error_unreachable)
        else -> ex.localizedMessage ?: ex.toString()
    }
}

/**
 * An error loading something from Fetcherr, with a Retry button
 *
 * @param requestFocus whether Retry takes focus, so that focus doesn't escape the page
 */
@Composable
fun FetcherrError(
    exception: Throwable?,
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    if (requestFocus) {
        LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    }
    Column(modifier = modifier) {
        TextButton(
            stringRes = R.string.retry,
            onClick = onRetry,
            modifier =
                Modifier
                    .padding(start = 16.dp, top = 16.dp)
                    .focusRequester(focusRequester),
        )
        ErrorMessage(
            message = exception?.let { fetcherrErrorMessage(it) } ?: message,
            exception = null,
        )
    }
}
