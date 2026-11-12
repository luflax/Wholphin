package com.github.damontecres.wholphin.ui.fetcherr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.FetcherrPage
import com.github.damontecres.wholphin.util.FetcherrPager
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel(assistedFactory = FetcherrListViewModel.Factory::class)
class FetcherrListViewModel
    @AssistedInject
    constructor(
        private val fetcherrRepository: FetcherrRepository,
        val navigationManager: NavigationManager,
        @Assisted private val listId: String,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(listId: String): FetcherrListViewModel
        }

        private val _pager = MutableStateFlow<DataLoadingState<FetcherrPager<FetcherrGridItem>>>(DataLoadingState.Pending)
        val pager: StateFlow<DataLoadingState<FetcherrPager<FetcherrGridItem>>> = _pager

        init {
            load()
        }

        fun load() {
            _pager.value = DataLoadingState.Loading
            viewModelScope.launchIO {
                _pager.value =
                    loadFetcherrData {
                        FetcherrPager<FetcherrGridItem>(viewModelScope) { page ->
                            val result = fetcherrRepository.call { it.discoverApi.list(listId, page) }
                            FetcherrPage(result.items.map { FetcherrGridItem.Title(it) }, result.totalResults)
                        }.init()
                    }
            }
        }
    }

/**
 * Every card of one discover list, as a grid
 */
@Composable
fun FetcherrListGrid(
    destination: Destination.FetcherrList,
    modifier: Modifier = Modifier,
    viewModel: FetcherrListViewModel =
        hiltViewModel<FetcherrListViewModel, FetcherrListViewModel.Factory>(
            key = "fetcherr_list_${destination.listId}",
            creationCallback = { it.create(destination.listId) },
        ),
) {
    val pagerState by viewModel.pager.collectAsState()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = destination.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            (pagerState as? DataLoadingState.Success)?.data?.size?.let { count ->
                Text(
                    text = pluralStringResource(R.plurals.fetcherr_titles, count, count),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (val state = pagerState) {
            DataLoadingState.Pending,
            DataLoadingState.Loading,
            -> {
                LoadingPage(Modifier.fillMaxSize())
            }

            is DataLoadingState.Error -> {
                FetcherrError(
                    exception = state.exception,
                    message = state.message,
                    onRetry = viewModel::load,
                )
            }

            is DataLoadingState.Success -> {
                FetcherrCardGrid(
                    pager = state.data,
                    onClickCard = { viewModel.navigationManager.navigateTo(it.destination) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * A grid of titles & people, shared by "View all" and search
 */
@Composable
fun FetcherrCardGrid(
    pager: List<FetcherrGridItem?>,
    onClickCard: (FetcherrGridItem) -> Unit,
    modifier: Modifier = Modifier,
    positionCallback: ((columns: Int, position: Int) -> Unit)? = null,
    requestFocus: Boolean = true,
) {
    val gridFocusRequester = remember { FocusRequester() }
    if (requestFocus) {
        LaunchedEffect(Unit) { gridFocusRequester.tryRequestFocus() }
    }
    CardGrid(
        pager = pager,
        onClickItem = { _, item -> onClickCard.invoke(item) },
        onLongClickItem = { _, _ -> },
        onClickPlay = { _, _ -> },
        letterPosition = { 0 },
        gridFocusRequester = gridFocusRequester,
        showJumpButtons = false,
        showLetterButtons = false,
        cardContent = { (item, _, onClick, onLongClick, _, mod) ->
            FetcherrGridCard(
                item = item,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = mod,
                width = Dp.Unspecified,
            )
        },
        columns = 6,
        positionCallback = positionCallback,
        modifier = modifier,
    )
}
