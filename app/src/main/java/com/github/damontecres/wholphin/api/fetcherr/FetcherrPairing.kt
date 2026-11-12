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
 * Body of `POST /api/auth/pair`
 *
 * @param name suggested for the device in Fetcherr's Devices list; the user can change it when approving
 */
@Serializable
data class PairingRequest(
    val name: String,
)

/**
 * A code to show to the user, who enters it under Settings → Devices in Fetcherr's web UI
 */
@Serializable
data class PairingCode(
    val code: String,
    /** Identifies this pairing when polling. Never shown. */
    val secret: String,
    @SerialName("expires_in_secs") val expiresInSeconds: Long,
    /** How often to poll */
    @SerialName("poll_interval_secs") val pollIntervalSeconds: Long,
)

@Serializable
private data class PairedDevice(
    @SerialName("api_key") val apiKey: String,
)

sealed interface PairingStatus {
    data object Pending : PairingStatus

    data class Approved(
        val apiKey: String,
    ) : PairingStatus

    /** The code expired, or its key was already collected */
    data object Expired : PairingStatus
}

/**
 * What answers at an address
 */
sealed interface FetcherrProbe {
    val url: String

    data class Found(
        override val url: String,
    ) : FetcherrProbe

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

    /**
     * Check whether Fetcherr answers at [baseUrl]
     *
     * Without a key, Fetcherr answers its status endpoint with a 401 and its own error body, which
     * tells it apart from other servers.
     */
    suspend fun probe(baseUrl: String): FetcherrProbe =
        try {
            probeClient.get("$baseUrl/api/system/status").use { response ->
                if (response.code == 401 && response.errorBody()?.error?.code != null) {
                    FetcherrProbe.Found(baseUrl)
                } else {
                    FetcherrProbe.NotFetcherr(baseUrl)
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
     * Ask for a code to show to the user
     *
     * @throws FetcherrException if Fetcherr refuses, eg 404 or 405 for a version that can't pair, or
     * 429 when too many pairings are in progress
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

    /** Whether the user approved the code yet: 202 while waiting, then 200 with the key, once */
    suspend fun poll(
        baseUrl: String,
        secret: String,
    ): PairingStatus =
        client.get("$baseUrl/api/auth/pair/$secret").use { response ->
            when (response.code) {
                200 -> PairingStatus.Approved(json.decodeFromString<PairedDevice>(response.body.string()).apiKey)
                202 -> PairingStatus.Pending
                404, 410 -> PairingStatus.Expired
                else -> throw response.toException()
            }
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
