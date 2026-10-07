package app.fjj.p2ptap.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Where the watchdog's self-rearm lands. Single job, on purpose.
 *
 * Boot is handled by TvBootReceiver rather than here, so there is exactly one
 * receiver per action. Two receivers both claiming BOOT_COMPLETED would double
 * the restore path and make it unclear from logcat which one acted.
 *
 * exported="false": the only sender is this app's own AlarmManager PendingIntent,
 * which does not need to be reachable from other apps, and an exported receiver
 * that starts a foreground service on demand is the shape of a background-launch
 * vector that this app should not have to think about.
 */
class TvAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) {
            Log.w(TAG, "Unexpected action ${intent.action}")
            return
        }

        val state = TvKeepAliveState.resolve(context)
        if (state == TvKeepAliveState.WatchdogState.STOPPED) {
            // The user chose to stop the watchdog. A tick must not undo that
            // silently, even though the alarm fired.
            Log.i(TAG, "Tick with watchdog stopped; leaving it stopped")
            return
        }

        when (state) {
            TvKeepAliveState.WatchdogState.PROCESS_GONE ->
                Log.i(TAG, "Tick with watchdog process gone; re-arming")
            TvKeepAliveState.WatchdogState.DEGRADED ->
                // Retry on purpose: the box may free a foreground slot later,
                // which is exactly the moment a degraded watchdog should get
                // another go.
                Log.i(TAG, "Tick with watchdog degraded; retrying")
            TvKeepAliveState.WatchdogState.RUNNING ->
                // The service re-armed itself, so this is a harmless overlap.
                Log.i(TAG, "Tick with watchdog already running; no-op")
            else -> Unit
        }
        TvWatchdogService.startWatchdog(context)
    }

    companion object {
        const val TAG = "TvAlarmReceiver"
        const val ACTION_TICK = "app.fjj.p2ptap.tv.WATCHDOG_TICK"
    }
}
