package com.github.damontecres.wholphin.api.fetcherr

import com.github.damontecres.wholphin.api.fetcherr.infrastructure.ApiClient
import com.github.damontecres.wholphin.api.fetcherr.infrastructure.ClientError
import com.github.damontecres.wholphin.api.fetcherr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.fetcherr.infrastructure.Serializer
import com.github.damontecres.wholphin.api.fetcherr.infrastructure.ServerError
import com.github.damontecres.wholphin.api.fetcherr.infrastructure.ServerException
import com.github.damontecres.wholphin.api.fetcherr.model.ErrorBody
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * A client for a Fetcherr server
 *
 * Every request carries the API key. [cache] lets OkHttp handle the `ETag`/`If-None-Match` round
 * trip of the discover endpoints, so an unchanged layout answers with a 304 served from disk.
 */
class FetcherrApiClient(
    val baseUrl: String,
    val apiKey: String,
    okHttpClient: OkHttpClient,
    cache: Cache?,
) {
    val client: OkHttpClient =
        okHttpClient
            .newBuilder()
            .apply {
                if (cache != null) cache(cache)
                // First, so that the shared client's own interceptors see the key too
                interceptors().add(
                    0,
                    Interceptor {
                        Timber.d("FetcherrApiClient: ${it.request().method} ${it.request().url}")
                        it.proceed(
                            it
                                .request()
                                .newBuilder()
                                .header(API_KEY_HEADER, apiKey)
                                .build(),
                        )
                    },
                )
            }.build()

    private fun <T : ApiClient> create(initializer: (String, Call.Factory) -> T): Lazy<T> =
        lazy {
            initializer.invoke(baseUrl, client)
        }

    val discoverApi by create(::DiscoverApi)
    val titlesApi by create(::TitlesApi)
    val requestsApi by create(::RequestsApi)
    val searchApi by create(::SearchApi)
    val systemApi by create(::SystemApi)

    companion object {
        const val API_KEY_HEADER = "X-Api-Key"
    }
}

/**
 * An error answered by Fetcherr, with its `{"error": {"code", "message"}}` body parsed when present
 */
class FetcherrException(
    val statusCode: Int,
    val code: String?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val isUnauthorized: Boolean get() = statusCode == 401

    /** The release chosen for a request is no longer cached by the server; search again */
    val isReleaseExpired: Boolean get() = statusCode == 410

    /** A service Fetcherr depends on (TMDB, Prowlarr, qBittorrent) isn't configured */
    val isNotConfigured: Boolean get() = statusCode == 409
}

/**
 * Convert the generated client's exceptions into a [FetcherrException], or return null if [this]
 * is not an HTTP error from Fetcherr
 */
fun Throwable.toFetcherrException(): FetcherrException? {
    val (statusCode, body) =
        when (this) {
            is FetcherrException -> return this
            is ClientException -> statusCode to (response as? ClientError<*>)?.body as? String
            is ServerException -> statusCode to (response as? ServerError<*>)?.body as? String
            else -> return null
        }
    val error =
        body?.let {
            try {
                Serializer.kotlinxSerializationJson.decodeFromString<ErrorBody>(it).error
            } catch (_: Exception) {
                null
            }
        }
    return FetcherrException(
        statusCode = statusCode,
        code = error?.code,
        message = error?.message ?: message ?: "HTTP $statusCode",
        cause = this,
    )
}

/**
 * Run [block], rethrowing Fetcherr's HTTP errors as [FetcherrException]
 */
suspend inline fun <T> fetcherrCall(crossinline block: suspend () -> T): T =
    try {
        block.invoke()
    } catch (ex: ClientException) {
        throw ex.toFetcherrException()!!
    } catch (ex: ServerException) {
        throw ex.toFetcherrException()!!
    }
