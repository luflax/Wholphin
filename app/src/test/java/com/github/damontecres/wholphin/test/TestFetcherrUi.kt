package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.fetcherr.model.Coverage
import com.github.damontecres.wholphin.api.fetcherr.model.Quality
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.services.FetcherrEvents
import com.github.damontecres.wholphin.services.normalizeFetcherrUrl
import com.github.damontecres.wholphin.ui.fetcherr.episodesLabel
import com.github.damontecres.wholphin.ui.fetcherr.formatProgress
import com.github.damontecres.wholphin.ui.fetcherr.request.qualityLabel
import com.github.damontecres.wholphin.ui.fetcherr.toScope
import com.github.damontecres.wholphin.util.FetcherrPager
import org.junit.Assert
import org.junit.Test

/**
 * The pure helpers behind the Fetcherr screens
 */
class TestFetcherrUi {
    @Test
    fun `urls are normalized`() {
        Assert.assertEquals("http://homeassistant.local:8734", normalizeFetcherrUrl(" homeassistant.local:8734/ "))
        Assert.assertEquals("https://fetch.example.com", normalizeFetcherrUrl("https://fetch.example.com/"))
        Assert.assertEquals("HTTP://host", normalizeFetcherrUrl("HTTP://host"))
    }

    @Test
    fun `episode labels`() {
        Assert.assertEquals("S02 E03", episodesLabel(2, listOf(3)))
        Assert.assertEquals("S02 E03–E05", episodesLabel(2, listOf(5, 3, 4)))
        Assert.assertEquals("S02 E01, E04", episodesLabel(2, listOf(4, 1)))
        Assert.assertEquals("S02", episodesLabel(2, emptyList()))
    }

    @Test
    fun `a release's coverage becomes the request scope`() {
        Assert.assertEquals(Scope.Movie, Coverage.Movie.toScope(null))
        Assert.assertEquals(Scope.Season(1), Coverage.SeasonPack(listOf(1), false).toScope(1))
        // A multi-season pack is requested for the season it was picked for
        Assert.assertEquals(Scope.Season(2), Coverage.SeasonPack(listOf(1, 2, 3), false).toScope(2))
        // Picked for a season it doesn't cover: fall back to its first season
        Assert.assertEquals(Scope.Season(1), Coverage.SeasonPack(listOf(1, 2), false).toScope(5))
        // A complete-series pack lists no seasons
        Assert.assertEquals(Scope.Season(3), Coverage.SeasonPack(emptyList(), true).toScope(3))
        Assert.assertEquals(Scope.Episodes(1, listOf(7)), Coverage.Episodes(1, listOf(7)).toScope(1))
    }

    @Test
    fun `quality labels`() {
        Assert.assertEquals("2160p Remux", qualityLabel(Quality.REMUX_2160P))
        Assert.assertEquals("1080p BluRay", qualityLabel(Quality.BLURAY_1080P))
        Assert.assertEquals("720p WEB", qualityLabel(Quality.WEB_720P))
        Assert.assertEquals("1080p HDTV", qualityLabel(Quality.HDTV_1080P))
        Assert.assertEquals("DVD", qualityLabel(Quality.DVD))
        Assert.assertEquals("UNKNOWN", qualityLabel(Quality.UNKNOWN))
    }

    @Test
    fun `progress`() {
        Assert.assertEquals("62%", formatProgress(0.62))
        Assert.assertEquals("100%", formatProgress(1.4))
    }

    @Test
    fun `pager pages are 1-based`() {
        Assert.assertEquals(1, FetcherrPager.fetcherrPageNumber(0))
        Assert.assertEquals(3, FetcherrPager.fetcherrPageNumber(2))
    }

    @Test
    fun `events reconnect with a capped exponential backoff`() {
        Assert.assertEquals(1_000L, FetcherrEvents.retryDelayMillis(1))
        Assert.assertEquals(2_000L, FetcherrEvents.retryDelayMillis(2))
        Assert.assertEquals(32_000L, FetcherrEvents.retryDelayMillis(6))
        Assert.assertEquals(60_000L, FetcherrEvents.retryDelayMillis(7))
        Assert.assertEquals(60_000L, FetcherrEvents.retryDelayMillis(50))
    }
}
