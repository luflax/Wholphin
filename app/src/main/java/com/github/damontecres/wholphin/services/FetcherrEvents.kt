package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.fetcherr.FetcherrApiClient
import com.github.damontecres.wholphin.api.fetcherr.FetcherrException
import com.github.damontecres.wholphin.api.fetcherr.infrastructure.Serializer
import com.github.damontecres.wholphin.api.fetcherr.model.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Live updates from Fetcherr's `GET /api/ws/events` WebSocket
 */
@Singleton
class FetcherrEvents
    @Inject
    constructor(
        private val fetcherrRepository: FetcherrRepository,
    ) {
        /** How long to wait before reconnecting after [failures] failed attempts in a row */
        internal var retryDelay: (failures: Int) -> Long = ::retryDelayMillis

        /**
         * Follow requests and titles until collection stops
         *
         * Opens a WebSocket filtered to [titles] (keys such as `movie:603`) and [requests] (ids).
         * With neither, the server sends every request's events. If the connection drops, it
         * reconnects with an exponential backoff. The server re-sends a `request_updated` for every
         * matching request (every unfinished one without a filter) on connect, so nothing is lost
         * while disconnected.
         */
        fun subscribe(
            titles: Collection<String> = emptyList(),
            requests: Collection<Long> = emptyList(),
        ): Flow<Event> =
            channelFlow {
                var failures = 0
                while (isActive) {
                    val api =
                        (
                            fetcherrRepository.connection.first {
                                it is FetcherrConnection.Connected
                            } as FetcherrConnection.Connected
                        ).api
                    val closed = CompletableDeferred<Throwable?>()
                    val socket =
                        createClient(api).newWebSocket(
                            buildRequest(api, titles, requests),
                            object : WebSocketListener() {
                                override fun onOpen(
                                    webSocket: WebSocket,
                                    response: Response,
                                ) {
                                    Timber.v("Fetcherr events connected: titles=%s, requests=%s", titles, requests)
                                    failures = 0
                                }

                                override fun onMessage(
                                    webSocket: WebSocket,
                                    text: String,
                                ) {
                                    // Blocks OkHttp's reader thread rather than dropping an event
                                    decodeEvent(text)?.let { channel.trySendBlocking(it) }
                                }

                                override fun onClosing(
                                    webSocket: WebSocket,
                                    code: Int,
                                    reason: String,
                                ) {
                                    webSocket.close(code, null)
                                }

                                override fun onClosed(
                                    webSocket: WebSocket,
                                    code: Int,
                                    reason: String,
                                ) {
                                    closed.complete(null)
                                }

                                override fun onFailure(
                                    webSocket: WebSocket,
                                    t: Throwable,
                                    response: Response?,
                                ) {
                                    if (response?.code == 401) {
                                        fetcherrRepository.onUnauthorized(
                                            FetcherrException(401, null, "Fetcherr rejected the API key", t),
                                        )
                                    }
                                    closed.complete(t)
                                }
                            },
                        )
                    try {
                        closed.await()?.let { Timber.w(it, "Fetcherr events disconnected") }
                    } finally {
                        socket.cancel()
                    }
                    failures++
                    delay(retryDelay(failures))
                }
            }

        private fun createClient(api: FetcherrApiClient) =
            api.client
                .newBuilder()
                .cache(null)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()

        private fun buildRequest(
            api: FetcherrApiClient,
            titles: Collection<String>,
            requests: Collection<Long>,
        ): Request {
            val url =
                "${api.baseUrl}/api/ws/events"
                    .toHttpUrl()
                    .newBuilder()
                    .apply {
                        if (titles.isNotEmpty()) addQueryParameter("titles", titles.joinToString(","))
                        if (requests.isNotEmpty()) addQueryParameter("requests", requests.joinToString(","))
                    }.build()
            return Request
                .Builder()
                .url(url)
                .header(FetcherrApiClient.API_KEY_HEADER, api.apiKey)
                .build()
        }

        companion object {
            private const val BASE_RETRY_MS = 1_000L
            private const val MAX_RETRY_MS = 60_000L

            /** 1s, 2s, 4s… capped at a minute */
            fun retryDelayMillis(failures: Int): Long = min(MAX_RETRY_MS, BASE_RETRY_MS shl min(failures - 1, 16).coerceAtLeast(0))

            /** Decode one frame, or null if it is not an event this app understands */
            fun decodeEvent(text: String): Event? =
                try {
                    Serializer.kotlinxSerializationJson.decodeFromString(Event.serializer(), text)
                } catch (ex: Exception) {
                    Timber.w(ex, "Ignoring Fetcherr event: %s", text)
                    null
                }
        }
    }
