package io.github.dustzed.moonegg.demo

import io.github.dustzed.moonegg.bindings.NativeState

internal sealed interface DemoCommand {
    data object Prepare : DemoCommand
    data object Play : DemoCommand
    data object Pause : DemoCommand
    data object Stop : DemoCommand
    data class Seek(val positionMs: Long) : DemoCommand
}

internal data class DemoUiState(
    val connected: Boolean = false,
    val state: NativeState = NativeState.IDLE,
    val positionMs: Long = 0L,
    val message: String = "",
    val durationMs: Long? = null,
)
