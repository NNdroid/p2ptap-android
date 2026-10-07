package app.fjj.p2ptap.tv

import android.content.Context
import android.content.Intent
import android.util.Log
import app.fjj.p2ptap.config.P2PBootFlagStore
import app.fjj.p2ptap.service.P2PTapVpnService

/**
 * The single place that answers "should this set bring the tunnel back up".
 *
 * Three unrelated callers need the same decision: the boot receiver (device
 * just came back), the in-process watchdog (the VPN died while the process
 * lived), and the Shizuku keeper (the whole app died and shell UID resurrected
 * it). Before this existed each of them re-derived the rule inline, and three
 * inline copies of a keep-alive policy is where keep-alive bugs come from:
 * one of them will eventually decide that "user last disconnected" means
 * "keep it off" and another will decide it means "bring it back anyway".
 *
 * The rule is deliberately one lookup and one guard. There is nothing to tune.
 */
object TvTunnelRestore {

    /**
     * @param reason what woke this object up. Logged only; it does not change
     *               the decision, and a misleading log line here would send
     *               whoever reads logcat after a bad night down the wrong tree.
     * @return true if a start request was issued.
     */
    fun restoreIfNeeded(context: Context, reason: String): Boolean {
        if (!P2PBootFlagStore.isAutostart(context)) {
            Log.i(TAG, "Skip restore (${reason}): user last left the tunnel off")
            return false
        }
        if (P2PTapVpnService.isRunning()) {
            Log.i(TAG, "Skip restore (${reason}): tunnel already running")
            return false
        }
        // startForegroundService rather than startService: minSdk is 31, so
        // the pre-O fallback would be dead code, and a background start without
        // it would throw on Android 12+.
        val start = Intent(context, P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_START
        }
        context.startForegroundService(start)
        TvKeepAliveState.recordRestore(context)
        Log.i(TAG, "Restore issued (${reason})")
        return true
    }

    private const val TAG = "TvTunnelRestore"
}
