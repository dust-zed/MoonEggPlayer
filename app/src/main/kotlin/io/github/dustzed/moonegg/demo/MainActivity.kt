package io.github.dustzed.moonegg.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.dustzed.moonegg.demo.ui.theme.MoonEggPlayerTheme

class MainActivity : ComponentActivity() {
    private lateinit var controller: DemoPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = DemoPlayerController(
            assets = assets,
            scope = lifecycleScope,
        )

        setContent {
            MoonEggPlayerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) { padding ->
                    PlayerDemo(
                        ui = controller.uiState,
                        onCommand = controller::submit,
                        onSurfaceReady = controller::onSurfaceReady,
                        onSurfaceLost = controller::onSurfaceLost,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onForegroundChanged(true)
    }

    override fun onStop() {
        controller.onForegroundChanged(false)
        super.onStop()
    }
}
