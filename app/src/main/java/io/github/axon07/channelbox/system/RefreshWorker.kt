package io.github.axon07.channelbox.system

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.axon07.channelbox.container
import java.util.concurrent.TimeUnit

/**
 * Reloads the playlists and TV guide in the background on the interval chosen in Settings, and the
 * TV guide alone on its own interval (Settings → TV guide → Update interval) when it has one.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        if (!s.hasSource) return Result.success()
        val now = System.currentTimeMillis()
        fun due(last: Long, hours: Int) = hours > 0 && now - last >= hours * HOUR - SLACK
        return when {
            due(s.lastRefresh, s.refreshHours) -> if (c.repository.refresh()) Result.success() else Result.retry()
            due(s.lastGuideRefresh, s.guideRefreshHours) -> if (c.repository.refreshGuide()) Result.success() else Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val NAME = "refresh"
        private const val HOUR = 3_600_000L
        /** Runs come a little early or late; a run within half an hour of being due counts. */
        private const val SLACK = 30 * 60_000L

        /** Every [refreshHours] (playlists) and [guideHours] (guide alone); 0 = off for that one. */
        fun schedule(context: Context, refreshHours: Int, guideHours: Int = 0) {
            val hours = listOf(refreshHours, guideHours).filter { it > 0 }.minOrNull() ?: 0
            val workManager = WorkManager.getInstance(context)
            if (hours <= 0) {
                workManager.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
