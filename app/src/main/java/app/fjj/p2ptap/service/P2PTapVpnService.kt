package app.fjj.p2ptap.service

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
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.fjj.p2ptap.MainActivity
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PConfig
import com.p2ptap.P2PTap.P2PTap
import com.p2ptap.P2PTap.InterfaceProvider
import com.p2ptap.P2PTap.Protector
import com.p2ptap.P2PTap.StateListener
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray

class P2PTapVpnService : VpnService(), Protector, StateListener, InterfaceProvider {

    companion object {
        const val TAG = "P2PTapVpnService"
        const val ACTION_START = "app.fjj.p2ptap.START"
        const val ACTION_STOP = "app.fjj.p2ptap.STOP"
        const val ACTION_RELOAD = "app.fjj.p2ptap.RELOAD"
        const val ACTION_STATE_CHANGED = "app.fjj.p2ptap.STATE_CHANGED"

        const val EXTRA_STATE = "extra_state"
        const val EXTRA_MESSAGE = "extra_message"

        const val STATE_IDLE = "IDLE"
        const val STATE_STARTING = "STARTING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_STOPPING = "STOPPING"
        const val STATE_TIMEOUT = "TIMEOUT"
        const val STATE_ERROR = "ERROR"

        @Volatile
        var currentState = STATE_IDLE
            private set

        @Volatile
        var lastErrorMessage = ""
            private set

        fun isRunning(): Boolean = currentState == STATE_RUNNING
    }

    private val notificationChannelId = "p2ptap_vpn_channel"
    private val notificationId = 1001

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cachedConfig: P2PConfig? = null
    private var lastNotifUpdateTime: Long = 0
    private val lifecycleGeneration = AtomicLong(0)
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "P2PTap-Lifecycle")
    }
    private val networkChangeHandler = Handler(Looper.getMainLooper())
    private val activeUnderlyingNetwork = AtomicLong(-1L)
    @Volatile private var desiredRunning = false
    @Volatile private var lastStartId = 0
    @Volatile private var destroyed = false

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = maxOf(lastStartId, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                requestReload(snapshotConfig(AppConfigManager.load(this)))
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
        desiredRunning = true
        val generation = lifecycleGeneration.incrementAndGet()
        val config = snapshotConfig(AppConfigManager.load(this))
        ensureForeground(config)
        if (currentState != STATE_STOPPING) updateState(STATE_STARTING)
        submitLifecycle { startVpnInternal(generation, config) }
    }

    private fun startVpnInternal(generation: Long, config: P2PConfig) {
        if (!desiredRunning || generation != lifecycleGeneration.get()) return
        updateState(STATE_STARTING)
        var nativeStarted = false
        try {
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
            P2PTap.setStateListener(this)
            P2PTap.setInterfaceProvider(this)
            P2PTap.start(cfgJson, tunFd.toLong())
            nativeStarted = true

            if (!desiredRunning || generation != lifecycleGeneration.get()) {
                P2PTap.stop()
                clearNativeCallbacks()
                cleanup()
                return
            }

            cachedConfig = snapshotConfig(config)
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
                val msg = e.message ?: "Unknown error"
                val isTimeout = msg.contains("timeout", ignoreCase = true) || msg.contains("deadline", ignoreCase = true)
                updateState(if (isTimeout) STATE_TIMEOUT else STATE_ERROR, msg)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(lastStartId)
            }
        }
    }

    private fun requestStop() {
        desiredRunning = false
        lifecycleGeneration.incrementAndGet()
        if (currentState != STATE_IDLE) updateState(STATE_STOPPING)
        submitLifecycle { stopVpnInternal() }
    }

    private fun stopVpnInternal() {
        try {
            Log.i(TAG, "Stopping P2PTap native engine...")
            P2PTap.stop()
            Log.i(TAG, "P2PTap native engine stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping P2PTap native engine", e)
        } finally {
            clearNativeCallbacks()
            cleanup()
        }

        // A START received while Stop() was releasing Go resources is queued on
        // the same executor. Keep the service alive and let that command run.
        if (desiredRunning) return

        updateState(STATE_IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private fun requestReload(newConfig: P2PConfig) {
        if (!desiredRunning && currentState != STATE_RUNNING) {
            desiredRunning = true
            val generation = lifecycleGeneration.incrementAndGet()
            ensureForeground(newConfig)
            updateState(STATE_STARTING)
            submitLifecycle { startVpnInternal(generation, newConfig) }
            return
        }

        val generation = lifecycleGeneration.incrementAndGet()
        submitLifecycle { reloadVpnInternal(generation, newConfig) }
    }

    private fun reloadVpnInternal(generation: Long, newConfig: P2PConfig) {
        if (!desiredRunning || generation != lifecycleGeneration.get()) return
        val oldConfig = cachedConfig
        if (!P2PTap.isRunning() || oldConfig == null) {
            startVpnInternal(generation, newConfig)
            return
        }

        when (planVpnReload(oldConfig, newConfig)) {
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
                    cachedConfig = snapshotConfig(newConfig)
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
        // gomobile exposes Java platform types, so null clears the retained
        // Service instances without requiring a new binary API method.
        P2PTap.setStateListener(null)
        P2PTap.setInterfaceProvider(null)
        P2PTap.setProtector(null)
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

        // If Exit Node is designated, route ALL default IPv4 and IPv6 traffic + DNS through TUN
        if (config.exitNode.isNotBlank()) {
            addRouteSafe(builder, "0.0.0.0", 1)
            addRouteSafe(builder, "128.0.0.0", 1)
            addRouteSafe(builder, "::", 1)
            addRouteSafe(builder, "8000::", 1)
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
        val pfd = builder.establish() ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
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
        lifecycleGeneration.incrementAndGet()
        networkChangeHandler.removeCallbacks(networkChangeRunnable)
        submitLifecycle { stopVpnInternal() }
        lifecycleExecutor.shutdown()
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
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "P2PTap VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows P2PTap VPN connection status"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
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
            .setSmallIcon(R.mipmap.ic_launcher)
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
        updateState(targetState, m)
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

        // Periodically refresh notification with live speed and active peers (at most once per second)
        if (currentState == STATE_RUNNING) {
            val now = System.currentTimeMillis()
            if (now - lastNotifUpdateTime >= 1000) {
                val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val config = cachedConfig ?: AppConfigManager.load(this)
                val notifTitle = "P2PTap: ${config.nodeName} (${config.tapIp})"
                val spdText = "↑ ${P2PStateRepository.formatSpeed(txSpeed)}  ↓ ${P2PStateRepository.formatSpeed(rxSpeed)}  ·  $activePeerCount Peers ($directPeers direct)"
                notifManager.notify(notificationId, buildNotification(notifTitle, spdText))
                lastNotifUpdateTime = now
            }
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
