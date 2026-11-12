package com.github.damontecres.wholphin.ui.fetcherr.request

import androidx.compose.runtime.Immutable
import com.github.damontecres.wholphin.api.fetcherr.model.Event
import com.github.damontecres.wholphin.api.fetcherr.model.RequestDetail
import com.github.damontecres.wholphin.api.fetcherr.model.RequestState
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary

/**
 * Live download numbers for a request, from `request_progress` events
 */
@Immutable
data class RequestProgress(
    val progress: Double,
    val dlSpeed: Long,
    val etaSecs: Long?,
)

/**
 * The requests shown on a page, kept current by [applyEvent]
 *
 * @param key only requests for this title key are kept, or every request when null
 */
@Immutable
data class LiveRequests(
    val requests: List<RequestSummary> = emptyList(),
    val progress: Map<Long, RequestProgress> = emptyMap(),
    val key: String? = null,
) {
    /** The progress to show: the live value if one arrived since the summary was loaded */
    fun progressOf(request: RequestSummary): RequestProgress =
        progress[request.id] ?: RequestProgress(request.progress, 0, null)

    /** Replace the requests, eg after reloading the page, keeping live progress of the remaining ones */
    fun withRequests(requests: List<RequestSummary>): LiveRequests {
        val ids = requests.mapTo(HashSet()) { it.id }
        return copy(requests = requests, progress = progress.filterKeys { it in ids })
    }

    /** Insert or replace one request, eg the result of creating, pausing or retrying it */
    fun upsert(request: RequestSummary): LiveRequests {
        if (key != null && request.key != key) return this
        val index = requests.indexOfFirst { it.id == request.id }
        val updated =
            if (index >= 0) {
                requests.toMutableList().apply { set(index, request) }
            } else {
                listOf(request) + requests
            }
        // A new summary carries the current progress, so drop the older live value
        return copy(requests = updated, progress = progress - request.id)
    }

    fun remove(requestId: Long): LiveRequests =
        copy(
            requests = requests.filterNot { it.id == requestId },
            progress = progress - requestId,
        )
}

/**
 * Apply a WebSocket [event]. Availability changes are not handled here, since they need the
 * title's details to be reloaded.
 */
fun LiveRequests.applyEvent(event: Event): LiveRequests =
    when (event) {
        is Event.RequestUpdated -> {
            upsert(event.request)
        }

        is Event.RequestProgress -> {
            if (requests.any { it.id == event.requestId }) {
                copy(
                    progress =
                        progress +
                            (event.requestId to RequestProgress(event.progress, event.dlSpeed, event.etaSecs)),
                )
            } else {
                this
            }
        }

        is Event.RequestDeleted -> {
            remove(event.requestId)
        }

        is Event.AvailabilityChanged -> {
            this
        }
    }

/**
 * Keep the requests in [states] (all of them when null), newest first, eg after an event moved a
 * request out of the Downloads tab's filter or added one from another page
 */
fun LiveRequests.filtered(states: Set<RequestState>?): LiveRequests =
    withRequests(
        requests
            .filter { states == null || it.state in states }
            .sortedByDescending { it.createdAt },
    )

/**
 * The requests grouped by title, ordered by each title's newest request
 */
fun List<RequestSummary>.groupedByTitle(): List<List<RequestSummary>> = groupBy { it.key }.values.toList()

fun RequestDetail.toSummary(): RequestSummary =
    RequestSummary(
        createdAt = createdAt,
        id = id,
        key = key,
        mediaType = mediaType,
        progress = progress,
        scope = scope,
        state = state,
        title = title,
        tmdbId = tmdbId,
        updatedAt = updatedAt,
        error = error,
        poster = poster,
        quality = quality,
        year = year,
    )
