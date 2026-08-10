package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.api.seerr.model.DiscoverSlider
import kotlinx.serialization.Serializable

/**
 * The type of a discover slider configured on the Seerr server
 *
 * Source: https://github.com/seerr-team/seerr/blob/develop/server/constants/discover.ts
 *
 * Note the ordering is not intuitive: [TMDB_MOVIE_GENRE] comes before [TMDB_TV_KEYWORD].
 */
@Serializable
enum class DiscoverSliderType(
    val type: Int,
) {
    RECENTLY_ADDED(1),
    RECENT_REQUESTS(2),
    PLEX_WATCHLIST(3),
    TRENDING(4),
    POPULAR_MOVIES(5),
    MOVIE_GENRES(6),
    UPCOMING_MOVIES(7),
    STUDIOS(8),
    POPULAR_TV(9),
    TV_GENRES(10),
    UPCOMING_TV(11),
    NETWORKS(12),
    TMDB_MOVIE_KEYWORD(13),
    TMDB_MOVIE_GENRE(14),
    TMDB_TV_KEYWORD(15),
    TMDB_TV_GENRE(16),
    TMDB_SEARCH(17),
    TMDB_STUDIO(18),
    TMDB_NETWORK(19),
    TMDB_MOVIE_STREAMING_SERVICES(20),
    TMDB_TV_STREAMING_SERVICES(21),
    UNKNOWN(-1),
    ;

    /**
     * Whether this is one of Seerr's built-in sliders
     *
     * Built-in sliders have no admin-provided title and are currently rendered by the fixed rows in
     * `SeerrDiscoverPage` rather than driven by the server config.
     */
    val builtIn: Boolean get() = type in RECENTLY_ADDED.type..NETWORKS.type

    companion object {
        fun from(type: Int?): DiscoverSliderType = entries.firstOrNull { it.type == type } ?: UNKNOWN
    }
}

/**
 * How to fetch the items for a [CustomDiscoverSlider]
 *
 * Each value holds Seerr's raw `data` payload verbatim rather than parsing it into ids. TMDB treats
 * `,` and `|` as AND/OR within these parameters, so re-parsing and re-joining would silently change
 * the query the server admin configured.
 */
@Serializable
sealed interface CustomSliderQuery {
    /**
     * Whether the slider queries movies or TV. [SeerrItemType.UNKNOWN] for [Search], which is mixed.
     */
    val itemType: SeerrItemType

    @Serializable
    data class Genre(
        override val itemType: SeerrItemType,
        val genre: String,
    ) : CustomSliderQuery

    @Serializable
    data class Keyword(
        override val itemType: SeerrItemType,
        val keywords: String,
    ) : CustomSliderQuery

    @Serializable
    data class StreamingServices(
        override val itemType: SeerrItemType,
        val watchRegion: String,
        val watchProviders: String,
    ) : CustomSliderQuery

    @Serializable
    data class Studio(
        val studioId: String,
    ) : CustomSliderQuery {
        override val itemType: SeerrItemType get() = SeerrItemType.MOVIE
    }

    @Serializable
    data class Network(
        val networkId: String,
    ) : CustomSliderQuery {
        override val itemType: SeerrItemType get() = SeerrItemType.TV
    }

    @Serializable
    data class Search(
        val query: String,
    ) : CustomSliderQuery {
        override val itemType: SeerrItemType get() = SeerrItemType.UNKNOWN
    }
}

/**
 * A custom discover slider configured by an admin on the Seerr server
 *
 * Unlike built-in sliders, these always carry an admin-provided [title] and a `data` payload
 * describing what to query.
 *
 * @see DiscoverSlider.toCustomSlider
 */
@Serializable
data class CustomDiscoverSlider(
    val id: Int,
    val order: Int,
    val type: DiscoverSliderType,
    val title: String,
    val query: CustomSliderQuery,
)

/**
 * Sentinel order for sliders whose `order` the server did not return, so they sort last while
 * keeping the order the API listed them in.
 */
private const val UNORDERED = Int.MAX_VALUE

/**
 * Convert a slider from the Seerr API into a [CustomDiscoverSlider]
 *
 * Returns null when the slider cannot be rendered as a custom row: it is disabled, it is a built-in
 * (or unrecognized) type, or it is missing the id, title, or `data` payload it needs.
 */
fun DiscoverSlider.toCustomSlider(): CustomDiscoverSlider? {
    if (!enabled) return null
    val sliderId = id ?: return null
    val sliderType = DiscoverSliderType.from(type)
    val sliderTitle = title?.takeIf { it.isNotBlank() } ?: return null
    val payload = data?.takeIf { it.isNotBlank() } ?: return null

    val query =
        when (sliderType) {
            DiscoverSliderType.TMDB_MOVIE_GENRE ->
                CustomSliderQuery.Genre(SeerrItemType.MOVIE, payload)

            DiscoverSliderType.TMDB_TV_GENRE ->
                CustomSliderQuery.Genre(SeerrItemType.TV, payload)

            DiscoverSliderType.TMDB_MOVIE_KEYWORD ->
                CustomSliderQuery.Keyword(SeerrItemType.MOVIE, payload)

            DiscoverSliderType.TMDB_TV_KEYWORD ->
                CustomSliderQuery.Keyword(SeerrItemType.TV, payload)

            DiscoverSliderType.TMDB_STUDIO ->
                CustomSliderQuery.Studio(payload)

            DiscoverSliderType.TMDB_NETWORK ->
                CustomSliderQuery.Network(payload)

            DiscoverSliderType.TMDB_SEARCH ->
                CustomSliderQuery.Search(payload)

            DiscoverSliderType.TMDB_MOVIE_STREAMING_SERVICES ->
                streamingQuery(SeerrItemType.MOVIE, payload)

            DiscoverSliderType.TMDB_TV_STREAMING_SERVICES ->
                streamingQuery(SeerrItemType.TV, payload)

            else -> null
        } ?: return null

    return CustomDiscoverSlider(
        id = sliderId,
        order = order ?: UNORDERED,
        type = sliderType,
        title = sliderTitle,
        query = query,
    )
}

/**
 * Parse a streaming services payload, which Seerr stores as `<watchRegion>,<watchProviders>`
 *
 * The providers segment may itself contain `|` separators, so only the first comma is split on.
 */
private fun streamingQuery(
    itemType: SeerrItemType,
    payload: String,
): CustomSliderQuery? {
    val parts = payload.split(",", limit = 2)
    val watchRegion = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
    val watchProviders = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    return CustomSliderQuery.StreamingServices(itemType, watchRegion, watchProviders)
}
