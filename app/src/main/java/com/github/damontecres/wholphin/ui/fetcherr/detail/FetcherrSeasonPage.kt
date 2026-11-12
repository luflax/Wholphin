package com.github.damontecres.wholphin.ui.fetcherr.detail

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Availability
import com.github.damontecres.wholphin.api.fetcherr.model.EpisodeView
import com.github.damontecres.wholphin.api.fetcherr.model.Event
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.api.fetcherr.model.SeasonView
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FetcherrEvents
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.StatusChip
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrError
import com.github.damontecres.wholphin.ui.fetcherr.availabilityLabel
import com.github.damontecres.wholphin.ui.fetcherr.canRetry
import com.github.damontecres.wholphin.ui.fetcherr.episodesLabel
import com.github.damontecres.wholphin.ui.fetcherr.jellyfinDestination
import com.github.damontecres.wholphin.ui.fetcherr.loadFetcherrData
import com.github.damontecres.wholphin.ui.fetcherr.posterUrl
import com.github.damontecres.wholphin.ui.fetcherr.request.FetcherrStatusBlock
import com.github.damontecres.wholphin.ui.fetcherr.request.ReleasePickerDialog
import com.github.damontecres.wholphin.ui.fetcherr.request.RemoveRequestDialog
import com.github.damontecres.wholphin.ui.fetcherr.request.RequestQualityDialog
import com.github.damontecres.wholphin.ui.fetcherr.request.color
import com.github.damontecres.wholphin.ui.fetcherr.request.fetcherrRowColors
import com.github.damontecres.wholphin.ui.fetcherr.requestStateLabel
import com.github.damontecres.wholphin.ui.fetcherr.toScope
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
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

@HiltViewModel(assistedFactory = FetcherrSeasonViewModel.Factory::class)
class FetcherrSeasonViewModel
    @AssistedInject
    constructor(
        @ApplicationContext context: Context,
        fetcherrRepository: FetcherrRepository,
        fetcherrEvents: FetcherrEvents,
        navigationManager: NavigationManager,
        backdropService: BackdropService,
        @Assisted("tmdbId") tmdbId: Int,
        @Assisted("season") val seasonNumber: Int,
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
            fun create(
                @Assisted("tmdbId") tmdbId: Int,
                @Assisted("season") seasonNumber: Int,
            ): FetcherrSeasonViewModel
        }

        private val _season = MutableStateFlow<DataLoadingState<SeasonView>>(DataLoadingState.Pending)
        val season: StateFlow<DataLoadingState<SeasonView>> = _season

        override fun load() {
            if (_season.value !is DataLoadingState.Success) _season.value = DataLoadingState.Loading
            viewModelScope.launchIO {
                val result = loadFetcherrData { fetcherrRepository.call { it.titlesApi.season(tmdbId, seasonNumber) } }
                if (result is DataLoadingState.Success) {
                    mutableLive.update { it.withRequests(result.data.requests) }
                }
                _season.value = result
            }
        }

        override fun onAvailabilityChanged(event: Event.AvailabilityChanged) {
            if (event.season == null || event.season == seasonNumber) scheduleReload()
        }
    }

/** Whether an episode has aired, so that it can be downloaded */
private fun EpisodeView.hasAired(today: LocalDate): Boolean =
    airDate?.let {
        try {
            !LocalDate.parse(it).isAfter(today)
        } catch (_: Exception) {
            true
        }
    } ?: false

/** Whether a request covers this episode */
private fun RequestSummary.coversEpisode(
    season: Int,
    episode: Int,
): Boolean =
    when (val scope = scope) {
        Scope.Movie -> false
        is Scope.Season -> scope.season == season
        is Scope.Episodes -> scope.season == season && episode in scope.episodes
    }

/** Whether a request covers this season */
private fun RequestSummary.coversSeason(season: Int): Boolean =
    when (val scope = scope) {
        Scope.Movie -> false
        is Scope.Season -> scope.season == season
        is Scope.Episodes -> scope.season == season
    }

@Composable
fun FetcherrSeasonPage(
    destination: Destination.FetcherrSeason,
    modifier: Modifier = Modifier,
    viewModel: FetcherrSeasonViewModel =
        hiltViewModel<FetcherrSeasonViewModel, FetcherrSeasonViewModel.Factory>(
            key = "fetcherr_season_${destination.tmdbId}_${destination.season}",
            creationCallback = { it.create(destination.tmdbId, destination.season) },
        ),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        viewModel.startEvents()
        onPauseOrDispose { viewModel.stopEvents() }
    }
    val seasonState by viewModel.season.collectAsState()
    when (val state = seasonState) {
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
            FetcherrSeasonContent(
                seriesTitle = destination.seriesTitle,
                season = state.data,
                viewModel = viewModel,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun FetcherrSeasonContent(
    seriesTitle: String,
    season: SeasonView,
    viewModel: FetcherrSeasonViewModel,
    modifier: Modifier = Modifier,
) {
    val live by viewModel.live.collectAsState()
    val qualities by viewModel.qualities.collectAsState()
    val releases by viewModel.releases.collectAsState()
    val today = remember { LocalDate.now() }

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var qualityTarget by remember { mutableStateOf<RequestTarget?>(null) }
    var releaseTarget by remember { mutableStateOf<RequestTarget?>(null) }
    var episodeActions by remember { mutableStateOf<EpisodeView?>(null) }
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

    val title = "$seriesTitle · ${season.name}"
    val aired = season.episodes.filter { it.hasAired(today) }
    val available = season.episodes.count { it.availability == Availability.AVAILABLE }
    // Episodes with a request (even a failed one, which is retried instead) aren't missing
    val missing = aired.filter { it.availability == Availability.UNAVAILABLE && it.requestState == null }
    val seasonLive = live.copy(requests = live.requests.filter { it.coversSeason(season.number) })

    BackHandler(selecting) {
        selecting = false
        selected = emptySet()
    }

    fun seasonTarget() = RequestTarget(Scope.Season(season.number), title, season = season.number)

    fun episodesTarget(episodes: List<Int>) =
        RequestTarget(
            scope = Scope.Episodes(season.number, episodes.sorted()),
            label = "$seriesTitle · ${episodesLabel(season.number, episodes)}",
            season = season.number,
            episode = episodes.singleOrNull(),
        )

    /**
     * Only the missing episodes, unless none of the season is there, where a season request lets
     * Fetcherr pick a season pack
     */
    fun missingTarget() =
        if (missing.size == aired.size) {
            seasonTarget()
        } else {
            episodesTarget(missing.map { it.number })
        }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = HeaderUtils.topPadding),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        buildList {
                            add(pluralStringResource(R.plurals.fetcherr_episodes_available, aired.size, available, aired.size))
                            if (missing.isNotEmpty()) {
                                add(pluralStringResource(R.plurals.fetcherr_episodes_missing, missing.size, missing.size))
                            }
                        }.joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.focusRestorer().focusRequester(buttonsFocus),
                ) {
                    if (selecting) {
                        item(key = "download_selected") {
                            ExpandableFaButton(
                                title = R.string.fetcherr_download_selected,
                                iconStringRes = R.string.fa_download,
                                enabled = selected.isNotEmpty(),
                                onClick = {
                                    viewModel.loadQualities()
                                    qualityTarget = episodesTarget(selected.toList())
                                },
                            )
                        }
                        item(key = "cancel_select") {
                            ExpandableFaButton(
                                title = R.string.cancel,
                                iconStringRes = R.string.fa_xmark,
                                onClick = {
                                    selecting = false
                                    selected = emptySet()
                                },
                            )
                        }
                    } else {
                        if (missing.isNotEmpty()) {
                            item(key = "download_missing") {
                                ExpandableFaButton(
                                    title = R.string.fetcherr_download_missing,
                                    iconStringRes = R.string.fa_download,
                                    onClick = {
                                        viewModel.loadQualities()
                                        qualityTarget = missingTarget()
                                    },
                                )
                            }
                            item(key = "choose_release") {
                                ExpandableFaButton(
                                    title = R.string.fetcherr_choose_release,
                                    iconStringRes = R.string.fa_list_ul,
                                    onClick = {
                                        viewModel.loadReleases(seasonTarget())
                                        releaseTarget = seasonTarget()
                                    },
                                )
                            }
                            item(key = "select") {
                                ExpandableFaButton(
                                    title = R.string.fetcherr_select_episodes,
                                    iconStringRes = R.string.fa_check,
                                    onClick = { selecting = true },
                                )
                            }
                        }
                        jellyfinDestination(season.jellyfinItemId, BaseItemKind.SEASON)?.let { dest ->
                            item(key = "open") {
                                ExpandableFaButton(
                                    title = R.string.fetcherr_open_in_library,
                                    iconStringRes = R.string.fa_play,
                                    onClick = { viewModel.navigationManager.navigateTo(dest) },
                                )
                            }
                        }
                    }
                }
                FetcherrStatusBlock(
                    live = seasonLive,
                    actions = viewModel,
                    onRemove = { removing = it },
                    modifier = Modifier.fillMaxWidth(.75f),
                )
            }
        }
        items(season.episodes, key = { it.number }) { episode ->
            val canDownload = episode.availability == Availability.UNAVAILABLE && episode.hasAired(today)
            EpisodeRow(
                episode = episode,
                selecting = selecting,
                selected = episode.number in selected,
                selectable = canDownload,
                onClick = {
                    when {
                        selecting -> {
                            if (canDownload) {
                                selected =
                                    if (episode.number in selected) selected - episode.number else selected + episode.number
                            }
                        }

                        else -> {
                            val dest = jellyfinDestination(episode.jellyfinItemId, BaseItemKind.EPISODE)
                            if (dest != null) {
                                viewModel.navigationManager.navigateTo(dest)
                            } else if (canDownload) {
                                episodeActions = episode
                            }
                        }
                    }
                },
            )
        }
    }

    episodeActions?.let { episode ->
        val target = episodesTarget(listOf(episode.number))
        // A failed request for the episode is retried rather than requested again
        val failed =
            live.requests.firstOrNull { it.state.canRetry && it.coversEpisode(season.number, episode.number) }
        DialogPopup(
            params =
                DialogParams(
                    fromLongClick = false,
                    title = target.label,
                    items =
                        listOfNotNull(
                            failed?.let {
                                DialogItem(R.string.retry, R.string.fa_arrows_rotate) { viewModel.retry(it) }
                            },
                            DialogItem(R.string.fetcherr_download, R.string.fa_download) {
                                viewModel.loadQualities()
                                qualityTarget = target
                            },
                            DialogItem(R.string.fetcherr_choose_release, R.string.fa_list_ul) {
                                viewModel.loadReleases(target)
                                releaseTarget = target
                            },
                        ),
                ),
            onDismissRequest = { episodeActions = null },
        )
    }
    qualityTarget?.let { target ->
        RequestQualityDialog(
            title = stringResource(R.string.fetcherr_download_title, target.label),
            qualities = qualities,
            onSubmit = { quality ->
                qualityTarget = null
                selecting = false
                selected = emptySet()
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
    removing?.let { request ->
        RemoveRequestDialog(
            title = title,
            onRemove = { deleteFiles ->
                viewModel.remove(request, deleteFiles)
                refocusButtons = true
            },
            onDismissRequest = { removing = null },
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeView,
    selecting: Boolean,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = fetcherrRowColors(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            if (selecting) {
                SelectionBox(selected = selected, selectable = selectable)
            }
            Text(
                text = episode.number.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(32.dp),
            )
            ItemCardImage(
                imageUrl = episode.still.posterUrl,
                name = episode.name,
                showOverlay = false,
                favorite = false,
                watched = false,
                unwatchedCount = 0,
                watchedPercent = null,
                numberOfVersions = -1,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .width(120.dp)
                        .aspectRatio(AspectRatios.WIDE),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        listOfNotNull(
                            episode.airDate,
                            episode.runtime?.takeIf { it > 0 }?.let { resources.formatDuration(it.minutes) },
                        ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val requestState = episode.requestState
            if (requestState != null && episode.availability != Availability.AVAILABLE) {
                StatusChip(requestStateLabel(requestState), requestState.color)
            } else {
                StatusChip(
                    availabilityLabel(episode.availability),
                    if (episode.availability == Availability.AVAILABLE) {
                        AppColors.Discover.Green
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * A checkbox for selecting episodes, or empty space for one that can't be selected
 */
@Composable
private fun SelectionBox(
    selected: Boolean,
    selectable: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(20.dp)
                .then(
                    if (selectable || selected) {
                        Modifier.border(2.dp, LocalContentColor.current, RoundedCornerShape(4.dp))
                    } else {
                        Modifier
                    },
                ),
    ) {
        if (selected) {
            Text(
                text = stringResource(R.string.fa_check),
                fontFamily = FontAwesome,
                fontSize = 12.sp,
            )
        }
    }
}
