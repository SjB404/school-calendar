package nl.arch.deltionrooster

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background job that Android runs about every 3 hours, even after a reboot. */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ok = CalendarSync.runAndRecord(applicationContext)
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "deltion-sync"

        fun schedule(ctx: Context, replace: Boolean) {
            val request = PeriodicWorkRequest.Builder(SyncWorker::class.java, 3, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
