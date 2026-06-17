package com.connor.pendant.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.connor.pendant.store.AudioStore
import java.util.concurrent.TimeUnit

class RetentionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = AudioStore(applicationContext)
        return try {
            val n = store.sweepOldSegments()
            Log.i("RetentionWorker", "deleted $n segments")
            Result.success()
        } catch (e: Exception) {
            Log.e("RetentionWorker", "sweep failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val NAME = "pendant-retention"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RetentionWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
