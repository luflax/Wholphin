package com.github.damontecres.wholphin.ui.detail.discover

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrDownload
import com.github.damontecres.wholphin.data.model.SeerrDownloadState
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.DownloadProgressBar
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.formatDurationWithDays

/**
 * The number of individual downloads shown before the rest collapse into a "+N more" line
 *
 * A requested season can produce a dozen concurrent entries, which would otherwise push the
 * action buttons off screen.
 */
private const val MAX_VISIBLE_DOWNLOADS = 3

/**
 * Shows the in-progress Radarr/Sonarr downloads for a requested item
 *
 * Renders nothing when there is nothing downloading, which is the common case.
 */
@Composable
fun SeerrStatusBlock(
    downloads: List<SeerrDownload>,
    downloads4k: List<SeerrDownload>,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = downloads.isNotEmpty() || downloads4k.isNotEmpty(),
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(.6f),
        ) {
            if (downloads.isNotEmpty()) {
                SeerrDownloadGroup(R.string.seerr_downloads_title, downloads)
            }
            if (downloads4k.isNotEmpty()) {
                SeerrDownloadGroup(R.string.seerr_downloads_title_4k, downloads4k)
            }
        }
    }
}

/**
 * The full download list, without the "+N more" truncation the header applies
 *
 * Reached from the "Pending" button on the movie details page.
 */
@Composable
fun SeerrDownloadsDialog(
    downloads: List<SeerrDownload>,
    downloads4k: List<SeerrDownload>,
    onDismissRequest: () -> Unit,
) {
    BasicDialog(onDismissRequest = onDismissRequest) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            if (downloads.isEmpty() && downloads4k.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (downloads.isNotEmpty()) {
                SeerrDownloadGroup(R.string.seerr_downloads_title, downloads, showAll = true)
            }
            if (downloads4k.isNotEmpty()) {
                SeerrDownloadGroup(R.string.seerr_downloads_title_4k, downloads4k, showAll = true)
            }
        }
    }
}

/**
 * A titled group of downloads: a summary, an overall progress bar, and the most interesting rows
 */
@Composable
private fun SeerrDownloadGroup(
    @StringRes title: Int,
    downloads: List<SeerrDownload>,
    modifier: Modifier = Modifier,
    showAll: Boolean = false,
) {
    // Show what is happening first, then what needs attention, then the rest. `sortedBy` is
    // stable, so the season/episode ordering from SeerrService survives within each group.
    val visible =
        remember(downloads, showAll) {
            downloads
                .sortedBy {
                    when {
                        it.state == SeerrDownloadState.DOWNLOADING -> 0
                        it.state.isProblem -> 1
                        else -> 2
                    }
                }.let { if (showAll) it else it.take(MAX_VISIBLE_DOWNLOADS) }
        }
    val overflow = downloads.size - visible.size

    val totalBytes = remember(downloads) { downloads.sumOf { it.sizeBytes } }
    val doneBytes = remember(downloads) { downloads.sumOf { it.downloadedBytes } }
    val overallProgress = if (totalBytes > 0L) doneBytes.toFloat() / totalBytes else 0f

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = downloadSummary(downloads),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (overallProgress > 0f) {
            DownloadProgressBar(overallProgress, Modifier.fillMaxWidth())
        }
        visible.forEach { download ->
            key(download.downloadId ?: download.releaseTitle) {
                SeerrDownloadRow(download)
            }
        }
        if (overflow > 0) {
            Text(
                text = pluralStringResource(R.plurals.seerr_downloads_more, overflow, overflow),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A single download: what state it is in, what it is, and how far along
 */
@Composable
private fun SeerrDownloadRow(
    download: SeerrDownload,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        SeerrDownloadStateChip(download.state)
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = downloadLabel(download),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only worth drawing once bytes have actually moved; a queued season would
            // otherwise render as a stack of empty bars
            if (download.hasProgress) {
                DownloadProgressBar(download.progress, Modifier.fillMaxWidth())
            }
            if (download.sizeBytes > 0L) {
                val caption =
                    buildString {
                        append(
                            stringResource(
                                R.string.seerr_download_progress,
                                formatBytes(download.downloadedBytes),
                                formatBytes(download.sizeBytes),
                            ),
                        )
                        download.timeLeft?.let {
                            append("  •  ")
                            append(
                                stringResource(
                                    R.string.seerr_time_left,
                                    resources.formatDurationWithDays(it),
                                ),
                            )
                        }
                    }
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The state of a download, which is often the only thing distinguishing one queued entry from
 * another, so it leads the row
 */
@Composable
private fun SeerrDownloadStateChip(
    state: SeerrDownloadState,
    modifier: Modifier = Modifier,
) {
    val labelRes = state.labelRes
    val color = state.chipColor
    Box(
        modifier =
            modifier
                .background(AppColors.TransparentBlack50, RoundedCornerShape(25))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@get:StringRes
private val SeerrDownloadState.labelRes: Int
    get() =
        when (this) {
            SeerrDownloadState.DOWNLOADING -> R.string.downloading
            SeerrDownloadState.QUEUED -> R.string.seerr_download_queued
            SeerrDownloadState.PAUSED -> R.string.seerr_download_paused
            SeerrDownloadState.WARNING -> R.string.seerr_download_warning
            SeerrDownloadState.FAILED -> R.string.seerr_download_failed
            SeerrDownloadState.COMPLETED -> R.string.seerr_download_completed
            SeerrDownloadState.UNKNOWN -> R.string.unknown
        }

private val SeerrDownloadState.chipColor: Color
    @Composable
    get() =
        when (this) {
            SeerrDownloadState.DOWNLOADING -> AppColors.Discover.Blue
            SeerrDownloadState.COMPLETED -> AppColors.Discover.Green
            SeerrDownloadState.WARNING -> AppColors.Discover.Yellow
            SeerrDownloadState.FAILED -> AppColors.Discover.Red
            SeerrDownloadState.QUEUED,
            SeerrDownloadState.PAUSED,
            SeerrDownloadState.UNKNOWN,
            -> MaterialTheme.colorScheme.onSurface
        }

/**
 * Prefer the episode name for TV, since the release name is a long unreadable scene string
 */
@Composable
private fun downloadLabel(download: SeerrDownload): String {
    val season = download.seasonNumber
    val episode = download.episodeNumber
    if (season == null || episode == null) {
        return download.releaseTitle.orEmpty()
    }
    val code = stringResource(R.string.seerr_episode_code, season, episode)
    return download.episodeTitle?.let {
        stringResource(R.string.seerr_episode_label, code, it)
    } ?: code
}

/**
 * A one-line breakdown, eg "5 downloads  •  1 downloading, 3 queued, 1 warning"
 */
@Composable
private fun downloadSummary(downloads: List<SeerrDownload>): String {
    val active = downloads.count { it.state == SeerrDownloadState.DOWNLOADING }
    val queued = downloads.count { it.state == SeerrDownloadState.QUEUED || it.state == SeerrDownloadState.PAUSED }
    val problems = downloads.count { it.state.isProblem }
    val separator = stringResource(R.string.seerr_download_summary_separator)
    val parts =
        buildList {
            if (active > 0) add(stringResource(R.string.seerr_download_summary_active, active))
            if (queued > 0) add(stringResource(R.string.seerr_download_summary_queued, queued))
            if (problems > 0) add(stringResource(R.string.seerr_download_summary_problem, problems))
        }
    val count = pluralStringResource(R.plurals.seerr_downloads_count, downloads.size, downloads.size)
    return if (parts.isEmpty()) {
        count
    } else {
        count + "  •  " + parts.joinToString(separator)
    }
}
