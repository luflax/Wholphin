package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.fetcherr.model.RequestState
import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.services.FetcherrEvents
import com.github.damontecres.wholphin.ui.fetcherr.DownloadsFilter
import com.github.damontecres.wholphin.ui.fetcherr.FetcherrDownloadsViewModel
import com.github.damontecres.wholphin.util.LoadingState
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Loading, paging, filtering & removing on the Downloads tab
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestFetcherrDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    /** [count] requests in [state], numbered from [firstId], newest first */
    private fun addRequests(
        count: Int,
        state: RequestState,
        firstId: Long = (server.requests.maxOfOrNull { it.id } ?: 0) + 1,
    ) {
        val added = (firstId until firstId + count).map { fakeRequest(it, state) }
        server.requests.addAll(added)
        server.requests.sortByDescending { it.id }
    }

    private suspend fun viewModel(): FetcherrDownloadsViewModel {
        val repository = fakeRepository(server, FakePreferences.configured(), scope)
        repository.connection.await { it is FetcherrConnection.Connected }
        return FetcherrDownloadsViewModel(repository, FetcherrEvents(repository), mockk(relaxed = true))
    }

    private suspend fun FetcherrDownloadsViewModel.awaitLoaded() =
        withTimeout(10_000) { state.first { it.loading is LoadingState.Success || it.loading is LoadingState.Error } }

    private suspend fun FetcherrDownloadsViewModel.awaitCount(count: Int) =
        withTimeout(10_000) { live.first { it.requests.size == count } }

    @Test
    fun `pages load as the user scrolls`() =
        runBlocking<Unit> {
            addRequests(45, RequestState.AVAILABLE)
            val vm = viewModel()
            vm.reload()
            vm.awaitLoaded()
            vm.awaitCount(20)
            vm.loadMore()
            vm.awaitCount(40)
            vm.loadMore()
            vm.awaitCount(45)
            Assert.assertFalse(vm.state.value.hasMore)
            Assert.assertEquals((45L downTo 1L).toList(), vm.live.value.requests.map { it.id })
        }

    @Test
    fun `refreshing keeps the pages already shown`() =
        runBlocking<Unit> {
            addRequests(45, RequestState.AVAILABLE)
            val vm = viewModel()
            vm.reload()
            vm.awaitLoaded()
            vm.loadMore()
            vm.awaitCount(40)
            addRequests(1, RequestState.SEARCHING)
            // Eg the tab resumes
            vm.reload()
            withTimeout(10_000) { vm.live.first { it.requests.firstOrNull()?.id == 46L } }
            Assert.assertEquals(40, vm.live.value.requests.size)
            Assert.assertTrue(vm.state.value.hasMore)
        }

    @Test
    fun `the active filter loads every active state`() =
        runBlocking<Unit> {
            addRequests(25, RequestState.DOWNLOADING)
            addRequests(3, RequestState.SEARCHING)
            addRequests(2, RequestState.PAUSED)
            addRequests(1, RequestState.IMPORTING)
            addRequests(5, RequestState.AVAILABLE)
            addRequests(2, RequestState.FAILED)
            val vm = viewModel()
            vm.reload(DownloadsFilter.ACTIVE)
            vm.awaitLoaded()
            vm.awaitCount(31)
            Assert.assertTrue(vm.live.value.requests.all { it.state in DownloadsFilter.ACTIVE.states!! })
            // Newest first across states
            Assert.assertEquals(vm.live.value.requests.sortedByDescending { it.id }, vm.live.value.requests)
            Assert.assertFalse(vm.state.value.hasMore)
            Assert.assertTrue(server.calls.contains("GET /api/requests?state=downloading&page=2"))
        }

    @Test
    fun `the failed filter includes requests with no release`() =
        runBlocking<Unit> {
            addRequests(2, RequestState.FAILED)
            addRequests(3, RequestState.NOT_FOUND)
            addRequests(4, RequestState.DOWNLOADING)
            val vm = viewModel()
            vm.reload(DownloadsFilter.FAILED)
            vm.awaitLoaded()
            vm.awaitCount(5)
        }

    @Test
    fun `a load error can be retried`() =
        runBlocking<Unit> {
            addRequests(3, RequestState.DOWNLOADING)
            val vm = viewModel()
            server.up = false
            vm.reload()
            val failed = vm.awaitLoaded()
            Assert.assertTrue(failed.loading is LoadingState.Error)
            server.up = true
            vm.reload()
            vm.awaitCount(3)
        }

    @Test
    fun `removing a request deletes it on the server`() =
        runBlocking<Unit> {
            addRequests(3, RequestState.FAILED)
            val vm = viewModel()
            vm.reload()
            vm.awaitLoaded()
            vm.awaitCount(3)
            vm.remove(vm.live.value.requests.first(), deleteFiles = true)
            vm.awaitCount(2)
            Assert.assertTrue(server.calls.contains("DELETE /api/requests/3?delete_files=true"))
            Assert.assertEquals(listOf(2L, 1L), server.requests.map { it.id })
        }
}
