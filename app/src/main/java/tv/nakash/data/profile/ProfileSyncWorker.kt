package tv.nakash.data.profile

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** One profile sync handed to Android when the app leaves the screen, so it runs even if the app is frozen. */
@HiltWorker
class ProfileSyncWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters, private val sync: ProfileSync,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (sync.syncNow()) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
}
