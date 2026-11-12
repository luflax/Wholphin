package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.api.fetcherr.FetcherrApiClient
import com.github.damontecres.wholphin.api.fetcherr.FetcherrException
import com.github.damontecres.wholphin.api.fetcherr.fetcherrCall
import com.github.damontecres.wholphin.api.fetcherr.model.QualityOption
import com.github.damontecres.wholphin.api.fetcherr.model.SystemStatus
import com.github.damontecres.wholphin.api.fetcherr.toFetcherrException
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.FetcherrSettings
import com.github.damontecres.wholphin.preferences.update
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cache
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Owns the app-wide connection to a Fetcherr server
 *
 * The URL & API key live in [AppPreferences.getFetcherrSettings], so every Jellyfin user of the
 * app shares them. Saving new settings reconnects automatically.
 */
@Singleton
class FetcherrRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val preferences: DataStore<AppPreferences>,
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
        @param:IoCoroutineScope private val scope: CoroutineScope,
    ) {
        private val cache: Cache by lazy {
            Cache(File(context.cacheDir, "fetcherr_http"), HTTP_CACHE_SIZE)
        }

        private val _connection = MutableStateFlow<FetcherrConnection>(FetcherrConnection.NotConfigured)
        val connection: StateFlow<FetcherrConnection> = _connection

        /** Whether Fetcherr is configured and reachable */
        val active: StateFlow<Boolean> =
            _connection
                .map { it is FetcherrConnection.Connected }
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, false)

        private val qualitiesMutex = Mutex()
        private var qualities: List<QualityOption>? = null

        /** Bumped to connect again with unchanged settings */
        private val reconnects = MutableStateFlow(0)

        /** How long to wait before retrying after [failures] failed attempts in a row */
        internal var retryDelayMillis: (failures: Int) -> Long = ::defaultRetryDelayMillis

        init {
            scope.launch {
                combine(
                    preferences.data
                        .map { it.fetcherrSettings }
                        .distinctUntilChanged(),
                    reconnects,
                ) { settings, _ -> settings }
                    .collectLatest { connectWithRetry(it) }
            }
        }

        /**
         * The client for the connected server
         *
         * @throws IllegalStateException if Fetcherr is not connected
         */
        val api: FetcherrApiClient
            get() =
                (_connection.value as? FetcherrConnection.Connected)?.api
                    ?: throw IllegalStateException("Fetcherr is not connected")

        /**
         * Connect, retrying with a backoff while the server can't be reached, eg when the TV starts
         * before the server. A rejected API key isn't retried, since only new settings can fix it.
         */
        private suspend fun connectWithRetry(settings: FetcherrSettings) {
            var failures = 0
            while (true) {
                val result = connect(settings)
                if (result !is FetcherrConnection.Error || result.isUnauthorized) return
                failures++
                delay(retryDelayMillis(failures))
            }
        }

        private suspend fun connect(settings: FetcherrSettings): FetcherrConnection {
            qualitiesMutex.withLock { qualities = null }
            if (settings.url.isBlank() || settings.apiKey.isBlank()) {
                _connection.value = FetcherrConnection.NotConfigured
                return FetcherrConnection.NotConfigured
            }
            // Keep showing an earlier error while retrying, rather than flickering through Connecting
            if (_connection.value !is FetcherrConnection.Error) {
                _connection.value = FetcherrConnection.Connecting(settings.url)
            }
            val api = createClient(settings.url, settings.apiKey)
            val result =
                try {
                    val status = fetcherrCall { api.systemApi.status() }
                    Timber.i("Connected to Fetcherr %s at %s", status.version, api.baseUrl)
                    FetcherrConnection.Connected(api, status)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not connect to Fetcherr at %s", api.baseUrl)
                    FetcherrConnection.Error(settings.url, ex.toFetcherrException() ?: ex)
                }
            _connection.value = result
            return result
        }

        /** Connect again with the saved settings, eg after the server was down */
        fun reconnect() {
            reconnects.update { it + 1 }
        }

        /**
         * Check [url] & [apiKey] without saving them
         *
         * @return the server's status
         * @throws Exception if the server is unreachable or rejects the key
         */
        suspend fun testConnection(
            url: String,
            apiKey: String,
        ): SystemStatus {
            val api = createClient(url, apiKey, useCache = false)
            return fetcherrCall { api.systemApi.status() }
        }

        /** Save the connection settings, which reconnects */
        suspend fun save(
            url: String,
            apiKey: String,
        ) {
            preferences.updateData {
                it.update {
                    fetcherrSettings =
                        FetcherrSettings
                            .newBuilder()
                            .setUrl(normalizeFetcherrUrl(url))
                            .setApiKey(apiKey.trim())
                            .build()
                }
            }
            // Unchanged settings don't trigger a new connection on their own
            reconnect()
        }

        /** Forget the server */
        suspend fun remove() {
            preferences.updateData { it.update { clearFetcherrSettings() } }
            try {
                cache.evictAll()
            } catch (ex: Exception) {
                Timber.w(ex, "Error clearing Fetcherr cache")
            }
        }

        /**
         * Mark the connection as failed after an API call was rejected, such as when the API key
         * was regenerated on the server
         */
        fun onUnauthorized(ex: Exception) {
            val current = _connection.value
            if (current is FetcherrConnection.Connected) {
                _connection.value = FetcherrConnection.Error(current.api.baseUrl, ex)
            }
        }

        /**
         * Call the connected server, converting HTTP errors to
         * [com.github.damontecres.wholphin.api.fetcherr.FetcherrException] and dropping the
         * connection if the API key was rejected
         */
        suspend fun <T> call(block: suspend (FetcherrApiClient) -> T): T {
            val client = api
            return try {
                fetcherrCall { block.invoke(client) }
            } catch (ex: FetcherrException) {
                if (ex.isUnauthorized) onUnauthorized(ex)
                throw ex
            }
        }

        /** The qualities Fetcherr detects, best first. Cached for the connection. */
        suspend fun qualities(): List<QualityOption> =
            qualitiesMutex.withLock {
                qualities ?: call { it.searchApi.qualities() }.also { qualities = it }
            }

        private fun createClient(
            url: String,
            apiKey: String,
            useCache: Boolean = true,
        ) = FetcherrApiClient(
            baseUrl = normalizeFetcherrUrl(url),
            apiKey = apiKey.trim(),
            okHttpClient = okHttpClient,
            cache = if (useCache) cache else null,
        )

        companion object {
            private const val HTTP_CACHE_SIZE = 20L * 1024 * 1024
            private const val BASE_RETRY_MS = 5_000L
            private const val MAX_RETRY_MS = 5 * 60_000L

            /** 5s, 10s, 20s… capped at 5 minutes */
            fun defaultRetryDelayMillis(failures: Int): Long =
                min(MAX_RETRY_MS, BASE_RETRY_MS shl (failures - 1).coerceIn(0, 10))
        }
    }

/**
 * Trim the URL, default to http, and drop any trailing slash, since the generated client appends
 * paths starting with `/api`
 */
fun normalizeFetcherrUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        trimmed
    } else {
        "http://$trimmed"
    }
}

sealed interface FetcherrConnection {
    data object NotConfigured : FetcherrConnection

    data class Connecting(
        val url: String,
    ) : FetcherrConnection

    data class Connected(
        val api: FetcherrApiClient,
        val status: SystemStatus,
    ) : FetcherrConnection

    data class Error(
        val url: String,
        val exception: Exception,
    ) : FetcherrConnection {
        val isUnauthorized: Boolean get() = (exception as? FetcherrException)?.isUnauthorized == true
    }
}
