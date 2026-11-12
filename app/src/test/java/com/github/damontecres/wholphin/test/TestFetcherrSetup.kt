package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.fetcherr.FetcherrException
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.ui.setup.fetcherr.AddressProblem
import com.github.damontecres.wholphin.ui.setup.fetcherr.FetcherrSettingsViewModel
import com.github.damontecres.wholphin.ui.setup.fetcherr.FetcherrSetupStep
import com.github.damontecres.wholphin.ui.setup.fetcherr.PairingPhase
import com.github.damontecres.wholphin.ui.setup.fetcherr.formatPairingCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * Connecting to Fetcherr: finding it next to Jellyfin, pairing with a code, and the fallbacks
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestFetcherrSetup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = FakeFetcherrServer()

    /** Where the fake Jellyfin runs, so Fetcherr is looked for at port 8734 of the same host */
    private val jellyfinUrl = "http://fetcherr.test:8096"
    private val candidate = "http://fetcherr.test:8734"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private data class Setup(
        val vm: FetcherrSettingsViewModel,
        val preferences: FakePreferences,
        val repository: FetcherrRepository,
    )

    private fun setup(
        preferences: FakePreferences = FakePreferences(),
        jellyfin: String? = jellyfinUrl,
    ): Setup {
        val repository = fakeRepository(server, preferences, scope)
        val user =
            jellyfin?.let { url ->
                mockk<CurrentUser> { every { server } returns mockk { every { this@mockk.url } returns url } }
            }
        val serverRepository = mockk<ServerRepository> { every { current } returns MutableStateFlow(user) }
        val vm =
            FetcherrSettingsViewModel(
                repository,
                serverRepository,
                preferences,
                DeviceInfo(id = "device1", name = "Living room TV"),
                server.client,
            ).apply { minPollIntervalMillis = 10 }
        return Setup(vm, preferences, repository)
    }

    private suspend fun FetcherrSettingsViewModel.awaitStep(predicate: (FetcherrSetupStep?) -> Boolean): FetcherrSetupStep? =
        withTimeout(10_000) { state.first { predicate(it.step) }.step }

    private suspend fun FetcherrSettingsViewModel.awaitCode(): PairingPhase.Waiting =
        (awaitStep { (it as? FetcherrSetupStep.Pairing)?.phase is PairingPhase.Waiting } as FetcherrSetupStep.Pairing)
            .phase as PairingPhase.Waiting

    /** Wait for a code other than [previous] */
    private suspend fun FetcherrSettingsViewModel.awaitNewCode(previous: String) =
        awaitStep {
            val code = ((it as? FetcherrSetupStep.Pairing)?.phase as? PairingPhase.Waiting)?.code
            code != null && code != previous
        }

    private fun pairingStarts() = server.calls.count { it == "POST /api/auth/pair" }

    @Test
    fun `finds Fetcherr next to Jellyfin and pairs with a code`() =
        runBlocking<Unit> {
            val (vm, preferences, repository) = setup()
            vm.init()
            val waiting = vm.awaitCode()
            val step = vm.state.value.step as FetcherrSetupStep.Pairing
            Assert.assertEquals(candidate, step.url)
            // Fetcherr's pairing form, where the user types the code
            Assert.assertEquals("$candidate/settings#devices-card", waiting.approveUrl)
            val pairing = server.latestPairing!!
            Assert.assertEquals(pairing.code, waiting.code)
            Assert.assertEquals("""{"name":"Living room TV"}""", pairing.request)

            // The user enters the code in Fetcherr's web UI
            pairing.status = "approved"
            val done = vm.awaitStep { it is FetcherrSetupStep.Done } as FetcherrSetupStep.Done
            Assert.assertEquals("0.4.0", done.status.version)
            val saved = preferences.data.value.fetcherrSettings
            Assert.assertEquals(candidate, saved.url)
            Assert.assertEquals(FAKE_KEY, saved.apiKey)
            repository.connection.await { it is FetcherrConnection.Connected }
        }

    @Test
    fun `asks for the address when Fetcherr isn't next to Jellyfin`() =
        runBlocking<Unit> {
            server.otherHosts.add("fetcherr.test")
            val vm = setup().vm
            vm.init()
            val address = vm.awaitStep { it is FetcherrSetupStep.Address } as FetcherrSetupStep.Address
            // Filled in, so that only part of it needs changing
            Assert.assertEquals(candidate, address.url)
            Assert.assertEquals(candidate, address.notFoundAt)

            vm.submitAddress("192.168.1.10:8734")
            val pairing = vm.awaitStep { it is FetcherrSetupStep.Pairing } as FetcherrSetupStep.Pairing
            Assert.assertEquals("http://192.168.1.10:8734", pairing.url)
            vm.awaitCode()

            // Back returns to the address
            Assert.assertTrue(vm.back())
            Assert.assertTrue(vm.state.value.step is FetcherrSetupStep.Address)
            Assert.assertFalse(vm.back())
        }

    @Test
    fun `an address where something else answers isn't Fetcherr`() =
        runBlocking<Unit> {
            server.otherHosts.add("router.test")
            val vm = setup(jellyfin = null).vm
            vm.init()
            val address = vm.awaitStep { it is FetcherrSetupStep.Address } as FetcherrSetupStep.Address
            Assert.assertEquals("http://", address.url)
            Assert.assertNull(address.notFoundAt)
            vm.submitAddress("http://router.test")
            val checked = vm.awaitStep { (it as? FetcherrSetupStep.Address)?.problem != null } as FetcherrSetupStep.Address
            Assert.assertEquals(AddressProblem.NotFetcherr, checked.problem)
        }

    @Test
    fun `an unreachable address says so`() =
        runBlocking<Unit> {
            val vm = setup(jellyfin = null).vm
            vm.init()
            vm.awaitStep { it is FetcherrSetupStep.Address }
            server.up = false
            vm.submitAddress("http://fetcherr.test:8734")
            val checked = vm.awaitStep { (it as? FetcherrSetupStep.Address)?.problem != null } as FetcherrSetupStep.Address
            Assert.assertEquals(AddressProblem.Unreachable, checked.problem)
        }

    @Test
    fun `a server that can't pair asks for the API key`() =
        runBlocking<Unit> {
            server.pairingApi = false
            val (vm, preferences) = setup()
            vm.init()
            val step = vm.awaitStep { it is FetcherrSetupStep.ApiKey } as FetcherrSetupStep.ApiKey
            Assert.assertTrue(step.pairingUnavailable)
            Assert.assertEquals(candidate, step.url)

            vm.submitApiKey(step.url, "wrong")
            val rejected = vm.awaitStep { (it as? FetcherrSetupStep.ApiKey)?.error != null } as FetcherrSetupStep.ApiKey
            Assert.assertFalse(rejected.checking)
            Assert.assertEquals("", preferences.data.value.fetcherrSettings.apiKey)

            vm.submitApiKey(step.url, FAKE_KEY)
            vm.awaitStep { it is FetcherrSetupStep.Done }
            Assert.assertEquals(FAKE_KEY, preferences.data.value.fetcherrSettings.apiKey)
        }

    @Test
    fun `an expired code is replaced`() =
        runBlocking<Unit> {
            val vm = setup().vm
            vm.init()
            val first = vm.awaitCode()
            server.latestPairing!!.status = "expired"
            vm.awaitNewCode(first.code)
            Assert.assertEquals(2, server.pairings.size)
        }

    @Test
    fun `codes stop being replaced when nobody approves them`() =
        runBlocking<Unit> {
            val vm = setup().vm.apply { maxCodes = 2 }
            vm.init()
            val first = vm.awaitCode()
            server.latestPairing!!.status = "expired"
            vm.awaitNewCode(first.code)
            server.latestPairing!!.status = "expired"
            vm.awaitStep { (it as? FetcherrSetupStep.Pairing)?.phase == PairingPhase.Expired }
            Assert.assertEquals(2, server.pairings.size)
            // The user comes back and asks for another
            vm.newCode()
            vm.awaitCode()
            Assert.assertEquals(3, server.pairings.size)
        }

    @Test
    fun `coming back to pairing shows the same code`() =
        runBlocking<Unit> {
            val vm = setup().vm
            vm.init()
            val first = vm.awaitCode()
            // Fetcherr can't withdraw codes and limits how many wait at once, so going back and forth
            // mustn't ask for more
            repeat(FakeFetcherrServer.MAX_PENDING + 1) {
                vm.useApiKey()
                val step = vm.awaitStep { it is FetcherrSetupStep.ApiKey } as FetcherrSetupStep.ApiKey
                Assert.assertFalse(step.pairingUnavailable)
                vm.back()
                Assert.assertEquals(first.code, vm.awaitCode().code)
            }
            Assert.assertEquals(1, pairingStarts())

            // The code is still approved & collected
            server.latestPairing!!.status = "approved"
            vm.awaitStep { it is FetcherrSetupStep.Done }
        }

    @Test
    fun `a new code can be asked for`() =
        runBlocking<Unit> {
            val vm = setup().vm
            vm.init()
            val first = vm.awaitCode()
            vm.newCode()
            vm.awaitNewCode(first.code)
            Assert.assertEquals(2, pairingStarts())
        }

    @Test
    fun `too many pairings in progress says so`() =
        runBlocking<Unit> {
            val vm = setup().vm
            vm.init()
            vm.awaitCode()
            // Eg other TVs pairing at the same time
            repeat(FakeFetcherrServer.MAX_PENDING - 1) {
                server.pairings["other$it"] = FakePairing(100 + it, "OTHER$it", "other$it", "{}")
            }
            vm.newCode()
            val failed = vm.awaitStep { (it as? FetcherrSetupStep.Pairing)?.phase is PairingPhase.Failed } as FetcherrSetupStep.Pairing
            Assert.assertEquals(429, ((failed.phase as PairingPhase.Failed).exception as FetcherrException).statusCode)
        }

    @Test
    fun `a revoked device pairs again`() =
        runBlocking<Unit> {
            server.apiKey = "regenerated"
            val (vm, preferences, repository) = setup(FakePreferences.configured())
            repository.connection.await { it is FetcherrConnection.Error }
            vm.init()
            vm.awaitStep { it == FetcherrSetupStep.Saved }
            Assert.assertEquals(FAKE_URL, vm.state.value.savedUrl)

            vm.pairAgain()
            vm.awaitCode()
            server.latestPairing!!.status = "approved"
            vm.awaitStep { it is FetcherrSetupStep.Done }
            Assert.assertEquals("regenerated", preferences.data.value.fetcherrSettings.apiKey)
            repository.connection.await { it is FetcherrConnection.Connected }
        }

    @Test
    fun `where Fetcherr would be next to Jellyfin`() {
        Assert.assertEquals("http://192.168.1.10:8734", FetcherrSettingsViewModel.candidateUrl("https://192.168.1.10:8920/jellyfin"))
        Assert.assertEquals("http://media.local:8734", FetcherrSettingsViewModel.candidateUrl("http://media.local"))
        Assert.assertEquals("http://[fd00::1]:8734", FetcherrSettingsViewModel.candidateUrl("http://[fd00::1]:8096"))
        Assert.assertNull(FetcherrSettingsViewModel.candidateUrl(null))
        Assert.assertNull(FetcherrSettingsViewModel.candidateUrl("not a url"))
    }

    @Test
    fun `codes are split to be read across a room`() {
        Assert.assertEquals("K7Q 4M2", formatPairingCode("K7Q4M2"))
        Assert.assertEquals("ABCD EFGH", formatPairingCode("ABCDEFGH"))
        Assert.assertEquals("12345", formatPairingCode("12345"))
        Assert.assertEquals("K7Q 4M2", formatPairingCode("K7Q 4M2"))
    }
}
