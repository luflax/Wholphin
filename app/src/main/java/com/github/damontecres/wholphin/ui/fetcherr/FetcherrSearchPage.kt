package com.github.damontecres.wholphin.ui.fetcherr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.VoiceSearchButton
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.FetcherrPage
import com.github.damontecres.wholphin.util.FetcherrPager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class FetcherrSearchViewModel
    @Inject
    constructor(
        private val fetcherrRepository: FetcherrRepository,
        val navigationManager: NavigationManager,
        val voiceInputManager: VoiceInputManager,
    ) : ViewModel() {
        /** Null until something is searched */
        private val _results = MutableStateFlow<DataLoadingState<FetcherrPager<FetcherrGridItem>>?>(null)
        val results: StateFlow<DataLoadingState<FetcherrPager<FetcherrGridItem>>?> = _results

        var currentQuery: String = ""
            private set
        private var searchJob: Job? = null

        /**
         * Search for [query], unless it is already showing. [force] searches again anyway, eg on
         * the search key or Retry; a failed search is always repeated.
         */
        fun search(
            query: String,
            force: Boolean = false,
        ) {
            val trimmed = query.trim()
            if (!force && trimmed == currentQuery && _results.value !is DataLoadingState.Error) return
            currentQuery = trimmed
            searchJob?.cancel()
            if (trimmed.isBlank()) {
                _results.value = null
                return
            }
            _results.value = DataLoadingState.Loading
            searchJob =
                viewModelScope.launchIO {
                    _results.value =
                        loadFetcherrData {
                            FetcherrPager<FetcherrGridItem>(viewModelScope) { page ->
                                val result = fetcherrRepository.call { it.titlesApi.search(trimmed, page) }
                                FetcherrPage(result.items.map { it.toGridItem() }, result.totalResults)
                            }.init()
                        }
                }
        }
    }

/**
 * Search Fetcherr for movies, series and people
 */
@Composable
fun FetcherrSearchPage(
    positionCallback: (columns: Int, position: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FetcherrSearchViewModel = hiltViewModel(),
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val results by viewModel.results.collectAsState()
    var query by rememberSaveable { mutableStateOf(viewModel.currentQuery) }
    var immediateQuery by remember { mutableStateOf<String?>(null) }

    fun searchNow(text: String) {
        // Skip the debounce the text change would otherwise start
        if (text != query) immediateQuery = text
        query = text
        viewModel.search(text, force = true)
    }

    // Debounce typing, but search right away for the search key or voice input
    LaunchedEffect(query) {
        if (immediateQuery == query) {
            immediateQuery = null
        } else {
            delay(750L)
            viewModel.search(query)
        }
    }

    val textFieldFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { textFieldFocusRequester.tryRequestFocus("fetcherr_search") }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            var isSearchActive by remember { mutableStateOf(false) }
            var isTextFieldFocused by remember { mutableStateOf(false) }
            BackHandler(isTextFieldFocused) {
                if (isSearchActive) {
                    isSearchActive = false
                    keyboardController?.hide()
                } else {
                    focusManager.moveFocus(FocusDirection.Next)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .focusGroup()
                        .focusRestorer()
                        .focusRequester(textFieldFocusRequester),
            ) {
                VoiceSearchButton(
                    onSpeechResult = { spoken -> searchNow(spoken) },
                    voiceInputManager = viewModel.voiceInputManager,
                    modifier = Modifier.padding(end = 12.dp),
                )
                SearchEditTextBox(
                    value = query,
                    onValueChange = {
                        isSearchActive = true
                        query = it
                    },
                    onSearchClick = { searchNow(query) },
                    readOnly = !isSearchActive,
                    modifier =
                        Modifier
                            .onFocusChanged { state ->
                                isTextFieldFocused = state.isFocused
                                if (!state.isFocused) isSearchActive = false
                            }.onPreviewKeyEvent { event ->
                                val activate = event.key == Key.DirectionCenter || event.key == Key.Enter
                                if (event.type == KeyEventType.KeyUp && activate && !isSearchActive) {
                                    isSearchActive = true
                                    keyboardController?.show()
                                    true
                                } else {
                                    false
                                }
                            },
                )
            }
        }

        when (val state = results) {
            null -> {}

            DataLoadingState.Pending,
            DataLoadingState.Loading,
            -> {
                LoadingPage(Modifier.fillMaxSize(), focusEnabled = false)
            }

            is DataLoadingState.Error -> {
                FetcherrError(
                    exception = state.exception,
                    message = state.message,
                    onRetry = { viewModel.search(viewModel.currentQuery, force = true) },
                    requestFocus = false,
                )
            }

            is DataLoadingState.Success -> {
                if (state.data.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_results),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    )
                } else {
                    FetcherrCardGrid(
                        pager = state.data,
                        onClickCard = { viewModel.navigationManager.navigateTo(it.destination) },
                        positionCallback = positionCallback,
                        requestFocus = false,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
