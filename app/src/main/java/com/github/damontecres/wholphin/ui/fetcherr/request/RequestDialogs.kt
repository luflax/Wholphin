package com.github.damontecres.wholphin.ui.fetcherr.request

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Quality
import com.github.damontecres.wholphin.api.fetcherr.model.QualityOption
import com.github.damontecres.wholphin.api.fetcherr.model.TitleRelease
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogItemDivider
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrError
import com.github.damontecres.wholphin.ui.fetcherr.coverageLabel
import com.github.damontecres.wholphin.ui.fetcherr.formatAgeHours
import com.github.damontecres.wholphin.ui.fetcherr.formatBytes
import com.github.damontecres.wholphin.util.DataLoadingState

/**
 * Pick a quality for a request, or let Fetcherr choose by its ranking
 *
 * Selecting an entry submits the request right away. Shows a spinner while [qualities] is null,
 * so the list is complete once it appears.
 */
@Composable
fun RequestQualityDialog(
    title: String,
    qualities: List<QualityOption>?,
    onSubmit: (Quality?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (qualities == null) {
        BasicDialog(onDismissRequest = onDismissRequest, elevation = 3.dp) {
            LoadingPage(Modifier.padding(32.dp))
        }
        return
    }
    val autoLabel = stringResource(R.string.fetcherr_quality_auto)
    val autoDescription = stringResource(R.string.fetcherr_quality_auto_summary)
    DialogPopup(
        params =
            DialogParams(
                fromLongClick = false,
                title = title,
                items =
                    buildList {
                        add(
                            DialogItem(
                                headlineContent = { Text(autoLabel) },
                                supportingContent = { Text(autoDescription) },
                                leadingContent = {
                                    Text(text = stringResource(R.string.fa_download), fontFamily = FontAwesome)
                                },
                                onClick = { onSubmit.invoke(null) },
                            ),
                        )
                        if (qualities.isNotEmpty()) add(DialogItemDivider)
                        qualities.forEach { option ->
                            add(
                                DialogItem(
                                    headlineContent = { Text(option.label) },
                                    onClick = { onSubmit.invoke(option.id) },
                                ),
                            )
                        }
                    },
            ),
        onDismissRequest = onDismissRequest,
        elevation = 3.dp,
    )
}

/**
 * Pick a specific release for a request, eg a season pack rather than single episodes
 *
 * The server lists releases best first, so the first one is marked as recommended.
 */
@Composable
fun ReleasePickerDialog(
    title: String,
    releases: DataLoadingState<List<TitleRelease>>,
    onSelect: (TitleRelease) -> Unit,
    onRefresh: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    when (releases) {
        DataLoadingState.Pending,
        DataLoadingState.Loading,
        -> {
            BasicDialog(onDismissRequest = onDismissRequest, elevation = 3.dp) {
                LoadingPage(Modifier.padding(32.dp))
            }
        }

        is DataLoadingState.Error -> {
            BasicDialog(onDismissRequest = onDismissRequest, elevation = 3.dp) {
                FetcherrError(
                    exception = releases.exception,
                    message = releases.message,
                    onRetry = onRefresh,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        is DataLoadingState.Success -> {
            val resources = LocalResources.current
            val refresh = stringResource(R.string.fetcherr_refresh_releases)
            val noReleases = stringResource(R.string.fetcherr_no_releases)
            DialogPopup(
                params =
                    DialogParams(
                        fromLongClick = false,
                        title = title,
                        items =
                            buildList {
                                if (releases.data.isEmpty()) {
                                    add(DialogItem(headlineContent = { Text(noReleases) }, onClick = {}, enabled = false))
                                }
                                releases.data.forEachIndexed { index, release ->
                                    add(
                                        DialogItem(
                                            overlineContent = {
                                                Text(
                                                    text =
                                                        listOf(
                                                            qualityLabel(release.quality),
                                                            resources.coverageLabel(release.coverage),
                                                        ).joinToString(" · "),
                                                    color = if (index == 0) AppColors.GoldenYellow else MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            },
                                            headlineContent = {
                                                Text(
                                                    text = release.title,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            supportingContent = {
                                                Text(
                                                    text =
                                                        stringResource(
                                                            R.string.fetcherr_release_details,
                                                            formatBytes(release.propertySize),
                                                            release.seeders,
                                                            resources.formatAgeHours(release.ageHours),
                                                            release.indexer,
                                                        ),
                                                )
                                            },
                                            leadingContent =
                                                if (index == 0) {
                                                    {
                                                        Text(
                                                            text = "★",
                                                            color = AppColors.GoldenYellow,
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                            onClick = { onSelect.invoke(release) },
                                        ),
                                    )
                                }
                                add(DialogItemDivider)
                                add(
                                    DialogItem(
                                        headlineContent = { Text(refresh) },
                                        leadingContent = {
                                            Text(text = stringResource(R.string.fa_arrows_rotate), fontFamily = FontAwesome)
                                        },
                                        onClick = onRefresh,
                                        dismissOnClick = false,
                                    ),
                                )
                            },
                    ),
                onDismissRequest = onDismissRequest,
                // So Refresh keeps the dialog open; the caller dismisses it once a release is chosen
                dismissOnClick = false,
                elevation = 3.dp,
            )
        }
    }
}
