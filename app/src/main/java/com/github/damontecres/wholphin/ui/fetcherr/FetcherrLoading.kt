package com.github.damontecres.wholphin.ui.fetcherr

import com.github.damontecres.wholphin.util.DataLoadingState
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Run [block], wrapping its result or failure in a [DataLoadingState]
 */
suspend fun <T> loadFetcherrData(block: suspend () -> T): DataLoadingState<T> =
    try {
        DataLoadingState.Success(block.invoke())
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        Timber.w(ex, "Error loading from Fetcherr")
        DataLoadingState.Error(ex)
    }
