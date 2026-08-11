package com.github.damontecres.wholphin.util

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.model.MovieResult
import com.github.damontecres.wholphin.api.seerr.model.TvResult
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.model.CustomSliderQuery
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.services.SeerrSearchResult
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.discover.discoverGenreTitle
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

/**
 * A [RequestPager] for Seerr server queries
 */
class DiscoverRequestPager<T>(
    private val api: SeerrApi,
    val requestHandler: DiscoverRequestHandler<T>,
    val transform: suspend (T) -> DiscoverItem,
    scope: CoroutineScope,
    pageSize: Int = SEERR_PAGE_SIZE,
    cacheSize: Long = 16,
) : RequestPager<DiscoverItem>(scope, pageSize, cacheSize) {
    override suspend fun init(initialPosition: Int): DiscoverRequestPager<T> = super.init(initialPosition) as DiscoverRequestPager<T>

    override suspend fun fetchPage(
        pageNumber: Int,
        includeTotalCount: Boolean,
    ): QueryResult<DiscoverItem> {
        val result = requestHandler.execute(api, pageNumber + 1) // Seerr pages are 1-indexed
        val transformed = result.items.map { transform.invoke(it) }
        return QueryResult(transformed, result.totalCount)
    }
}

const val SEERR_PAGE_SIZE = 20

enum class DiscoverRequestType(
    @param:StringRes val stringRes: Int,
) {
    DISCOVER_TV(R.string.discover_tv),
    DISCOVER_MOVIES(R.string.discover_movies),
    TRENDING(R.string.trending),
    UPCOMING_TV(R.string.upcoming_tv),
    UPCOMING_MOVIES(R.string.upcoming_movies),

    /**
     * A custom slider configured on the Seerr server
     *
     * The title always comes from [com.github.damontecres.wholphin.ui.nav.Destination.DiscoverMoreResult.titleOverride],
     * so [stringRes] is only a fallback.
     */
    CUSTOM_SLIDER(R.string.more_to_explore),
    UNKNOWN(R.string.unknown),
}

@Serializable
sealed interface DiscoverPagerType {
    val title: StringProvider

    @Serializable
    data class RequestType(
        val type: DiscoverRequestType,
    ) : DiscoverPagerType {
        override val title: StringProvider = ResStringProvider(type.stringRes)
    }

    @Serializable
    data class Genre(
        val genreId: Int,
        val name: String,
        val type: SeerrItemType,
    ) : DiscoverPagerType {
        override val title: StringProvider = discoverGenreTitle(name, type)
    }
}

/**
 * Specifies how a [RequestPager] should prepare and execute API calls
 */
interface DiscoverRequestHandler<T> {
    suspend fun execute(
        api: SeerrApi,
        pageNumber: Int,
    ): QueryResult<T>
}

class DiscoverTvRequestHandler(
    val filter: DiscoverFilter = DiscoverFilter(),
) : DiscoverRequestHandler<TvResult> {
    override suspend fun execute(
        api: SeerrApi,
        pageNumber: Int,
    ): QueryResult<TvResult> =
        filter.discoverTv(api.api.searchApi, pageNumber).let {
            QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
        }
}

class DiscoverMovieRequestHandler(
    val filter: DiscoverFilter = DiscoverFilter(),
) : DiscoverRequestHandler<MovieResult> {
    override suspend fun execute(
        api: SeerrApi,
        pageNumber: Int,
    ): QueryResult<MovieResult> =
        filter.discoverMovies(api.api.searchApi, pageNumber).let {
            QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
        }
}

val TrendingRequestHandler =
    object : DiscoverRequestHandler<SeerrSearchResult> {
        override suspend fun execute(
            api: SeerrApi,
            pageNumber: Int,
        ): QueryResult<SeerrSearchResult> =
            api.api.searchApi.discoverTrendingGet(page = pageNumber).let {
                QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
            }
    }

val UpcomingTvRequestHandler =
    object : DiscoverRequestHandler<TvResult> {
        override suspend fun execute(
            api: SeerrApi,
            pageNumber: Int,
        ): QueryResult<TvResult> =
            api.api.searchApi.discoverTvUpcomingGet(page = pageNumber).let {
                QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
            }
    }

val UpcomingMovieRequestHandler =
    object : DiscoverRequestHandler<MovieResult> {
        override suspend fun execute(
            api: SeerrApi,
            pageNumber: Int,
        ): QueryResult<MovieResult> =
            api.api.searchApi.discoverMoviesUpcomingGet(page = pageNumber).let {
                QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
            }
    }

/**
 * Runs the query behind a custom discover slider configured on the Seerr server
 *
 * Each [CustomSliderQuery] variant hits a different endpoint and so returns a different model, hence
 * the [Any] item type. Pair this with [SeerrService.createDiscoverItem] as the pager's transform,
 * which dispatches on the runtime type of each result.
 */
class CustomSliderRequestHandler(
    val query: CustomSliderQuery,
) : DiscoverRequestHandler<Any> {
    override suspend fun execute(
        api: SeerrApi,
        pageNumber: Int,
    ): QueryResult<Any> {
        val searchApi = api.api.searchApi
        return when (query) {
            is CustomSliderQuery.Genre -> {
                if (query.itemType == SeerrItemType.TV) {
                    searchApi.discoverTvGet(page = pageNumber, genre = query.genre).let {
                        QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                    }
                } else {
                    searchApi.discoverMoviesGet(page = pageNumber, genre = query.genre).let {
                        QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                    }
                }
            }

            is CustomSliderQuery.Keyword -> {
                if (query.itemType == SeerrItemType.TV) {
                    searchApi.discoverTvGet(page = pageNumber, keywords = query.keywords).let {
                        QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                    }
                } else {
                    searchApi.discoverMoviesGet(page = pageNumber, keywords = query.keywords).let {
                        QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                    }
                }
            }

            is CustomSliderQuery.StreamingServices -> {
                if (query.itemType == SeerrItemType.TV) {
                    searchApi
                        .discoverTvGet(
                            page = pageNumber,
                            watchRegion = query.watchRegion,
                            watchProviders = query.watchProviders,
                        ).let {
                            QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                        }
                } else {
                    searchApi
                        .discoverMoviesGet(
                            page = pageNumber,
                            watchRegion = query.watchRegion,
                            watchProviders = query.watchProviders,
                        ).let {
                            QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                        }
                }
            }

            is CustomSliderQuery.Studio -> {
                searchApi
                    .discoverMoviesStudioStudioIdGet(
                        studioId = query.studioId,
                        page = pageNumber,
                    ).let {
                        QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                    }
            }

            is CustomSliderQuery.Network -> {
                searchApi
                    .discoverTvNetworkNetworkIdGet(
                        networkId = query.networkId,
                        page = pageNumber,
                    ).let {
                        QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                    }
            }

            is CustomSliderQuery.Search -> {
                searchApi.searchGet(query = query.query, page = pageNumber).let {
                    QueryResult<Any>(it.results.orEmpty(), it.totalResults ?: 0)
                }
            }
        }
    }
}
