package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.seerr.model.DiscoverSlider
import com.github.damontecres.wholphin.data.model.CustomSliderQuery
import com.github.damontecres.wholphin.data.model.DiscoverSliderType
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.data.model.toCustomSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun slider(
    type: Int,
    data: String? = "1234",
    title: String? = "My slider",
    enabled: Boolean = true,
    id: Int? = 1,
    order: Int? = 0,
) = DiscoverSlider(

    type = type,
    title = title,
    enabled = enabled,
    data = data,
    id = id,
    order = order,
)

class TestDiscoverSliderType {
    @Test
    fun `Maps known types`() {
        assertEquals(DiscoverSliderType.RECENTLY_ADDED, DiscoverSliderType.from(1))
        assertEquals(DiscoverSliderType.NETWORKS, DiscoverSliderType.from(12))
        assertEquals(DiscoverSliderType.TMDB_MOVIE_KEYWORD, DiscoverSliderType.from(13))
        assertEquals(DiscoverSliderType.TMDB_MOVIE_GENRE, DiscoverSliderType.from(14))
        assertEquals(DiscoverSliderType.TMDB_TV_KEYWORD, DiscoverSliderType.from(15))
        assertEquals(DiscoverSliderType.TMDB_TV_GENRE, DiscoverSliderType.from(16))
        assertEquals(DiscoverSliderType.TMDB_SEARCH, DiscoverSliderType.from(17))
        assertEquals(DiscoverSliderType.TMDB_STUDIO, DiscoverSliderType.from(18))
        assertEquals(DiscoverSliderType.TMDB_NETWORK, DiscoverSliderType.from(19))
        assertEquals(
            DiscoverSliderType.TMDB_TV_STREAMING_SERVICES,
            DiscoverSliderType.from(21),
        )
    }

    @Test
    fun `Unknown and null types fall back`() {
        assertEquals(DiscoverSliderType.UNKNOWN, DiscoverSliderType.from(99))
        assertEquals(DiscoverSliderType.UNKNOWN, DiscoverSliderType.from(0))
        assertEquals(DiscoverSliderType.UNKNOWN, DiscoverSliderType.from(null))
    }

    @Test
    fun `Built in flag covers types 1 through 12`() {
        val builtIn = DiscoverSliderType.entries.filter { it.builtIn }
        assertEquals(12, builtIn.size)
        assertTrue(builtIn.all { it.type in 1..12 })
        assertTrue(DiscoverSliderType.entries.none { it.builtIn && it.type >= 13 })
    }
}

class TestCustomDiscoverSlider {
    @Test
    fun `Movie genre slider`() {
        val result = slider(type = 14, data = "28,12").toCustomSlider()
        assertEquals(
            CustomSliderQuery.Genre(SeerrItemType.MOVIE, "28,12"),
            result?.query,
        )
        assertEquals("My slider", result?.title)
        assertEquals(DiscoverSliderType.TMDB_MOVIE_GENRE, result?.type)
    }

    @Test
    fun `Tv genre slider`() {
        val result = slider(type = 16, data = "10765").toCustomSlider()
        assertEquals(CustomSliderQuery.Genre(SeerrItemType.TV, "10765"), result?.query)
    }

    @Test
    fun `Keyword sliders keep the raw payload`() {
        // TMDB treats , as AND and | as OR, so the payload must not be re-parsed and re-joined
        val movie = slider(type = 13, data = "9715|180547").toCustomSlider()
        assertEquals(
            CustomSliderQuery.Keyword(SeerrItemType.MOVIE, "9715|180547"),
            movie?.query,
        )

        val tv = slider(type = 15, data = "9715,180547").toCustomSlider()
        assertEquals(
            CustomSliderQuery.Keyword(SeerrItemType.TV, "9715,180547"),
            tv?.query,
        )
    }

    @Test
    fun `Studio and network sliders`() {
        assertEquals(
            CustomSliderQuery.Studio("420"),
            slider(type = 18, data = "420").toCustomSlider()?.query,
        )
        assertEquals(
            CustomSliderQuery.Network("213"),
            slider(type = 19, data = "213").toCustomSlider()?.query,
        )
    }

    @Test
    fun `Studio and network sliders imply their item type`() {
        assertEquals(
            SeerrItemType.MOVIE,
            slider(type = 18, data = "420").toCustomSlider()?.query?.itemType,
        )
        assertEquals(
            SeerrItemType.TV,
            slider(type = 19, data = "213").toCustomSlider()?.query?.itemType,
        )
    }

    @Test
    fun `Search slider`() {
        val result = slider(type = 17, data = "star wars").toCustomSlider()
        assertEquals(CustomSliderQuery.Search("star wars"), result?.query)
        assertEquals(SeerrItemType.UNKNOWN, result?.query?.itemType)
    }

    @Test
    fun `Streaming services slider splits region from providers`() {
        val result = slider(type = 20, data = "US,8").toCustomSlider()
        assertEquals(
            CustomSliderQuery.StreamingServices(SeerrItemType.MOVIE, "US", "8"),
            result?.query,
        )
    }

    @Test
    fun `Streaming services slider keeps multiple providers intact`() {
        val result = slider(type = 21, data = "GB,8|9|337").toCustomSlider()
        assertEquals(
            CustomSliderQuery.StreamingServices(SeerrItemType.TV, "GB", "8|9|337"),
            result?.query,
        )
    }

    @Test
    fun `Streaming services slider only splits on the first comma`() {
        val result = slider(type = 20, data = "US,8,9").toCustomSlider()
        assertEquals(
            CustomSliderQuery.StreamingServices(SeerrItemType.MOVIE, "US", "8,9"),
            result?.query,
        )
    }

    @Test
    fun `Streaming services slider needs both segments`() {
        assertNull(slider(type = 20, data = "US").toCustomSlider())
        assertNull(slider(type = 20, data = "US,").toCustomSlider())
        assertNull(slider(type = 20, data = ",8").toCustomSlider())
    }

    @Test
    fun `Built in sliders are dropped`() {
        (1..12).forEach { type ->
            assertNull(
                "Built-in type $type should not map to a custom slider",
                slider(type = type).toCustomSlider(),
            )
        }
    }

    @Test
    fun `Unknown types are dropped`() {
        assertNull(slider(type = 99).toCustomSlider())
    }

    @Test
    fun `Disabled sliders are dropped`() {
        assertNull(slider(type = 14, enabled = false).toCustomSlider())
    }

    @Test
    fun `Sliders missing required values are dropped`() {
        assertNull(slider(type = 14, id = null).toCustomSlider())
        assertNull(slider(type = 14, title = null).toCustomSlider())
        assertNull(slider(type = 14, title = "  ").toCustomSlider())
        assertNull(slider(type = 14, data = null).toCustomSlider())
        assertNull(slider(type = 14, data = "").toCustomSlider())
    }

    @Test
    fun `Sliders sort by order`() {
        val sliders =
            listOf(
                slider(type = 14, id = 1, order = 5, data = "a"),
                slider(type = 16, id = 2, order = 1, data = "b"),
                slider(type = 17, id = 3, order = 3, data = "c"),
            ).mapNotNull { it.toCustomSlider() }
                .sortedBy { it.order }

        assertEquals(listOf(2, 3, 1), sliders.map { it.id })
    }

    @Test
    fun `Sliders without an order sort last in API order`() {
        val sliders =
            listOf(
                slider(type = 14, id = 1, order = null, data = "a"),
                slider(type = 16, id = 2, order = 7, data = "b"),
                slider(type = 17, id = 3, order = null, data = "c"),
            ).mapNotNull { it.toCustomSlider() }
                .sortedBy { it.order }

        assertEquals(listOf(2, 1, 3), sliders.map { it.id })
    }
}
