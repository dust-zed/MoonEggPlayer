package io.github.dustzed.moonegg.demo

import android.view.Surface
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.dustzed.moonegg.bindings.NativeState
import io.github.dustzed.moonegg.player.VideoSurfaceView

private const val DEMO_SEEK_POSITION_MS: Long = 4_000L

@Composable
internal fun PlayerDemo(
    ui: DemoUiState,
    onCommand: (DemoCommand) -> Unit,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceLost: () -> Unit,
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
        Text("MoonEgg · MP4 播放")
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

        PlayerVideoSurface(
            onSurfaceReady = onSurfaceReady,
            onSurfaceLost = onSurfaceLost,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )
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

@Composable
private fun PlayerVideoSurface(
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceLost: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            val view: VideoSurfaceView = VideoSurfaceView(context)
            view
        },
        update = { view ->
            view.onSurfaceReady = onSurfaceReady
            view.onSurfaceLost = onSurfaceLost
        },
        modifier = modifier
    )
}
