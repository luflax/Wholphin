package com.github.damontecres.wholphin.ui.fetcherr.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.CastMemberView
import com.github.damontecres.wholphin.api.fetcherr.model.RatingsView
import com.github.damontecres.wholphin.ui.cards.PersonCard
import com.github.damontecres.wholphin.ui.cards.personRowCardWidth
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.GenreText
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.fetcherr.posterUrl
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.nav.Destination
import java.util.Locale

/**
 * Title, quick details, ratings, genres, tagline and overview of a movie or series
 */
@Composable
fun FetcherrDetailHeader(
    title: String,
    details: List<String>,
    ratings: RatingsView?,
    genres: List<String>,
    tagline: String?,
    overview: String?,
    onClickOverview: () -> Unit,
    modifier: Modifier = Modifier,
    overviewModifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        TitleOrLogo(
            title = title,
            logoImageUrl = null,
            showLogo = false,
            modifier =
                Modifier
                    .fillMaxWidth(.75f)
                    .padding(start = HeaderUtils.startPadding),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(.60f).padding(start = HeaderUtils.startPadding),
        ) {
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            ratings?.let { RatingsLine(it) }
            if (genres.isNotEmpty()) GenreText(genres)
            tagline?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        overview?.takeIf { it.isNotBlank() }?.let {
            OverviewText(
                overview = it,
                maxLines = 3,
                onClick = onClickOverview,
                textBoxHeight = Dp.Unspecified,
                modifier = overviewModifier.fillMaxWidth(.60f),
            )
        }
    }
}

/** Whether [FetcherrTitleButtons] shows anything */
fun hasTitleButtons(
    canRequest: Boolean,
    jellyfinDestination: Destination?,
): Boolean = canRequest || jellyfinDestination != null

/**
 * The main actions of a title: play it if Jellyfin has it, otherwise download it
 *
 * @param jellyfinDestination where Play goes, or null if the media server doesn't have the title
 */
@Composable
fun FetcherrTitleButtons(
    canRequest: Boolean,
    jellyfinDestination: Destination?,
    @StringRes playLabel: Int,
    onDownload: () -> Unit,
    onChooseRelease: () -> Unit,
    onOpen: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(8.dp),
        modifier =
            modifier
                .focusGroup()
                .focusRestorer(firstFocus),
    ) {
        // Play leads when the title is in the library, otherwise Download does
        val playFirst = jellyfinDestination != null
        if (jellyfinDestination != null) {
            item(key = "play") {
                ExpandableFaButton(
                    title = playLabel,
                    iconStringRes = R.string.fa_play,
                    onClick = { onOpen.invoke(jellyfinDestination) },
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
        }
        if (canRequest) {
            item(key = "download") {
                ExpandableFaButton(
                    title = R.string.fetcherr_download,
                    iconStringRes = R.string.fa_download,
                    onClick = onDownload,
                    modifier = Modifier.ifElse(!playFirst, Modifier.focusRequester(firstFocus)),
                )
            }
            item(key = "choose_release") {
                ExpandableFaButton(
                    title = R.string.fetcherr_choose_release,
                    iconStringRes = R.string.fa_list_ul,
                    onClick = onChooseRelease,
                )
            }
        }
    }
}

/**
 * eg "TMDB 7.0   IMDb 7.2   RT 80%   MC 64"
 */
@Composable
fun RatingsLine(
    ratings: RatingsView,
    modifier: Modifier = Modifier,
) {
    val parts =
        buildList {
            if (ratings.tmdb.votes > 0) add(stringResource(R.string.fetcherr_rating_tmdb, formatScore(ratings.tmdb.score)))
            ratings.imdb?.let { add(stringResource(R.string.fetcherr_rating_imdb, formatScore(it.score))) }
            ratings.rottenTomatoes?.let { add(stringResource(R.string.fetcherr_rating_rt, it)) }
            ratings.metacritic?.let { add(stringResource(R.string.fetcherr_rating_mc, it)) }
        }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("   "),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

private fun formatScore(score: Double) = String.format(Locale.getDefault(), "%.1f", score)

/**
 * The cast of a title, opening each person's Fetcherr page
 */
@Composable
fun FetcherrCastRow(
    cast: List<CastMemberView>,
    onClick: (CastMemberView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.people_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(8.dp),
            modifier =
                Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRestorer(firstFocus),
        ) {
            itemsIndexed(cast, key = { index, person -> "${person.tmdbId}_$index" }) { index, person ->
                PersonCard(
                    name = person.name,
                    role = person.character,
                    imageUrl = person.profile.posterUrl,
                    favorite = false,
                    onClick = { onClick.invoke(person) },
                    onLongClick = {},
                    modifier =
                        Modifier
                            .width(personRowCardWidth)
                            .ifElse(index == 0, Modifier.focusRequester(firstFocus)),
                )
            }
        }
    }
}
