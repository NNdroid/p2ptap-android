package app.fjj.p2ptap.service

import app.fjj.p2ptap.i18n.UiMessages

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.fjj.p2ptap.core.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PBootFlagStore
import app.fjj.p2ptap.config.P2PConfig
import com.p2ptap.P2PTap.P2PTap
import com.p2ptap.P2PTap.InterfaceProvider
import com.p2ptap.P2PTap.Protector
import com.p2ptap.P2PTap.StateListener
import com.p2ptap.P2PTap.ConfigStore
import com.p2ptap.P2PTap.LogCallback
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray

class P2PTapVpnService : VpnService(), Protector, StateListener, InterfaceProvider, LogCallback {

    companion object {
        const val TAG = "P2PTapVpnService"
        const val ACTION_START = "app.fjj.p2ptap.START"
        const val ACTION_STOP = "app.fjj.p2ptap.STOP"
        const val ACTION_RELOAD = "app.fjj.p2ptap.RELOAD"
        const val ACTION_STATE_CHANGED = "app.fjj.p2ptap.STATE_CHANGED"
        const val ACTION_APP_BACKGROUND = "app.fjj.p2ptap.APP_BACKGROUND"
        const val ACTION_APP_FOREGROUND = "app.fjj.p2ptap.APP_FOREGROUND"

        const val EXTRA_STATE = "extra_state"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_FORCE_RESTART = "extra_force_restart"

        const val STATE_IDLE = "IDLE"
        const val STATE_STARTING = "STARTING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_STOPPING = "STOPPING"
        const val STATE_TIMEOUT = "TIMEOUT"
        const val STATE_ERROR = "ERROR"

        // Gives up on the native teardown this long after a stop was requested.
        // The engine's Close() is bounded (worst case ~17s of sequential caps),
        // so this only trips if something genuinely wedged — and a wedged stop
        // is worse than leaving the native teardown to finish unobserved.
        // The 3 s margin over 17 s covers the gap between the watchdog firing
        // and teardown finishing: without it, onDestroy() blocks the main
        // thread on mu while the lifecycle executor still holds it, and the
        // system may kill the app as unresponsive.
        const val STOP_TIMEOUT_MS = 20_000L

        // Cadence of the service's own stats poll. The engine also PUSHES stats
        // over JNI, and the poll exists only as a safety net for that push path
        // going silent (see runHeartbeatTick()), where it is also what makes the
        // notification keep updating at all.
        const val HEARTBEAT_INTERVAL_MS = 2_000L

        // Upper bound for one P2PTap.getStatsJSON() call. A wedged engine must
        // not be able to hold a caller forever.
        const val PULL_TIMEOUT_MS = 5_000L

        // Consecutive failed pulls before the engine is declared unresponsive.
        // One bad pull is noise; three in a row is a real problem.
        const val PULL_FAIL_STREAKS = 3

        // How long the engine may go without delivering a pushed sample before
        // the pull path is counted as being load-bearing. Diagnostic only.
        const val HEARTBEAT_STALE_MS = 5_000L

        // Grace period before auto-restarting a dead engine, so a brief stall
        // doesn't cause a disruptive restart.
        const val RECOVERY_DELAY_MS = 15_000L

        // Max consecutive recovery attempts before giving up and leaving the
        // user on ERROR with a manual-reconnect message. Prevents an infinite
        // restart loop when the engine fails to start at all.
        const val RECOVERY_MAX_ATTEMPTS = 5

        // If the engine has been running (no recovery needed) for this long,
        // the consecutive-attempt counter resets.
        const val RECOVERY_RESET_MS = 5 * 60_000L

        @Volatile
        var currentState = STATE_IDLE
            private set

        @Volatile
        var lastErrorMessage = ""
            private set

        fun isRunning(): Boolean = currentState == STATE_RUNNING

        @Volatile private var activeConfig: P2PConfig? = null
        fun runningConfiguration(): P2PConfig? = if (isRunning()) activeConfig?.snapshot() else null
    }

    private val notificationChannelId = "p2ptap_vpn_channel"
    private val notificationId = 1001

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cachedConfig: P2PConfig? = null
    private var lastNotifUpdateTime: Long = 0
    private val lifecycleGeneration = AtomicLong(0)
    private val nativeSessionGeneration = AtomicLong(0)
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "P2PTap-Lifecycle")
    }
    private val networkChangeHandler = Handler(Looper.getMainLooper())
    private val activeUnderlyingNetwork = AtomicLong(-1L)
    @Volatile private var desiredRunning = false
    @Volatile private var destroyed = false
    @Volatile private var backgroundPaused = false
    @Volatile private var currentNativeSession: Long = 0
    private var activeStateListener: StateListener? = null
    private val stopFinalized = AtomicBoolean(false)


    // Ticks on its own thread so that a slow JNI call can never stall the main
    // looper or the lifecycle executor.
    private val heartbeatThread = HandlerThread("P2PTap-Heartbeat").apply { start() }
    private val heartbeatHandler = Handler(heartbeatThread.looper)
    // The one thread that runs P2PTap.getStatsJSON(). Future.get() on the
    // heartbeat thread is what actually enforces PULL_TIMEOUT_MS.
    private val statsPullExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "P2PTap-StatsPull")
    }
    // Last sample that arrived over the engine's PUSH path. Zero until the
    // first one; used only to notice when that path has gone silent.
    @Volatile private var lastPushAtMs: Long = 0
    private val pullFailStreak = AtomicLong(0)
    private val pushLossLogged = AtomicBoolean(false)
    @Volatile private var recoveryAttempts = 0
    @Volatile private var lastSuccessfulRunAtMs: Long = 0

    private val networkChangeRunnable = Runnable {
        if (!destroyed && desiredRunning && currentState == STATE_RUNNING) {
            submitLifecycle {
                if (desiredRunning && P2PTap.isRunning()) {
                    Log.i(TAG, "Underlying Android network changed -> refreshing Go network state")
                    P2PTap.onNetworkChanged()
                }
            }
        }
    }

    // Fires on the main looper, deliberately OFF the lifecycle executor: if the
    // executor is blocked inside P2PTap.stop(), only an executor-independent
    // path can end the stop. Runs directly (not via submitLifecycle) for the
    // same reason.
    private val stopWatchdogRunnable = Runnable {
        if (destroyed || desiredRunning || currentState != STATE_STOPPING) return@Runnable
        Log.w(TAG, "Native teardown exceeded ${STOP_TIMEOUT_MS}ms; finalizing stop without waiting for it")
        finalizeStop(stopService = true)
    }

    // ── Heartbeat ──────────────────────────────────────────────────────────────
    //
    // The engine pushes live stats over JNI, and that push path has a single
    // point of failure with two distinct symptoms, neither of which the app can
    // otherwise see:
    //
    //   * the Go metrics goroutine panics away (a Java exception thrown inside
    //     the callback is a panic on that goroutine), so samples stop arriving;
    //   * the engine reports ERROR and never emits RUNNING again. currentState
    //     is then stuck, and onMetricsUpdate() discards every sample unless the
    //     state is RUNNING — so the notification and the UI freeze with the
    //     node still forwarding traffic.
    //
    // The service therefore pulls as well. Every HEARTBEAT_INTERVAL_MS it asks
    // the engine for a snapshot through the pull API, which is independent of
    // the push goroutine, publishes it the same way a pushed sample would be,
    // and heals the state if it had wedged. If the pull fails too, the engine
    // is genuinely unresponsive and the user is told so instead of seeing a
    // silently frozen counter.
    private val heartbeatRunnable = Runnable { runHeartbeatTick() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                requestReload(snapshotConfig(AppConfigManager.load(this)), intent.getBooleanExtra(EXTRA_FORCE_RESTART, false))
                return START_STICKY
            }
            ACTION_APP_BACKGROUND -> {
                onAppBackground()
                return START_STICKY
            }
            ACTION_APP_FOREGROUND -> {
                onAppForeground()
                return START_STICKY
            }
            ACTION_START, null -> {
                requestStart()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun submitLifecycle(block: () -> Unit) {
        try {
            lifecycleExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            Log.d(TAG, "Lifecycle executor already shut down")
        }
    }

    private fun snapshotConfig(config: P2PConfig): P2PConfig = config.copy(
        bootstrapPeers = config.bootstrapPeers.toList(),
        staticPeers = config.staticPeers.toList(),
        advertisedSubnets = config.advertisedSubnets.toList(),
        allowedSubnetPeers = config.allowedSubnetPeers.toList(),
        dnsServers = config.dnsServers.toList()
    )

    private fun ensureForeground(config: P2PConfig) {
        val notif = buildNotification(getString(R.string.notification_starting), config.tapIp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, notificationId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notif)
        }
    }

    private fun requestStart() {
        if (desiredRunning && (currentState == STATE_RUNNING || currentState == STATE_STARTING)) return
        // A stop in flight may still be blocked inside P2PTap.stop(); its
        // watchdog must not tear down the service that this start just created.
        networkChangeHandler.removeCallbacks(stopWatchdogRunnable)
        heartbeatHandler.removeCallbacks(recoveryRunnable)
        stopFinalized.set(false)
        desiredRunning = true
        val generation = lifecycleGeneration.incrementAndGet()
        val config = snapshotConfig(AppConfigManager.load(this))
        ensureForeground(config)
        if (currentState != STATE_STOPPING) updateState(STATE_STARTING)
        scheduleHeartbeatTick()
        submitLifecycle { startVpnInternal(generation, config) }
    }

    private fun startVpnInternal(generation: Long, config: P2PConfig) {
        if (!desiredRunning || generation != lifecycleGeneration.get()) return
        updateState(STATE_STARTING)
        var nativeStarted = false
        try {
            AppConfigManager.validate(this, config)
            if (P2PTap.isRunning()) {
                Log.w(TAG, "Found an unowned native instance; stopping it before start")
                P2PTap.stop()
                clearNativeCallbacks()
                cleanup()
            }
            val tunFd = establishVpn(config)
            if (!desiredRunning || generation != lifecycleGeneration.get()) {
                closeDetachedTunFd(tunFd)
                return
            }

            // Ownership window: between establishVpn() and P2PTap.start() the fd
            // belongs to THIS code. Go consumes the fd on every path inside
            // start() (validation, already-running, device/node failure), but a
            // throw in pure-Kotlin code before that hand-off would leak it.
            val cfgJson: String = try {
                config.toJsonString(this)
            } catch (e: Exception) {
                closeDetachedTunFd(tunFd)
                throw e
            }
            Log.i(TAG, "Starting P2PTap native engine")

            P2PTap.setProtector(this)
            P2PTap.setLogCallback(this)
            currentNativeSession = nativeSessionGeneration.incrementAndGet()
            registerStateListener()
            P2PTap.setInterfaceProvider(this)
            P2PTap.setConfigStore(object : ConfigStore {
                override fun saveConfig(cfgJSON: String?) {
                    if (currentNativeSession != nativeSessionGeneration.get() || destroyed || !desiredRunning) {
                        throw app.fjj.p2ptap.i18n.LocalizedException(R.string.error_vpn_session_ended)
                    }
                    AppConfigManager.saveEngineConfig(applicationContext, requireNotNull(cfgJSON))
                }
            })
            P2PTap.start(cfgJson, tunFd.toLong())
            nativeStarted = true

            if (!desiredRunning || generation != lifecycleGeneration.get()) {
                P2PTap.stop()
                clearNativeCallbacks()
                cleanup()
                return
            }

            cachedConfig = snapshotConfig(config)
            activeConfig = snapshotConfig(config)
            updateState(STATE_RUNNING)
            registerNetworkCallback()
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ipInfo = "IPv4: ${config.tapIp}" + if (config.tapIpv6.isNotBlank()) " | IPv6: ${config.tapIpv6}" else ""
            notifManager.notify(notificationId, buildNotification(getString(R.string.notification_running), ipInfo))
            Log.i(TAG, "P2PTap native engine running successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start P2PTap VPN", e)
            if (nativeStarted || P2PTap.isRunning()) {
                try {
                    P2PTap.stop()
                } catch (stopError: Exception) {
                    Log.e(TAG, "Failed to clean up native engine after start failure", stopError)
                }
            }
            clearNativeCallbacks()
            cleanup()
            if (desiredRunning && generation == lifecycleGeneration.get()) {
                desiredRunning = false
                networkChangeHandler.removeCallbacks(stopWatchdogRunnable)
                val msg = e.message ?: "Unknown error"
                val isTimeout = msg.contains("timeout", ignoreCase = true) || msg.contains("deadline", ignoreCase = true)
                updateState(if (isTimeout) STATE_TIMEOUT else STATE_ERROR, UiMessages.describe(this, e))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun requestStop() {
        desiredRunning = false
        stopFinalized.set(false)
        lifecycleGeneration.incrementAndGet()
        if (currentState != STATE_IDLE) updateState(STATE_STOPPING)
        submitLifecycle { stopVpnInternal(finalizeService = true) }
        // Belt and braces: P2PTap.stop() blocks the executor for the whole
        // native teardown, so without this the service would sit in STOPPING
        // (button disabled, notification pinned) until it returns on its own.
        networkChangeHandler.removeCallbacks(stopWatchdogRunnable)
        networkChangeHandler.postDelayed(stopWatchdogRunnable, STOP_TIMEOUT_MS)
    }

    private fun stopVpnInternal(finalizeService: Boolean) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            Log.i(TAG, "Stopping P2PTap native engine...")
            P2PTap.stop()
            Log.i(TAG, "P2PTap native engine stopped")
        } catch (e: Throwable) {
            // Throwable, not Exception: an UnsatisfiedLinkError from a torn-down
            // native library must still reach finalizeStop, otherwise the
            // service is stuck in STOPPING with the notification pinned.
            Log.e(TAG, "Error stopping P2PTap native engine", e)
        } finally {
            clearNativeCallbacks()
            cleanup()
        }

        // A START received while Stop() was releasing Go resources is queued on
        // the same executor. Keep the service alive and let that command run.
        if (desiredRunning) return

        Log.i(TAG, "P2PTap native engine stopped in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        finalizeStop(stopService = finalizeService)
    }

    /**
     * Ends the stop. Runs at most once per stop request: the normal path calls
     * it after the native teardown returns, [stopWatchdogRunnable] calls it if
     * that teardown overruns. It may therefore run on the main looper while the
     * lifecycle executor is still inside P2PTap.stop(), so it only does
     * main-thread-safe work and no native call that would need the engine's
     * global mutex (the Go setters in clearNativeCallbacks() are independent of
     * it).
     *
     * [stopService] is false when the system already destroyed the service,
     * where stopForeground/stopSelf would be wrong.
     */
    private fun finalizeStop(stopService: Boolean) {
        if (!stopFinalized.compareAndSet(false, true)) return
        if (desiredRunning) {
            // A start superseded this stop; let that request own the service.
            stopFinalized.set(false)
            return
        }
        cancelHeartbeat()
        heartbeatHandler.removeCallbacks(recoveryRunnable)
        pullFailStreak.set(0)
        lastPushAtMs = 0
        pushLossLogged.set(false)
        clearNativeCallbacks()
        cleanup()
        updateState(STATE_IDLE)
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Arm the heartbeat, replacing any pending tick. Idempotent. */
    private fun scheduleHeartbeatTick() {
        if (destroyed) return
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    /** Disarm the heartbeat. Called wherever the VPN is definitively off. */
    private fun cancelHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun runHeartbeatTick() {
        if (destroyed || !desiredRunning || backgroundPaused) return
        when (currentState) {
            STATE_RUNNING -> {
                // Steady state: the engine is pushing and updating the
                // notification on its own, so the pull is pure overhead. Pull
                // only once the push has gone quiet — which is precisely the
                // case the pull exists for. A fresh pull keeps the numbers
                // moving even though no sample will ever arrive on its own.
                val silentFor = System.currentTimeMillis() - lastPushAtMs
                if (lastPushAtMs > 0L && silentFor <= HEARTBEAT_STALE_MS) {
                    scheduleHeartbeatTick()
                    return
                }
                pullStatsOnce("heartbeat", isReconcile = false)
            }
            // ERROR can be permanent: the engine never re-emits RUNNING once it
            // has emitted ERROR, so nothing else would ever restore the state.
            // Asking the engine directly is the only way to tell a real failure
            // from that wedge.
            STATE_ERROR, STATE_TIMEOUT -> pullStatsOnce("reconcile", isReconcile = true)
            // STARTING and STOPPING belong to the lifecycle executor; probing
            // during them would race a start or stop in progress.
            else -> scheduleHeartbeatTick()
        }
    }

    /**
     * Pulls one snapshot off the engine and publishes it. Runs the blocking JNI
     * call on [statsPullExecutor] and waits for it here, which is what makes the
     * wait bounded: Future.get() on this thread enforces PULL_TIMEOUT_MS, while
     * a single-threaded executor also keeps concurrent pulls serialized.
     *
     * [isReconcile] means the state was ERROR/TIMEOUT rather than RUNNING, so a
     * successful pull proves the engine alive and the state must be healed.
     */
    private fun pullStatsOnce(reason: String, isReconcile: Boolean) {
        if (destroyed || !desiredRunning) return
        var timedOut = false
        var body: String? = null
        try {
            val future = statsPullExecutor.submit<String> { P2PTap.getStatsJSON() }
            try {
                body = future.get(PULL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Cancelling stops a queued pull from running; one already inside
                // JNI cannot be interrupted and simply finishes on its own.
                future.cancel(true)
                timedOut = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stats pull ($reason) rejected: ${e.message}")
        }
        applyPulledStats(reason, isReconcile, timedOut, body)
    }

    private fun applyPulledStats(reason: String, isReconcile: Boolean, timedOut: Boolean, body: String?) {
        if (destroyed || !desiredRunning) return
        val text = body?.trim().orEmpty()
        if (timedOut || text.isEmpty() || text == "{}") {
            val streak = pullFailStreak.incrementAndGet()
            Log.w(TAG, "Stats pull ($reason) got no engine data" +
                if (timedOut) " (timed out after ${PULL_TIMEOUT_MS}ms)" else "" +
                "; streak=$streak")
            if (streak >= PULL_FAIL_STREAKS) {
                reconcileDeadEngine("$reason after $streak failed pulls")
            }
            scheduleHeartbeatTick()
            return
        }

        pullFailStreak.set(0)
        val snapshot = try {
            StatsSnapshotParser.parse(text)
        } catch (e: Exception) {
            Log.w(TAG, "Stats pull ($reason) returned unparseable data", e)
            return
        }

        if (isReconcile && currentState != STATE_RUNNING) {
            Log.i(TAG, "Engine answers stats while state was $currentState; restoring RUNNING")
            updateState(STATE_RUNNING)
        }

        // The pull is carrying the whole load because the push path went quiet.
        // Log it once per incident: nothing else will ever say that the push
        // path stopped, so this line is the only trace of it in production.
        val now = System.currentTimeMillis()
        val silentFor = now - lastPushAtMs
        if (lastPushAtMs > 0L && silentFor > HEARTBEAT_STALE_MS && pushLossLogged.compareAndSet(false, true)) {
            Log.w(TAG, "Engine push path silent for ${silentFor}ms; the service pull is keeping stats alive")
        }

        publishSnapshot(snapshot)
        scheduleHeartbeatTick()
    }

    /**
     * The engine refuses to report an unresponsive session, and a pull that
     * cannot reach it is the only evidence the app has. Rather than leave the
     * notification and the UI frozen on stale numbers, say so.
     */
    private fun reconcileDeadEngine(why: String) {
        if (destroyed || !desiredRunning) return
        if (currentState == STATE_IDLE || currentState == STATE_STOPPING) return
        // The engine's own message is more specific than ours; keep it.
        if (currentState == STATE_ERROR && lastErrorMessage.isNotEmpty()) return

        // Auto-recovery: try a few times before giving up. If the engine has
        // been healthy for a while, reset the counter so an old incident
        // doesn't burn through all attempts on a new one.
        val now = System.currentTimeMillis()
        if (lastSuccessfulRunAtMs > 0L && now - lastSuccessfulRunAtMs > RECOVERY_RESET_MS) {
            recoveryAttempts = 0
        }
        recoveryAttempts++
        if (recoveryAttempts > RECOVERY_MAX_ATTEMPTS) {
            Log.e(TAG, "Engine unresponsive ($why); $recoveryAttempts failed recovery attempts — giving up")
            updateState(STATE_ERROR, getString(R.string.error_engine_stalled))
            return
        }

        Log.w(TAG, "Engine unresponsive ($why); scheduling auto-recovery (attempt $recoveryAttempts)")
        updateState(STATE_ERROR, getString(R.string.error_engine_stalled))
        scheduleRecovery()
    }

    /**
     * Restarts the engine after a delay. Runs on the heartbeat thread so the
     * blocking P2PTap.stop() inside startVpnInternal cannot stall the main
     * looper or the lifecycle executor.
     */
    private fun scheduleRecovery() {
        heartbeatHandler.removeCallbacks(recoveryRunnable)
        heartbeatHandler.postDelayed(recoveryRunnable, RECOVERY_DELAY_MS)
    }

    private val recoveryRunnable = Runnable {
        if (destroyed || !desiredRunning) return@Runnable
        // If the engine came back on its own while we waited, don't restart.
        if (currentState == STATE_RUNNING || currentState == STATE_STARTING) return@Runnable
        val config = AppConfigManager.load(this@P2PTapVpnService)
        Log.i(TAG, "Auto-recovery: restarting engine (attempt $recoveryAttempts)")
        pullFailStreak.set(0)
        lastPushAtMs = 0L
        pushLossLogged.set(false)
        requestStart()
    }

    /**
     * Publishes one snapshot the way the engine's push path would: into the
     * repository and onto the notification. Shared by both paths so they
     * cannot drift apart in what they show.
     *
     * The direct/relay counts are derived from the snapshot here because the
     * pull API does not hand them back separately. They must stay byte-for-byte
     * identical to metricsTick() in p2ptap-core/pkg/android/android.go, which
     * counts relay as "relay_ok" or ("ok" and relayed) and direct as "ok" and
     * not relayed; len(active_peers) is not a truthful active count because
     * that list also carries known, connecting and unreachable rows.
     */
    private fun publishSnapshot(snapshot: StatsSnapshot) {
        recoveryAttempts = 0
        lastSuccessfulRunAtMs = System.currentTimeMillis()
        val relayPeers = snapshot.peers.count { it.connState == "relay_ok" || (it.connState == "ok" && it.isRelayed) }
        val directPeers = snapshot.peers.count { it.connState == "ok" && !it.isRelayed }
        val counters = snapshot.counters
        P2PStateRepository.updateMetrics(
            directPeers + relayPeers,
            directPeers,
            relayPeers,
            counters["tx_bytes_per_sec"] ?: 0L,
            counters["rx_bytes_per_sec"] ?: 0L,
            counters["bytes_sent"] ?: 0L,
            counters["bytes_recv"] ?: 0L
        )
        if (P2PStateRepository.finishRefresh(snapshot, P2PStateRepository.sessionRevision)) {
            P2PStateRepository.markSnapshotFresh(System.currentTimeMillis())
        }
        notifyRunningStats(
            counters["tx_bytes_per_sec"] ?: 0L,
            counters["rx_bytes_per_sec"] ?: 0L,
            directPeers + relayPeers
        )
    }

    private fun requestReload(newConfig: P2PConfig, forceRestart: Boolean = false) {
        if (!desiredRunning && currentState != STATE_RUNNING) {
            desiredRunning = true
            val generation = lifecycleGeneration.incrementAndGet()
            ensureForeground(newConfig)
            updateState(STATE_STARTING)
            scheduleHeartbeatTick()
            submitLifecycle { startVpnInternal(generation, newConfig) }
            return
        }

        val generation = lifecycleGeneration.incrementAndGet()
        submitLifecycle { reloadVpnInternal(generation, newConfig, forceRestart) }
    }

    private fun reloadVpnInternal(generation: Long, newConfig: P2PConfig, forceRestart: Boolean = false) {
        if (!desiredRunning || generation != lifecycleGeneration.get()) return
        try {
            AppConfigManager.validate(this, newConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Rejected configuration; keeping the running VPN", e)
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, getString(R.string.config_invalid_fmt, UiMessages.describe(this, e)), android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        val oldConfig = cachedConfig
        if (!P2PTap.isRunning() || oldConfig == null) {
            startVpnInternal(generation, newConfig)
            return
        }

        when (planVpnReload(oldConfig, newConfig, forceRestart)) {
            VpnReloadPlan.NONE -> return
            VpnReloadPlan.HOT -> {
                try {
                    if (oldConfig.logLevel != newConfig.logLevel) {
                        P2PTap.setLogLevel(newConfig.logLevel)
                    }
                    if (oldConfig.exitNode != newConfig.exitNode) {
                        if (newConfig.exitNode.isBlank()) P2PTap.clearExitNode()
                        else P2PTap.setExitNode(newConfig.exitNode, "", "")
                    }
                    // Publish the full config atomically so the Go data plane
                    // observes the new obfuscation, hole-punch, relay-upgrade,
                    // subnet-advertisement and transport settings without a VPN
                    // teardown.  applyHotReload is idempotent; it re-applies
                    // obfuscation packer, exit-node NAT, and peer re-announce.
                    val cfgJSON = newConfig.toJsonString(applicationContext)
                    P2PTap.applyHotReload(cfgJSON)
                    cachedConfig = snapshotConfig(newConfig)
                    activeConfig = snapshotConfig(newConfig)
                    Log.i(TAG, "Applied hot-reloadable P2PTap configuration")
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Hot reload failed; restarting the engine", e)
                }
            }
            VpnReloadPlan.RESTART -> Unit
        }

        updateState(STATE_STOPPING)
        try {
            P2PTap.stop()
        } finally {
            clearNativeCallbacks()
            cleanup()
        }
        if (desiredRunning && generation == lifecycleGeneration.get()) {
            startVpnInternal(generation, newConfig)
        }
    }

    private fun closeDetachedTunFd(fd: Int) {
        if (fd <= 0) return
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close detached TUN fd=$fd", e)
        }
    }

    private fun clearNativeCallbacks() {
        activeConfig = null
        nativeSessionGeneration.incrementAndGet()
        backgroundPaused = false
        // gomobile exposes Java platform types, so null clears the retained
        // Service instances without requiring a new binary API method.
        P2PTap.setStateListener(null)
        activeStateListener = null
        P2PTap.setConfigStore(null)
        P2PTap.setInterfaceProvider(null)
        P2PTap.setProtector(null)
        P2PTap.setLogCallback(null)
    }

    /**
     * Creates and registers the engine's state/metrics callback. The listener
     * checks [currentNativeSession] against [nativeSessionGeneration] so a late
     * callback from a previous session is ignored. It also checks
     * [backgroundPaused]: while the app is in the background the callback is
     * unregistered entirely, so this guard is belt-and-braces for the brief
     * window between re-registration and the heartbeat picking up again.
     */
    private fun registerStateListener() {
        val session = currentNativeSession
        val listener = object : StateListener {
            override fun onStateChange(state: String?, message: String?) {
                if (session == nativeSessionGeneration.get() && !destroyed && !backgroundPaused) {
                    this@P2PTapVpnService.onStateChange(state, message)
                }
            }

            override fun onMetricsUpdate(peerCount: Int, directPeers: Int, relayPeers: Int,
                txSpeed: Long, rxSpeed: Long, totalTx: Long, totalRx: Long) {
                if (session == nativeSessionGeneration.get() && !destroyed && !backgroundPaused) {
                    this@P2PTapVpnService.onMetricsUpdate(peerCount, directPeers, relayPeers,
                        txSpeed, rxSpeed, totalTx, totalRx)
                }
            }
        }
        activeStateListener = listener
        P2PTap.setStateListener(listener)
    }

    /** Unregister the engine's callback. Used when the app goes to background. */
    private fun unregisterStateListener() {
        P2PTap.setStateListener(null)
        activeStateListener = null
    }

    /**
     * Called by the Activity when it moves to the background. Unregisters the
     * engine callback so the engine stops pushing metrics to an invisible app.
     * The engine itself keeps running — it is a foreground service, not an
     * Activity — so there is no need to restart it. The "engine stopped
     * reporting" false alarm that this was causing is eliminated at the source.
     */
    private fun onAppBackground() {
        if (!desiredRunning || currentState != STATE_RUNNING) return
        if (backgroundPaused) return
        backgroundPaused = true
        unregisterStateListener()
        cancelHeartbeat()
        Log.d(TAG, "App went to background; engine callbacks paused")
    }

    /**
     * Called by the Activity when it returns to the foreground. Re-registers
     * the engine callback and resumes heartbeat monitoring. Resets
     * [lastPushAtMs] so the heartbeat does not immediately fire a stale pull.
     */
    private fun onAppForeground() {
        if (!desiredRunning || currentState != STATE_RUNNING) return
        if (!backgroundPaused) return
        backgroundPaused = false
        lastPushAtMs = 0L
        pushLossLogged.set(false)
        registerStateListener()
        scheduleHeartbeatTick()
        Log.d(TAG, "App returned to foreground; engine callbacks resumed")
    }

    private fun registerNetworkCallback() {
        try {
            unregisterNetworkCallback()
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val capabilities = cm.getNetworkCapabilities(network)
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
                    val netId = network.networkHandle
                    if (activeUnderlyingNetwork.getAndSet(netId) != netId) {
                        scheduleNetworkRefresh("available:$netId")
                    }
                }

                override fun onLost(network: Network) {
                    val netId = network.networkHandle
                    if (activeUnderlyingNetwork.compareAndSet(netId, -1L)) {
                        scheduleNetworkRefresh("lost:$netId")
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (network.networkHandle == activeUnderlyingNetwork.get()) {
                        scheduleNetworkRefresh("link-properties:${network.networkHandle}")
                    }
                }
            }
            networkCallback = callback
            // The app itself is excluded from the VPN, so its default callback
            // follows the actual Wi-Fi/cellular egress instead of every network
            // advertising INTERNET (including P2PTap's own VPN network).
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun scheduleNetworkRefresh(reason: String) {
        Log.d(TAG, "Scheduling debounced network refresh: $reason")
        networkChangeHandler.removeCallbacks(networkChangeRunnable)
        networkChangeHandler.postDelayed(networkChangeRunnable, 750L)
    }

    private fun unregisterNetworkCallback() {
        networkChangeHandler.removeCallbacks(networkChangeRunnable)
        activeUnderlyingNetwork.set(-1L)
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister NetworkCallback", e)
            }
            networkCallback = null
        }
    }

    private fun addRouteSafe(builder: Builder, ipStr: String, prefix: Int) {
        try {
            val cleanIp = ipStr.trim()
            if (cleanIp.isBlank()) return
            val inetAddr = java.net.InetAddress.getByName(cleanIp)
            val rawBytes = inetAddr.address
            val isV4 = rawBytes.size == 4

            val maxPrefix = if (isV4) 32 else 128
            val validPrefix = prefix.coerceIn(0, maxPrefix)

            // Zero out host bits to guarantee valid subnet base address (prevents IllegalArgumentException on Android 10+)
            val maskedBytes = ByteArray(rawBytes.size)
            var remainingBits = validPrefix
            for (i in rawBytes.indices) {
                if (remainingBits >= 8) {
                    maskedBytes[i] = rawBytes[i]
                    remainingBits -= 8
                } else if (remainingBits > 0) {
                    val mask = ((0xFF shl (8 - remainingBits)) and 0xFF).toByte()
                    maskedBytes[i] = (rawBytes[i].toInt() and mask.toInt()).toByte()
                    remainingBits = 0
                } else {
                    maskedBytes[i] = 0
                }
            }

            val maskedAddr = java.net.InetAddress.getByAddress(maskedBytes)
            builder.addRoute(maskedAddr, validPrefix)
            Log.d(TAG, "Added VPN route: ${maskedAddr.hostAddress}/$validPrefix (from $ipStr/$prefix)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add route: $ipStr/$prefix", e)
        }
    }

    private fun establishVpn(config: P2PConfig): Int {
        val builder = Builder()
        val mtu = if (config.mtu in 576..9000) config.mtu else 1500
        builder.setMtu(mtu)
        builder.setSession("P2PTap-${config.nodeName}")

        // Exclude P2PTap app itself from the VPN TUN interface to prevent routing loops and ensure direct P2P transport
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to addDisallowedApplication: $packageName", e)
        }

        val parts = config.tapIp.split("/")
        val ipStr = parts[0].trim()
        val prefix = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 24 else 24

        try {
            builder.addAddress(ipStr, prefix.coerceIn(1, 32))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add IPv4 address: $ipStr/$prefix", e)
        }

        // Configure virtual overlay IPv6 address
        val v6Text = if (config.tapIpv6.isNotBlank()) {
            config.tapIpv6
        } else {
            val lastOctet = ipStr.substringAfterLast(".", "88")
            "fd00::$lastOctet/64"
        }
        try {
            val v6Parts = v6Text.split("/")
            val v6Ip = v6Parts[0].trim()
            val v6Prefix = if (v6Parts.size > 1) v6Parts[1].trim().toIntOrNull() ?: 64 else 64
            builder.addAddress(v6Ip, v6Prefix.coerceIn(1, 128))
            Log.i(TAG, "Configured IPv6 address: $v6Ip/$v6Prefix")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add IPv6 address: $v6Text", e)
        }

        // Route virtual overlay IPv4 and accepted subnet networks
        if (config.acceptSubnets) {
            // When acceptSubnets is true, route all private IPv4 ranges into the VPN so any peer's advertised subnets are reachable
            addRouteSafe(builder, "10.0.0.0", 8)
            addRouteSafe(builder, "172.16.0.0", 12)
            addRouteSafe(builder, "192.168.0.0", 16)
        } else {
            addRouteSafe(builder, ipStr, prefix)
        }

        // IPv6 Overlay Route
        addRouteSafe(builder, "fd00::", 8)

        // Also add explicitly advertised custom subnets if specified
        for (sub in config.advertisedSubnets) {
            val subParts = sub.split("/")
            val sIp = subParts[0].trim()
            val sPrefix = if (subParts.size > 1) subParts[1].trim().toIntOrNull() ?: 24 else 24
            if (sIp.isNotEmpty()) {
                addRouteSafe(builder, sIp, sPrefix)
            }
        }

        // If Exit Node is designated, route ALL default IPv4 and IPv6 traffic through TUN.
        // Using 0.0.0.0/0 and ::/0 (instead of split-default /1 routes) is safe on
        // Android because addDisallowedApplication() already keeps P2PTap's own traffic
        // off the TUN, so the physical gateway stays reachable for P2P transport.
        if (config.exitNode.isNotBlank()) {
            addRouteSafe(builder, "0.0.0.0", 0)
            addRouteSafe(builder, "::", 0)
        }

        // Configure custom DNS servers if specified by user.
        // If empty, Android VpnService automatically uses the underlying System Default DNS (Wi-Fi / Cellular carrier)!
        if (config.dnsServers.isNotEmpty()) {
            for (dns in config.dnsServers) {
                val cleanDns = dns.trim()
                if (cleanDns.isNotBlank()) {
                    try {
                        builder.addDnsServer(cleanDns)
                        Log.i(TAG, "Configured custom VPN DNS server: $cleanDns")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add DNS server: $cleanDns", e)
                    }
                }
            }
        } else {
            Log.i(TAG, "No custom DNS configured -> Using Android System Default DNS")
        }

        // Allow app traffic through VPN
        val pfd = builder.establish() ?: throw app.fjj.p2ptap.i18n.LocalizedException(R.string.error_vpn_establish)
        // Transfer file descriptor ownership completely to Go native engine
        return pfd.detachFd()
    }

    private fun cleanup() {
        unregisterNetworkCallback()
    }

    override fun onDestroy() {
        Log.i(TAG, "P2PTapVpnService onDestroy -> stopping VPN")
        destroyed = true
        desiredRunning = false
        // Retires every queued lifecycle task at its next generation check, so
        // nothing can resurrect the engine behind this teardown.
        lifecycleGeneration.incrementAndGet()
        networkChangeHandler.removeCallbacks(networkChangeRunnable)
        networkChangeHandler.removeCallbacks(stopWatchdogRunnable)
        cancelHeartbeat()
        heartbeatHandler.removeCallbacks(recoveryRunnable)

        // Always queue the stop on the lifecycle executor — never call
        // stopVpnInternal synchronously here: it would block the main thread
        // on Go's mu for the entire native teardown (~17s worst case), and
        // the watchdog (which runs on the main looper) cannot fire while the
        // main thread is blocked. The executor's shutdownNow() below
        // interrupts any in-flight stop, but Android's VpnService contract
        // tears down the TAP interface when stopSelf() was called, so a stale
        // engine is harmless.
        submitLifecycle { stopVpnInternal(finalizeService = false) }
        clearNativeCallbacks()
        cleanup()

        // currentState is a static that outlives this instance for the life of
        // the process. Reset it or the next session inherits a stale RUNNING
        // from a destroyed service, and P2PStateRepository keeps advertising a
        // session that no longer exists. stopVpnInternal() normally reaches the
        // same reset through finalizeStop(), but that is a once-per-stop latch
        // and an earlier stop can have already burned it.
        updateState(STATE_IDLE)

        statsPullExecutor.shutdownNow()
        lifecycleExecutor.shutdownNow()
        heartbeatThread.quitSafely()
        super.onDestroy()
    }

    // com.p2ptap.P2PTap.Protector implementation
    override fun protect(fd: Int): Boolean {
        if (fd <= 0) return false
        val ok = super.protect(fd)
        if (!ok) {
            Log.w(TAG, "VpnService.protect(fd=$fd) returned false")
        }
        return ok
    }

    private fun updateState(state: String, message: String = "") {
        currentState = state
        if (state == STATE_ERROR || state == STATE_TIMEOUT) {
            lastErrorMessage = message
        } else if (state == STATE_RUNNING || state == STATE_IDLE) {
            lastErrorMessage = ""
        }
        P2PStateRepository.updateState(state, message)
        persistBootIntent(state)
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * Records the intent a reboot should restore. Only two transitions are
     * conclusive: RUNNING means the user wanted this on, STOPPING means they
     * took it off. STARTING is already covered by the RUNNING that follows it,
     * and ERROR/TIMEOUT leave the flag alone so a failed session does not flip
     * it off and silently disable restore.
     */
    private fun persistBootIntent(state: String) {
        val autostart = when (state) {
            STATE_RUNNING -> true
            STATE_STOPPING -> false
            else -> P2PBootFlagStore.isAutostart(this)
        }
        if (autostart != P2PBootFlagStore.isAutostart(this)) {
            P2PBootFlagStore.setAutostart(this, autostart)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun launchIntent(): Intent {
        // Resolve this package's own launcher instead of naming a class. This
        // service lives in :core and is shared by :app and :app-tv, whose
        // launcher activities are unrelated classes, so the intent filter is
        // the only coupling that stays true for both.
        return packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, P2PTapVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_p2p_notification)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_disconnect), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // com.p2ptap.P2PTap.StateListener implementations (ultra-fast JNI callbacks)
    override fun onStateChange(state: String?, message: String?) {
        val s = state ?: STATE_IDLE
        val m = message ?: ""
        val isTimeout = s == STATE_TIMEOUT || m.contains("timeout", ignoreCase = true) || m.contains("deadline", ignoreCase = true)
        val targetState = if (isTimeout && (s == STATE_ERROR || s == STATE_TIMEOUT)) STATE_TIMEOUT else s
        // Lifecycle commands are owned by the serialized service executor. A
        // late Go callback from an old generation must not make STOPPING look
        // RUNNING or make an in-progress restart look IDLE.
        if (targetState == STATE_RUNNING || targetState == STATE_IDLE) return
        if (!desiredRunning) return
        if (targetState == STATE_ERROR || targetState == STATE_TIMEOUT) {
            Log.w(TAG, "Native state $targetState: $m")
            updateState(targetState, if (targetState == STATE_TIMEOUT) getString(R.string.error_timeout) else UiMessages.nativeError(this, m))
        } else updateState(targetState)
    }

    override fun onMetricsUpdate(
        peerCount: Int,
        directPeers: Int,
        relayPeers: Int,
        txSpeed: Long,
        rxSpeed: Long,
        totalTx: Long,
        totalRx: Long
    ) {
        if (!desiredRunning || currentState != STATE_RUNNING) return

        // Older engines used len(active_peers), which also contains known but
        // connecting/unreachable rows. Healthy direct + relay paths are the
        // truthful active count across both old and new AAR versions.
        val activePeerCount = directPeers + relayPeers
        P2PStateRepository.updateMetrics(activePeerCount, directPeers, relayPeers, txSpeed, rxSpeed, totalTx, totalRx)
        // A sample just arrived over the push path, so it is alive again.
        lastPushAtMs = System.currentTimeMillis()
        pushLossLogged.set(false)
        notifyRunningStats(txSpeed, rxSpeed, activePeerCount)
    }

    /**
     * com.p2ptap.P2PTap.LogCallback implementation. Receives structured log
     * entries from the Go engine with the correct android.util.Log priority.
     * Without this, every Go log line goes through stderr→logcat and arrives at
     * a single indistinguishable level, so INFO messages show up as ERROR.
     *
     * The tag is "GoLog" (already in LogCollector.CAPTURED_TAGS) and the module
     * name is embedded in the message so the in-process viewer can still
     * distinguish log sources.
     */
    override fun onLog(priority: Int, module: String?, message: String?) {
        val tag = "GoLog"
        val mod = module ?: "unknown"
        val msg = message ?: ""
        val formatted = "[$mod] $msg"

        when (priority) {
            Log.VERBOSE -> Log.v(tag, formatted)
            Log.DEBUG -> Log.d(tag, formatted)
            Log.INFO -> Log.i(tag, formatted)
            Log.WARN -> Log.w(tag, formatted)
            Log.ERROR -> Log.e(tag, formatted)
            else -> Log.w(tag, formatted)
        }
    }

    /**
     * Refreshes the foreground notification from fresh speeds. Shared by the
     * engine's push path and by the heartbeat pull so the notification text
     * can never diverge between the two. Throttled to one update per second.
     *
     * Keep Binder calls and configuration I/O off Go's metrics callback: the
     * actual notify() is posted to the main looper with a generation check.
     */
    private fun notifyRunningStats(txSpeed: Long, rxSpeed: Long, activePeerCount: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotifUpdateTime < 1000) return
        lastNotifUpdateTime = now
        val generation = lifecycleGeneration.get()
        val config = cachedConfig ?: return
        networkChangeHandler.post {
            if (destroyed || !desiredRunning || generation != lifecycleGeneration.get() || currentState != STATE_RUNNING) return@post
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val title = "P2PTap: ${config.nodeName} (${config.tapIp})"
            val speeds = "↑ ${P2PStateRepository.formatSpeed(txSpeed)}  ↓ ${P2PStateRepository.formatSpeed(rxSpeed)}"
            manager.notify(notificationId, buildNotification(title, "$speeds · $activePeerCount"))
        }
    }

    // com.p2ptap.P2PTap.InterfaceProvider implementation (supplies Android physical IPs to Go libp2p)
    override fun getInterfaceAddresses(): String {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("dummy") || name.startsWith("vnic") || name.startsWith("veth") || name.startsWith("gso")) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue
                    if (addr is java.net.Inet4Address && addr.isLinkLocalAddress) continue
                    val host = addr.hostAddress ?: continue
                    val cleanHost = host.split("%")[0].trim()
                    if (cleanHost.isNotEmpty()) {
                        list.add(cleanHost)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query Android NetworkInterfaces", e)
        }
        return JSONArray(list).toString()
    }

    override fun onRevoke() {
        Log.i(TAG, "VpnService permission revoked by Android system -> stopping VPN")
        requestStop()
        super.onRevoke()
    }
}
