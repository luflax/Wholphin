package com.github.damontecres.wholphin.ui.fetcherr.detail

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Availability
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.api.fetcherr.model.SeasonCard
import com.github.damontecres.wholphin.api.fetcherr.model.SeriesView
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FetcherrEvents
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.ScrollableDialog
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrCardRow
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrError
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrPosterCard
import com.github.damontecres.wholphin.ui.fetcherr.availabilityLabel
import com.github.damontecres.wholphin.ui.fetcherr.backdropUrl
import com.github.damontecres.wholphin.ui.fetcherr.canRequest
import com.github.damontecres.wholphin.ui.fetcherr.destination
import com.github.damontecres.wholphin.ui.fetcherr.jellyfinDestination
import com.github.damontecres.wholphin.ui.fetcherr.loadFetcherrData
import com.github.damontecres.wholphin.ui.fetcherr.posterUrl
import com.github.damontecres.wholphin.ui.fetcherr.request.FetcherrStatusBlock
import com.github.damontecres.wholphin.ui.fetcherr.request.ReleasePickerDialog
import com.github.damontecres.wholphin.ui.fetcherr.request.RemoveRequestDialog
import com.github.damontecres.wholphin.ui.fetcherr.request.RequestQualityDialog
import com.github.damontecres.wholphin.ui.fetcherr.seriesStatusLabel
import com.github.damontecres.wholphin.ui.fetcherr.toScope
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.BaseItemKind

@HiltViewModel(assistedFactory = FetcherrSeriesViewModel.Factory::class)
class FetcherrSeriesViewModel
    @AssistedInject
    constructor(
        @ApplicationContext context: Context,
        fetcherrRepository: FetcherrRepository,
        fetcherrEvents: FetcherrEvents,
        navigationManager: NavigationManager,
        backdropService: BackdropService,
        @Assisted tmdbId: Int,
    ) : FetcherrTitleViewModel(
            context,
            fetcherrRepository,
            fetcherrEvents,
            navigationManager,
            backdropService,
            TitleKind.SERIES,
            tmdbId,
        ) {
        @AssistedFactory
        interface Factory {
            fun create(tmdbId: Int): FetcherrSeriesViewModel
        }

        private val _series = MutableStateFlow<DataLoadingState<SeriesView>>(DataLoadingState.Pending)
        val series: StateFlow<DataLoadingState<SeriesView>> = _series

        init {
            loadRatings()
        }

        override fun load() {
            if (_series.value !is DataLoadingState.Success) _series.value = DataLoadingState.Loading
            viewModelScope.launchIO {
                val result = loadFetcherrData { fetcherrRepository.call { it.titlesApi.series(tmdbId) } }
                if (result is DataLoadingState.Success) {
                    mutableLive.update { it.withRequests(result.data.requests) }
                    submitBackdrop(result.data.card.backdrop.backdropUrl)
                }
                _series.value = result
            }
        }
    }

/**
 * What the season picker is choosing a season for
 */
private enum class SeasonPickPurpose { DOWNLOAD, CHOOSE_RELEASE }

@Composable
fun FetcherrSeriesDetails(
    destination: Destination.FetcherrSeries,
    modifier: Modifier = Modifier,
    viewModel: FetcherrSeriesViewModel =
        hiltViewModel<FetcherrSeriesViewModel, FetcherrSeriesViewModel.Factory>(
            key = "fetcherr_series_${destination.tmdbId}",
            creationCallback = { it.create(destination.tmdbId) },
        ),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        viewModel.startEvents()
        onPauseOrDispose { viewModel.stopEvents() }
    }
    val seriesState by viewModel.series.collectAsState()
    when (val state = seriesState) {
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
            FetcherrSeriesContent(series = state.data, viewModel = viewModel, modifier = modifier)
        }
    }
}

@Composable
private fun FetcherrSeriesContent(
    series: SeriesView,
    viewModel: FetcherrSeriesViewModel,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val ratings by viewModel.ratings.collectAsState()
    val live by viewModel.live.collectAsState()
    val qualities by viewModel.qualities.collectAsState()
    val releases by viewModel.releases.collectAsState()
    val card = series.card

    var seasonPick by remember { mutableStateOf<SeasonPickPurpose?>(null) }
    var qualityTarget by remember { mutableStateOf<RequestTarget?>(null) }
    var releaseTarget by remember { mutableStateOf<RequestTarget?>(null) }
    var showOverview by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<RequestSummary?>(null) }
    val buttonsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { buttonsFocus.tryRequestFocus() }
    // Removing a request takes its row (and the focused Remove button) away, so move focus first
    var refocusButtons by remember { mutableStateOf(false) }
    LaunchedEffect(refocusButtons) {
        if (refocusButtons) {
            buttonsFocus.tryRequestFocus()
            refocusButtons = false
        }
    }

    val details =
        remember(series, resources) {
            listOfNotNull(
                series.firstAirDate?.take(4) ?: card.year?.toString(),
                series.status?.let { resources.seriesStatusLabel(it) },
            )
        }

    val canRequest = card.availability.canRequest && series.seasons.any { it.availability.canRequest }
    val playDestination = jellyfinDestination(card.jellyfinItemId, BaseItemKind.SERIES)
    val showButtons = hasTitleButtons(canRequest, playDestination)

    Box(modifier = modifier) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FetcherrDetailHeader(
            title = card.title,
                        details = details,
                        ratings = ratings,
                        genres = series.genres,
                        tagline = series.tagline,
                        overview = card.overview,
                        onClickOverview = { showOverview = true },
                        modifier = Modifier.padding(top = HeaderUtils.topPadding),
                        // Without buttons, focus starts on the overview
                        overviewModifier = if (showButtons) Modifier else Modifier.focusRequester(buttonsFocus),
                    )
                    FetcherrTitleButtons(
                        canRequest = canRequest,
                        jellyfinDestination = playDestination,
                        playLabel = R.string.fetcherr_open_in_library,
                        onDownload = { seasonPick = SeasonPickPurpose.DOWNLOAD },
                        onChooseRelease = { seasonPick = SeasonPickPurpose.CHOOSE_RELEASE },
                        onOpen = { viewModel.navigationManager.navigateTo(it) },
                        modifier = if (showButtons) Modifier.focusRequester(buttonsFocus) else Modifier,
                    )
                    FetcherrStatusBlock(
                        live = live,
                        actions = viewModel,
                        onRemove = { removing = it },
                        modifier = Modifier.fillMaxWidth(.75f),
                    )
                }
            }
            if (series.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonsRow(
                        seasons = series.seasons,
                        onClick = {
                            viewModel.navigationManager.navigateTo(
                                Destination.FetcherrSeason(viewModel.tmdbId, it.number, card.title),
                            )
                        },
                    )
                }
            }
            if (series.cast.isNotEmpty()) {
                item(key = "cast") {
                    FetcherrCastRow(
                        cast = series.cast,
                        onClick = { viewModel.navigationManager.navigateTo(Destination.FetcherrPerson(it.tmdbId)) },
                    )
                }
            }
            items(series.related.filter { it.cards.isNotEmpty() }, key = { "related_${it.id}" }) { related ->
                FetcherrCardRow(
                    title = related.title,
                    cards = related.cards,
                    onClickCard = { _, it -> viewModel.navigationManager.navigateTo(it.destination()) },
                )
            }
        }
    }

    seasonPick?.let { purpose ->
        DialogPopup(
            params =
                DialogParams(
                    fromLongClick = false,
                    title = card.title,
                    items =
                        series.seasons.filter { it.availability.canRequest }.map { season ->
                            DialogItem(
                                headlineContent = { Text(season.name) },
                                supportingContent = {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.fetcherr_episodes_available,
                                            season.airedEpisodes,
                                            season.availableEpisodes,
                                            season.airedEpisodes,
                                        ),
                                    )
                                },
                                onClick = {
                                    val target =
                                        RequestTarget(
                                            scope = Scope.Season(season.number),
                                            label = "${card.title} · ${season.name}",
                                            season = season.number,
                                        )
                                    when (purpose) {
                                        SeasonPickPurpose.DOWNLOAD -> {
                                            viewModel.loadQualities()
                                            qualityTarget = target
                                        }

                                        SeasonPickPurpose.CHOOSE_RELEASE -> {
                                            viewModel.loadReleases(target)
                                            releaseTarget = target
                                        }
                                    }
                                },
                            )
                        },
                ),
            onDismissRequest = { seasonPick = null },
        )
    }
    qualityTarget?.let { target ->
        RequestQualityDialog(
            title = stringResource(R.string.fetcherr_download_title, target.label),
            qualities = qualities,
            onSubmit = { quality ->
                qualityTarget = null
                viewModel.request(target.scope, quality = quality)
            },
            onDismissRequest = { qualityTarget = null },
        )
    }
    releaseTarget?.let { target ->
        ReleasePickerDialog(
            title = stringResource(R.string.fetcherr_choose_release_title, target.label),
            releases = releases,
            onSelect = { release ->
                releaseTarget = null
                viewModel.request(
                    scope = release.coverage.toScope(target.season),
                    releaseId = release.releaseId,
                    onExpired = {
                        viewModel.loadReleases(target)
                        releaseTarget = target
                    },
                )
            },
            onRefresh = { viewModel.loadReleases(target) },
            onDismissRequest = { releaseTarget = null },
        )
    }
    if (showOverview) {
        ScrollableDialog(onDismissRequest = { showOverview = false }) {
            item { Text(card.overview) }
        }
    }
    removing?.let { request ->
        RemoveRequestDialog(
            title = card.title,
            onRemove = { deleteFiles ->
                viewModel.remove(request, deleteFiles)
                refocusButtons = true
            },
            onDismissRequest = { removing = null },
        )
    }
}

@Composable
private fun SeasonsRow(
    seasons: List<SeasonCard>,
    onClick: (SeasonCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        ItemRowTitle(stringResource(R.string.fetcherr_seasons))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(seasons, key = { it.number }) { season ->
                FetcherrPosterCard(
                    imageUrl = season.poster.posterUrl,
                    title = season.name,
                    subtitle =
                        if (season.availability == Availability.UNAVAILABLE) {
                            stringResource(R.string.fetcherr_episodes_count, season.availableEpisodes, season.airedEpisodes)
                        } else {
                            availabilityLabel(season.availability) + " · " +
                                stringResource(R.string.fetcherr_episodes_count, season.availableEpisodes, season.airedEpisodes)
                        },
                    availability = season.availability,
                    onClick = { onClick.invoke(season) },
                    onLongClick = {},
                )
            }
        }
    }
}
