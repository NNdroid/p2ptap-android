package app.fjj.p2ptap.tv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import app.fjj.p2ptap.config.P2PBootFlagStore
import app.fjj.p2ptap.service.P2PTapVpnService

/**
 * L2 keep-alive: an in-process watchdog that notices the VPN dying while the
 * app's own process is still alive.
 *
 * Why it is a second foreground service rather than a timer inside the VPN
 * service: a timer inside the service it is supposed to rescue is the classic
 * "watchdog that dies with the thing it watches". Running as its own FGS means
 * the VPN can stop, fail, or throw while the watchdog keeps polling. It also
 * means `START_STICKY` gives the watchdog its own resurrection, independent of
 * the VPN's.
 *
 * What this does NOT cover: a kill of this app's whole uid. When the box runs
 * out of memory or the user clears the app, the watchdog dies with it —
 * nothing inside a process can observe the process ending. That gap is L3, and
 * it is stated on the keep-alive screen rather than papered over here.
 *
 * Why a 30 s poll and not an observer: the tunnel's own failure states are
 * reported through the state repository, which is the right source for the UI
 * but not the right one for a decision. Whether a tunnel is *useful* is
 * better approximated by "is the VPN service still running", which needs no
 * second source of truth to get wrong. Thirty seconds is long enough that the
 * poll is free and short enough that a viewer sitting at the sofa would call
 * the tunnel "back".
 */
class TvWatchdogService : Service() {

    private val handlerThread = HandlerThread("p2ptap-watchdog").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * The one thing this service is for. Kept private and re-run on every
     * interval rather than fired from a chain of postDelayed calls, so a
     * missed tick cannot desynchronise the service from its own state.
     */
    private val poll = object : Runnable {
        override fun run() {
            // First, unconditionally: this tick is how the keep-alive screen
            // distinguishes "armed and working" from "armed and dead".
            TvKeepAliveState.heartbeat(this@TvWatchdogService)

            if (P2PBootFlagStore.isAutostart(this@TvWatchdogService) &&
                !P2PTapVpnService.isRunning()
            ) {
                Log.w(TAG, "VPN not running while autostart is set; restoring")
                TvTunnelRestore.restoreIfNeeded(this@TvWatchdogService, "watchdog poll")
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISARM -> {
                disarm()
                return START_NOT_STICKY
            }
        }

        if (!startForegroundCompat()) return START_NOT_STICKY

        // Harmless if nothing was posted: re-arming is idempotent, and
        // removeCallbacks returns void in the SDK this compiles against, so
        // there is nothing to branch on.
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_INTERVAL_MS)
        scheduleRearm()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        cancelRearm()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Foreground declaration ──────────────────────────────────────────────

    /**
     * This service is the second foreground service this app can have: the VPN
     * service already holds one. There is no documented per-app cap on the
     * count, but there are boxes that enforce one, and the failure mode here
     * is lopsided in a bad direction — if the cap is hit, throwing out of
     * startForeground would end the process and take the VPN session with it.
     *
     * So the call is guarded, the degraded state is recorded for the
     * keep-alive screen to show, and the poll loop simply does not run. L1
     * (boot restore) and L3 (Shizuku) still work, which is the honest
     * remaining behaviour.
     *
     * @return false when the foreground declaration failed, in which case the
     *         caller must not start the poll loop.
     */
    private fun startForegroundCompat(): Boolean {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv_keepalive)
            .setContentTitle(getString(R.string.tv_ka_running))
            .setContentText(getString(R.string.tv_ka_watchdog_desc))
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
        try {
            startForeground(NOTIFICATION_ID, builder.build())
            TvKeepAliveState.persist(this, TvKeepAliveState.WatchdogState.RUNNING)
            return true
        } catch (e: Exception) {
            // TooManyForegroundServicesException and its platform cousins all
            // land here. Logged with the cause so it can be recognised on a box
            // that hits it, rather than guessed at later.
            Log.e(TAG, "Foreground declaration failed; watchdog degraded to L1+L3 only", e)
            TvKeepAliveState.persist(
                this, TvKeepAliveState.WatchdogState.DEGRADED,
                e.javaClass.simpleName
            )
            return false
        }
    }

    // ── Self-rearm ────────────────────────────────────────────────────────────

    /**
     * AlarmManager backstop for the gap between "this process died" and
     * "the system decided to restart us".
     *
     * Inexact and coarse on purpose. A 5 minute window is far better than no
     * backstop, and asking for an exact alarm would trade that for a settings
     * hop on Android 13+ (canScheduleExactAlarms is false by default there)
     * which a TV user would never complete. The watchdog's own 30 s poll
     * covers the case that matters most — the process alive, the VPN gone.
     */
    private fun scheduleRearm() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = PendingIntent.getService(
            this, 0,
            Intent(this, TvAlarmReceiver::class.java).setAction(TvAlarmReceiver.ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val whenMs = System.currentTimeMillis() + REARM_WINDOW_MS
        try {
            am.setWindow(
                AlarmManager.RTC_WAKEUP, whenMs,
                ALARM_WINDOW_SLACK_MS, pending
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Alarm scheduling denied; relying on START_STICKY only", e)
        }
    }

    private fun cancelRearm() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = PendingIntent.getService(
            this, 0,
            Intent(this, TvAlarmReceiver::class.java).setAction(TvAlarmReceiver.ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pending)
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    private fun disarm() {
        TvKeepAliveState.persist(this, TvKeepAliveState.WatchdogState.STOPPED)
        handler.removeCallbacks(poll)
        cancelRearm()
        stopForeground(true)
        stopSelf()
    }

    companion object {
        const val TAG = "TvWatchdogService"

        const val ACTION_ARM = "app.fjj.p2ptap.tv.WATCHDOG_ARM"
        const val ACTION_DISARM = "app.fjj.p2ptap.tv.WATCHDOG_DISARM"

        const val CHANNEL_ID = "p2ptap_watchdog"
        const val NOTIFICATION_ID = 102

        const val POLL_INTERVAL_MS = 30_000L
        const val REARM_WINDOW_MS = 5 * 60_000L
        const val ALARM_WINDOW_SLACK_MS = 5 * 60_000L

        fun startWatchdog(context: Context) {
            ensureChannel(context)
            context.startForegroundService(
                Intent(context, TvWatchdogService::class.java).apply {
                    action = ACTION_ARM
                }
            )
        }

        fun stopWatchdog(context: Context) {
            context.startService(
                Intent(context, TvWatchdogService::class.java).apply {
                    action = ACTION_DISARM
                }
            )
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.tv_ka_watchdog),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
