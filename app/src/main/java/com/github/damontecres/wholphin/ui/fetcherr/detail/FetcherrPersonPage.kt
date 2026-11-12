package com.github.damontecres.wholphin.ui.fetcherr.detail

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Card
import com.github.damontecres.wholphin.api.fetcherr.model.CreditCard
import com.github.damontecres.wholphin.api.fetcherr.model.PersonView
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.ScrollableDialog
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrCardRow
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrError
import com.github.damontecres.wholphin.ui.fetcherr.destination
import com.github.damontecres.wholphin.ui.fetcherr.loadFetcherrData
import com.github.damontecres.wholphin.ui.fetcherr.posterUrl
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel(assistedFactory = FetcherrPersonViewModel.Factory::class)
class FetcherrPersonViewModel
    @AssistedInject
    constructor(
        private val fetcherrRepository: FetcherrRepository,
        private val backdropService: BackdropService,
        val navigationManager: NavigationManager,
        @Assisted private val tmdbId: Int,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(tmdbId: Int): FetcherrPersonViewModel
        }

        private val _person = MutableStateFlow<DataLoadingState<PersonView>>(DataLoadingState.Pending)
        val person: StateFlow<DataLoadingState<PersonView>> = _person

        init {
            viewModelScope.launchIO { backdropService.clearBackdrop() }
            load()
        }

        fun load() {
            _person.value = DataLoadingState.Loading
            viewModelScope.launchIO {
                _person.value = loadFetcherrData { fetcherrRepository.call { it.titlesApi.person(tmdbId) } }
            }
        }
    }

fun CreditCard.toCard(): Card =
    Card(
        availability = availability,
        key = key,
        mediaType = mediaType,
        overview = overview,
        title = title,
        tmdbId = tmdbId,
        voteAverage = voteAverage,
        backdrop = backdrop,
        jellyfinItemId = jellyfinItemId,
        poster = poster,
        year = year,
    )

@Composable
fun FetcherrPersonPage(
    destination: Destination.FetcherrPerson,
    modifier: Modifier = Modifier,
    viewModel: FetcherrPersonViewModel =
        hiltViewModel<FetcherrPersonViewModel, FetcherrPersonViewModel.Factory>(
            key = "fetcherr_person_${destination.tmdbId}",
            creationCallback = { it.create(destination.tmdbId) },
        ),
) {
    val personState by viewModel.person.collectAsState()
    when (val state = personState) {
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
            PersonContent(
                person = state.data,
                onClickCard = { viewModel.navigationManager.navigateTo(it.destination()) },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PersonContent(
    person: PersonView,
    onClickCard: (Card) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showBiography by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }

    // Credits are newest first; collapse repeated titles (eg several crew jobs on one film)
    val cast = remember(person) { person.cast.distinctBy { it.key } }
    val castCards = remember(cast) { cast.map { it.toCard() } }
    val castRoles = remember(cast) { cast.associate { it.key to it.role } }
    val crew =
        remember(person) {
            person.crew
                .groupBy { it.key }
                .map { (_, credits) -> credits.first() to credits.joinToString(", ") { it.role } }
        }
    val crewCards = remember(crew) { crew.map { it.first.toCard() } }
    val crewRoles = remember(crew) { crew.associate { it.first.key to it.second } }
    // Focus starts on the biography, or else the first row of credits
    val focusOnCast = person.biography.isBlank()
    val focusOnCrew = focusOnCast && cast.isEmpty()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(top = HeaderUtils.topPadding),
            ) {
                ItemCardImage(
                    imageUrl = person.profile.posterUrl,
                    name = person.name,
                    showOverlay = false,
                    favorite = false,
                    watched = false,
                    unwatchedCount = 0,
                    watchedPercent = null,
                    numberOfVersions = -1,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .width(140.dp)
                            .aspectRatio(AspectRatios.TALL),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(.7f),
                ) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val details =
                        listOfNotNull(
                            person.knownForDepartment,
                            person.birthday?.let { stringResource(R.string.fetcherr_born, it) },
                            person.deathday?.let { stringResource(R.string.fetcherr_died, it) },
                            person.placeOfBirth,
                        )
                    if (details.isNotEmpty()) {
                        Text(
                            text = details.joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (person.biography.isNotBlank()) {
                        OverviewText(
                            overview = person.biography,
                            maxLines = 5,
                            onClick = { showBiography = true },
                            textBoxHeight = Dp.Unspecified,
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                    }
                }
            }
        }
        if (cast.isNotEmpty()) {
            item(key = "cast") {
                FetcherrCardRow(
                    title = stringResource(R.string.fetcherr_acting),
                    cards = castCards,
                    subtitle = { castRoles[it.key] },
                    onClickCard = { _, card -> onClickCard.invoke(card) },
                    modifier = if (focusOnCast) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
        }
        if (crew.isNotEmpty()) {
            item(key = "crew") {
                FetcherrCardRow(
                    title = stringResource(R.string.fetcherr_crew),
                    cards = crewCards,
                    subtitle = { crewRoles[it.key] },
                    onClickCard = { _, card -> onClickCard.invoke(card) },
                    modifier = if (focusOnCrew) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
        }
    }
    if (showBiography) {
        ScrollableDialog(onDismissRequest = { showBiography = false }) {
            item { Text(person.biography) }
        }
    }
}
