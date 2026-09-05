package eu.kanade.tachiyomi.data.preprocessing

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class PreprocessingJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    private val manager = context.appGraph.preprocessingManager

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_PREPROCESSING_PROGRESS) {
            setContentTitle(applicationContext.stringResource(MR.strings.preprocessing))
            setContentText(applicationContext.stringResource(MR.strings.preprocessing_notification_summary))
            setSmallIcon(R.drawable.ic_book_24dp)
            setOngoing(true)
            setProgress(0, 0, true)
        }.build()
        return ForegroundInfo(
            Notifications.ID_PREPROCESSING_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        val processor = manager.workerStart() ?: return Result.success()
        setForegroundSafely()
        return try {
            manager.isRunning.first { running -> !running }
            Result.success()
        } finally {
            if (isStopped) manager.workerStop(processor)
        }
    }

    companion object {
        private const val TAG = "Preprocessing"

        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                TAG,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PreprocessingJob>().addTag(TAG).build(),
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { jobs -> jobs.any { it.state == WorkInfo.State.RUNNING } }
        }
    }
}
