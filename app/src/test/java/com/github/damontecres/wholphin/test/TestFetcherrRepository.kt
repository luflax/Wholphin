package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.fetcherr.FetcherrException
import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.services.FetcherrRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert
import org.junit.Test

/**
 * Connecting to Fetcherr, and recovering when it is down or rejects the API key
 */
class TestFetcherrRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = FakeFetcherrServer()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun statusCalls() = server.calls.count { it == "GET /api/system/status" }

    @Test
    fun `connects with the saved settings`() =
        runBlocking<Unit> {
            val repository = fakeRepository(server, FakePreferences.configured(), scope)
            val connected = repository.connection.await { it is FetcherrConnection.Connected } as FetcherrConnection.Connected
            Assert.assertEquals("0.4.0", connected.status.version)
            Assert.assertTrue(withTimeout(5_000) { repository.active.first { it } })
        }

    @Test
    fun `nothing is called without settings`() =
        runBlocking<Unit> {
            val repository = fakeRepository(server, FakePreferences(), scope)
            delay(200)
            Assert.assertEquals(FetcherrConnection.NotConfigured, repository.connection.value)
            Assert.assertTrue(server.calls.isEmpty())
        }

    @Test
    fun `an unreachable server is retried until it comes up`() =
        runBlocking<Unit> {
            server.up = false
            val repository = fakeRepository(server, FakePreferences.configured(), scope)
            repository.connection.await { it is FetcherrConnection.Error }
            server.up = true
            repository.connection.await { it is FetcherrConnection.Connected }
            Assert.assertTrue(statusCalls() >= 2)
        }

    @Test
    fun `a rejected API key is not retried`() =
        runBlocking<Unit> {
            server.apiKey = "regenerated"
            val repository = fakeRepository(server, FakePreferences.configured(), scope)
            val error = repository.connection.await { it is FetcherrConnection.Error } as FetcherrConnection.Error
            Assert.assertTrue(error.isUnauthorized)
            delay(300)
            Assert.assertEquals(1, statusCalls())
        }

    @Test
    fun `saving unchanged settings connects again`() =
        runBlocking<Unit> {
            server.apiKey = "regenerated"
            val repository = fakeRepository(server, FakePreferences.configured(), scope)
            repository.connection.await { it is FetcherrConnection.Error }
            // Eg the key was changed back on the server
            server.apiKey = FAKE_KEY
            repository.save(FAKE_URL, FAKE_KEY)
            repository.connection.await { it is FetcherrConnection.Connected }
        }

    @Test
    fun `a rejected call drops the connection`() =
        runBlocking<Unit> {
            val repository = fakeRepository(server, FakePreferences.configured(), scope)
            repository.connection.await { it is FetcherrConnection.Connected }
            server.apiKey = "regenerated"
            val ex = runCatching { repository.call { it.requestsApi.list(null, 1) } }.exceptionOrNull()
            Assert.assertTrue((ex as? FetcherrException)?.isUnauthorized == true)
            val error = repository.connection.await { it is FetcherrConnection.Error } as FetcherrConnection.Error
            Assert.assertTrue(error.isUnauthorized)
        }

    @Test
    fun `retries back off up to five minutes`() {
        Assert.assertEquals(5_000L, FetcherrRepository.defaultRetryDelayMillis(1))
        Assert.assertEquals(10_000L, FetcherrRepository.defaultRetryDelayMillis(2))
        Assert.assertEquals(40_000L, FetcherrRepository.defaultRetryDelayMillis(4))
        Assert.assertEquals(300_000L, FetcherrRepository.defaultRetryDelayMillis(100))
    }
}
