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
import android.view.View
import android.widget.Toast
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
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.ui.BackupDialog
import app.fjj.p2ptap.ui.ConfigActivity
import app.fjj.p2ptap.ui.ExitNodeSelectorDialog
import app.fjj.p2ptap.ui.LogViewerActivity
import app.fjj.p2ptap.ui.PeersDetailDialog
import app.fjj.p2ptap.ui.QrDialog
import app.fjj.p2ptap.ui.TrafficDetailDialog
import app.fjj.p2ptap.viewmodel.MainViewModel
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
                        } else {
                            content.tvActivePeers.text = getString(if (data.failed) R.string.telemetry_refresh_failed else R.string.telemetry_unknown)
                            content.tvLiveSpeed.text = "↑ $unknown\n↓ $unknown"
                            content.tvTraffic.text = "↑ $unknown  ↓ $unknown"
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
            binding.contentMain.tvExitNodeStatus.text = getString(R.string.exit_node_active_fmt, shortNode, getString(when {
                P2PTapVpnService.currentState != P2PTapVpnService.STATE_RUNNING -> R.string.telemetry_saved
                viewModel.telemetry.value.snapshot?.matchesExit(node) == true -> R.string.telemetry_applied
                else -> R.string.telemetry_pending
            }))
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
