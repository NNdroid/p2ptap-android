package app.fjj.p2ptap.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.fjj.p2ptap.tv.keeper.TvKeeperService

/**
 * The receiving end of the keeper's broadcast, and the only reason the keeper
 * broadcasts rather than calling a service directly.
 *
 * This receiver is the policy point of L3. The keeper runs under shell UID and
 * cannot read this app's state, so it sends a single word and this receiver
 * decides. Keeping the decision here means all three keep-alive layers — boot,
 * watchdog, keeper — reduce to one function, and a change to the rule cannot
 * silently diverge between them.
 *
 * exported="true" is required, not defensive: the sender holds shell UID, which
 * the framework treats as a different package, so a non-exported receiver would
 * never see the broadcast and the whole layer would be a no-op. The surface is
 * inert by construction — see TvKeeperService for why that is acceptable rather
 * than something to lock down further.
 *
 * The class name is referenced as a string from TvKeeperService, which may run
 * in a process whose class loader reaches this class but resolves it lazily.
 * Keep the two in sync; a mismatch here is invisible until a box restarts.
 */
class TvKeeperReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvKeeperService.ACTION_KEEPER_RESTORE) {
            Log.w(TAG, "Unexpected action ${intent.action}")
            return
        }

        val issued = TvTunnelRestore.restoreIfNeeded(context, "keeper broadcast")

        // Also arm L2. A box that just lost this app's process has lost its
        // watchdog with it, and restoring only the tunnel would leave the next
        // failure unrecoverable without Shizuku.
        if (issued) TvWatchdogService.startWatchdog(context)
    }

    private companion object {
        const val TAG = "TvKeeperReceiver"
    }
}
