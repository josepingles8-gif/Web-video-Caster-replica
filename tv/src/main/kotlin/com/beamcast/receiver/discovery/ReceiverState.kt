package com.beamcast.receiver.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class Visibility { STARTING, VISIBLE, NO_NETWORK, FAILED }

data class ReceiverUiState(
    val name: String = "",
    val ip: String? = null,
    val port: Int = 0,
    val visibility: Visibility = Visibility.STARTING,
)

/** Observable state shown on the home screen; written by the service. */
object ReceiverState {
    val state = MutableStateFlow(ReceiverUiState())
    fun update(block: ReceiverUiState.() -> ReceiverUiState) = state.update(block)
}
