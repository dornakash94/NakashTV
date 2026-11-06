package tv.nakash.data.repo

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Daily catalog refresh (live, VOD, series) + XMLTV. Diff-based: Room upserts, missing channels are deactivated. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val catalog: CatalogRepository, private val epg: EpgRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Scheduled at app start, so on a new install it first runs before anyone signed in: nothing to do yet
        // (retrying only failed three times and then fought the sign-in screen's own first sync).
        if (!catalog.signedIn) return Result.success()
        val results = listOf(
            runCatching { catalog.syncLive() }, runCatching { catalog.syncVod() },
            runCatching { catalog.syncSeries() }, runCatching { epg.syncXmltv() },
        )
        return if (results.all { it.isSuccess }) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("catalog-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
