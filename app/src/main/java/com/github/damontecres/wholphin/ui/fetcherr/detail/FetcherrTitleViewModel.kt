package com.github.damontecres.wholphin.ui.fetcherr.detail

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.fetcherr.model.Event
import com.github.damontecres.wholphin.api.fetcherr.model.NewRequest
import com.github.damontecres.wholphin.api.fetcherr.model.Quality
import com.github.damontecres.wholphin.api.fetcherr.model.QualityOption
import com.github.damontecres.wholphin.api.fetcherr.model.RatingsView
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.api.fetcherr.model.TitleRelease
import com.github.damontecres.wholphin.api.fetcherr.toFetcherrException
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FetcherrEvents
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.fetcherr.loadFetcherrData
import com.github.damontecres.wholphin.ui.fetcherr.request.FetcherrRequestsViewModel
import com.github.damontecres.wholphin.ui.fetcherr.titleKey
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.DataLoadingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * What a request dialog is for: the scope it requests and how to label it
 */
data class RequestTarget(
    val scope: Scope,
    val label: String,
    /** For the release picker of a series: the season & episode to search, if any */
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Shared by the movie, series & season pages: requesting, releases, qualities and ratings
 */
abstract class FetcherrTitleViewModel(
    protected val context: Context,
    fetcherrRepository: FetcherrRepository,
    fetcherrEvents: FetcherrEvents,
    val navigationManager: NavigationManager,
    protected val backdropService: BackdropService,
    val kind: TitleKind,
    val tmdbId: Int,
) : FetcherrRequestsViewModel(fetcherrRepository, fetcherrEvents, titleKey(kind, tmdbId)) {
    val key: String = titleKey(kind, tmdbId)

    private val _ratings = MutableStateFlow<RatingsView?>(null)
    val ratings: StateFlow<RatingsView?> = _ratings

    /** Null until loaded, so the request dialog doesn't change size while it's showing */
    private val _qualities = MutableStateFlow<List<QualityOption>?>(null)
    val qualities: StateFlow<List<QualityOption>?> = _qualities

    private val _releases = MutableStateFlow<DataLoadingState<List<TitleRelease>>>(DataLoadingState.Pending)
    val releases: StateFlow<DataLoadingState<List<TitleRelease>>> = _releases

    private var reloadJob: Job? = null

    override fun followedTitles(): Collection<String> = listOf(key)

    /** Load (or reload) the page's title */
    abstract fun load()

    /**
     * Reload once a burst of availability changes settles, since importing a season pack sends one
     * event per episode
     */
    protected fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob =
            viewModelScope.launchIO {
                delay(RELOAD_DEBOUNCE_MS)
                load()
            }
    }

    override fun onAvailabilityChanged(event: Event.AvailabilityChanged) = scheduleReload()

    /** Load ratings separately, since IMDb & Rotten Tomatoes go through OMDb and may be slow */
    protected fun loadRatings() {
        viewModelScope.launchIO {
            _ratings.value =
                try {
                    fetcherrRepository.call {
                        when (kind) {
                            TitleKind.MOVIE -> it.titlesApi.movieRatings(tmdbId)
                            TitleKind.SERIES -> it.titlesApi.seriesRatings(tmdbId)
                        }
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not load ratings for %s", key)
                    null
                }
        }
    }

    /** Load the qualities for the request dialog */
    fun loadQualities() {
        if (_qualities.value != null) return
        viewModelScope.launchIO {
            _qualities.value =
                try {
                    fetcherrRepository.qualities()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not load qualities")
                    emptyList()
                }
        }
    }

    /** Search releases for the manual picker */
    fun loadReleases(target: RequestTarget) {
        _releases.value = DataLoadingState.Loading
        viewModelScope.launchIO {
            _releases.value =
                loadFetcherrData {
                    fetcherrRepository.call {
                        when (kind) {
                            TitleKind.MOVIE -> it.titlesApi.movieReleases(tmdbId)
                            TitleKind.SERIES -> it.titlesApi.seriesReleases(tmdbId, target.season, target.episode)
                        }
                    }
                }
        }
    }

    /**
     * Request [scope], letting Fetcherr pick the release unless [releaseId] is given
     *
     * @param onExpired called when the picked release is no longer known to the server
     */
    fun request(
        scope: Scope,
        quality: Quality? = null,
        releaseId: String? = null,
        onExpired: () -> Unit = {},
    ) {
        viewModelScope.launchIO {
            try {
                val detail =
                    fetcherrRepository.call {
                        it.requestsApi.create(
                            NewRequest(
                                mediaType = kind,
                                tmdbId = tmdbId,
                                scope = scope,
                                quality = quality,
                                releaseId = releaseId,
                            ),
                        )
                    }
                upsert(detail)
                onRequested()
                showToast(context, context.getString(R.string.fetcherr_requested_toast), Toast.LENGTH_SHORT)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                val fetcherr = ex.toFetcherrException()
                if (fetcherr?.isReleaseExpired == true) {
                    showToast(context, context.getString(R.string.fetcherr_release_expired), Toast.LENGTH_LONG)
                    onExpired.invoke()
                } else {
                    Timber.e(ex, "Error requesting %s %s", key, scope)
                    showToast(context, fetcherr?.message ?: ex.localizedMessage ?: ex.toString())
                }
            }
        }
    }

    /** Called after a request was created, to refresh availability */
    protected open fun onRequested() = load()

    /** Submit the title's backdrop for the page background */
    protected fun submitBackdrop(imageUrl: String?) {
        viewModelScope.launchIO {
            backdropService.submit("fetcherr_$key", imageUrl)
        }
    }

    companion object {
        private const val RELOAD_DEBOUNCE_MS = 1_500L
    }
}
