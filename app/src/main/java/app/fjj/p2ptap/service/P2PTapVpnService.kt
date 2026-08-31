package app.fjj.p2ptap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
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
import java.io.File
import java.net.NetworkInterface
import org.json.JSONArray
import kotlin.concurrent.thread

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

    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cachedConfig: P2PConfig? = null
    private var lastNotifUpdateTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                restartVpn()
                return START_STICKY
            }
            ACTION_START, null -> {
                if (currentState == STATE_RUNNING || currentState == STATE_STARTING) {
                    return START_STICKY
                }
                startVpn()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun restartVpn() {
        thread(name = "P2PTap-RestartThread") {
            try {
                if (P2PTap.isRunning()) {
                    P2PTap.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping P2PTap engine on restart", e)
            }
            cleanup()
            startVpn()
        }
    }

    private fun startVpn() {
        updateState(STATE_STARTING)
        val config = AppConfigManager.load(this)
        cachedConfig = config

        val notif = buildNotification(getString(R.string.notification_starting), config.tapIp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, notificationId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notif)
        }

        thread(name = "P2PTap-VpnThread") {
            try {
                val tunFd = establishVpn(config)
                val cfgJson = config.toJsonString(this)
                Log.i(TAG, "Starting P2PTap native engine with config: $cfgJson")

                P2PTap.setProtector(this)
                P2PTap.setStateListener(this)
                P2PTap.setInterfaceProvider(this)
                P2PTap.start(cfgJson, tunFd.toLong())

                updateState(STATE_RUNNING)
                registerNetworkCallback()
                val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ipInfo = "IPv4: ${config.tapIp}" + if (config.tapIpv6.isNotBlank()) " | IPv6: ${config.tapIpv6}" else ""
                notifManager.notify(notificationId, buildNotification(getString(R.string.notification_running), ipInfo))
                Log.i(TAG, "P2PTap native engine running successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start P2PTap VPN", e)
                lastErrorMessage = e.message ?: "Unknown error"
                updateState(STATE_ERROR, lastErrorMessage)
                cleanup()
                stopSelf()
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (P2PTap.isRunning()) {
                        Log.i(TAG, "Android Network Available -> triggering Go network refresh")
                        P2PTap.onNetworkChanged()
                    }
                }

                override fun onLost(network: Network) {
                    if (P2PTap.isRunning()) {
                        Log.i(TAG, "Android Network Lost -> triggering Go network refresh")
                        P2PTap.onNetworkChanged()
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (P2PTap.isRunning()) {
                        P2PTap.onNetworkChanged()
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister NetworkCallback", e)
            }
            networkCallback = null
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

        builder.addAddress(ipStr, prefix)

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
            builder.addAddress(v6Ip, v6Prefix)
            Log.i(TAG, "Configured IPv6 address: $v6Ip/$v6Prefix")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add IPv6 address: $v6Text", e)
        }

        // Route virtual overlay IPv4 and accepted subnet networks
        if (config.acceptSubnets) {
            // When acceptSubnets is true, route all private IPv4 ranges into the VPN so any peer's advertised subnets are reachable
            builder.addRoute("10.0.0.0", 8)
            builder.addRoute("172.16.0.0", 12)
            builder.addRoute("192.168.0.0", 16)
        } else {
            if (ipStr.startsWith("10.")) {
                builder.addRoute("10.0.0.0", 8)
            } else if (ipStr.startsWith("172.")) {
                builder.addRoute("172.16.0.0", 12)
            } else if (ipStr.startsWith("192.168.")) {
                builder.addRoute("192.168.0.0", 16)
            } else {
                builder.addRoute(ipStr, prefix)
            }
        }

        // IPv6 Overlay Route
        try {
            builder.addRoute("fd00::", 8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add IPv6 ULA route: fd00::/8", e)
        }

        // Also add explicitly advertised custom subnets if specified
        for (sub in config.advertisedSubnets) {
            try {
                val subParts = sub.split("/")
                val sIp = subParts[0].trim()
                val sPrefix = if (subParts.size > 1) subParts[1].trim().toIntOrNull() ?: 24 else 24
                if (sIp.isNotEmpty()) {
                    builder.addRoute(sIp, sPrefix)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add route for advertised subnet: $sub", e)
            }
        }

        // If Exit Node is designated, route ALL default IPv4 and IPv6 traffic + DNS through TUN
        if (config.exitNode.isNotBlank()) {
            try {
                builder.addRoute("0.0.0.0", 1)
                builder.addRoute("128.0.0.0", 1)
                Log.i(TAG, "Configured Exit Node default IPv4 routes: 0.0.0.0/1 & 128.0.0.0/1 via ${config.exitNode}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add default IPv4 routes for exit node: ${config.exitNode}", e)
            }

            try {
                builder.addRoute("::", 1)
                builder.addRoute("8000::", 1)
                Log.i(TAG, "Configured Exit Node default IPv6 routes: ::/1 & 8000::/1 via ${config.exitNode}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add default IPv6 routes for exit node: ${config.exitNode}", e)
            }

            // Configure DNS servers to route DNS requests through the TUN interface and prevent DNS leaks
            try {
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("223.5.5.5")
                builder.addDnsServer("2606:4700:4700::1111")
                Log.i(TAG, "Configured encrypted/tunneled DNS servers for Exit Node mode")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DNS servers for exit node mode", e)
            }
        }

        // Allow app traffic through VPN
        val pfd = builder.establish() ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
        // Transfer file descriptor ownership completely to Go native engine
        return pfd.detachFd()
    }

    private fun stopVpn() {
        if (currentState == STATE_STOPPING || currentState == STATE_IDLE) return
        updateState(STATE_STOPPING)
        thread(name = "P2PTap-StopThread") {
            try {
                Log.i(TAG, "Stopping P2PTap native engine...")
                if (P2PTap.isRunning()) {
                    com.p2ptap.P2PTap.P2PTap.stop()
                }
                Log.i(TAG, "P2PTap native engine stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping P2PTap native engine", e)
            }
            cleanup()
            updateState(STATE_IDLE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        unregisterNetworkCallback()
        vpnInterface = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // com.p2ptap.P2PTap.Protector implementation
    override fun protect(fd: Int): Boolean {
        return super.protect(fd)
    }

    private fun updateState(state: String, message: String = "") {
        currentState = state
        if (state == STATE_ERROR) {
            lastErrorMessage = message
        }
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
        P2PStateRepository.updateState(s, m)
        updateState(s, m)
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
        if (currentState != STATE_RUNNING) return

        P2PStateRepository.updateMetrics(peerCount, directPeers, relayPeers, txSpeed, rxSpeed, totalTx, totalRx)

        // Periodically refresh notification with live speed and active peers (at most once per second)
        if (currentState == STATE_RUNNING) {
            val now = System.currentTimeMillis()
            if (now - lastNotifUpdateTime >= 1000) {
                val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val config = cachedConfig ?: AppConfigManager.load(this)
                val notifTitle = "P2PTap: ${config.nodeName} (${config.tapIp})"
                val spdText = "↑ ${P2PStateRepository.formatSpeed(txSpeed)}  ↓ ${P2PStateRepository.formatSpeed(rxSpeed)}  ·  $peerCount Peers ($directPeers direct)"
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
                if (name.startsWith("tun") || name.startsWith("p2p") || name.startsWith("dummy")) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
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
}
