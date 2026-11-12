package com.github.damontecres.wholphin.ui.fetcherr

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Availability
import com.github.damontecres.wholphin.api.fetcherr.model.Card
import com.github.damontecres.wholphin.api.fetcherr.model.Coverage
import com.github.damontecres.wholphin.api.fetcherr.model.Images
import com.github.damontecres.wholphin.api.fetcherr.model.PersonCard
import com.github.damontecres.wholphin.api.fetcherr.model.RequestState
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.api.fetcherr.model.SearchResult
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.ui.detail.CardGridItem
import com.github.damontecres.wholphin.ui.formatDurationWithDays
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Where selecting a title goes: the Jellyfin page when the media server already has it, otherwise
 * the Fetcherr details page
 */
fun Card.destination(): Destination {
    val jellyfinId = jellyfinItemId?.toUUIDOrNull()
    return when (mediaType) {
        TitleKind.MOVIE -> {
            if (jellyfinId != null) {
                Destination.MediaItem(jellyfinId, BaseItemKind.MOVIE)
            } else {
                Destination.FetcherrMovie(tmdbId)
            }
        }

        TitleKind.SERIES -> {
            if (jellyfinId != null) {
                Destination.MediaItem(jellyfinId, BaseItemKind.SERIES)
            } else {
                Destination.FetcherrSeries(tmdbId)
            }
        }
    }
}

/** The Fetcherr details page, even if the title is in Jellyfin */
fun Card.fetcherrDestination(): Destination =
    when (mediaType) {
        TitleKind.MOVIE -> Destination.FetcherrMovie(tmdbId)
        TitleKind.SERIES -> Destination.FetcherrSeries(tmdbId)
    }

/** The Jellyfin item for a title, season or episode, if the media server has it */
fun jellyfinDestination(
    jellyfinItemId: String?,
    type: BaseItemKind,
): Destination.MediaItem? = jellyfinItemId?.toUUIDOrNull()?.let { Destination.MediaItem(it, type) }

/**
 * A title or a person, so they can be shown in a [com.github.damontecres.wholphin.ui.detail.CardGrid]
 */
@Immutable
sealed interface FetcherrGridItem : CardGridItem {
    override val playable: Boolean get() = false

    @Immutable
    data class Title(
        val card: Card,
    ) : FetcherrGridItem {
        override val gridId: String get() = card.key
        override val sortName: String get() = card.title
    }

    @Immutable
    data class Person(
        val person: PersonCard,
    ) : FetcherrGridItem {
        override val gridId: String get() = person.key
        override val sortName: String get() = person.name
    }

    val destination: Destination
        get() =
            when (this) {
                is Title -> card.destination()
                is Person -> Destination.FetcherrPerson(person.tmdbId)
            }
}

fun SearchResult.toGridItem(): FetcherrGridItem =
    when (this) {
        is SearchResult.Title -> FetcherrGridItem.Title(card)
        is SearchResult.Person -> FetcherrGridItem.Person(person)
    }

/** The title key used by the events WebSocket, eg `movie:603` */
fun titleKey(
    kind: TitleKind,
    tmdbId: Int,
): String = "${kind.value}:$tmdbId"

val Images?.posterUrl: String? get() = this?.medium
val Images?.backdropUrl: String? get() = this?.large

val Availability.isInLibrary: Boolean
    get() = this == Availability.AVAILABLE || this == Availability.PARTIALLY_AVAILABLE

/** Whether there is still something to download */
val Availability.canRequest: Boolean
    get() = this == Availability.UNAVAILABLE || this == Availability.PARTIALLY_AVAILABLE

@Composable
fun availabilityLabel(availability: Availability): String =
    stringResource(
        when (availability) {
            Availability.AVAILABLE -> R.string.fetcherr_available
            Availability.PARTIALLY_AVAILABLE -> R.string.fetcherr_partially_available
            Availability.DOWNLOADING -> R.string.fetcherr_downloading
            Availability.REQUESTED -> R.string.fetcherr_requested
            Availability.UNAVAILABLE -> R.string.fetcherr_unavailable
        },
    )

@Composable
fun requestStateLabel(state: RequestState): String =
    stringResource(
        when (state) {
            RequestState.SEARCHING -> R.string.fetcherr_state_searching
            RequestState.NOT_FOUND -> R.string.fetcherr_state_not_found
            RequestState.DOWNLOADING -> R.string.fetcherr_downloading
            RequestState.PAUSED -> R.string.fetcherr_state_paused
            RequestState.IMPORTING -> R.string.fetcherr_state_importing
            RequestState.AVAILABLE -> R.string.fetcherr_available
            RequestState.FAILED -> R.string.fetcherr_state_failed
        },
    )

/** Whether a request will still change on its own */
val RequestState.isActive: Boolean
    get() =
        this == RequestState.SEARCHING ||
            this == RequestState.DOWNLOADING ||
            this == RequestState.IMPORTING

val RequestState.canPause: Boolean get() = this == RequestState.DOWNLOADING
val RequestState.canResume: Boolean get() = this == RequestState.PAUSED
val RequestState.canRetry: Boolean get() = this == RequestState.FAILED || this == RequestState.NOT_FOUND

/**
 * TMDB's status of a series, eg "Returning Series", translated when it is one of the known values
 */
fun Resources.seriesStatusLabel(status: String): String =
    when (status) {
        "Returning Series" -> getString(R.string.fetcherr_series_returning)
        "Ended" -> getString(R.string.fetcherr_series_ended)
        "Canceled" -> getString(R.string.fetcherr_series_canceled)
        "In Production" -> getString(R.string.fetcherr_series_in_production)
        "Planned" -> getString(R.string.fetcherr_series_planned)
        "Pilot" -> getString(R.string.fetcherr_series_pilot)
        else -> status
    }

/**
 * A short description of what a request covers, eg "Movie", "Season 2" or "S02 E03–E05"
 */
fun Resources.scopeLabel(scope: Scope): String =
    when (scope) {
        Scope.Movie -> getString(R.string.fetcherr_scope_movie)
        is Scope.Season -> getString(R.string.fetcherr_scope_season, scope.season)
        is Scope.Episodes -> episodesLabel(scope.season, scope.episodes)
    }

/**
 * What part of a title a release covers, eg "Season pack (S01)", "Complete series" or "Episode 7"
 */
fun Resources.coverageLabel(coverage: Coverage): String =
    when (coverage) {
        Coverage.Movie -> {
            getString(R.string.fetcherr_scope_movie)
        }

        is Coverage.SeasonPack -> {
            if (coverage.seasons.isEmpty()) {
                getString(R.string.fetcherr_complete_series)
            } else {
                getString(
                    R.string.fetcherr_season_pack,
                    coverage.seasons.joinToString(", ") { "S%02d".format(Locale.ROOT, it) },
                )
            }
        }

        is Coverage.Episodes -> {
            episodesLabel(coverage.season, coverage.episodes)
        }
    }

/**
 * eg "S02 E03" for one episode, "S02 E03–E05" for a consecutive run, "S02 E01, E04" otherwise
 */
fun episodesLabel(
    season: Int,
    episodes: List<Int>,
): String {
    val sorted = episodes.sorted()
    val seasonPart = "S%02d".format(Locale.ROOT, season)
    if (sorted.isEmpty()) return seasonPart
    val consecutive = sorted.zipWithNext().all { (a, b) -> b == a + 1 }
    val episodePart =
        when {
            sorted.size == 1 -> "E%02d".format(Locale.ROOT, sorted.first())
            consecutive -> "E%02d–E%02d".format(Locale.ROOT, sorted.first(), sorted.last())
            else -> sorted.joinToString(", ") { "E%02d".format(Locale.ROOT, it) }
        }
    return "$seasonPart $episodePart"
}

/** eg "42 GB" or "850 MB" */
fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 10 || unit == 0) {
        "${value.roundToInt()} ${units[unit]}"
    } else {
        "%.1f %s".format(Locale.getDefault(), value, units[unit])
    }
}

/** eg "8.2 MB/s" */
fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

/** A release's age, eg "5h", "3d" or "2y" */
fun Resources.formatAgeHours(hours: Double): String =
    when {
        hours < 1 -> getString(R.string.fetcherr_age_under_hour)
        hours < 48 -> getString(R.string.fetcherr_age_hours, hours.roundToInt())
        hours < 24 * 365 -> getString(R.string.fetcherr_age_days, (hours / 24).roundToInt())
        else -> getString(R.string.fetcherr_age_years, (hours / (24 * 365)).roundToInt())
    }

/** Time left, to the minute once it's over a minute */
fun Resources.formatEta(seconds: Long): String =
    formatDurationWithDays((if (seconds < 60) seconds else seconds / 60 * 60).seconds)

/** eg "62%" */
fun formatProgress(progress: Double): String = "${(progress.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

/**
 * The request scope for a release picked for [season] (null for a movie)
 *
 * A pack covering several seasons is requested for the season the user picked it for.
 */
fun Coverage.toScope(season: Int?): Scope =
    when (this) {
        Coverage.Movie -> {
            Scope.Movie
        }

        is Coverage.SeasonPack -> {
            val chosen = season?.takeIf { seasons.isEmpty() || it in seasons } ?: seasons.firstOrNull() ?: season ?: 1
            Scope.Season(chosen)
        }

        is Coverage.Episodes -> {
            Scope.Episodes(this.season, episodes)
        }
    }
