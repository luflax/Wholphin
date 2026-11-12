package com.github.damontecres.wholphin.test

import android.content.Context
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.api.fetcherr.FetcherrApiClient
import com.github.damontecres.wholphin.api.fetcherr.infrastructure.Serializer
import com.github.damontecres.wholphin.api.fetcherr.model.ConfiguredServices
import com.github.damontecres.wholphin.api.fetcherr.model.PageRequestSummary
import com.github.damontecres.wholphin.api.fetcherr.model.RequestState
import com.github.damontecres.wholphin.api.fetcherr.model.RequestSummary
import com.github.damontecres.wholphin.api.fetcherr.model.Scope
import com.github.damontecres.wholphin.api.fetcherr.model.SystemStatus
import com.github.damontecres.wholphin.api.fetcherr.model.TitleKind
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.FetcherrSettings
import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.services.FetcherrRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

const val FAKE_URL = "http://fetcherr.test"
const val FAKE_KEY = "secret"

/**
 * [AppPreferences] kept in memory
 */
class FakePreferences(
    initial: AppPreferences = AppPreferences.getDefaultInstance(),
) : DataStore<AppPreferences> {
    private val state = MutableStateFlow(initial)
    override val data = state

    override suspend fun updateData(transform: suspend (t: AppPreferences) -> AppPreferences): AppPreferences =
        transform(state.value).also { state.value = it }

    companion object {
        fun configured(
            url: String = FAKE_URL,
            apiKey: String = FAKE_KEY,
        ) = FakePreferences(
            AppPreferences
                .newBuilder()
                .setFetcherrSettings(
                    FetcherrSettings
                        .newBuilder()
                        .setUrl(url)
                        .setApiKey(apiKey),
                ).build(),
        )
    }
}

/**
 * A Fetcherr server answering from memory, as an OkHttp interceptor
 */
class FakeFetcherrServer : Interceptor {
    /** False to fail every call as if the server were unreachable */
    @Volatile var up = true

    @Volatile var apiKey = FAKE_KEY

    /** Every request served, as "METHOD /path?query" */
    val calls = CopyOnWriteArrayList<String>()

    /** The server's requests, newest first */
    val requests = CopyOnWriteArrayList<RequestSummary>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    /** False for a version from before pairing, without `/api/auth/pair` */
    @Volatile var pairingApi = true

    /** Hosts where something other than Fetcherr answers */
    val otherHosts = CopyOnWriteArraySet<String>()

    /** Pairings by secret */
    val pairings = ConcurrentHashMap<String, FakePairing>()
    private val pairingCount = AtomicInteger()

    /** The pairing the TV is currently showing */
    val latestPairing: FakePairing? get() = pairings.values.maxByOrNull { it.number }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        calls.add("${request.method} ${request.url.encodedPath}" + (request.url.encodedQuery?.let { "?$it" } ?: ""))
        if (!up) throw IOException("Fetcherr is down")
        if (request.url.host in otherHosts) return respond(request, 404, "<html>Not found</html>")
        pairingResponse(request)?.let { return it }
        if (request.header(FetcherrApiClient.API_KEY_HEADER) != apiKey) {
            return respond(request, 401, """{"error":{"code":"unauthorized","message":"Invalid API key"}}""")
        }
        val path = request.url.encodedPath
        return when {
            path == "/api/system/status" -> {
                respond(request, 200, json(SystemStatus.serializer(), STATUS))
            }

            path == "/api/requests" && request.method == "GET" -> {
                val state = request.url.queryParameter("state")
                val page = request.url.queryParameter("page")?.toInt() ?: 1
                val matching = requests.filter { state == null || it.state.value == state }
                val totalPages = ceil(matching.size / PAGE_SIZE.toDouble()).toInt()
                val items = matching.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
                respond(
                    request,
                    200,
                    json(PageRequestSummary.serializer(), PageRequestSummary(items, page, totalPages, matching.size)),
                )
            }

            path == "/api/titles/search" -> {
                respond(request, 200, """{"items":[],"page":1,"total_pages":0,"total_results":0}""")
            }

            path.startsWith("/api/requests/") && request.method == "DELETE" -> {
                val id = path.removePrefix("/api/requests/").toLong()
                requests.removeIf { it.id == id }
                respond(request, 204, "")
            }

            else -> {
                respond(request, 404, """{"error":{"code":"not_found","message":"Not found"}}""")
            }
        }
    }

    /**
     * The pairing endpoints, which answer without an API key, as in Fetcherr 0.5.0: there is no
     * way to withdraw a code, `202` means waiting, and the key is handed out once
     */
    private fun pairingResponse(request: Request): Response? {
        val path = request.url.encodedPath
        if (!path.startsWith("/api/auth/pair")) return null
        val notFound = """{"error":{"code":"not_found","message":"Not found"}}"""
        if (!pairingApi) return respond(request, 404, notFound)
        return when {
            path == "/api/auth/pair" && request.method == "POST" -> {
                if (pairings.values.count { it.status == "pending" } >= MAX_PENDING) {
                    return respond(request, 429, """{"error":{"code":"too_many","message":"Too many pairings in progress"}}""")
                }
                val number = pairingCount.incrementAndGet()
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                val pairing = FakePairing(number, "K7Q%03d".format(number), "secret$number", body)
                pairings[pairing.secret] = pairing
                respond(
                    request,
                    201,
                    """{"code":"${pairing.code}","secret":"${pairing.secret}","expires":"2026-11-12T20:00:00Z",""" +
                        """"expires_in_secs":300,"poll_interval_secs":0}""",
                )
            }

            request.method != "GET" -> {
                respond(request, 405, "")
            }

            else -> {
                val pairing = pairings[path.substringAfterLast('/')]
                when (pairing?.status) {
                    "pending" -> {
                        respond(request, 202, """{"status":"pending","expires":"2026-11-12T20:00:00Z"}""")
                    }

                    "approved" -> {
                        pairing.status = "collected"
                        respond(request, 200, """{"api_key":"$apiKey","device_id":${pairing.number}}""")
                    }

                    else -> {
                        respond(request, 404, notFound)
                    }
                }
            }
        }
    }

    private fun <T> json(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ) = Serializer.kotlinxSerializationJson.encodeToString(serializer, value)

    private fun respond(
        request: Request,
        code: Int,
        body: String,
    ) = Response
        .Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("HTTP $code")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    companion object {
        const val PAGE_SIZE = 20

        /** Pairings waiting at once before Fetcherr answers 429 */
        const val MAX_PENDING = 3
        val STATUS = SystemStatus(ConfiguredServices(true, true, true, true, true), "0.4.0")
    }
}

/**
 * A code shown on the TV
 *
 * @param request the body the TV sent
 */
class FakePairing(
    val number: Int,
    val code: String,
    val secret: String,
    val request: String,
) {
    /** pending, approved (in the web UI), collected (by the TV) or expired */
    @Volatile var status = "pending"
}

fun fakeRequest(
    id: Long,
    state: RequestState = RequestState.DOWNLOADING,
    tmdbId: Int = id.toInt(),
    kind: TitleKind = TitleKind.MOVIE,
    scope: Scope = Scope.Movie,
) = RequestSummary(
    // Larger ids are newer, so sorting newest first matches the server's order
    createdAt = "2026-11-10T00:00:00.%06dZ".format(id),
    id = id,
    key = "${kind.value}:$tmdbId",
    mediaType = kind,
    progress = 0.0,
    scope = scope,
    state = state,
    title = "Title $tmdbId",
    tmdbId = tmdbId,
    updatedAt = "2026-11-10T00:00:00Z",
)

/** A repository talking to [server], with its retries sped up */
fun fakeRepository(
    server: FakeFetcherrServer,
    preferences: FakePreferences,
    scope: CoroutineScope,
): FetcherrRepository {
    val cacheDir = Files.createTempDirectory("fetcherr").toFile()
    val context = mockk<Context> { every { this@mockk.cacheDir } returns cacheDir }
    return FetcherrRepository(context, preferences, server.client, scope).apply {
        retryDelayMillis = { 20L }
    }
}

/** Wait for the connection to reach a state matching [predicate] */
suspend fun StateFlow<FetcherrConnection>.await(predicate: (FetcherrConnection) -> Boolean): FetcherrConnection =
    withTimeout(10_000) { first(predicate) }
