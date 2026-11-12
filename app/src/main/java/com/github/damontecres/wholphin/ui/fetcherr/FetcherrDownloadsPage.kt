package com.github.damontecres.wholphin.ui.fetcherr

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.FetcherrApiClient
import com.github.damontecres.wholphin.api.fetcherr.RequestsApi
import com.github.damontecres.wholphin.api.fetcherr.model.Event
import com.github.damontecres.wholphin.api.fetcherr.model.PageRequestSummary
import com.github.damontecres.wholphin.api.fetcherr.model.RequestState
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.services.FetcherrEvents
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.fetcherr.request.FetcherrRequestStatus
import com.github.damontecres.wholphin.ui.fetcherr.request.FetcherrRequestsViewModel
import com.github.damontecres.wholphin.ui.fetcherr.request.LiveRequests
import com.github.damontecres.wholphin.ui.fetcherr.request.RemoveRequestDialog
import com.github.damontecres.wholphin.ui.fetcherr.request.fetcherrRowColors
import com.github.damontecres.wholphin.ui.fetcherr.request.filtered
import com.github.damontecres.wholphin.ui.fetcherr.request.groupedByTitle
import com.github.damontecres.wholphin.ui.fetcherr.request.requestActionsDialog
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Which requests the Downloads tab lists
 *
 * The API filters by one state at a time, so the multi-state filters load every page of each of
 * their states, which are expected to stay short. The others page as the user scrolls.
 *
 * @param states the states shown, or null for all
 */
enum class DownloadsFilter(
    @param:StringRes val label: Int,
    val states: Set<RequestState>?,
) {
    ALL(R.string.fetcherr_filter_all, null),
    ACTIVE(
        R.string.fetcherr_filter_active,
        setOf(RequestState.SEARCHING, RequestState.DOWNLOADING, RequestState.PAUSED, RequestState.IMPORTING),
    ),
    FAILED(R.string.fetcherr_state_failed, setOf(RequestState.FAILED, RequestState.NOT_FOUND)),
    AVAILABLE(R.string.fetcherr_available, setOf(RequestState.AVAILABLE)),
    ;

    /** Whether the server pages through the results, rather than every page being loaded up front */
    val paged: Boolean get() = states == null || states.size == 1
}

private fun RequestState.toStateList(): RequestsApi.StateList = RequestsApi.StateList.entries.first { it.value == value }

data class DownloadsState(
    val filter: DownloadsFilter = DownloadsFilter.ALL,
    val loading: LoadingState = LoadingState.Pending,
    val page: Int = 0,
    val totalPages: Int = 0,
) {
    val hasMore: Boolean get() = page < totalPages
}

@HiltViewModel
class FetcherrDownloadsViewModel
    @Inject
    constructor(
        fetcherrRepository: FetcherrRepository,
        fetcherrEvents: FetcherrEvents,
        val navigationManager: NavigationManager,
    ) : FetcherrRequestsViewModel(fetcherrRepository, fetcherrEvents, titleKey = null) {
        private val _state = MutableStateFlow(DownloadsState())
        val state: StateFlow<DownloadsState> = _state

        private var loadJob: Job? = null
        private var moreJob: Job? = null

        /**
         * Whether [reload] is still loading. Cleared before its result is published, so that
         * [loadMore] works as soon as the list shows, unlike checking whether [loadJob] is active.
         */
        @Volatile private var reloading = false

        /** Like [reloading], for [loadMore] */
        @Volatile private var loadingMore = false

        override fun followedTitles(): Collection<String> = emptyList()

        /** New requests, eg made from another device, should show up too */
        override val followsEverything: Boolean = true

        override fun onEvent(event: Event) {
            super.onEvent(event)
            // Drop requests that left the filter, and place ones that joined it
            if (event is Event.RequestUpdated) {
                mutableLive.update { it.filtered(_state.value.filter.states) }
            }
        }

        /**
         * Load [filter] from the first page. Refreshing the current filter, eg when the tab resumes,
         * reloads every page already shown so that the list keeps its length.
         */
        fun reload(filter: DownloadsFilter = _state.value.filter) {
            loadJob?.cancel()
            moreJob?.cancel()
            loadingMore = false
            reloading = true
            val current = _state.value
            val refresh = filter == current.filter && current.loading is LoadingState.Success
            if (!refresh) {
                _state.update { it.copy(filter = filter, loading = LoadingState.Loading, page = 0, totalPages = 0) }
            }
            loadJob =
                viewModelScope.launchIO {
                    try {
                        val (requests, page) =
                            if (filter.paged) {
                                loadPages(filter, upTo = if (refresh) current.page.coerceAtLeast(1) else 1)
                            } else {
                                loadAll(filter.states.orEmpty()) to null
                            }
                        reloading = false
                        mutableLive.update { it.withRequests(requests).filtered(filter.states) }
                        _state.update {
                            it.copy(
                                loading = LoadingState.Success,
                                page = page?.page ?: 1,
                                totalPages = page?.totalPages ?: 1,
                            )
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        reloading = false
                        _state.update { it.copy(loading = LoadingState.Error(ex)) }
                    }
                }
        }

        /** Load the next page, when the user nears the end of the list */
        fun loadMore() {
            val current = _state.value
            if (!current.hasMore || current.loading !is LoadingState.Success || reloading || loadingMore) return
            loadingMore = true
            moreJob =
                viewModelScope.launchIO(ExceptionHandler(autoToast = true)) {
                    val result =
                        try {
                            fetcherrRepository.call { list(it, current.filter, current.page + 1) }
                        } finally {
                            loadingMore = false
                        }
                    // The page first, so that a scroll right after the rows show asks for the next one
                    _state.update { it.copy(page = result.page, totalPages = result.totalPages) }
                    mutableLive.update { live ->
                        val known = live.requests.mapTo(HashSet()) { it.id }
                        live
                            .withRequests(live.requests + result.items.filterNot { it.id in known })
                            .filtered(current.filter.states)
                    }
                }
        }

        private suspend fun list(
            api: FetcherrApiClient,
            filter: DownloadsFilter,
            page: Int,
        ): PageRequestSummary = api.requestsApi.list(filter.states?.single()?.toStateList(), page)

        /** Pages 1 to [upTo] of a paged filter, and the last page loaded */
        private suspend fun loadPages(
            filter: DownloadsFilter,
            upTo: Int,
        ): Pair<List<RequestSummary>, PageRequestSummary> {
            val requests = mutableListOf<RequestSummary>()
            var page = fetcherrRepository.call { list(it, filter, 1) }
            requests.addAll(page.items)
            while (page.page < upTo && page.page < page.totalPages) {
                val next = page.page + 1
                page = fetcherrRepository.call { list(it, filter, next) }
                requests.addAll(page.items)
            }
            return requests.distinctBy { it.id } to page
        }

        /** Every page of each of [states] */
        private suspend fun loadAll(states: Set<RequestState>): List<RequestSummary> =
            coroutineScope {
                states
                    .map { state ->
                        async {
                            val requests = mutableListOf<RequestSummary>()
                            var page = 0
                            do {
                                val next = page + 1
                                val result = fetcherrRepository.call { it.requestsApi.list(state.toStateList(), next) }
                                requests.addAll(result.items)
                                page = result.page
                            } while (page < result.totalPages && page < MAX_PAGES_PER_STATE)
                            requests
                        }
                    }.awaitAll()
                    .flatten()
                    .distinctBy { it.id }
            }

        fun openTitle(request: RequestSummary) {
            navigationManager.navigateTo(
                when (request.mediaType) {
                    TitleKind.MOVIE -> Destination.FetcherrMovie(request.tmdbId)
                    TitleKind.SERIES -> Destination.FetcherrSeries(request.tmdbId)
                },
            )
        }

        companion object {
            /** A safety limit for the filters that load every page */
            private const val MAX_PAGES_PER_STATE = 25
        }
    }

/**
 * Every request, grouped by title, with live progress and actions
 */
@Composable
fun FetcherrDownloadsPage(
    focusRequesterOnEmpty: FocusRequester,
    modifier: Modifier = Modifier,
    viewModel: FetcherrDownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val live by viewModel.live.collectAsState()
    var dialog by remember { mutableStateOf<DialogParams?>(null) }
    var removing by remember { mutableStateOf<RequestSummary?>(null) }
    val firstFocus = remember { FocusRequester() }

    // Once a removed request's row is gone, focus the first remaining row so focus doesn't escape
    var pendingRemovedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(live.requests, pendingRemovedId) {
        val removedId = pendingRemovedId ?: return@LaunchedEffect
        if (live.requests.none { it.id == removedId }) {
            pendingRemovedId = null
            if (live.requests.isNotEmpty()) firstFocus.tryRequestFocus() else focusRequesterOnEmpty.tryRequestFocus()
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.reload()
        viewModel.startEvents()
        onPauseOrDispose { viewModel.stopEvents() }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadsFilter.entries.forEach { filter ->
                TextButton(
                    onClick = { viewModel.reload(filter) },
                ) {
                    Text(
                        text = stringResource(filter.label),
                        fontWeight = if (filter == state.filter) FontWeight.Bold else FontWeight.Normal,
                        color =
                            if (filter == state.filter) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }
        }

        when (val loading = state.loading) {
            LoadingState.Pending,
            LoadingState.Loading,
            -> {
                LoadingPage(Modifier.fillMaxSize(), focusEnabled = false)
            }

            is LoadingState.Error -> {
                FetcherrError(
                    exception = loading.exception,
                    message = loading.message,
                    onRetry = { viewModel.reload() },
                )
            }

            LoadingState.Success -> {
                if (live.requests.isEmpty()) {
                    LaunchedEffect(Unit) { focusRequesterOnEmpty.tryRequestFocus() }
                    Text(
                        text =
                            stringResource(
                                if (state.filter == DownloadsFilter.ALL) R.string.fetcherr_no_requests else R.string.no_results,
                            ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, start = 8.dp),
                    )
                } else {
                    val groups = remember(live.requests) { live.requests.groupedByTitle() }
                    // The first row on screen, which is the first of the first group
                    val firstId = groups.first().first().id
                    // Positions in the flat list, to load more as the user nears its end
                    val positions =
                        remember(live.requests) {
                            live.requests.withIndex().associate { (index, request) -> request.id to index }
                        }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .focusRestorer(firstFocus),
                    ) {
                        groups.forEach { group ->
                            val first = group.first()
                            item(key = "title_${first.key}") {
                                TitleHeader(request = first, modifier = Modifier.padding(top = 8.dp))
                            }
                            items(group, key = { it.id }) { request ->
                                DownloadRow(
                                    request = request,
                                    live = live,
                                    onClick = {
                                        dialog =
                                            requestActionsDialog(
                                                title = request.title,
                                                request = request,
                                                actions = viewModel,
                                                onRemove = { removing = request },
                                                extra =
                                                    listOf(
                                                        DialogItem(R.string.fetcherr_open_title, R.string.fa_film) {
                                                            viewModel.openTitle(request)
                                                        },
                                                    ),
                                            )
                                    },
                                    modifier =
                                        Modifier
                                            .then(if (request.id == firstId) Modifier.focusRequester(firstFocus) else Modifier)
                                            .onFocusChanged {
                                                val position = positions[request.id] ?: 0
                                                if (it.isFocused && position >= live.requests.size - 5) viewModel.loadMore()
                                            },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    dialog?.let {
        DialogPopup(params = it, onDismissRequest = { dialog = null })
    }
    removing?.let { request ->
        RemoveRequestDialog(
            title = request.title,
            onRemove = { deleteFiles ->
                pendingRemovedId = request.id
                viewModel.remove(request, deleteFiles)
            },
            onDismissRequest = { removing = null },
        )
    }
}

private val TITLE_POSTER_WIDTH = 40.dp

/**
 * The poster & title above a title's requests
 */
@Composable
private fun TitleHeader(
    request: RequestSummary,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        ItemCardImage(
            imageUrl = request.poster.posterUrl,
            name = request.title,
            showOverlay = false,
            favorite = false,
            watched = false,
            unwatchedCount = 0,
            watchedPercent = null,
            numberOfVersions = -1,
            contentScale = ContentScale.FillBounds,
            modifier =
                Modifier
                    .width(TITLE_POSTER_WIDTH)
                    .aspectRatio(AspectRatios.TALL),
        )
        Text(
            text = listOfNotNull(request.title, request.year?.let { "($it)" }).joinToString(" "),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DownloadRow(
    request: RequestSummary,
    live: LiveRequests,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = fetcherrRowColors(),
        // Indented under the title's poster
        modifier = modifier.padding(start = TITLE_POSTER_WIDTH + 16.dp).fillMaxWidth(),
    ) {
        FetcherrRequestStatus(
            request = request,
            progress = live.progressOf(request),
            // A movie has one scope, so its label would only repeat "Movie"
            showScope = request.mediaType == TitleKind.SERIES,
            modifier = Modifier.padding(8.dp),
        )
    }
}
