package com.github.damontecres.wholphin.data.model

import androidx.compose.runtime.Stable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * An in-progress download reported by Radarr/Sonarr via Seerr's `mediaInfo.downloadStatus`
 *
 * This is the app-side normalization of the generated
 * [com.github.damontecres.wholphin.api.seerr.model.DownloadingItem], see
 * [com.github.damontecres.wholphin.services.SeerrService.downloads]
 */
@Stable
data class SeerrDownload(
    /**
     * Unique per entry and stable across polls, so it works as a compose list key.
     *
     * Note the generated `externalId` is *not* unique: it is the Radarr movie or Sonarr series id,
     * so every episode of a series shares one.
     */
    val downloadId: String?,
    /** The release name, eg `Lucky 2026 S01E03 480p x264-mSD` */
    val releaseTitle: String?,
    /** The episode name from Sonarr, eg `Read the Room`. Null for movies. */
    val episodeTitle: String?,
    val state: SeerrDownloadState,
    val sizeBytes: Long,
    val sizeLeftBytes: Long,
    /** Parsed [rawTimeLeft], or null if it was absent or in an unrecognized format */
    val timeLeft: Duration?,
    /** The unparsed value, so the UI can still show something if parsing fails */
    val rawTimeLeft: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
) {
    val downloadedBytes: Long get() = (sizeBytes - sizeLeftBytes).coerceAtLeast(0L)

    /**
     * Progress from 0 to 1
     *
     * Radarr/Sonarr report `size: 0` for entries that have been queued but not yet grabbed, so the
     * zero check is required rather than defensive.
     */
    val progress: Float
        get() =
            if (sizeBytes > 0L) {
                (downloadedBytes.toFloat() / sizeBytes).coerceIn(0f, 1f)
            } else {
                0f
            }

    /** Whether there is anything meaningful to draw a progress bar with */
    val hasProgress: Boolean get() = progress > 0f
}

/**
 * The state of a [SeerrDownload] in the Radarr/Sonarr queue
 */
enum class SeerrDownloadState {
    DOWNLOADING,
    QUEUED,
    PAUSED,
    WARNING,
    FAILED,
    COMPLETED,
    UNKNOWN,
    ;

    /** Whether this state needs the user's attention */
    val isProblem: Boolean get() = this == WARNING || this == FAILED

    companion object {
        /**
         * Radarr/Sonarr also emit states this app doesn't model (`delay`,
         * `downloadClientUnavailable`, `fallback`), so anything unrecognized becomes [UNKNOWN]
         */
        fun from(raw: String?) = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

/** How often to refresh an item's status while bytes are actively moving */
val SEERR_POLL_ACTIVE = 5.seconds

/** How often to refresh while waiting on approval, an indexer grab, or a stalled queue */
val SEERR_POLL_IDLE = 15.seconds

/** Stop polling after this many consecutive failures, so an unreachable server isn't hammered */
const val SEERR_MAX_POLL_FAILURES = 4

/** Cap the exponential backoff at 2^3, ie 8x the base interval */
const val SEERR_MAX_BACKOFF_SHIFT = 3

/**
 * How long to wait before refreshing an item's Seerr status
 *
 * Seerr has no push API, so status updates have to be polled for. The interval is derived from
 * what the state can still turn into, and is null when nothing can change without user action —
 * important because a TV can sit on one page for hours.
 *
 * Note this keys off the downloads rather than [availability] alone: a partially available series
 * with a season still downloading needs polling just as much as a pending one does.
 *
 * @return the delay before the next refresh, or null to stop polling
 */
fun seerrPollInterval(
    availability: SeerrAvailability,
    downloads: List<SeerrDownload>,
): Duration? =
    when {
        // Bytes are moving, so there is something to animate
        downloads.any { it.state == SeerrDownloadState.DOWNLOADING && it.hasProgress } -> SEERR_POLL_ACTIVE
        // Queued or stalled entries can start at any moment, but nothing is progressing yet
        downloads.isNotEmpty() -> SEERR_POLL_IDLE
        // Requested, but Radarr/Sonarr has not queued anything yet
        availability == SeerrAvailability.PENDING || availability == SeerrAvailability.PROCESSING -> SEERR_POLL_IDLE
        else -> null
    }

/**
 * Matches .NET `TimeSpan` formatting as emitted by Radarr/Sonarr: `HH:mm:ss`, optionally prefixed
 * with `d.` for multi-day estimates and optionally suffixed with fractional seconds
 */
private val TIME_LEFT_REGEX = Regex("""^(?:(\d+)\.)?(\d{1,2}):(\d{2}):(\d{2})(?:\.\d+)?$""")

/**
 * Parse a `timeLeft` value such as `05:57:46` or `60.01:15:25`
 *
 * @return the parsed duration, or null if the value was absent, blank or unrecognized
 */
fun parseTimeLeft(raw: String?): Duration? {
    val match = TIME_LEFT_REGEX.matchEntire(raw?.trim().orEmpty()) ?: return null
    val (days, hours, minutes, seconds) = match.destructured
    return (days.toIntOrNull() ?: 0).days +
        hours.toInt().hours +
        minutes.toInt().minutes +
        seconds.toInt().seconds
}
