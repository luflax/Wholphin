package com.github.damontecres.wholphin.ui.setup.fetcherr

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.api.fetcherr.model.SystemStatus
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.FetcherrConnection
import com.github.damontecres.wholphin.services.FetcherrRepository
import com.github.damontecres.wholphin.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class FetcherrSettingsViewModel
    @Inject
    constructor(
        private val fetcherrRepository: FetcherrRepository,
        private val preferences: DataStore<AppPreferences>,
    ) : ViewModel() {
        val connection: StateFlow<FetcherrConnection> = fetcherrRepository.connection

        private val _state = MutableStateFlow(FetcherrSettingsState())
        val state: StateFlow<FetcherrSettingsState> = _state

        /** Load the saved settings into the form */
        fun init() {
            viewModelScope.launchIO {
                val settings = preferences.data.first().fetcherrSettings
                // Show how the saved settings are doing, eg why the server can't be reached
                val test =
                    when (val current = connection.value) {
                        is FetcherrConnection.Connected -> FetcherrTestState.Success(current.status)
                        is FetcherrConnection.Error -> FetcherrTestState.Failed(current.exception)
                        else -> FetcherrTestState.None
                    }
                _state.value =
                    FetcherrSettingsState(
                        initialUrl = settings.url,
                        initialApiKey = settings.apiKey,
                        test = test,
                        loaded = true,
                    )
            }
        }

        fun test(
            url: String,
            apiKey: String,
        ) {
            _state.update { it.copy(test = FetcherrTestState.Testing) }
            viewModelScope.launchIO {
                val result =
                    try {
                        FetcherrTestState.Success(fetcherrRepository.testConnection(url, apiKey))
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Fetcherr connection test failed")
                        FetcherrTestState.Failed(ex)
                    }
                _state.update { it.copy(test = result) }
            }
        }

        /** Test the settings, and save them only if the server accepts them */
        fun save(
            url: String,
            apiKey: String,
        ) {
            _state.update { it.copy(test = FetcherrTestState.Testing) }
            viewModelScope.launchIO {
                try {
                    val status = fetcherrRepository.testConnection(url, apiKey)
                    fetcherrRepository.save(url, apiKey)
                    _state.update { it.copy(test = FetcherrTestState.Success(status), saved = true) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Not saving Fetcherr settings")
                    _state.update { it.copy(test = FetcherrTestState.Failed(ex)) }
                }
            }
        }

        fun remove() {
            viewModelScope.launchIO {
                fetcherrRepository.remove()
                _state.update { FetcherrSettingsState(loaded = true, saved = true) }
            }
        }

        /** Clear a test result, eg after the user edits a field */
        fun resetTest() {
            _state.update { it.copy(test = FetcherrTestState.None) }
        }
    }

data class FetcherrSettingsState(
    val initialUrl: String = "",
    val initialApiKey: String = "",
    val test: FetcherrTestState = FetcherrTestState.None,
    val loaded: Boolean = false,
    val saved: Boolean = false,
)

sealed interface FetcherrTestState {
    data object None : FetcherrTestState

    data object Testing : FetcherrTestState

    data class Success(
        val status: SystemStatus,
    ) : FetcherrTestState

    data class Failed(
        val exception: Exception,
    ) : FetcherrTestState
}
