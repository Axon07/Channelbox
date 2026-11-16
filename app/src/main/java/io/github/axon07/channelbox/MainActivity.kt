package io.github.axon07.channelbox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.content.res.Configuration
import io.github.axon07.channelbox.system.SetTopBox
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

    /** Home pressed while watching: keep playing in a small window if that's switched on. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.settings.value?.pipOnLeave == true && vm.current.value != null && vm.player.exo.isPlaying) {
            SetTopBox.enterPip(this)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip.value = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
        vm.resumePendingUpdate()
    }

    override fun onStop() {
        // Stop the stream when the user leaves (Home, screensaver) so it doesn't keep downloading.
        vm.onBackground()
        super.onStop()
    }
}
