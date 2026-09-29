package io.github.dustzed.moonegg.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.dustzed.moonegg.bindings.NativeEvent
import io.github.dustzed.moonegg.bindings.NativePlayer
import io.github.dustzed.moonegg.bindings.NativeState
import io.github.dustzed.moonegg.demo.ui.theme.MoonEggPlayerTheme
import io.github.dustzed.moonegg.player.MoonEggInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.Compiler.command
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private sealed interface DemoCommand {
    data object Prepare : DemoCommand
    data object Play : DemoCommand
    data object Pause : DemoCommand
    data object Stop : DemoCommand

    data class Seek(
        val positionMs: Long
    ) : DemoCommand
}

private data class DemoUiState(
    val connected: Boolean = false,
    val state: NativeState = NativeState.IDLE,
    val positionMs: Long = 0,
    val message: String = "",
    val durationMs: Long? = null,
)
private const  val DEMO_SEEK_POSITION_MS: Long = 4_000L

class MainActivity : ComponentActivity() {

    private var uiState by mutableStateOf(DemoUiState())

    private val commands = Channel<DemoCommand>(capacity = 16)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MoonEggPlayerTheme{
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { padding ->
                    PlayerDemo(ui = uiState,
                        onCommand = { command ->
                            if (commands.trySend(command).isFailure) {
                                Log.w("MoonEgg", "命令队列已满")
                            }
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                uiState = DemoUiState()

                while (commands.tryReceive().isSuccess) {

                }

                withContext(Dispatchers.IO) {
                    runPlayerSession()
                }
            }
        }
    }

    private suspend fun publish(state: DemoUiState) {
        withContext(Dispatchers.Main.immediate) {
            uiState = state
        }
    }

    private suspend fun runPlayerSession() {
        var player: NativePlayer? = null
        var state = DemoUiState()

        try {
            val current = createAacDemoPlayer()
            player = current

            state = state.copy(connected = true)
            publish(state)

            while (currentCoroutineContext().isActive) {
                for (index in 0 until 16) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    state = state.copy(message = "")
                    when(command) {
                        DemoCommand.Prepare -> current.prepare()
                        DemoCommand.Play -> current.play()
                        DemoCommand.Pause -> current.pause()
                        DemoCommand.Stop -> current.stop()
                        is DemoCommand.Seek -> current.seek(command.positionMs)
                    }
                }

                for (index in 0 until 64) {
                    val event = current.pollEvent() ?: break

                    state = when (event) {
                        is NativeEvent.StateChanged -> {
                            val resetTimeline = event.current in setOf(NativeState.IDLE,
                                NativeState.PREPARING, NativeState.RELEASED)

                            state.copy(
                                state = event.current,
                                positionMs = if (resetTimeline) {
                                    0L
                                } else {
                                    state.positionMs
                                },
                                durationMs = if (resetTimeline) {
                                    null
                                } else {
                                    state.durationMs
                                }
                            )
                        }
                        is NativeEvent.DurationChanged -> {
                            state.copy(durationMs = event.durationMs)
                        }
                        is NativeEvent.AudioProgress -> {
                            state.copy(positionMs = event.positionMs)
                        }

                        is NativeEvent.SeekCompleted -> {
                            Log.d("MoonEgg","Seek 完成： 请求 ${event.requestedMs} ms, 落点 ${event.landedMs} ms")
                            state.copy(positionMs = event.landedMs)
                        }

                        is NativeEvent.PlaybackFailed -> {
                            state.copy(
                                state = NativeState.ERROR,
                                message = event.reason,
                                positionMs = 0L,
                                durationMs = null
                            )
                        }

                        is NativeEvent.CommandRejected -> {
                            state.copy(message = event.reason)
                        }
                    }
                }
                publish(state)
                delay(100.milliseconds)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(
                state.copy(
                    connected = false,
                    state = NativeState.ERROR,
                    message = error.message ?: error.toString(),
                    positionMs = 0L,
                    durationMs = null
                )
            )
        } finally {
            player?.let { current ->
                try {
                    current.release()
                    Log.d("MoonEgg", "Rust 播放器已正常释放")
                } catch (error: Exception) {
                    Log.e("MoonEgg", "Rust 播放器释放失败", error)
                } finally {
                    current.destroy()
                }
            }
        }
    }

    private fun createAacDemoPlayer(): NativePlayer {
        return assets.openFd("demo-nonnegative.m4a").use { asset ->
            val fd = asset.parcelFileDescriptor.fd
            val offset = asset.startOffset
            val length = asset.declaredLength
            require(length >= 0) { "WAV asset 长度未知" }
            Log.d("MoonEgg", "asset offset: $offset, length: $length")

            val created = NativePlayer.fromAacFileDescriptor(fd, offset, length)

            created
        }
    }
}

@Composable
fun Greeting(description: String, modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = description,
            modifier = modifier
        )
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MoonEggPlayerTheme {
        Greeting("Android")
    }
}

@Composable
private fun PlayerDemo(
    ui: DemoUiState,
    onCommand: (DemoCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    val canSeek = ui.connected && ui.state in setOf(NativeState.READY, NativeState.PLAYING,
        NativeState.PAUSED, NativeState.ENDED)
    val durationText = if (ui.durationMs == null) "未知" else "${ui.durationMs / 1000.0} 秒"
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            space = 12.dp,
            alignment = Alignment.CenterVertically
        )
    ) {
        Text("MoonEgg . WAV 模拟播放")
        Text(
            if (ui.connected) {
                "状态：${ui.state}"
            } else {
                "播放器未连接"
            }
        )
        Text("进度：${ui.positionMs / 1000.0} 秒 / $durationText")

        PlaybackSeekBar(
            positionMs = ui.positionMs,
            durationMs = ui.durationMs,
            enabled = canSeek,
            onSeek = { targetMs ->
                onCommand(DemoCommand.Seek(targetMs))
            },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = ui.connected &&
            ui.state in setOf(NativeState.IDLE),
            onClick = { onCommand(DemoCommand.Prepare) }
        ) {
            Text("准备")
        }

        Button(
            enabled = ui.connected &&
            ui.state in setOf(NativeState.READY, NativeState.PAUSED),
            onClick = { onCommand(DemoCommand.Play) }
        ) {
            Text("播放")
        }

        Button(
            enabled = ui.connected &&
            ui.state == NativeState.PLAYING,
            onClick = { onCommand(DemoCommand.Pause)}
        ) {
            Text("暂停")
        }

        Button(
            enabled = canSeek,
            onClick = { onCommand(DemoCommand.Seek(0))}
        ) {
            Text("回到开头")
        }

        Button(
            enabled = canSeek,
            onClick = { onCommand(DemoCommand.Seek(DEMO_SEEK_POSITION_MS))}
        ) {
            Text("跳到 4 秒")
        }

        Button(
            enabled = ui.connected &&
            ui.state !in setOf(NativeState.IDLE, NativeState.RELEASED),
            onClick = { onCommand(DemoCommand.Stop) }
        ) {
            Text("停止")
        }

        if (ui.message.isNotEmpty()) {
            Text(
                text = ui.message,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PlaybackSeekBar(
    positionMs: Long,
    durationMs: Long?,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalDurationMs = (durationMs ?: 0L).coerceAtLeast(0L)
    val sliderEnabled = enabled && totalDurationMs > 0L
    val playbackFraction = if (totalDurationMs > 0L) {
        (positionMs.toDouble() / totalDurationMs)
            .coerceIn(0.0, 1.0)
            .toFloat()
    } else {
        0f
    }

    var dragFraction by remember(durationMs, sliderEnabled) {
        mutableStateOf<Float?>(null)
    }

    val displayedFraction: Float = dragFraction ?: playbackFraction

    val interactionSource = remember(durationMs, sliderEnabled) {
        MutableInteractionSource()
    }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Cancel -> {
                    dragFraction = null
                }
                else -> {}
            }
        }
    }

    Slider(
        value = displayedFraction,
        onValueChange = { fraction ->
            if (sliderEnabled) {
                dragFraction = fraction.coerceIn(0f, 1f)
            }
        },
        onValueChangeFinished = {
            val targetFraction = dragFraction

            dragFraction = null

            if (sliderEnabled && targetFraction != null) {
                val targetMs = (targetFraction.toDouble() * totalDurationMs).toLong().coerceIn(0L, totalDurationMs)
                onSeek(targetMs)
            }
        },
        enabled = sliderEnabled,
        valueRange = 0f..1f,
        interactionSource = interactionSource,
        modifier = modifier
    )
}