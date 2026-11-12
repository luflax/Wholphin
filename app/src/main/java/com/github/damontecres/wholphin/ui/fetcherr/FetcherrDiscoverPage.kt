package com.github.damontecres.wholphin.ui.fetcherr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Availability
import com.github.damontecres.wholphin.api.fetcherr.model.Card
import com.github.damontecres.wholphin.api.fetcherr.model.DiscoverPageView
import com.github.damontecres.wholphin.data.model.QuickDetailsData
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.listToDotString
import com.github.damontecres.wholphin.ui.main.HomePageHeader
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ScrollToTopBringIntoViewSpec
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel(assistedFactory = FetcherrDiscoverViewModel.Factory::class)
class FetcherrDiscoverViewModel
    @AssistedInject
    constructor(
        private val fetcherrRepository: FetcherrRepository,
        private val backdropService: BackdropService,
        val navigationManager: NavigationManager,
        @Assisted private val pageId: String,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(pageId: String): FetcherrDiscoverViewModel
        }

        private val _page = MutableStateFlow<DataLoadingState<DiscoverPageView>>(DataLoadingState.Pending)
        val page: StateFlow<DataLoadingState<DiscoverPageView>> = _page

        init {
            load()
        }

        /** Every list arrives with its first page of cards, so this is the page's only request */
        fun load() {
            if (_page.value !is DataLoadingState.Success) _page.value = DataLoadingState.Loading
            viewModelScope.launchIO {
                _page.value = loadFetcherrData { fetcherrRepository.call { it.discoverApi.page(pageId) } }
            }
        }

        /** Reload a page already shown, eg so that availability is current after requesting */
        fun refresh() {
            if (_page.value is DataLoadingState.Success) load()
        }

        fun updateBackdrop(card: Card?) {
            viewModelScope.launchIO {
                if (card != null) {
                    backdropService.submit("fetcherr_${card.key}", card.backdrop.backdropUrl)
                } else {
                    backdropService.clearBackdrop()
                }
            }
        }
    }

/**
 * One server-defined discover page: a header for the focused title, then each list as a row
 *
 * @param onRowFocused called with the focused row index, eg to hide the tabs past the first row
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FetcherrDiscoverPage(
    preferences: UserPreferences,
    pageId: String,
    onRowFocused: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FetcherrDiscoverViewModel =
        hiltViewModel<FetcherrDiscoverViewModel, FetcherrDiscoverViewModel.Factory>(
            key = "fetcherr_page_$pageId",
            creationCallback = { it.create(pageId) },
        ),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val pageState by viewModel.page.collectAsState()
    when (val state = pageState) {
        DataLoadingState.Pending,
        DataLoadingState.Loading,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Error -> {
            FetcherrError(
                exception = state.exception,
                message = state.message,
                onRetry = viewModel::load,
                modifier = modifier,
            )
        }

        is DataLoadingState.Success -> {
            FetcherrDiscoverContent(
                preferences = preferences,
                page = state.data,
                onFocusCard = viewModel::updateBackdrop,
                onClickCard = { viewModel.navigationManager.navigateTo(it.destination()) },
                onClickViewAll = { listId, title ->
                    viewModel.navigationManager.navigateTo(Destination.FetcherrList(listId, title))
                },
                onRowFocused = onRowFocused,
                modifier = modifier,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FetcherrDiscoverContent(
    preferences: UserPreferences,
    page: DiscoverPageView,
    onFocusCard: (Card?) -> Unit,
    onClickCard: (Card) -> Unit,
    onClickViewAll: (listId: String, title: String) -> Unit,
    onRowFocused: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedRow by rememberSaveable { mutableStateOf(0) }
    var focusedCard by remember { mutableStateOf<Card?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(focusedCard) { onFocusCard.invoke(focusedCard) }
    LaunchedEffect(Unit) { firstFocus.tryRequestFocus("fetcherr_discover") }

    Column(modifier = modifier) {
        val card = focusedCard
        HomePageHeader(
            title = card?.title,
            subtitle = null,
            overview = card?.overview,
            overviewTwoLines = false,
            quickDetails = card?.let { QuickDetailsData(cardDetails(it)) },
            timeRemaining = null,
            endsAt = null,
            showLogo = false,
            logoImageUrl = null,
            modifier =
                Modifier
                    .padding(
                        top = HeaderUtils.topPadding,
                        // No bottom padding because of the tabs
                        bottom = 0.dp,
                        start = HeaderUtils.startPadding,
                    ).height(HeaderUtils.height),
        )

        val spaceAbovePx = with(LocalDensity.current) { 50.dp.toPx() }
        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides ScrollToTopBringIntoViewSpec(spaceAbovePx),
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
                modifier =
                    Modifier
                        .focusRestorer(firstFocus)
                        .fillMaxSize(),
            ) {
                itemsIndexed(page.lists, key = { _, list -> list.id }) { rowIndex, list ->
                    CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                        val rowModifier =
                            Modifier
                                .fillMaxWidth()
                                .then(if (rowIndex == focusedRow) Modifier.focusRequester(firstFocus) else Modifier)
                        when {
                            list.cards.items.isNotEmpty() -> {
                                FetcherrCardRow(
                                    title = list.title,
                                    cards = list.cards.items,
                                    onClickCard = { _, card -> onClickCard.invoke(card) },
                                    onCardFocus = { card ->
                                        focusedRow = rowIndex
                                        focusedCard = card
                                        onRowFocused.invoke(rowIndex)
                                    },
                                    onClickViewAll =
                                        if (list.cards.totalPages > 1) {
                                            { onClickViewAll.invoke(list.id, list.title) }
                                        } else {
                                            null
                                        },
                                    modifier = rowModifier,
                                )
                            }

                            else -> {
                                // An empty list, with the reason when TMDB failed for it
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    ItemRowTitle(list.title)
                                    Text(
                                        text = list.error ?: stringResource(R.string.no_results),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color =
                                            if (list.error != null) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** eg "2024 · Available" with the TMDB score as the community rating */
@Composable
fun cardDetails(card: Card): AnnotatedString {
    val parts =
        buildList {
            card.year?.let { add(it.toString()) }
            if (card.availability != Availability.UNAVAILABLE) add(availabilityLabel(card.availability))
        }
    return listToDotString(parts, card.voteAverage.takeIf { it > 0 }?.toFloat(), null)
}
