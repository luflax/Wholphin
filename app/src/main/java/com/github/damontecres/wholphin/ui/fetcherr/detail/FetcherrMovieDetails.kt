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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.MovieView
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FetcherrEvents
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.ScrollableDialog
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrCardRow
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrError
import com.github.damontecres.wholphin.ui.fetcherr.backdropUrl
import com.github.damontecres.wholphin.ui.fetcherr.canRequest
import com.github.damontecres.wholphin.ui.fetcherr.destination
import com.github.damontecres.wholphin.ui.fetcherr.jellyfinDestination
import com.github.damontecres.wholphin.ui.fetcherr.loadFetcherrData
import com.github.damontecres.wholphin.ui.fetcherr.request.FetcherrStatusBlock
import com.github.damontecres.wholphin.ui.fetcherr.request.ReleasePickerDialog
import com.github.damontecres.wholphin.ui.fetcherr.request.RemoveRequestDialog
import com.github.damontecres.wholphin.ui.fetcherr.request.RequestQualityDialog
import com.github.damontecres.wholphin.ui.formatDuration
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
import kotlin.time.Duration.Companion.minutes

@HiltViewModel(assistedFactory = FetcherrMovieViewModel.Factory::class)
class FetcherrMovieViewModel
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
            TitleKind.MOVIE,
            tmdbId,
        ) {
        @AssistedFactory
        interface Factory {
            fun create(tmdbId: Int): FetcherrMovieViewModel
        }

        private val _movie = MutableStateFlow<DataLoadingState<MovieView>>(DataLoadingState.Pending)
        val movie: StateFlow<DataLoadingState<MovieView>> = _movie

        init {
            loadRatings()
        }

        override fun load() {
            if (_movie.value !is DataLoadingState.Success) _movie.value = DataLoadingState.Loading
            viewModelScope.launchIO {
                val result = loadFetcherrData { fetcherrRepository.call { it.titlesApi.movie(tmdbId) } }
                if (result is DataLoadingState.Success) {
                    mutableLive.update { it.withRequests(result.data.requests) }
                    submitBackdrop(result.data.card.backdrop.backdropUrl)
                }
                _movie.value = result
            }
        }
    }

@Composable
fun FetcherrMovieDetails(
    destination: Destination.FetcherrMovie,
    modifier: Modifier = Modifier,
    viewModel: FetcherrMovieViewModel =
        hiltViewModel<FetcherrMovieViewModel, FetcherrMovieViewModel.Factory>(
            key = "fetcherr_movie_${destination.tmdbId}",
            creationCallback = { it.create(destination.tmdbId) },
        ),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        viewModel.startEvents()
        onPauseOrDispose { viewModel.stopEvents() }
    }
    val movieState by viewModel.movie.collectAsState()
    when (val state = movieState) {
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
            FetcherrMovieContent(movie = state.data, viewModel = viewModel, modifier = modifier)
        }
    }
}

@Composable
private fun FetcherrMovieContent(
    movie: MovieView,
    viewModel: FetcherrMovieViewModel,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val ratings by viewModel.ratings.collectAsState()
    val live by viewModel.live.collectAsState()
    val qualities by viewModel.qualities.collectAsState()
    val releases by viewModel.releases.collectAsState()
    val card = movie.card

    var showQualityDialog by remember { mutableStateOf(false) }
    var showReleases by remember { mutableStateOf(false) }
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
        remember(movie, resources) {
            buildList {
                (movie.releaseDate ?: card.year?.toString())?.let(::add)
                movie.runtime?.takeIf { it > 0 }?.let { add(resources.formatDuration(it.minutes)) }
            }
        }

    val canRequest = card.availability.canRequest && live.requests.isEmpty()
    val playDestination = jellyfinDestination(card.jellyfinItemId, BaseItemKind.MOVIE)
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
                        genres = movie.genres,
                        tagline = movie.tagline,
                        overview = card.overview,
                        onClickOverview = { showOverview = true },
                        modifier = Modifier.padding(top = HeaderUtils.topPadding),
                        // Without buttons, focus starts on the overview
                        overviewModifier = if (showButtons) Modifier else Modifier.focusRequester(buttonsFocus),
                    )
                    FetcherrTitleButtons(
                        canRequest = canRequest,
                        jellyfinDestination = playDestination,
                        playLabel = R.string.play,
                        onDownload = {
                            viewModel.loadQualities()
                            showQualityDialog = true
                        },
                        onChooseRelease = {
                            viewModel.loadReleases(RequestTarget(Scope.Movie, card.title))
                            showReleases = true
                        },
                        onOpen = { viewModel.navigationManager.navigateTo(it) },
                        modifier = if (showButtons) Modifier.focusRequester(buttonsFocus) else Modifier,
                    )
                    FetcherrStatusBlock(
                        live = live,
                        actions = viewModel,
                        onRemove = { removing = it },
                        showScope = false,
                        modifier = Modifier.fillMaxWidth(.75f),
                    )
                }
            }
            if (movie.cast.isNotEmpty()) {
                item(key = "cast") {
                    FetcherrCastRow(
                        cast = movie.cast,
                        onClick = { viewModel.navigationManager.navigateTo(Destination.FetcherrPerson(it.tmdbId)) },
                    )
                }
            }
            items(movie.related.filter { it.cards.isNotEmpty() }, key = { "related_${it.id}" }) { related ->
                FetcherrCardRow(
                    title = related.title,
                    cards = related.cards,
                    onClickCard = { _, it -> viewModel.navigationManager.navigateTo(it.destination()) },
                )
            }
        }
    }

    if (showQualityDialog) {
        RequestQualityDialog(
            title = stringResource(R.string.fetcherr_download_title, card.title),
            qualities = qualities,
            onSubmit = { quality ->
                showQualityDialog = false
                viewModel.request(Scope.Movie, quality = quality)
            },
            onDismissRequest = { showQualityDialog = false },
        )
    }
    if (showReleases) {
        val target = RequestTarget(Scope.Movie, card.title)
        ReleasePickerDialog(
            title = stringResource(R.string.fetcherr_choose_release_title, card.title),
            releases = releases,
            onSelect = { release ->
                showReleases = false
                viewModel.request(
                    scope = Scope.Movie,
                    releaseId = release.releaseId,
                    onExpired = {
                        viewModel.loadReleases(target)
                        showReleases = true
                    },
                )
            },
            onRefresh = { viewModel.loadReleases(target) },
            onDismissRequest = { showReleases = false },
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
