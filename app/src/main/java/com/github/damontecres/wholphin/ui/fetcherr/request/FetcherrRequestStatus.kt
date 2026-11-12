package com.github.damontecres.wholphin.ui.fetcherr.request

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Quality
import com.github.damontecres.wholphin.api.fetcherr.model.RequestState
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DownloadProgressBar
import com.github.damontecres.wholphin.ui.components.StatusChip
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.fetcherr.canPause
import com.github.damontecres.wholphin.ui.fetcherr.canResume
import com.github.damontecres.wholphin.ui.fetcherr.canRetry
import com.github.damontecres.wholphin.ui.fetcherr.formatEta
import com.github.damontecres.wholphin.ui.fetcherr.formatProgress
import com.github.damontecres.wholphin.ui.fetcherr.formatSpeed
import com.github.damontecres.wholphin.ui.fetcherr.requestStateLabel
import com.github.damontecres.wholphin.ui.fetcherr.scopeLabel
import com.github.damontecres.wholphin.ui.tryRequestFocus
import java.util.Locale

/**
 * What can be done to a request
 */
interface RequestActions {
    fun pause(request: RequestSummary)

    fun resume(request: RequestSummary)

    fun retry(request: RequestSummary)

    fun remove(
        request: RequestSummary,
        deleteFiles: Boolean,
    )
}

val RequestState.color: Color
    @Composable
    get() =
        when (this) {
            RequestState.SEARCHING -> AppColors.Discover.Yellow
            RequestState.NOT_FOUND -> AppColors.Discover.Red
            RequestState.DOWNLOADING -> AppColors.Discover.Blue
            RequestState.PAUSED -> MaterialTheme.colorScheme.onSurface
            RequestState.IMPORTING -> AppColors.Discover.Purple
            RequestState.AVAILABLE -> AppColors.Discover.Green
            RequestState.FAILED -> AppColors.Discover.Red
        }

/**
 * A readable quality, eg "2160p WEB" or "1080p BluRay"
 */
fun qualityLabel(quality: Quality): String {
    val parts = quality.value.split('_')
    if (parts.size != 2) return parts.first().uppercase(Locale.ROOT)
    val source =
        when (parts[0]) {
            "remux" -> "Remux"
            "bluray" -> "BluRay"
            "web" -> "WEB"
            "hdtv" -> "HDTV"
            else -> parts[0]
        }
    return "${parts[1]} $source"
}

/**
 * One request's state, progress and error, eg
 * "[Downloading] WEB 2160p · 62% ███████░░░ 12m left · 8 MB/s"
 */
@Composable
fun FetcherrRequestStatus(
    request: RequestSummary,
    progress: RequestProgress,
    modifier: Modifier = Modifier,
    showScope: Boolean = true,
) {
    val resources = LocalResources.current
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(requestStateLabel(request.state), request.state.color)
            val details =
                buildList {
                    if (showScope) add(resources.scopeLabel(request.scope))
                    request.quality?.let { add(qualityLabel(it)) }
                    if (request.state == RequestState.DOWNLOADING || request.state == RequestState.PAUSED) {
                        add(formatProgress(progress.progress))
                        progress.etaSecs?.let { add(stringResource(R.string.fetcherr_time_left, resources.formatEta(it))) }
                        if (progress.dlSpeed > 0) add(formatSpeed(progress.dlSpeed))
                    }
                }
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (request.state == RequestState.DOWNLOADING || request.state == RequestState.PAUSED) {
            DownloadProgressBar(progress.progress.toFloat(), Modifier.width(320.dp))
        }
        request.error?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (request.state == RequestState.FAILED || request.state == RequestState.NOT_FOUND) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The requests for a title on its details page, each with its status and actions
 *
 * Renders nothing when there are no requests.
 */
@Composable
fun FetcherrStatusBlock(
    live: LiveRequests,
    actions: RequestActions,
    onRemove: (RequestSummary) -> Unit,
    modifier: Modifier = Modifier,
    showScope: Boolean = true,
) {
    if (live.requests.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .background(AppColors.TransparentBlack50, RoundedCornerShape(8.dp))
                .padding(12.dp),
    ) {
        live.requests.forEach { request ->
            key(request.id) {
                RequestStatusRow(
                    request = request,
                    progress = live.progressOf(request),
                    showScope = showScope,
                    actions = actions,
                    onRemove = { onRemove.invoke(request) },
                )
            }
        }
    }
}

/**
 * The action a request's state allows, if any. Shown in one button slot, so that eg pausing turns
 * the focused Pause button into Resume rather than replacing it, which would drop focus.
 */
private enum class PrimaryAction(
    @param:StringRes val label: Int,
) {
    PAUSE(R.string.fetcherr_pause),
    RESUME(R.string.resume),
    RETRY(R.string.retry),
}

private val RequestState.primaryAction: PrimaryAction?
    get() =
        when {
            canPause -> PrimaryAction.PAUSE
            canResume -> PrimaryAction.RESUME
            canRetry -> PrimaryAction.RETRY
            else -> null
        }

@Composable
private fun RequestStatusRow(
    request: RequestSummary,
    progress: RequestProgress,
    showScope: Boolean,
    actions: RequestActions,
    onRemove: () -> Unit,
) {
    val action = request.state.primaryAction
    val currentAction by rememberUpdatedState(action)
    val removeFocusRequester = remember { FocusRequester() }
    // Whether the action button had focus. Not cleared when the button goes away, so focus can be
    // handed to Remove instead of escaping, eg to the nav drawer.
    var actionFocused by remember { mutableStateOf(false) }
    LaunchedEffect(action) {
        if (action == null && actionFocused) {
            actionFocused = false
            removeFocusRequester.tryRequestFocus()
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        FetcherrRequestStatus(
            request = request,
            progress = progress,
            showScope = showScope,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(
                stringRes = action.label,
                onClick = {
                    when (action) {
                        PrimaryAction.PAUSE -> {
                            actions.pause(request)
                        }

                        PrimaryAction.RESUME -> {
                            actions.resume(request)
                        }

                        PrimaryAction.RETRY -> {
                            // Retrying starts a search, which has no action, so move focus first
                            actionFocused = false
                            removeFocusRequester.tryRequestFocus()
                            actions.retry(request)
                        }
                    }
                },
                modifier =
                    Modifier.onFocusChanged {
                        if (it.isFocused) {
                            actionFocused = true
                        } else if (currentAction != null) {
                            // Focus moved on while the button remains
                            actionFocused = false
                        }
                    },
            )
        }
        TextButton(
            stringRes = R.string.remove,
            onClick = onRemove,
            modifier = Modifier.focusRequester(removeFocusRequester),
        )
    }
}

/**
 * Pause, resume, retry or remove a request, offering only what its state allows
 */
fun requestActionsDialog(
    title: String,
    request: RequestSummary,
    actions: RequestActions,
    onRemove: () -> Unit,
    extra: List<DialogItem> = emptyList(),
): DialogParams =
    DialogParams(
        fromLongClick = false,
        title = title,
        items =
            buildList {
                addAll(extra)
                if (request.state.canPause) {
                    add(DialogItem(R.string.fetcherr_pause, R.string.fa_pause) { actions.pause(request) })
                }
                if (request.state.canResume) {
                    add(DialogItem(R.string.resume, R.string.fa_play) { actions.resume(request) })
                }
                if (request.state.canRetry) {
                    add(DialogItem(R.string.retry, R.string.fa_arrows_rotate) { actions.retry(request) })
                }
                add(DialogItem(R.string.remove, R.string.fa_xmark) { onRemove.invoke() })
            },
    )

/**
 * Colors for focusable list rows (downloads, episodes)
 *
 * The default focused surface is near-white, which would hide the rows' explicitly colored text,
 * so focus lifts the row to the surface variant instead.
 */
@Composable
fun fetcherrRowColors(): ClickableSurfaceColors =
    ClickableSurfaceDefaults.colors(
        containerColor = AppColors.TransparentBlack50,
        contentColor = MaterialTheme.colorScheme.onSurface,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedContentColor = MaterialTheme.colorScheme.onSurface,
        pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        pressedContentColor = MaterialTheme.colorScheme.onSurface,
    )
