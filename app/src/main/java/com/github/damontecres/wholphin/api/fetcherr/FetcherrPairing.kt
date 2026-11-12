package com.github.damontecres.wholphin.api.fetcherr

import com.github.damontecres.wholphin.api.fetcherr.infrastructure.Serializer
import com.github.damontecres.wholphin.api.fetcherr.model.ErrorBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * `GET /api/system/info`, which answers without an API key so that a client can recognize Fetcherr
 */
@Serializable
data class FetcherrInfo(
    val product: String,
    val version: String,
    /** Shown to the user, eg "Fetcherr" or a name given in its settings */
    val name: String? = null,
    /** Whether new devices can pair with a code */
    val pairing: Boolean = false,
) {
    val isFetcherr: Boolean get() = product.equals("Fetcherr", ignoreCase = true)
}

@Serializable
data class PairingRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val app: String,
)

/**
 * A code to show to the user, who approves it in Fetcherr's web UI
 */
@Serializable
data class PairingCode(
    val code: String,
    /** Identifies this pairing when polling. Never shown. */
    val secret: String,
    @SerialName("expires_in") val expiresInSeconds: Int,
    /** How often to poll, in seconds */
    @SerialName("interval") val intervalSeconds: Int = 2,
    /** The page approving this code, if Fetcherr knows its public address */
    @SerialName("approve_url") val approveUrl: String? = null,
)

@Serializable
data class PairingStatusBody(
    val status: String,
    @SerialName("api_key") val apiKey: String? = null,
)

sealed interface PairingStatus {
    data object Pending : PairingStatus

    data class Approved(
        val apiKey: String,
    ) : PairingStatus

    data object Denied : PairingStatus

    /** The code expired or was already used */
    data object Expired : PairingStatus
}

/**
 * What answers at an address
 */
sealed interface FetcherrProbe {
    val url: String

    /**
     * A Fetcherr server. [info] is null for a version without `/api/system/info`, which can't pair.
     */
    data class Found(
        override val url: String,
        val info: FetcherrInfo?,
    ) : FetcherrProbe {
        val canPair: Boolean get() = info?.pairing == true
    }

    /** Something answered, but not Fetcherr */
    data class NotFetcherr(
        override val url: String,
    ) : FetcherrProbe

    data class Unreachable(
        override val url: String,
        val exception: Exception,
    ) : FetcherrProbe
}

/**
 * Finds a Fetcherr server and pairs this device with it, before there is an API key
 */
class FetcherrPairingClient(
    okHttpClient: OkHttpClient,
    probeTimeoutMillis: Long = PROBE_TIMEOUT_MS,
) {
    private val json = Serializer.kotlinxSerializationJson

    private val client: OkHttpClient = okHttpClient.newBuilder().build()

    /** Short, so that looking for a server that isn't there doesn't keep the user waiting */
    private val probeClient: OkHttpClient =
        okHttpClient
            .newBuilder()
            .callTimeout(probeTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()

    /** Check whether Fetcherr answers at [baseUrl] */
    suspend fun probe(baseUrl: String): FetcherrProbe =
        try {
            probeClient.get("$baseUrl/api/system/info").use { response ->
                when {
                    response.isSuccessful -> {
                        val info =
                            try {
                                json.decodeFromString<FetcherrInfo>(response.body.string())
                            } catch (_: Exception) {
                                null
                            }
                        if (info?.isFetcherr == true) {
                            FetcherrProbe.Found(baseUrl, info)
                        } else {
                            FetcherrProbe.NotFetcherr(baseUrl)
                        }
                    }

                    response.code == 404 -> {
                        probeOlderVersion(baseUrl)
                    }

                    else -> {
                        FetcherrProbe.NotFetcherr(baseUrl)
                    }
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: IOException) {
            FetcherrProbe.Unreachable(baseUrl, ex)
        } catch (ex: IllegalArgumentException) {
            // Not a valid URL
            FetcherrProbe.Unreachable(baseUrl, ex)
        }

    /**
     * Versions before pairing have no info endpoint, but answer the status endpoint without a key
     * with Fetcherr's error body
     */
    private suspend fun probeOlderVersion(baseUrl: String): FetcherrProbe =
        probeClient.get("$baseUrl/api/system/status").use { response ->
            if (response.code == 401 && response.errorBody()?.error?.code != null) {
                FetcherrProbe.Found(baseUrl, null)
            } else {
                FetcherrProbe.NotFetcherr(baseUrl)
            }
        }

    /**
     * Ask for a code to show to the user
     *
     * @throws FetcherrException if Fetcherr refuses, eg 404 when it can't pair or 429 when too many
     * codes were asked for
     */
    suspend fun start(
        baseUrl: String,
        request: PairingRequest,
    ): PairingCode {
        val body = json.encodeToString(PairingRequest.serializer(), request).toRequestBody(JSON)
        val call = client.newCall(Request.Builder().url("$baseUrl/api/auth/pair").post(body).build())
        return call.await().use { response ->
            if (!response.isSuccessful) throw response.toException()
            json.decodeFromString<PairingCode>(response.body.string())
        }
    }

    /** Whether the user approved the code yet */
    suspend fun poll(
        baseUrl: String,
        secret: String,
    ): PairingStatus =
        client.get("$baseUrl/api/auth/pair/$secret").use { response ->
            when {
                response.code == 404 || response.code == 410 -> {
                    PairingStatus.Expired
                }

                !response.isSuccessful -> {
                    throw response.toException()
                }

                else -> {
                    val status = json.decodeFromString<PairingStatusBody>(response.body.string())
                    when (status.status) {
                        "approved" -> status.apiKey?.let { PairingStatus.Approved(it) } ?: PairingStatus.Expired
                        "denied" -> PairingStatus.Denied
                        "expired" -> PairingStatus.Expired
                        else -> PairingStatus.Pending
                    }
                }
            }
        }

    /** Withdraw a code the user no longer needs, so that it can't be approved by mistake */
    suspend fun cancel(
        baseUrl: String,
        secret: String,
    ) {
        client
            .newCall(Request.Builder().url("$baseUrl/api/auth/pair/$secret").delete().build())
            .await()
            .close()
    }

    private suspend fun OkHttpClient.get(url: String): Response = newCall(Request.Builder().url(url).get().build()).await()

    private fun Response.errorBody(): ErrorBody? =
        try {
            json.decodeFromString<ErrorBody>(body.string())
        } catch (_: Exception) {
            null
        }

    private fun Response.toException(): FetcherrException {
        val error = errorBody()?.error
        return FetcherrException(code, error?.code, error?.message ?: "HTTP $code")
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 3_000L
        private val JSON = "application/json".toMediaType()
    }
}

/** Run the call without blocking, cancelling it if the coroutine is cancelled */
private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }
