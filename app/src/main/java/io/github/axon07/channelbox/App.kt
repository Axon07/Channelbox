package io.github.axon07.channelbox

import android.app.Application
import android.content.Context
import io.github.axon07.channelbox.data.AppDatabase
import io.github.axon07.channelbox.data.Recorder
import io.github.axon07.channelbox.data.Updater
import io.github.axon07.channelbox.data.SettingsStore
import io.github.axon07.channelbox.data.TvRepository
import io.github.axon07.channelbox.data.createHttpClient
import io.github.axon07.channelbox.system.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            RefreshWorker.schedule(this@App, container.settings.current().refreshHours)
            container.recorder.tidyUp()
        }
    }
}

/** Hand-rolled dependency container — small enough that a DI framework isn't worth it. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val database = AppDatabase.create(context)
    val http = createHttpClient()
    val repository = TvRepository(database, settings, http)
    val recorder = Recorder(context, database, http)
    val updater = Updater(context, http)
}

val Context.container: AppContainer get() = (applicationContext as App).container
