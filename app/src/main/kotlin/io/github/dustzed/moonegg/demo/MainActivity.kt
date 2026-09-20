package io.github.dustzed.moonegg.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

private enum class DemoCommand {
    PREPARE, PLAY, PAUSE, STOP
}

private data class DemoUiState(
    val connected: Boolean = false,
    val state: NativeState = NativeState.IDLE,
    val positionMs: Long = 0,
    val message: String = ""
)

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
            val wavFile = File(filesDir, "demo.wav")

            assets.open("demo.wav").use { input ->
                wavFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val current = NativePlayer(wavFile.absolutePath)
            player = current

            state = state.copy(connected = true)
            publish(state)

            while (currentCoroutineContext().isActive) {
                for (index in 0 until 16) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    state = state.copy(message = "")
                    when(command) {
                        DemoCommand.PREPARE -> current.prepare()
                        DemoCommand.PLAY -> current.play()
                        DemoCommand.PAUSE -> current.pause()
                        DemoCommand.STOP -> current.stop()
                    }
                }

                for (index in 0 until 64) {
                    val event = current.pollEvent() ?: break

                    state = when (event) {
                        is NativeEvent.StateChanged -> {
                            state.copy(
                                state = event.current,
                                positionMs = if (event.current == NativeState.IDLE) {
                                    0L
                                } else {
                                    state.positionMs
                                }
                            )
                        }

                        is NativeEvent.AudioProgress -> {
                            state.copy(positionMs = event.positionMs)
                        }

                        is NativeEvent.PlaybackFailed -> {
                            state.copy(
                                state = NativeState.ERROR,
                                message = event.reason
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
                    message = error.message ?: error.toString()
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
                "状态：{ui.state}"
            } else {
                "播放器未连接"
            }
        )
        Text("进度：${ui.positionMs / 1000.0} 秒")

        Button(
            enabled = ui.connected &&
            ui.state in setOf(NativeState.IDLE),
            onClick = { onCommand(DemoCommand.PREPARE) }
        ) {
            Text("准备")
        }

        Button(
            enabled = ui.connected &&
            ui.state in setOf(NativeState.READY, NativeState.PAUSED),
            onClick = { onCommand(DemoCommand.PLAY) }
        ) {
            Text("播放")
        }

        Button(
            enabled = ui.connected &&
            ui.state == NativeState.PLAYING,
            onClick = { onCommand(DemoCommand.PAUSE)}
        ) {
            Text("暂停")
        }

        Button(
            enabled = ui.connected &&
            ui.state !in setOf(NativeState.IDLE, NativeState.RELEASED),
            onClick = { onCommand(DemoCommand.STOP) }
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