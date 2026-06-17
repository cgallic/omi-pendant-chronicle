package com.connor.pendant.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.connor.pendant.service.PendantForegroundService

/**
 * Fires a one-shot Health Connect 30-day backfill on broadcast.
 *
 * The receiver itself does NOT call Health Connect — HC denies reads from transient
 * broadcast processes ("requires permission to read data from other applications").
 * Instead it dispatches to the already-running [PendantForegroundService], whose
 * foreground status satisfies HC's caller-app check.
 *
 * Trigger from a host with adb:
 *
 *     adb shell am broadcast -a com.connor.pendant.RUN_HEALTH_BACKFILL \
 *         -n com.connor.pendant/.health.BackfillReceiver
 *
 * Optional override:
 *     -e days 14
 *
 * Result lands in logcat under tag "HealthConnectBackfill" + "PendantFgService".
 */
class BackfillReceiver : BroadcastReceiver() {

    private val tag = "BackfillReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val days = intent.getIntExtra("days", 30).coerceIn(1, 365)
        Log.i(tag, "received ${intent.action} days=$days — dispatching to foreground service")

        val svc = Intent(context, PendantForegroundService::class.java).apply {
            action = PendantForegroundService.ACTION_RUN_BACKFILL
            putExtra(PendantForegroundService.EXTRA_DAYS, days)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }

    companion object {
        const val ACTION = "com.connor.pendant.RUN_HEALTH_BACKFILL"
    }
}
