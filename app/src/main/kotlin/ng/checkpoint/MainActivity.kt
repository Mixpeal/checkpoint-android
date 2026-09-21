package ng.checkpoint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import ng.checkpoint.ui.CheckpointScreen
import ng.checkpoint.ui.CheckpointTheme
import ng.checkpoint.ui.CheckpointViewModel
import ng.checkpoint.ui.Ink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CheckpointTheme {
                val vm: CheckpointViewModel = viewModel()
                CheckpointScreen(
                    vm,
                    Modifier.fillMaxSize().background(Ink.bg).windowInsetsPadding(WindowInsets.systemBars),
                )
            }
        }
    }
}
