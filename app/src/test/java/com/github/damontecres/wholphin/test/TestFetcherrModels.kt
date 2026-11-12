package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.fetcherr.infrastructure.Serializer
import com.github.damontecres.wholphin.api.fetcherr.model.Availability
import com.github.damontecres.wholphin.api.fetcherr.model.Coverage
import com.github.damontecres.wholphin.api.fetcherr.model.DiscoverPageView
import com.github.damontecres.wholphin.api.fetcherr.model.Event
import com.github.damontecres.wholphin.api.fetcherr.model.ListSource
import com.github.damontecres.wholphin.api.fetcherr.model.NewRequest
import com.github.damontecres.wholphin.api.fetcherr.model.PageSearchResult
import com.github.damontecres.wholphin.api.fetcherr.model.Quality
import com.github.damontecres.wholphin.api.fetcherr.model.RequestDetail
import com.github.damontecres.wholphin.api.fetcherr.model.RequestState
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.api.fetcherr.model.SearchResult
import com.github.damontecres.wholphin.api.fetcherr.model.TimeWindow
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.services.FetcherrEvents
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert
import org.junit.Test

/**
 * Decoding of Fetcherr's responses, focusing on the polymorphic types that are hand-written in
 * FetcherrPolymorphic.kt instead of generated
 *
 * The fixtures follow the Fetcherr v0.4.0 OpenAPI spec.
 */
class TestFetcherrModels {
    private val json = Serializer.kotlinxSerializationJson

    private inline fun <reified T> read(name: String): T {
        val stream =
            javaClass.classLoader?.getResourceAsStream(name)
                ?: throw IllegalStateException("Missing fixture $name")
        return stream.use { json.decodeFromStream(it) }
    }

    @Test
    fun `discover page decodes lists, sources and cards`() {
        val page = read<DiscoverPageView>("fetcherr_discover_page.json")
        Assert.assertEquals("discover", page.id)
        Assert.assertEquals(3, page.lists.size)

        val trending = page.lists[0]
        Assert.assertEquals(ListSource.Trending(media = null, window = TimeWindow.WEEK), trending.source)
        Assert.assertEquals(140, trending.cards.totalResults)
        val movie = trending.cards.items[0]
        Assert.assertEquals(TitleKind.MOVIE, movie.mediaType)
        Assert.assertEquals(Availability.AVAILABLE, movie.availability)
        Assert.assertEquals("https://img/w342/p.jpg", movie.poster?.medium)
        val series = trending.cards.items[1]
        Assert.assertEquals(Availability.PARTIALLY_AVAILABLE, series.availability)
        Assert.assertNull(series.poster)
        Assert.assertNull(series.year)

        Assert.assertEquals(ListSource.Discover(TitleKind.SERIES), page.lists[1].source)
        Assert.assertEquals("TMDB timed out", page.lists[1].error)
        Assert.assertEquals(ListSource.Upcoming, page.lists[2].source)
    }

    @Test
    fun `search results decode titles and people`() {
        val page = read<PageSearchResult>("fetcherr_search.json")
        val title = page.items[0] as SearchResult.Title
        Assert.assertEquals("movie:438631", title.key)
        Assert.assertEquals(Availability.DOWNLOADING, title.card.availability)

        val person = page.items[1] as SearchResult.Person
        Assert.assertEquals("Denis Villeneuve", person.person.name)
        Assert.assertEquals(1, person.person.knownFor.size)
    }

    @Test
    fun `search results encode back with their result_type`() {
        val page = read<PageSearchResult>("fetcherr_search.json")
        val encoded = json.encodeToJsonElement(SearchResult.serializer(), page.items[1]).jsonObject
        Assert.assertEquals("person", encoded["result_type"]?.jsonPrimitive?.content)
        Assert.assertEquals("Denis Villeneuve", encoded["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `request detail decodes scope, coverage and torrents`() {
        val detail = read<RequestDetail>("fetcherr_request_detail.json")
        Assert.assertEquals(Scope.Episodes(season = 1, episodes = listOf(7, 8)), detail.scope)
        Assert.assertEquals(RequestState.DOWNLOADING, detail.state)
        Assert.assertEquals(Quality.WEB_1080P, detail.quality)
        val torrent = detail.torrents.single()
        Assert.assertEquals(Coverage.SeasonPack(seasons = listOf(1), complete = false), torrent.coverage)
        Assert.assertEquals(3600L, torrent.etaSecs)
        Assert.assertEquals(1, detail.files.single().episode)
    }

    @Test
    fun `new request encodes its scope with a type discriminator`() {
        val movie = NewRequest(mediaType = TitleKind.MOVIE, tmdbId = 603, scope = Scope.Movie)
        val movieJson = json.encodeToJsonElement(NewRequest.serializer(), movie).jsonObject
        Assert.assertEquals("movie", movieJson["media_type"]?.jsonPrimitive?.content)
        Assert.assertEquals("movie", movieJson["scope"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val season =
            NewRequest(
                mediaType = TitleKind.SERIES,
                tmdbId = 1399,
                scope = Scope.Season(2),
                quality = Quality.WEB_2160P,
            )
        val seasonJson = json.encodeToJsonElement(NewRequest.serializer(), season).jsonObject
        val scope = seasonJson["scope"]!!.jsonObject
        Assert.assertEquals("season", scope["type"]?.jsonPrimitive?.content)
        Assert.assertEquals("2", scope["season"]?.jsonPrimitive?.content)
        Assert.assertEquals("web_2160p", seasonJson["quality"]?.jsonPrimitive?.content)
    }

    @Test
    fun `every event type decodes`() {
        val request =
            """{"id":7,"key":"movie:603","media_type":"movie","tmdb_id":603,"title":"The Matrix",""" +
                """"scope":{"type":"movie"},"state":"searching","progress":0.0,""" +
                """"created_at":"2026-11-10T00:00:00Z","updated_at":"2026-11-10T00:00:00Z"}"""
        val updated = FetcherrEvents.decodeEvent("""{"type":"request_updated","request":$request}""")
        Assert.assertTrue(updated is Event.RequestUpdated)
        Assert.assertEquals(RequestState.SEARCHING, (updated as Event.RequestUpdated).request.state)

        val progress =
            FetcherrEvents.decodeEvent(
                """{"type":"request_progress","request_id":7,"key":"movie:603","progress":0.5,"dl_speed":1024,"eta_secs":60}""",
            )
        Assert.assertEquals(Event.RequestProgress(7, "movie:603", 0.5, 1024, 60), progress)

        val progressNoEta =
            FetcherrEvents.decodeEvent(
                """{"type":"request_progress","request_id":7,"key":"movie:603","progress":0.5,"dl_speed":0}""",
            )
        Assert.assertNull((progressNoEta as Event.RequestProgress).etaSecs)

        val deleted = FetcherrEvents.decodeEvent("""{"type":"request_deleted","request_id":7,"key":"movie:603"}""")
        Assert.assertEquals(Event.RequestDeleted(7, "movie:603"), deleted)

        val availability =
            FetcherrEvents.decodeEvent(
                """{"type":"availability_changed","key":"series:1399","availability":"available",""" +
                    """"season":1,"episode":2,"jellyfin_item_id":"abc"}""",
            )
        Assert.assertEquals(
            Event.AvailabilityChanged("series:1399", Availability.AVAILABLE, 1, 2, "abc"),
            availability,
        )
    }

    @Test
    fun `unknown event types are ignored`() {
        Assert.assertNull(FetcherrEvents.decodeEvent("""{"type":"something_new","foo":1}"""))
        Assert.assertNull(FetcherrEvents.decodeEvent("not json"))
    }
}
