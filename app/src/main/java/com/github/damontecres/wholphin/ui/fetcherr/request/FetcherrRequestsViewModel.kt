package com.github.damontecres.wholphin.ui.fetcherr.request

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.api.fetcherr.FetcherrApiClient
import com.github.damontecres.wholphin.api.fetcherr.model.Event
import com.github.damontecres.wholphin.api.fetcherr.model.RequestDetail
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary
import com.github.damontecres.wholphin.services.FetcherrEvents
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared by pages that show requests: keeps [live] current from Fetcherr's event stream while
 * the page is visible, and implements the request actions
 */
abstract class FetcherrRequestsViewModel(
    protected val fetcherrRepository: FetcherrRepository,
    private val fetcherrEvents: FetcherrEvents,
    titleKey: String?,
) : ViewModel(),
    RequestActions {
    protected val mutableLive = MutableStateFlow(LiveRequests(key = titleKey))
    val live: StateFlow<LiveRequests> = mutableLive

    private var eventsJob: Job? = null

    /** The title keys to follow */
    protected abstract fun followedTitles(): Collection<String>

    /** The request ids to follow, in addition to [followedTitles] */
    protected open fun followedRequests(): Collection<Long> = emptyList()

    /** Follow every request's events instead of [followedTitles] & [followedRequests] */
    protected open val followsEverything: Boolean = false

    /** Start following live events, eg when the page resumes */
    fun startEvents() {
        eventsJob?.cancel()
        val titles = if (followsEverything) emptyList() else followedTitles()
        val requests = if (followsEverything) emptyList() else followedRequests()
        if (!followsEverything && titles.isEmpty() && requests.isEmpty()) return
        eventsJob =
            viewModelScope.launchIO {
                fetcherrEvents.subscribe(titles, requests).collect(::onEvent)
            }
    }

    /** Stop following live events, eg when the page pauses */
    fun stopEvents() {
        eventsJob?.cancel()
        eventsJob = null
    }

    protected open fun onEvent(event: Event) {
        mutableLive.update { it.applyEvent(event) }
        if (event is Event.AvailabilityChanged) onAvailabilityChanged(event)
    }

    /** A title, season or episode appeared in or left the media server */
    protected open fun onAvailabilityChanged(event: Event.AvailabilityChanged) {}

    override fun pause(request: RequestSummary) = update { it.requestsApi.pause(request.id) }

    override fun resume(request: RequestSummary) = update { it.requestsApi.resume(request.id) }

    override fun retry(request: RequestSummary) = update { it.requestsApi.retry(request.id) }

    override fun remove(
        request: RequestSummary,
        deleteFiles: Boolean,
    ) {
        viewModelScope.launchIO(ExceptionHandler(autoToast = true)) {
            fetcherrRepository.call { it.requestsApi.delete(request.id, deleteFiles) }
            mutableLive.update { it.remove(request.id) }
            onRequestRemoved(request)
        }
    }

    protected open fun onRequestRemoved(request: RequestSummary) {}

    /** Record a request returned by the server, eg after creating it */
    protected fun upsert(detail: RequestDetail) {
        mutableLive.update { it.upsert(detail.toSummary()) }
    }

    private fun update(block: suspend (FetcherrApiClient) -> RequestDetail) {
        viewModelScope.launchIO(ExceptionHandler(autoToast = true)) {
            upsert(fetcherrRepository.call(block))
        }
    }

    override fun onCleared() {
        stopEvents()
        super.onCleared()
    }
}
