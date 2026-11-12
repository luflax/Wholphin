package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.fetcherr.model.Availability
import com.github.damontecres.wholphin.api.fetcherr.model.Event
import com.github.damontecres.wholphin.api.fetcherr.model.RequestState
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.ui.fetcherr.request.LiveRequests
import com.github.damontecres.wholphin.ui.fetcherr.request.RequestProgress
import com.github.damontecres.wholphin.ui.fetcherr.request.applyEvent
import com.github.damontecres.wholphin.ui.fetcherr.request.filtered
import com.github.damontecres.wholphin.ui.fetcherr.request.groupedByTitle
import org.junit.Assert
import org.junit.Test

/**
 * Applying Fetcherr's WebSocket events to the requests shown on a page
 */
class TestFetcherrLiveRequests {
    private fun request(
        id: Long,
        key: String = "movie:603",
        state: RequestState = RequestState.DOWNLOADING,
        progress: Double = 0.1,
    ) = RequestSummary(
        createdAt = "2026-11-10T00:00:00Z",
        id = id,
        key = key,
        mediaType = TitleKind.MOVIE,
        progress = progress,
        scope = Scope.Movie,
        state = state,
        title = "The Matrix",
        tmdbId = 603,
        updatedAt = "2026-11-10T00:00:00Z",
    )

    @Test
    fun `progress updates a known request`() {
        val live = LiveRequests(listOf(request(1)), key = "movie:603")
        val updated = live.applyEvent(Event.RequestProgress(1, "movie:603", 0.6, 2048, 30))
        Assert.assertEquals(RequestProgress(0.6, 2048, 30), updated.progressOf(updated.requests.single()))
    }

    @Test
    fun `progress for an unknown request is ignored`() {
        val live = LiveRequests(listOf(request(1)), key = "movie:603")
        val updated = live.applyEvent(Event.RequestProgress(2, "movie:603", 0.6, 2048, 30))
        Assert.assertEquals(live, updated)
    }

    @Test
    fun `without live progress the summary's progress is used`() {
        val live = LiveRequests(listOf(request(1, progress = 0.25)))
        Assert.assertEquals(RequestProgress(0.25, 0, null), live.progressOf(live.requests.single()))
    }

    @Test
    fun `an update replaces the request and drops its stale live progress`() {
        val live =
            LiveRequests(listOf(request(1)), key = "movie:603")
                .applyEvent(Event.RequestProgress(1, "movie:603", 0.9, 10, 5))
        val updated =
            live.applyEvent(Event.RequestUpdated(request(1, state = RequestState.IMPORTING, progress = 1.0)))
        val request = updated.requests.single()
        Assert.assertEquals(RequestState.IMPORTING, request.state)
        Assert.assertEquals(RequestProgress(1.0, 0, null), updated.progressOf(request))
    }

    @Test
    fun `a new request for the title is added first`() {
        val live = LiveRequests(listOf(request(1)), key = "movie:603")
        val updated = live.applyEvent(Event.RequestUpdated(request(2, state = RequestState.SEARCHING)))
        Assert.assertEquals(listOf(2L, 1L), updated.requests.map { it.id })
    }

    @Test
    fun `requests for another title are ignored`() {
        val live = LiveRequests(listOf(request(1)), key = "movie:603")
        val updated = live.applyEvent(Event.RequestUpdated(request(3, key = "movie:604")))
        Assert.assertEquals(listOf(1L), updated.requests.map { it.id })
    }

    @Test
    fun `without a key every request is kept`() {
        val live = LiveRequests(listOf(request(1)))
        val updated = live.applyEvent(Event.RequestUpdated(request(3, key = "movie:604")))
        Assert.assertEquals(listOf(3L, 1L), updated.requests.map { it.id })
    }

    @Test
    fun `a deleted request is removed`() {
        val live =
            LiveRequests(listOf(request(1), request(2)))
                .applyEvent(Event.RequestProgress(2, "movie:603", 0.5, 1, null))
        val updated = live.applyEvent(Event.RequestDeleted(2, "movie:603"))
        Assert.assertEquals(listOf(1L), updated.requests.map { it.id })
        Assert.assertTrue(updated.progress.isEmpty())
    }

    @Test
    fun `availability changes leave the requests alone`() {
        val live = LiveRequests(listOf(request(1)))
        val updated = live.applyEvent(Event.AvailabilityChanged("movie:603", Availability.AVAILABLE))
        Assert.assertEquals(live, updated)
    }

    @Test
    fun `reloading keeps live progress only for remaining requests`() {
        val live =
            LiveRequests(listOf(request(1), request(2)))
                .applyEvent(Event.RequestProgress(1, "movie:603", 0.5, 1, null))
                .applyEvent(Event.RequestProgress(2, "movie:603", 0.7, 1, null))
        val reloaded = live.withRequests(listOf(request(2)))
        Assert.assertEquals(setOf(2L), reloaded.progress.keys)
    }

    @Test
    fun `filtering drops requests that left the filter and sorts newest first`() {
        val live =
            LiveRequests(
                listOf(
                    request(1, state = RequestState.DOWNLOADING).copy(createdAt = "2026-11-01T00:00:00Z"),
                    request(2, state = RequestState.AVAILABLE).copy(createdAt = "2026-11-02T00:00:00Z"),
                    request(3, state = RequestState.SEARCHING).copy(createdAt = "2026-11-03T00:00:00Z"),
                ),
            ).applyEvent(Event.RequestProgress(1, "movie:603", 0.5, 1, null))
        val active = live.filtered(setOf(RequestState.DOWNLOADING, RequestState.SEARCHING))
        Assert.assertEquals(listOf(3L, 1L), active.requests.map { it.id })
        Assert.assertEquals(setOf(1L), active.progress.keys)
        Assert.assertEquals(listOf(3L, 2L, 1L), live.filtered(null).requests.map { it.id })
    }

    @Test
    fun `requests are grouped by title in order of each title's newest request`() {
        val requests =
            listOf(
                request(5, key = "series:1"),
                request(4, key = "movie:603"),
                request(3, key = "series:1"),
                request(2, key = "movie:604"),
            )
        Assert.assertEquals(
            listOf(listOf(5L, 3L), listOf(4L), listOf(2L)),
            requests.groupedByTitle().map { group -> group.map { it.id } },
        )
    }
}
