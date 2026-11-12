package com.github.damontecres.wholphin.util

import kotlinx.coroutines.CoroutineScope

/** Every paginated Fetcherr endpoint answers with this many items per page */
const val FETCHERR_PAGE_SIZE = 20

/**
 * One page of a paginated Fetcherr endpoint
 */
data class FetcherrPage<T>(
    val items: List<T>,
    val totalResults: Int,
)

/**
 * A [RequestPager] for Fetcherr's paginated endpoints, whose pages are 1-based
 *
 * @param fetch loads the 1-based page number
 */
class FetcherrPager<T>(
    scope: CoroutineScope,
    private val fetch: suspend (page: Int) -> FetcherrPage<T>,
) : RequestPager<T>(scope, FETCHERR_PAGE_SIZE, cacheSize = 16) {
    override suspend fun init(initialPosition: Int): FetcherrPager<T> = super.init(initialPosition) as FetcherrPager<T>

    override suspend fun fetchPage(
        pageNumber: Int,
        includeTotalCount: Boolean,
    ): QueryResult<T> {
        val page = fetch.invoke(fetcherrPageNumber(pageNumber))
        return QueryResult(page.items, page.totalResults)
    }

    companion object {
        /** [RequestPager]'s 0-based page index to Fetcherr's 1-based page number */
        fun fetcherrPageNumber(pageIndex: Int): Int = pageIndex + 1
    }
}
