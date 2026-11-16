package io.github.axon07.channelbox

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.github.axon07.channelbox.data.AppDatabase
import io.github.axon07.channelbox.data.NetworkSettings
import io.github.axon07.channelbox.data.Recorder
import io.github.axon07.channelbox.data.RecordingStorage
import io.github.axon07.channelbox.data.SettingsStore
import io.github.axon07.channelbox.data.TvRepository
import io.github.axon07.channelbox.data.Updater
import io.github.axon07.channelbox.data.UserAgent
import io.github.axon07.channelbox.data.createHttpClient
import io.github.axon07.channelbox.system.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val settings = SettingsStore(this)
        // Network options (proxy, DNS, user agent) are read once here; changing them restarts the app.
        val network = runBlocking { settings.current().network }
        UserAgent.apply(network.userAgent)
        container = AppContainer(this, settings, network)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            RefreshWorker.schedule(this@App, container.settings.current().refreshHours)
            container.recorder.tidyUp()
        }
    }

    /** Channel logos also go through the app's HTTP client (proxy / DNS / user agent). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient { container.http }.build()
}

/** Hand-rolled dependency container — small enough that a DI framework isn't worth it. */
class AppContainer(context: Context, val settings: SettingsStore, val startupNetwork: NetworkSettings) {
    val database = AppDatabase.create(context)
    val http = createHttpClient(startupNetwork)
    val repository = TvRepository(database, settings, http)
    val storage = RecordingStorage(context, http, settings)
    val recorder = Recorder(context, database, http, storage)
    val updater = Updater(context, http)
}

val Context.container: AppContainer get() = (applicationContext as App).container
