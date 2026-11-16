package io.github.axon07.channelbox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.github.axon07.channelbox.ui.ChannelboxTheme
import io.github.axon07.channelbox.ui.TvApp
import io.github.axon07.channelbox.ui.TvViewModel

class MainActivity : ComponentActivity() {

    private val vm: TvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ChannelboxTheme {
                TvApp(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        // Stop the stream when the user leaves (Home, screensaver) so it doesn't keep downloading.
        vm.onBackground()
        super.onStop()
    }
}
