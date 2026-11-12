package com.github.damontecres.wholphin.ui.fetcherr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Availability
import com.github.damontecres.wholphin.api.fetcherr.model.Card
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.cards.AvailableIndicator
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.cards.PartiallyAvailableIndicator
import com.github.damontecres.wholphin.ui.cards.PendingIndicator
import com.github.damontecres.wholphin.ui.cards.SlidingCardText
import com.github.damontecres.wholphin.ui.cards.ViewMoreCard
import com.github.damontecres.wholphin.ui.enableMarquee
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import androidx.tv.material3.Card as TvCard

/**
 * A poster card for a Fetcherr title, with its availability as a badge
 */
@Composable
fun FetcherrCard(
    card: Card?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = card?.year?.toString(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    width: Dp = Cards.height2x3 * AspectRatios.TALL,
) = FetcherrPosterCard(
    imageUrl = card?.poster.posterUrl,
    title = card?.title,
    subtitle = subtitle,
    availability = card?.availability,
    onClick = onClick,
    onLongClick = onLongClick,
    modifier = modifier,
    interactionSource = interactionSource,
    width = width,
)

/**
 * A card for either kind of [FetcherrGridItem]
 */
@Composable
fun FetcherrGridCard(
    item: FetcherrGridItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Cards.height2x3 * AspectRatios.TALL,
) = when (item) {
    is FetcherrGridItem.Person -> {
        FetcherrPosterCard(
            imageUrl = item.person.profile.posterUrl,
            title = item.person.name,
            subtitle = item.person.knownForDepartment,
            availability = null,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            width = width,
        )
    }

    is FetcherrGridItem.Title,
    null,
    -> {
        FetcherrCard(
            card = item?.card,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            width = width,
        )
    }
}

/**
 * A 2:3 poster with a title & subtitle below it, and an optional [Availability] badge
 */
@Composable
fun FetcherrPosterCard(
    imageUrl: String?,
    title: String?,
    subtitle: String?,
    availability: Availability?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    width: Dp = Cards.height2x3 * AspectRatios.TALL,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    var focusedAfterDelay by remember { mutableStateOf(false) }
    if (focused) {
        LaunchedEffect(Unit) {
            delay(500L)
            focusedAfterDelay = focused
        }
    } else {
        focusedAfterDelay = false
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.size(width, Dp.Unspecified),
    ) {
        TvCard(
            modifier =
                Modifier
                    .size(width, Dp.Unspecified)
                    .aspectRatio(AspectRatios.TALL),
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            colors = CardDefaults.colors(containerColor = Color.Transparent),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ItemCardImage(
                    imageUrl = imageUrl,
                    name = title,
                    showOverlay = false,
                    favorite = false,
                    watched = false,
                    unwatchedCount = 0,
                    watchedPercent = null,
                    numberOfVersions = -1,
                    useFallbackText = true,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                availability?.let {
                    AvailabilityIndicator(it, Modifier.align(Alignment.TopEnd))
                }
            }
        }
        SlidingCardText(focused) {
            Text(
                text = title ?: "",
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .enableMarquee(focusedAfterDelay),
            )
            Text(
                text = subtitle ?: "",
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .enableMarquee(focusedAfterDelay),
            )
        }
    }
}

/**
 * The corner badge for a title's [Availability]. Nothing is shown when it is unavailable.
 */
@Composable
fun AvailabilityIndicator(
    availability: Availability,
    modifier: Modifier = Modifier,
) {
    when (availability) {
        Availability.AVAILABLE -> AvailableIndicator(modifier)
        Availability.PARTIALLY_AVAILABLE -> PartiallyAvailableIndicator(modifier)
        Availability.DOWNLOADING -> DownloadingIndicator(modifier)
        Availability.REQUESTED -> PendingIndicator(modifier)
        Availability.UNAVAILABLE -> {}
    }
}

@Composable
fun DownloadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .padding(4.dp)
                .border(width = .5.dp, color = Color.White, shape = CircleShape)
                .background(color = AppColors.Discover.Blue.copy(alpha = .85f), shape = CircleShape)
                .size(16.dp),
    ) {
        Text(
            text = stringResource(R.string.fa_download),
            fontFamily = FontAwesome,
            fontSize = 9.sp,
            color = Color.White,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * A titled horizontal row of [FetcherrCard]s, optionally ending with a "View all" card
 *
 * Repeated titles are shown once, since TMDB lists occasionally repeat one.
 *
 * @param onCardFocus called with the focused card, or null for the "View all" card
 */
@Composable
fun FetcherrCardRow(
    title: String,
    cards: List<Card>,
    onClickCard: (Int, Card) -> Unit,
    modifier: Modifier = Modifier,
    onCardFocus: (Card?) -> Unit = {},
    onClickViewAll: (() -> Unit)? = null,
    subtitle: (Card) -> String? = { it.year?.toString() },
    horizontalPadding: Dp = 16.dp,
) {
    val uniqueCards = remember(cards) { cards.distinctBy { it.key } }
    val state = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val focusRequester = remember { FocusRequester() }
    var position by rememberInt()

    val currentOnClickCard by rememberUpdatedState(onClickCard)
    val currentOnCardFocus by rememberUpdatedState(onCardFocus)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.focusProperties { onEnter = { focusRequester.tryRequestFocus() } },
    ) {
        ItemRowTitle(title)
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(horizontalPadding),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRestorer(firstFocus)
                    .focusRequester(focusRequester),
        ) {
            itemsIndexed(uniqueCards, key = { _, card -> card.key }) { index, card ->
                FetcherrCard(
                    card = card,
                    subtitle = subtitle.invoke(card),
                    onClick = {
                        position = index
                        currentOnClickCard(index, card)
                    },
                    onLongClick = {},
                    modifier =
                        Modifier
                            .ifElse(index == position, Modifier.focusRequester(firstFocus))
                            .onFocusChanged { if (it.isFocused) currentOnCardFocus(card) },
                )
            }
            if (onClickViewAll != null) {
                item(key = "view_all") {
                    ViewMoreCard(
                        onClick = {
                            position = uniqueCards.size
                            onClickViewAll.invoke()
                        },
                        onLongClick = {},
                        modifier =
                            Modifier
                                .ifElse(uniqueCards.size == position, Modifier.focusRequester(firstFocus))
                                .onFocusChanged { if (it.isFocused) currentOnCardFocus(null) },
                    )
                }
            }
        }
    }
}
