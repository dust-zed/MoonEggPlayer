package io.github.dustzed.moonegg.demo

import android.content.res.AssetManager
import android.util.Log
import android.view.Surface
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.dustzed.moonegg.bindings.NativeEvent
import io.github.dustzed.moonegg.bindings.NativePlayer
import io.github.dustzed.moonegg.bindings.NativeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

@MainThread
internal class DemoPlayerController(
    private val assets: AssetManager,
    private val scope: CoroutineScope,
) {
    var uiState: DemoUiState by mutableStateOf(DemoUiState())
        private set

    private val commands = Channel<DemoCommand>(capacity = COMMAND_BATCH_SIZE)
    private var foreground: Boolean = false
    private var activeSurface: Surface? = null
    private var activePlayer: NativePlayer? = null
    private var sessionJob: Job? = null

    fun onForegroundChanged(available: Boolean) {
        foreground = available
        if (available) {
            tryStartPlayerSession()
        } else {
            closePlayerSession()
        }
    }

    fun onSurfaceReady(surface: Surface) {
        val previousSurface: Surface? = activeSurface
        if (previousSurface != null && previousSurface !== surface) {
            closePlayerSession()
        }
        activeSurface = surface
        Log.d(TAG, "视频窗口创建: valid=${surface.isValid}")
        tryStartPlayerSession()
    }

    fun onSurfaceLost() {
        activeSurface = null
        // 窗口销毁前同步释放播放器。
        closePlayerSession()
        Log.d(TAG, "视频窗口即将销毁")
    }

    fun submit(command: DemoCommand) {
        if (activePlayer == null) return

        if (commands.trySend(command).isFailure) {
            Log.w(TAG, "命令队列已满")
        }
    }

    private fun tryStartPlayerSession() {
        if (activePlayer != null || !foreground || !scope.isActive) return
        val surface: Surface = activeSurface ?: return
        if (!surface.isValid) return

        clearCommands()
        try {
            val current: NativePlayer = createAvDemoPlayer(assets, surface)
            activePlayer = current
            uiState = DemoUiState(connected = true)

            // 先保存 Job，再启动；启动失败时也能完整清理。
            val job: Job = scope.launch(
                context = Dispatchers.Main.immediate,
                start = CoroutineStart.LAZY,
            ) {
                runPlayerSession(current)
            }
            sessionJob = job
            job.start()
        } catch (error: Exception) {
            closePlayerSession()
            uiState = uiState.copy(
                connected = false,
                state = NativeState.ERROR,
                message = error.message ?: error.toString(),
            )
        }
    }

    private fun clearCommands() {
        while (commands.tryReceive().isSuccess) {
            // 丢弃旧会话命令。
        }
    }

    private suspend fun runPlayerSession(current: NativePlayer) {
        var state: DemoUiState = uiState

        try {
            while (currentCoroutineContext().isActive) {
                for (index in 0 until COMMAND_BATCH_SIZE) {
                    val command: DemoCommand = commands.tryReceive().getOrNull() ?: break
                    state = state.copy(message = "")
                    executeCommand(current, command)
                }

                for (index in 0 until EVENT_BATCH_SIZE) {
                    val event: NativeEvent = current.pollEvent() ?: break
                    state = reduceEvent(state, event)
                }

                uiState = state
                delay(POLL_INTERVAL_MS)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            uiState = state.copy(
                connected = false,
                state = NativeState.ERROR,
                message = error.message ?: error.toString(),
                positionMs = 0L,
                durationMs = null,
            )
        } finally {
            // 旧协程不能关闭新会话的播放器。
            if (activePlayer === current) {
                closePlayerSession()
            }
        }
    }

    private fun executeCommand(current: NativePlayer, command: DemoCommand) {
        when (command) {
            DemoCommand.Prepare -> current.prepare()
            DemoCommand.Play -> current.play()
            DemoCommand.Pause -> current.pause()
            DemoCommand.Stop -> current.stop()
            is DemoCommand.Seek -> current.seek(command.positionMs)
        }
    }

    private fun reduceEvent(state: DemoUiState, event: NativeEvent): DemoUiState {
        return when (event) {
            is NativeEvent.StateChanged -> {
                val resetTimeline: Boolean = event.current in setOf(
                    NativeState.IDLE,
                    NativeState.PREPARING,
                    NativeState.RELEASED,
                )
                state.copy(
                    state = event.current,
                    positionMs = if (resetTimeline) 0L else state.positionMs,
                    durationMs = if (resetTimeline) null else state.durationMs,
                )
            }

            is NativeEvent.DurationChanged -> state.copy(durationMs = event.durationMs)
            is NativeEvent.AudioProgress -> state.copy(positionMs = event.positionMs)

            is NativeEvent.SeekCompleted -> {
                Log.d(TAG, "Seek 完成：请求 ${event.requestedMs} ms, 落点 ${event.landedMs} ms")
                state.copy(positionMs = event.landedMs)
            }

            is NativeEvent.PlaybackFailed -> state.copy(
                state = NativeState.ERROR,
                message = event.reason,
                positionMs = 0L,
                durationMs = null,
            )

            is NativeEvent.CommandRejected -> state.copy(message = event.reason)
        }
    }

    private fun closePlayerSession() {
        val current: NativePlayer? = activePlayer
        val job: Job? = sessionJob
        var cleanupError: Exception? = null

        // 先撤销所有权，避免协程 finally 重复关闭。
        activePlayer = null
        sessionJob = null
        job?.cancel()
        clearCommands()

        if (current == null) return

        try {
            current.release()
        } catch (error: Exception) {
            cleanupError = error
        }

        // release 失败也必须尝试销毁绑定对象。
        try {
            current.destroy()
        } catch (error: Exception) {
            val firstError: Exception? = cleanupError
            if (firstError == null) {
                cleanupError = error
            } else {
                firstError.addSuppressed(error)
            }
        }

        val failure: Exception? = cleanupError
        if (failure != null) {
            Log.e(TAG, "播放器关闭失败", failure)
        }

        uiState = uiState.copy(
            connected = false,
            positionMs = 0L,
            durationMs = null,
            state = if (failure != null) NativeState.ERROR else uiState.state,
            message = failure?.let { it.message ?: it.toString() } ?: uiState.message,
        )
    }

    private companion object {
        const val TAG = "MoonEgg"
        const val COMMAND_BATCH_SIZE = 16
        const val EVENT_BATCH_SIZE = 64
        const val POLL_INTERVAL_MS = 100L
    }
}
