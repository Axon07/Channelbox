package io.github.axon07.channelbox.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.axon07.channelbox.MainActivity
import io.github.axon07.channelbox.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opens live TV when the box powers on, if "Start on boot" is enabled. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (context.container.settings.current().startOnBoot) {
                    // Give the stock launcher a moment to finish starting, or it may cover us.
                    delay(3_000)
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            } catch (_: Exception) {
                // Background start blocked (Android 10+ without overlay permission) – nothing else to do.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
