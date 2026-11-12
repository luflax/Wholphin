package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrSearchViewModel
import com.github.damontecres.wholphin.util.DataLoadingState
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * When the search tab searches again for the same text
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestFetcherrSearch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Finds nothing
    private val server = FakeFetcherrServer()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun searchCalls() = server.calls.count { it.startsWith("GET /api/titles/search") }

    private suspend fun viewModel(): FetcherrSearchViewModel {
        val repository = fakeRepository(server, FakePreferences.configured(), scope)
        repository.connection.await { it is FetcherrConnection.Connected }
        return FetcherrSearchViewModel(repository, mockk(relaxed = true), mockk(relaxed = true))
    }

    private suspend fun FetcherrSearchViewModel.awaitResult(predicate: (DataLoadingState<*>?) -> Boolean) =
        withTimeout(10_000) { results.first(predicate) }

    @Test
    fun `a failed search is repeated for the same text`() =
        runBlocking<Unit> {
            val vm = viewModel()
            server.up = false
            vm.search("dune")
            vm.awaitResult { it is DataLoadingState.Error }
            server.up = true
            vm.search("dune")
            vm.awaitResult { it is DataLoadingState.Success }
        }

    @Test
    fun `unchanged text isn't searched again unless forced`() =
        runBlocking<Unit> {
            val vm = viewModel()
            vm.search("dune")
            vm.awaitResult { it is DataLoadingState.Success }
            Assert.assertEquals(1, searchCalls())
            // Eg the debounce firing after the search key already searched
            vm.search(" dune ")
            delay(200)
            Assert.assertEquals(1, searchCalls())
            vm.search("dune", force = true)
            withTimeout(10_000) { while (searchCalls() < 2) delay(20) }
        }
}
