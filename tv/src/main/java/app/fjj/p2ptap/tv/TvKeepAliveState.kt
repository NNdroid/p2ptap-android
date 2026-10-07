package app.fjj.p2ptap.tv

import android.content.Context
import androidx.annotation.StringRes

/**
 * What the keep-alive screen shows, kept in one place.
 *
 * Every layer writes into here and the screen reads from here, so a bad night
 * that leaves a box with a dead watchdog and a dead tunnel produces a screen
 * that says exactly that instead of one that says "all good". Persisting the
 * values as well as holding them in memory is the load-bearing part: a process
 * that dies without cleaning up leaves its last state on disk, which is the
 * only evidence the screen has that the layer was alive at all.
 *
 * Read-mostly and written from service threads, so the fields are volatile
 * and the screen snapshots them on its own thread rather than taking a lock.
 */
object TvKeepAliveState {

    enum class WatchdogState {
        /** Never started, or deliberately stopped. */
        STOPPED,
        /** Foreground service live, poll loop running. */
        RUNNING,
        /**
         * Armed but unable to declare itself a foreground service, so the poll
         * loop is not running. Everything else on the box is unaffected.
         */
        DEGRADED,
        /**
         * The process is gone. This value is only ever inferred: it is what
         * the screen concludes when the last persisted state was RUNNING and
         * the process is no longer there. It is the single most useful line on
         * the screen, because it is the precise condition L3 exists for.
         */
        PROCESS_GONE
    }

    @Volatile var watchdog: WatchdogState = WatchdogState.STOPPED
    @Volatile var watchdogDetail: String = ""
    @Volatile var lastRestoredAt: Long = 0L

    /**
     * How stale a heartbeat has to be before "running" becomes "process gone".
     * Deliberately wide: the poll itself is the heartbeat, and one skipped
     * tick should not read as a dead process.
     */
    const val HEARTBEAT_STALE_MS = 90_000L

    private const val PREFS = "p2ptap_keepalive"
    private const val KEY_WD_STATE = "watchdog_state"
    private const val KEY_WD_DETAIL = "watchdog_detail"
    private const val KEY_HEARTBEAT = "watchdog_heartbeat"
    const val KEY_LAST_RESTORED = "last_restored_at"

    fun persist(context: Context, state: WatchdogState, detail: String = "") {
        watchdog = state
        watchdogDetail = detail
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WD_STATE, state.name)
            .putString(KEY_WD_DETAIL, detail)
            .apply()
    }

    /**
     * Called on every poll, which is why a missing call is meaningful: this
     * service ticks unconditionally while armed, so silence is evidence of a
     * process death rather than of inactivity.
     */
    fun heartbeat(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    fun recordRestore(context: Context) {
        val now = System.currentTimeMillis()
        lastRestoredAt = now
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_RESTORED, now)
            .apply()
    }

    /**
     * Resolve the on-disk state against the heartbeat. Call from a UI thread;
     * the prefs read is small.
     */
    fun resolve(context: Context): WatchdogState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WD_STATE, WatchdogState.STOPPED.name)
            ?.let { runCatching { WatchdogState.valueOf(it) }.getOrNull() }
            ?: WatchdogState.STOPPED

        watchdogDetail = prefs.getString(KEY_WD_DETAIL, "") ?: ""
        lastRestoredAt = prefs.getLong(KEY_LAST_RESTORED, 0L)

        if (stored == WatchdogState.RUNNING) {
            val beat = prefs.getLong(KEY_HEARTBEAT, 0L)
            val age = System.currentTimeMillis() - beat
            watchdog = if (beat > 0L && age < HEARTBEAT_STALE_MS) {
                WatchdogState.RUNNING
            } else {
                WatchdogState.PROCESS_GONE
            }
        } else {
            watchdog = stored
        }
        return watchdog
    }

    @StringRes
    fun watchdogLabel(state: WatchdogState): Int = when (state) {
        WatchdogState.STOPPED -> R.string.tv_ka_stopped
        WatchdogState.RUNNING -> R.string.tv_ka_running
        WatchdogState.DEGRADED -> R.string.tv_ka_disabled
        WatchdogState.PROCESS_GONE -> R.string.tv_ka_missing
    }
}
