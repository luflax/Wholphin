package com.github.damontecres.wholphin.ui.setup.fetcherr

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.api.fetcherr.FetcherrException
import com.github.damontecres.wholphin.api.fetcherr.FetcherrPairingClient
import com.github.damontecres.wholphin.api.fetcherr.FetcherrProbe
import com.github.damontecres.wholphin.api.fetcherr.PairingCode
import com.github.damontecres.wholphin.api.fetcherr.PairingRequest
import com.github.damontecres.wholphin.api.fetcherr.PairingStatus
import com.github.damontecres.wholphin.api.fetcherr.model.SystemStatus
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.services.normalizeFetcherrUrl
import com.github.damontecres.wholphin.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber
import javax.inject.Inject

/**
 * Connects the app to Fetcherr with as little typing as possible:
 * 1. Look for Fetcherr on the Jellyfin server's machine
 * 2. Pair with a code the user approves in Fetcherr's web UI
 * 3. Otherwise, type the address and/or the API key
 *
 * The dialog's screens are a stack of [FetcherrSetupStep]s, so Back returns to the previous one.
 */
@HiltViewModel
class FetcherrSettingsViewModel
    @Inject
    constructor(
        private val fetcherrRepository: FetcherrRepository,
        private val serverRepository: ServerRepository,
        private val preferences: DataStore<AppPreferences>,
        private val deviceInfo: DeviceInfo,
        @param:StandardOkHttpClient okHttpClient: OkHttpClient,
        @param:IoCoroutineScope private val appScope: CoroutineScope,
    ) : ViewModel() {
        val connection: StateFlow<FetcherrConnection> = fetcherrRepository.connection

        internal var pairing = FetcherrPairingClient(okHttpClient)

        /** How many codes in a row are replaced when they expire, before waiting for the user */
        internal var maxCodes = MAX_CODES

        /** The shortest wait between polls, whatever the server asks for */
        internal var minPollIntervalMillis = MIN_POLL_MS

        private val _state = MutableStateFlow(FetcherrSetupState())
        val state: StateFlow<FetcherrSetupState> = _state

        private var pairingJob: Job? = null
        private var started = false

        /** Show the saved server, or start looking for one */
        fun init() {
            if (started) return
            started = true
            viewModelScope.launchIO {
                val settings = preferences.data.first().fetcherrSettings
                if (settings.url.isNotBlank() && settings.apiKey.isNotBlank()) {
                    _state.update { it.copy(savedUrl = settings.url) }
                    show(listOf(FetcherrSetupStep.Saved))
                } else {
                    search()
                }
            }
        }

        private suspend fun search() {
            show(listOf(FetcherrSetupStep.Searching))
            val candidate = candidateUrl(serverRepository.current.value?.server?.url)
            if (candidate == null) {
                show(listOf(FetcherrSetupStep.Address(url = "http://")))
                return
            }
            when (val probe = pairing.probe(candidate)) {
                is FetcherrProbe.Found -> {
                    show(listOf(stepFor(probe)))
                }

                else -> {
                    Timber.i("Fetcherr not found at %s: %s", candidate, probe)
                    show(listOf(FetcherrSetupStep.Address(url = candidate, notFoundAt = candidate)))
                }
            }
        }

        private fun stepFor(found: FetcherrProbe.Found): FetcherrSetupStep =
            if (found.canPair) {
                FetcherrSetupStep.Pairing(found.url, found.info?.name)
            } else {
                FetcherrSetupStep.ApiKey(found.url, pairingUnavailable = true)
            }

        /** Go back a screen. Returns false if there is none, so that the dialog closes. */
        fun back(): Boolean {
            val stack = _state.value.stack
            if (stack.size <= 1) return false
            show(stack.dropLast(1))
            return true
        }

        private fun push(step: FetcherrSetupStep) = show(_state.value.stack + step)

        private fun show(stack: List<FetcherrSetupStep>) {
            stopPairing()
            val top = stack.lastOrNull()
            if (top is FetcherrSetupStep.Pairing) {
                // Any code shown before was withdrawn when the user left, so start with a new one
                _state.update { it.copy(stack = stack.dropLast(1) + top.copy(phase = PairingPhase.Requesting)) }
                startPairing(top.url)
            } else {
                _state.update { it.copy(stack = stack) }
            }
        }

        private fun replaceTop(step: FetcherrSetupStep) {
            _state.update { it.copy(stack = it.stack.dropLast(1) + step) }
        }

        /** Update the top step if it's still a [T] for [url], eg after a slow call returns */
        private inline fun <reified T : FetcherrSetupStep> updateTop(transform: (T) -> FetcherrSetupStep) {
            _state.update { state ->
                val top = state.stack.lastOrNull()
                if (top is T) state.copy(stack = state.stack.dropLast(1) + transform(top)) else state
            }
        }

        // --- Saved server ---

        fun pairAgain() {
            push(FetcherrSetupStep.Pairing(_state.value.savedUrl, null))
        }

        fun changeServer() {
            val url = _state.value.savedUrl.ifBlank { "http://" }
            push(FetcherrSetupStep.Address(url = url))
        }

        fun retryConnection() = fetcherrRepository.reconnect()

        fun remove() {
            viewModelScope.launchIO {
                fetcherrRepository.remove()
                _state.update { it.copy(closed = true) }
            }
        }

        // --- Address ---

        /** Check the typed address, then pair with it */
        fun submitAddress(url: String) {
            val normalized = normalizeFetcherrUrl(url)
            updateTop<FetcherrSetupStep.Address> { it.copy(url = url, checking = true, problem = null) }
            viewModelScope.launchIO {
                val probe = pairing.probe(normalized)
                val problem =
                    when (probe) {
                        is FetcherrProbe.Found -> null
                        is FetcherrProbe.NotFetcherr -> AddressProblem.NotFetcherr
                        is FetcherrProbe.Unreachable -> AddressProblem.Unreachable
                    }
                updateTop<FetcherrSetupStep.Address> { it.copy(checking = false, problem = problem, notFoundAt = null) }
                if (probe is FetcherrProbe.Found) push(stepFor(probe))
            }
        }

        // --- Pairing ---

        /** Show another code, eg after it expired or the user declined it */
        fun newCode() {
            if (_state.value.step is FetcherrSetupStep.Pairing) show(_state.value.stack)
        }

        fun useAddress() {
            val stack = _state.value.stack
            val url = (stack.lastOrNull() as? FetcherrSetupStep.Pairing)?.url ?: "http://"
            val existing = stack.indexOfLast { it is FetcherrSetupStep.Address }
            if (existing >= 0) {
                show(stack.take(existing + 1))
            } else {
                push(FetcherrSetupStep.Address(url = url))
            }
        }

        fun useApiKey() {
            val url = (_state.value.stack.lastOrNull() as? FetcherrSetupStep.Pairing)?.url ?: return
            push(FetcherrSetupStep.ApiKey(url))
        }

        private fun startPairing(url: String) {
            pairingJob =
                viewModelScope.launchIO {
                    var codes = 0
                    while (true) {
                        updateTop<FetcherrSetupStep.Pairing> { it.copy(phase = PairingPhase.Requesting) }
                        val code =
                            try {
                                pairing.start(url, PairingRequest( deviceInfo.name, APP_NAME))
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                Timber.w(ex, "Could not start pairing with Fetcherr at %s", url)
                                if ((ex as? FetcherrException)?.statusCode == 404) {
                                    // Pairing was turned off, or the server is older than its info said
                                    replaceTop(FetcherrSetupStep.ApiKey(url, pairingUnavailable = true))
                                } else {
                                    updateTop<FetcherrSetupStep.Pairing> { it.copy(phase = PairingPhase.Failed(ex)) }
                                }
                                return@launchIO
                            }
                        codes++
                        updateTop<FetcherrSetupStep.Pairing> {
                            it.copy(phase = PairingPhase.Waiting(code.code, code.approveUrl ?: "$url/pair?code=${code.code}"))
                        }
                        when (val result = waitForApproval(url, code)) {
                            is PairingStatus.Approved -> {
                                finishPairing(url, result.apiKey)
                                return@launchIO
                            }

                            PairingStatus.Denied -> {
                                updateTop<FetcherrSetupStep.Pairing> { it.copy(phase = PairingPhase.Denied) }
                                return@launchIO
                            }

                            else -> {
                                // Expired: replace the code quietly, unless the user seems to have walked away
                                if (codes >= maxCodes) {
                                    updateTop<FetcherrSetupStep.Pairing> { it.copy(phase = PairingPhase.Expired) }
                                    return@launchIO
                                }
                            }
                        }
                    }
                }
        }

        /**
         * Poll until the code is approved, declined or expires. If the user leaves the screen first,
         * the code is withdrawn so that it can't be approved by mistake later.
         */
        private suspend fun waitForApproval(
            url: String,
            code: PairingCode,
        ): PairingStatus {
            val interval = (code.intervalSeconds * 1_000L).coerceIn(minPollIntervalMillis, MAX_POLL_MS)
            val expiresAt = System.currentTimeMillis() + code.expiresInSeconds * 1_000L
            var done = false
            try {
                while (System.currentTimeMillis() < expiresAt) {
                    delay(interval)
                    val status =
                        try {
                            pairing.poll(url, code.secret)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            // Eg Wi-Fi dropping for a moment; the code is still valid until it expires
                            Timber.w(ex, "Error polling Fetcherr pairing")
                            continue
                        }
                    if (status != PairingStatus.Pending) {
                        done = true
                        return status
                    }
                }
                done = true
                return PairingStatus.Expired
            } finally {
                if (!done) {
                    appScope.launch {
                        try {
                            pairing.cancel(url, code.secret)
                        } catch (ex: Exception) {
                            Timber.w(ex, "Error withdrawing Fetcherr pairing code")
                        }
                    }
                }
            }
        }

        private suspend fun finishPairing(
            url: String,
            apiKey: String,
        ) {
            try {
                val status = fetcherrRepository.testConnection(url, apiKey)
                fetcherrRepository.save(url, apiKey)
                show(listOf(FetcherrSetupStep.Done(url, status)))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Fetcherr approved pairing, but its key doesn't work")
                updateTop<FetcherrSetupStep.Pairing> { it.copy(phase = PairingPhase.Failed(ex)) }
            }
        }

        private fun stopPairing() {
            pairingJob?.cancel()
            pairingJob = null
        }

        // --- API key ---

        /** Check the address & key, and save them if Fetcherr accepts them */
        fun submitApiKey(
            url: String,
            apiKey: String,
        ) {
            updateTop<FetcherrSetupStep.ApiKey> { it.copy(url = url, checking = true, error = null) }
            viewModelScope.launchIO {
                try {
                    val status = fetcherrRepository.testConnection(url, apiKey)
                    fetcherrRepository.save(url, apiKey)
                    show(listOf(FetcherrSetupStep.Done(normalizeFetcherrUrl(url), status)))
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Not saving Fetcherr settings")
                    updateTop<FetcherrSetupStep.ApiKey> { it.copy(checking = false, error = ex) }
                }
            }
        }

        override fun onCleared() {
            stopPairing()
        }

        companion object {
            const val DEFAULT_PORT = 8734
            const val MAX_CODES = 6
            private const val MIN_POLL_MS = 1_000L
            private const val MAX_POLL_MS = 10_000L
            private const val APP_NAME = "Wholphin"

            /** Where Fetcherr would be if it runs on the same machine as Jellyfin, eg `http://192.168.1.10:8734` */
            fun candidateUrl(jellyfinUrl: String?): String? {
                val host = jellyfinUrl?.toHttpUrlOrNull()?.host ?: return null
                return HttpUrl
                    .Builder()
                    .scheme("http")
                    .host(host)
                    .port(DEFAULT_PORT)
                    .build()
                    .toString()
                    .trimEnd('/')
            }
        }
    }

data class FetcherrSetupState(
    val stack: List<FetcherrSetupStep> = emptyList(),
    /** The saved server's URL, if any */
    val savedUrl: String = "",
    /** The server was removed, so the dialog should close */
    val closed: Boolean = false,
) {
    val step: FetcherrSetupStep? get() = stack.lastOrNull()
}

sealed interface FetcherrSetupStep {
    /** Looking for Fetcherr next to the Jellyfin server */
    data object Searching : FetcherrSetupStep

    /** The saved server, how it's doing, and ways to fix or change it */
    data object Saved : FetcherrSetupStep

    /**
     * Type the server's address
     *
     * @param notFoundAt where Fetcherr was looked for automatically, but not found
     */
    data class Address(
        val url: String,
        val notFoundAt: String? = null,
        val checking: Boolean = false,
        val problem: AddressProblem? = null,
    ) : FetcherrSetupStep

    data class Pairing(
        val url: String,
        val serverName: String?,
        val phase: PairingPhase = PairingPhase.Requesting,
    ) : FetcherrSetupStep

    /**
     * @param pairingUnavailable the server can't pair, so the key is the only way
     */
    data class ApiKey(
        val url: String,
        val pairingUnavailable: Boolean = false,
        val checking: Boolean = false,
        val error: Exception? = null,
    ) : FetcherrSetupStep

    data class Done(
        val url: String,
        val status: SystemStatus,
    ) : FetcherrSetupStep
}

enum class AddressProblem {
    NotFetcherr,
    Unreachable,
}

sealed interface PairingPhase {
    data object Requesting : PairingPhase

    /**
     * @param approveUrl opens Fetcherr's approval page with the code filled in, for the QR code
     */
    data class Waiting(
        val code: String,
        val approveUrl: String,
    ) : PairingPhase

    /** Several codes expired without the user approving one */
    data object Expired : PairingPhase

    data object Denied : PairingPhase

    data class Failed(
        val exception: Exception,
    ) : PairingPhase
}
