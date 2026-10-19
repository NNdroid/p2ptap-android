package app.fjj.p2ptap

import app.fjj.p2ptap.i18n.UiMessages

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.databinding.ActivityMainBinding
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.ProtocolStatsData
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.ui.BackupDialog
import app.fjj.p2ptap.ui.ConfigActivity
import app.fjj.p2ptap.ui.ExitNodeSelectorDialog
import app.fjj.p2ptap.ui.LogViewerActivity
import app.fjj.p2ptap.ui.PeersDetailDialog
import app.fjj.p2ptap.ui.QrDialog
import app.fjj.p2ptap.ui.TrafficDetailDialog
import app.fjj.p2ptap.viewmodel.MainViewModel
import app.fjj.p2ptap.crash.CrashReportDialog
import com.p2ptap.P2PTap.P2PTap


import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            updateUiState(P2PTapVpnService.currentState, P2PTapVpnService.lastErrorMessage)
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReportDialog.showIfNeeded(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupListeners()
        observeMetrics()
        requestNotificationPermission()
    }


    private fun observeMetrics() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        viewModel.refreshStats()
                        kotlinx.coroutines.delay(1500)
                    }
                }
                kotlinx.coroutines.flow.combine(viewModel.telemetry, viewModel.state) { data, state -> data to state }
                    .collect { (data, state) ->
                        val snapshot = data.snapshot
                        val content = binding.contentMain
                        val unknown = getString(R.string.telemetry_unknown)
                        if (state == P2PTapVpnService.STATE_RUNNING && snapshot != null) {
                            // Use the pushed metrics (1 Hz from Go's metricsTick)
                            // as the authoritative source for peer counts and
                            // throughput — they are always fresh. Recomputing
                            // from snapshot.peers produces a second, potentially
                            // divergent count that was only ever as fresh as the
                            // last pull (TTL 1200 ms).
                            val m = viewModel.metrics.value
                            val activeCount = m.peerCount
                            val direct = m.directPeers
                            val relay = m.relayPeers
                            content.tvActivePeers.text = resources.getQuantityString(
                                R.plurals.active_peers_fmt, activeCount, activeCount, direct, relay
                            )
                            content.tvLiveSpeed.text = "↑ ${P2PStateRepository.formatSpeed(m.txSpeed)}\n↓ ${P2PStateRepository.formatSpeed(m.rxSpeed)}"
                            content.tvTraffic.text = "↑ ${P2PStateRepository.formatBytes(m.totalTx)}  ↓ ${P2PStateRepository.formatBytes(m.totalRx)}"
                            content.tvNatStatus.text = if (snapshot.natStatus.isNotBlank()) snapshot.natStatus else unknown
                            // System health
                            val sys = snapshot.systemHealth
                            content.tvUptime.text = if (sys.uptimeSeconds > 0) P2PStateRepository.formatUptime(sys.uptimeSeconds) else unknown
                            content.tvHeap.text = if (sys.heapAllocMB > 0f) P2PStateRepository.formatMemoryMB(sys.heapAllocMB) else unknown
                            content.tvGoroutines.text = if (sys.goroutines > 0) sys.goroutines.toString() else unknown
                            content.tvGcPause.text = if (sys.lastGcPauseMS > 0f) String.format(Locale.getDefault(), "%.1f ms", sys.lastGcPauseMS) else unknown
                            // Protocol traffic composition
                            renderProtocolStats(snapshot.protocolStats)
                        } else {
                            content.tvActivePeers.text = getString(if (data.failed) R.string.telemetry_refresh_failed else R.string.telemetry_unknown)
                            content.tvLiveSpeed.text = "↑ $unknown\n↓ $unknown"
                            content.tvTraffic.text = "↑ $unknown  ↓ $unknown"
                            content.tvNatStatus.text = unknown
                            content.tvUptime.text = unknown
                            content.tvHeap.text = unknown
                            content.tvGoroutines.text = unknown
                            content.tvGcPause.text = unknown
                            binding.contentMain.protoContainer.removeAllViews()
                        }
                        refreshExitNodeDisplay()
                    }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.status.collect { status ->
                    val state = status.state
                    updateUiState(state, status.message)
                    if (state == P2PTapVpnService.STATE_RUNNING || state == P2PTapVpnService.STATE_IDLE) {
                        refreshPeerId()
                        refreshMultiaddrs()
                        viewModel.refreshPeers()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadConfigDisplay()
        updateUiState(P2PTapVpnService.currentState, P2PTapVpnService.lastErrorMessage)
        viewModel.refreshPeers()

        // Resume engine callbacks when the app returns to foreground.
        if (P2PTapVpnService.isRunning()) {
            startService(Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_APP_FOREGROUND
            })
        }
    }

    override fun onPause() {
        // Pause engine callbacks while the app is in background to avoid
        // false "engine stopped reporting" alarms from the heartbeat monitor.
        if (P2PTapVpnService.isRunning()) {
            startService(Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_APP_BACKGROUND
            })
        }
        stopPulseAnimation()
        super.onPause()
    }

    private fun setupListeners() {
        binding.contentMain.btnConnect.setOnClickListener {
            if (P2PTapVpnService.currentState == P2PTapVpnService.STATE_STARTING || P2PTapVpnService.currentState == P2PTapVpnService.STATE_STOPPING) {
                return@setOnClickListener
            }
            if (P2PTapVpnService.isRunning()) {
                stopVpnService()
            } else {
                binding.contentMain.btnConnect.isEnabled = false
                prepareAndStartVpn()
            }
        }

        binding.contentMain.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }

        binding.contentMain.btnOpenWebUI.setOnClickListener {
            if (!P2PTapVpnService.isRunning()) {
                Toast.makeText(this, getString(R.string.prompt_vpn_not_running), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val config = P2PTapVpnService.runningConfiguration() ?: AppConfigManager.load(this)
            val tokenParam = if (config.webUiToken.isNotBlank()) {
                "?token=" + java.net.URLEncoder.encode(config.webUiToken.trim(), "UTF-8")
            } else {
                ""
            }
            val url = "http://127.0.0.1:${config.webUiPort}/$tokenParam"
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.err_open_browser, UiMessages.describe(this, e)), Toast.LENGTH_SHORT).show()
            }
        }

        binding.contentMain.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        binding.contentMain.btnShareQr.setOnClickListener {
            QrDialog.newInstance().show(supportFragmentManager, QrDialog.TAG)
        }

        binding.contentMain.cardSpeed.setOnClickListener {
            TrafficDetailDialog.newInstance().show(supportFragmentManager, TrafficDetailDialog.TAG)
        }

        binding.contentMain.cardPeers.setOnClickListener {
            PeersDetailDialog.newInstance().show(supportFragmentManager, PeersDetailDialog.TAG)
        }

        binding.contentMain.cardExitNode.setOnClickListener {
            ExitNodeSelectorDialog.newInstance {
                refreshExitNodeDisplay()
            }.show(supportFragmentManager, ExitNodeSelectorDialog.TAG)
        }

        binding.contentMain.btnQuickBackup.setOnClickListener {
            BackupDialog.newInstance {
                loadConfigDisplay()
            }.show(supportFragmentManager, BackupDialog.TAG)
        }

        binding.contentMain.layoutPeerId.setOnClickListener {
            val pid = binding.contentMain.tvPeerId.text?.toString() ?: ""
            if (pid.isNotEmpty() && pid != "-") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.label_peer_id), pid)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.msg_peer_id_copied), Toast.LENGTH_SHORT).show()
            }
        }

        binding.contentMain.layoutTapIp.setOnClickListener {
            val ip = binding.contentMain.tvTapIp.text?.toString() ?: ""
            if (ip.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.label_tap_ip), ip)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.msg_copied_clipboard) + ": $ip", Toast.LENGTH_SHORT).show()
            }
        }

        binding.contentMain.layoutTapIpv6.setOnClickListener {
            val v6 = binding.contentMain.tvTapIpv6.text?.toString() ?: ""
            if (v6.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.label_tap_ipv6), v6)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.msg_copied_clipboard) + ": $v6", Toast.LENGTH_SHORT).show()
            }
        }

        binding.contentMain.btnCopyMultiaddrs.setOnClickListener {
            copyMultiaddrsToClipboard()
        }
        binding.contentMain.tvMultiaddrs.setOnClickListener {
            copyMultiaddrsToClipboard()
        }

        binding.contentMain.btnOpenGithubRepo.setOnClickListener {
            openUrl("https://github.com/NNdroid/p2ptap")
        }

        binding.contentMain.btnOpenDeveloper.setOnClickListener {
            openUrl("https://github.com/NNdroid")
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.err_open_browser, UiMessages.describe(this, e)), Toast.LENGTH_SHORT).show()
        }
    }

    private var statusPulseAnimator: ObjectAnimator? = null
    private var statusScaleAnimatorX: ObjectAnimator? = null
    private var statusScaleAnimatorY: ObjectAnimator? = null

    private fun startPulseAnimation() {
        if (!ValueAnimator.areAnimatorsEnabled() || statusPulseAnimator?.isRunning == true) return
        stopPulseAnimation()
        val ring = binding.contentMain.viewStatusRing
        ring.scaleX = 1.0f
        ring.scaleY = 1.0f
        ring.alpha = 0.20f

        val interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        val scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1.0f, 1.22f).apply {
            duration = 2000L
            this.interpolator = interpolator
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1.0f, 1.22f).apply {
            duration = 2000L
            this.interpolator = interpolator
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val alpha = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.20f, 0.65f).apply {
            duration = 2000L
            this.interpolator = interpolator
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        statusScaleAnimatorX = scaleX
        statusScaleAnimatorY = scaleY
        statusPulseAnimator = alpha

        scaleX.start()
        scaleY.start()
        alpha.start()
    }

    private fun stopPulseAnimation() {
        statusScaleAnimatorX?.cancel()
        statusScaleAnimatorY?.cancel()
        statusPulseAnimator?.cancel()
        val ring = binding.contentMain.viewStatusRing
        ring.scaleX = 1.0f
        ring.scaleY = 1.0f
        ring.alpha = 0.20f
    }

    private fun copyMultiaddrsToClipboard() {
        val addrs = binding.contentMain.tvMultiaddrs.text?.toString() ?: ""
        if (addrs.isNotEmpty() && addrs != getString(R.string.multiaddrs_empty_hint) && addrs != getString(R.string.status_connecting)) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.label_multiaddrs), addrs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.msg_multiaddrs_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadConfigDisplay() {
        val config = AppConfigManager.load(this)
        binding.contentMain.tvNodeName.text = config.nodeName
        binding.contentMain.tvTapIp.text = config.tapIp

        // Load and format app/engine version display
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val appVer = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
            } catch (_: Exception) {
                "1.0"
            }
            val formattedAppVer = if (appVer.startsWith("v", ignoreCase = true)) appVer else "v$appVer"

            val engineVer = try {
                com.p2ptap.P2PTap.P2PTap.version().trim()
            } catch (_: Exception) {
                ""
            }

            val finalDisplayVer = if (engineVer.isNotBlank() && !engineVer.equals("dev", ignoreCase = true) && !engineVer.equals("vdev", ignoreCase = true)) {
                if (engineVer.startsWith("v", ignoreCase = true)) engineVer else "v$engineVer"
            } else {
                formattedAppVer
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                binding.contentMain.tvAppVersion.text = finalDisplayVer
            }
        }

        refreshPeerId()
        refreshMultiaddrs()
        refreshExitNodeDisplay()

        val v6Text = if (config.tapIpv6.isNotBlank()) {
            config.tapIpv6
        } else {
            getString(R.string.telemetry_unknown)
        }
        binding.contentMain.layoutTapIpv6.visibility = View.VISIBLE
        binding.contentMain.tvTapIpv6.text = v6Text
    }

    private fun renderProtocolStats(stats: ProtocolStatsData) {
        val container = binding.contentMain.protoContainer
        container.removeAllViews()
        if (stats.total == 0L) return
        val ctx = this
        val entries = listOf(
            Triple(getString(R.string.proto_ipv4), stats.ipv4, "#4ade80"),
            Triple(getString(R.string.proto_ipv6), stats.ipv6, "#60a5fa"),
            Triple(getString(R.string.proto_tcp), stats.tcp, "#f87171"),
            Triple(getString(R.string.proto_udp), stats.udp, "#fbbf24"),
            Triple(getString(R.string.proto_icmp), stats.icmp, "#a78bfa"),
            Triple(getString(R.string.proto_arp), stats.arp, "#34d399"),
            Triple(getString(R.string.proto_other), stats.other, "#94a3b8")
        )
        for ((name, value, color) in entries) {
            if (value <= 0L) continue
            val pct = ((value * 100L) / stats.total).toInt()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 2, 0, 2) }
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(ctx).apply {
                text = name
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.md_on_surface_variant))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val barSpace = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            val pctText = TextView(ctx).apply {
                text = "$pct%"
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.md_on_surface_variant))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dpToPx(4) }
            }
            header.addView(label)
            header.addView(barSpace)
            header.addView(pctText)
            row.addView(header)
            val progress = com.google.android.material.progressindicator.LinearProgressIndicator(ctx).apply {
                max = 100
                progress = pct
                setIndicatorColor(android.graphics.Color.parseColor(color))
                setTrackThickness(dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(4)
                ).apply { setMargins(0, 2, 0, 0) }
            }
            row.addView(progress)
            container.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun refreshExitNodeDisplay() {
        // Read through AppConfigManager every time: the exit node can be changed
        // and hot-reloaded without the VPN state changing, so caching it against
        // sessionRevision would keep showing the previous gateway. The config
        // itself is already memoised, so this is a cheap read.
        val config = AppConfigManager.load(this)
        if (config.exitNode.isBlank()) {
            binding.contentMain.tvExitNodeStatus.text = getString(if (
                viewModel.telemetry.value.snapshot?.let { it.activeExitPeerId.isNotBlank() || it.activeExitIpv4.isNotBlank() || it.activeExitIpv6.isNotBlank() } == true
            ) R.string.telemetry_disabling else R.string.exit_node_auto_display)
        } else {
            val node = config.exitNode.trim()
            val shortNode = if (node.length > 20) node.take(8) + "..." + node.takeLast(6) else node
            val snap = viewModel.telemetry.value.snapshot
            binding.contentMain.tvExitNodeStatus.text = when {
                P2PTapVpnService.currentState != P2PTapVpnService.STATE_RUNNING ->
                    getString(R.string.exit_node_idle_fmt, shortNode)
                snap?.matchesExit(node) == true ->
                    getString(R.string.exit_node_active_fmt, shortNode, getString(R.string.telemetry_applied))
                // The route is not installed, so say why. A peer the engine is
                // connected to means the gateway is still working at it; a peer
                // it cannot reach means nothing will change on its own.
                snap?.hasConnectedPeer(node) == true ->
                    getString(R.string.exit_node_active_fmt, shortNode, getString(R.string.telemetry_pending))
                else ->
                    getString(R.string.exit_node_active_fmt, shortNode, getString(R.string.telemetry_unreachable))
            }
        }
    }

    private fun refreshPeerId() {
        val cachedPid = AppConfigManager.getPeerId(this@MainActivity)
        if (cachedPid.isNotBlank()) {
            binding.contentMain.tvPeerId.text = cachedPid
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var pid = com.p2ptap.P2PTap.P2PTap.getPeerID()
            if (pid.isNullOrEmpty()) {
                pid = AppConfigManager.getPeerId(this@MainActivity)
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                binding.contentMain.tvPeerId.text = if (!pid.isNullOrEmpty()) pid else "-"
            }
        }
    }

    private fun refreshMultiaddrs() {
        if (!P2PTapVpnService.isRunning()) {
            binding.contentMain.tvMultiaddrs.text = getString(R.string.multiaddrs_empty_hint)
            return
        }

        // Render whatever the last session produced straight away, then let the
        // repository fetch only if it has nothing yet. getMultiaddrs() blocks on
        // the Go engine, so calling it on every onResume was the most visible
        // startup stall; the listen addresses only change with a new session.
        val cached = P2PStateRepository.multiaddrsOrNull()
        if (!cached.isNullOrEmpty()) {
            binding.contentMain.tvMultiaddrs.text = cached
            return
        }
        binding.contentMain.tvMultiaddrs.text = getString(R.string.status_connecting)

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val addrs = P2PStateRepository.multiaddrs {
                com.p2ptap.P2PTap.P2PTap.getMultiaddrs()
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                binding.contentMain.tvMultiaddrs.text =
                    if (addrs.isNullOrEmpty()) getString(R.string.status_connecting) else addrs
            }
        }
    }

    private fun prepareAndStartVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState(state: String, message: String) {
        val content = binding.contentMain
        content.btnOpenWebUI.isEnabled = state == P2PTapVpnService.STATE_RUNNING
        // The health grid reports what the running engine reported, so outside
        // RUNNING every one of its four cells shows "unknown". Keep the card
        // only when it has something real to show.
        content.cardSystemHealth.visibility =
            if (state == P2PTapVpnService.STATE_RUNNING) View.VISIBLE else View.GONE
        when (state) {
            P2PTapVpnService.STATE_RUNNING -> {
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_connected)
                content.tvStatusTitle.text = getString(R.string.status_connected)
                content.tvStatusSubtitle.text = getString(R.string.status_hint_connected)
                content.btnConnect.text = getString(R.string.btn_disconnect)
                content.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_error)
                content.btnConnect.setIconResource(R.drawable.ic_stop)
                content.btnConnect.isEnabled = true
                startPulseAnimation()
            }
            P2PTapVpnService.STATE_STARTING -> {
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_connecting)
                content.tvStatusTitle.text = getString(R.string.status_connecting)
                content.tvStatusSubtitle.text = getString(R.string.status_hint_starting)
                content.btnConnect.text = getString(R.string.status_connecting)
                content.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_connecting)
                content.btnConnect.setIconResource(R.drawable.ic_refresh)
                content.btnConnect.isEnabled = false
                startPulseAnimation()
            }
            P2PTapVpnService.STATE_STOPPING -> {
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_connecting)
                content.tvStatusTitle.text = getString(R.string.status_stopping)
                content.tvStatusSubtitle.text = getString(R.string.status_hint_stopping)
                content.btnConnect.text = getString(R.string.status_stopping)
                content.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_connecting)
                content.btnConnect.setIconResource(R.drawable.ic_refresh)
                content.btnConnect.isEnabled = false
                stopPulseAnimation()
            }
            P2PTapVpnService.STATE_TIMEOUT -> {
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_connecting)
                content.tvStatusTitle.text = getString(R.string.status_timeout)
                content.tvStatusSubtitle.text = if (message.isNotBlank()) message else getString(R.string.status_hint_timeout)
                content.btnConnect.text = getString(R.string.btn_retry)
                content.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_timeout)
                content.btnConnect.setIconResource(R.drawable.ic_refresh)
                content.btnConnect.isEnabled = true
                stopPulseAnimation()
            }
            P2PTapVpnService.STATE_ERROR -> {
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_disconnected)
                content.tvStatusTitle.text = getString(R.string.status_error)
                content.tvStatusSubtitle.text = if (message.isNotEmpty()) message else getString(R.string.status_error)
                content.btnConnect.text = getString(R.string.btn_retry)
                content.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.brand_primary)
                content.btnConnect.setIconResource(R.drawable.ic_refresh)
                content.btnConnect.isEnabled = true
                stopPulseAnimation()
            }
            else -> { // STATE_IDLE
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_disconnected)
                content.tvStatusTitle.text = getString(R.string.status_disconnected)
                content.tvStatusSubtitle.text = getString(R.string.status_hint_idle)
                content.btnConnect.text = getString(R.string.btn_connect)
                content.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.brand_primary)
                content.btnConnect.setIconResource(R.drawable.ic_play)
                content.btnConnect.isEnabled = true
                stopPulseAnimation()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
