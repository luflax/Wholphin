package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.services.FetcherrEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert
import org.junit.Test

/**
 * The events WebSocket's reconnect loop. The fake server can't complete a WebSocket handshake, so
 * every attempt fails, which is what these tests need.
 */
class TestFetcherrEvents {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = FakeFetcherrServer()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun socketCalls() = server.calls.filter { it.startsWith("GET /api/ws/events") }

    private suspend fun awaitSocketCalls(count: Int) {
        withTimeout(10_000) {
            while (socketCalls().size < count) delay(20)
        }
    }

    private suspend fun connectedEvents(): FetcherrEvents {
        val repository = fakeRepository(server, FakePreferences.configured(), scope)
        repository.connection.await { it is FetcherrConnection.Connected }
        return FetcherrEvents(repository).apply { retryDelay = { 20L } }
    }

    @Test
    fun `a dropped socket reconnects`() =
        runBlocking<Unit> {
            val events = connectedEvents()
            server.up = false
            val job = scope.launch { events.subscribe(titles = listOf("movie:603"), requests = listOf(7L)).collect {} }
            awaitSocketCalls(3)
            job.cancel()
            Assert.assertTrue(socketCalls().first(), socketCalls().first().contains("titles=movie%3A603"))
            Assert.assertTrue(socketCalls().first(), socketCalls().first().contains("requests=7"))
        }

    @Test
    fun `following everything sends no filters`() =
        runBlocking<Unit> {
            val events = connectedEvents()
            server.up = false
            val job = scope.launch { events.subscribe().collect {} }
            awaitSocketCalls(1)
            job.cancel()
            Assert.assertEquals("GET /api/ws/events", socketCalls().first())
        }

    @Test
    fun `a rejected key drops the connection`() =
        runBlocking<Unit> {
            val repository = fakeRepository(server, FakePreferences.configured(), scope)
            repository.connection.await { it is FetcherrConnection.Connected }
            val events = FetcherrEvents(repository).apply { retryDelay = { 20L } }
            server.apiKey = "regenerated"
            val job = scope.launch { events.subscribe(titles = listOf("movie:603")).collect {} }
            val error = repository.connection.await { it is FetcherrConnection.Error } as FetcherrConnection.Error
            job.cancel()
            Assert.assertTrue(error.isUnauthorized)
        }
}
