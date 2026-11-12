package com.github.damontecres.wholphin.ui.fetcherr

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.PageTab
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabbedPage
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class FetcherrHomeViewModel
    @Inject
    constructor(
        private val fetcherrRepository: FetcherrRepository,
        private val backdropService: BackdropService,
    ) : ViewModel() {
        private val _pages = MutableStateFlow<DataLoadingState<List<PageTab>>>(DataLoadingState.Pending)
        val pages: StateFlow<DataLoadingState<List<PageTab>>> = _pages

        init {
            load()
        }

        fun load() {
            _pages.value = DataLoadingState.Loading
            viewModelScope.launchIO {
                backdropService.clearBackdrop()
                _pages.value = loadFetcherrData { fetcherrRepository.call { it.discoverApi.pages() } }
            }
        }
    }

/**
 * The Fetcherr section: one tab per discover page configured on the server, then Search and
 * Downloads
 */
@Composable
fun FetcherrHomePage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: FetcherrHomeViewModel = hiltViewModel(),
) {
    val pagesState by viewModel.pages.collectAsState()
    when (val state = pagesState) {
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
            FetcherrTabs(
                preferences = preferences,
                pages = state.data,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun FetcherrTabs(
    preferences: UserPreferences,
    pages: List<PageTab>,
    modifier: Modifier = Modifier,
) {
    val tabs =
        remember(pages) {
            pages.map { TabDetails(StringStringProvider(it.name)) } +
                listOf(TabDetails(R.string.search), TabDetails(R.string.fetcherr_downloads))
        }
    val searchIndex = pages.size
    val downloadsIndex = pages.size + 1
    var showHeader by rememberSaveable { mutableStateOf(true) }

    TabbedPage(
        // Includes the tab count, so a layout change on the server doesn't restore a stale index
        itemId = "${NavDrawerItem.Fetcherr.id}-${tabs.size}",
        tabs = tabs,
        modifier = modifier,
        showTabs = showHeader,
    ) { tabIndex, tabDetails ->
        val contentModifier =
            Modifier
                .fillMaxSize()
                .focusRequester(tabDetails.contentFocusRequester)
        LaunchedEffect(tabIndex) { showHeader = true }
        when (tabIndex) {
            searchIndex -> {
                FetcherrSearchPage(
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier = contentModifier,
                )
            }

            downloadsIndex -> {
                FetcherrDownloadsPage(
                    focusRequesterOnEmpty = tabDetails.tabFocusRequester,
                    modifier = contentModifier,
                )
            }

            else -> {
                pages.getOrNull(tabIndex)?.let { page ->
                    FetcherrDiscoverPage(
                        preferences = preferences,
                        pageId = page.id,
                        onRowFocused = { row -> showHeader = row < 1 },
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}
