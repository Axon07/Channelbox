package io.github.axon07.channelbox.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.axon07.channelbox.MainActivity

/** Helpers that make the app behave like a dedicated set-top box. */
object SetTopBox {

    private fun homeAlias(context: Context) = ComponentName(
        context.packageName,
        MainActivity::class.java.name.substringBeforeLast('.') + ".HomeAlias",
    )

    fun isHomeScreen(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(homeAlias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Registers (or unregisters) the app as a launcher. Android asks which home app to use on the next Home press. */
    fun setHomeScreen(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            homeAlias(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Android 10+ blocks opening an app from the background unless it may draw over other apps. */
    fun needsOverlayPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun canStartFromBackground(context: Context): Boolean =
        !needsOverlayPermission() || Settings.canDrawOverlays(context)

    /** Opens the system screen to grant the permission. Returns false if the TV firmware hides it. */
    fun openOverlaySettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun adbGrantCommand(context: Context) =
        "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
}
