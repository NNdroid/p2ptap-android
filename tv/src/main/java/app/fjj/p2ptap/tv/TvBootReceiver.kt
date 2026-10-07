package app.fjj.p2ptap.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * L1 keep-alive: the plainest form of "still alive after a reboot".
 *
 * It answers one question — did the user want the tunnel up? — using the flag
 * the service writes on every state change, and if so starts it back up. That
 * is the whole thing, deliberately. It costs no permission the user must grant,
 * survives with Shizuku absent, and it is what makes the Shizuku layers worth
 * having at all: they only matter if there is a recorded intent to resume.
 *
 * The decision itself lives in TvTunnelRestore, because the watchdog and the
 * Shizuku keeper reach the same question from different directions.
 */
class TvBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON, ACTION_POWER_CONNECTED -> Unit
            else -> return
        }

        // Also arm L2: a restart alone leaves the app with no watchdog to notice
        // the VPN dying again, which is how a tunnel that survived one kill
        // usually dies on the second.
        context.startForegroundService(
            Intent(context, TvWatchdogService::class.java).apply {
                action = TvWatchdogService.ACTION_ARM
            }
        )

        TvTunnelRestore.restoreIfNeeded(context, "after ${intent.action}")
    }

    private companion object {
        const val TAG = "TvBootReceiver"

        // Spelled out as literals rather than Intent.ACTION_* /
        // PowerManager.ACTION_*: the compile SDK (37) has removed
        // ACTION_QUICKBOOT_POWERON and PowerManager.ACTION_POWER_CONNECTED
        // from android.jar entirely, so the named constants do not resolve.
        // BOOT_COMPLETED could be named, but mixing styles for no benefit
        // would be harder to read than spelling all three.
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_POWER_CONNECTED = "android.intent.action.POWER_CONNECTED"
    }
}
