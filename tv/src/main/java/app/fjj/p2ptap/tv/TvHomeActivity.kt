package app.fjj.p2ptap.tv

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PBootFlagStore
import app.fjj.p2ptap.tv.databinding.ActivityTvHomeBinding
import app.fjj.p2ptap.service.LogCollector
import app.fjj.p2ptap.service.NodeMetrics
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S1, the home screen, and the only default entry point on a TV.
 *
 * A set-top box has two inputs: a remote and a sofa. So this screen is one
 * status surface and one decision, because the decision a TV user actually
 * makes is "is my mesh on", and everything else is one focus hop away on a
 * dedicated screen. No scrolling, no BottomSheet, no EditText anywhere in the
 * tree — the platform's BottomSheet dismiss gesture and the IME are both
 * absent from a set-top box.
 */
class TvHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvHomeBinding

    /**
     * Whether the next press of the toggle button will bring the VPN up.
     *
     * Kept as an intent rather than derived from the state each time: the state
     * lagging behind the user's action is exactly the window in which a double
     * press would send two opposite requests to the engine.
     */
    private var pendingConnect = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // DPAD navigation comes from the platform focus system; every focusable
        // view here declares it explicitly rather than relying on a library
        // Activity subclass, so this works without a TV-only appcompat.

        observeRepository()
        loadIdentity()
        loadVersions()

        // The sink is installed at the entry point, not in the log screen, so
        // the session's own lines are captured even when the viewer never
        // opens the log. It is idempotent, and the phone build never calls it.
        LogCollector.install()

        binding.tvToggleButton.setOnClickListener { toggleVpn() }
        binding.tvNavPeers.setOnClickListener {
            startActivity(Intent(this, TvPeersActivity::class.java))
        }
        binding.tvNavDiagnostics.setOnClickListener {
            startActivity(Intent(this, TvDiagnosticsActivity::class.java))
        }
        binding.tvNavLogs.setOnClickListener {
            startActivity(Intent(this, TvLogsActivity::class.java))
        }
        binding.tvNavSettings.setOnClickListener {
            startActivity(Intent(this, TvSettingsActivity::class.java))
        }
        binding.tvNavTransfer.setOnClickListener {
            startActivity(Intent(this, TvImportActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        TvTelemetry.start(this)
    }

    override fun onPause() {
        TvTelemetry.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus the toggle, not the header: the button is the action a viewer
        // opens the app for, and restoring it here covers returning from the
        // system VPN consent dialog as well as from the child screens.
        if (hasFocus) binding.tvToggleButton.requestFocus()
    }

    // BACK is left to the platform: finishing this activity closes the app on
    // the home screen, and from Peers/Diagnostics it returns here. Intercepting
    // it here would also trip the predictive-back lint check, which asks for
    // OnBackPressedDispatcher registration — not worth it for default behaviour.

    // ── Repository observation ──────────────────────────────────────────────

    private fun observeRepository() {
        lifecycleScope.launch {
            P2PStateRepository.status.collect { status ->
                renderState(status.state, status.message)
            }
        }
        lifecycleScope.launch {
            P2PStateRepository.metrics.collect { renderMetrics(it) }
        }
    }

    private fun renderState(state: String, message: String) {
        val (labelRes, dotRes) = when (state) {
            P2PTapVpnService.STATE_RUNNING ->
                R.string.tv_status_running to R.color.tv_status_connected
            P2PTapVpnService.STATE_STARTING ->
                R.string.tv_status_starting to R.color.tv_status_connecting
            P2PTapVpnService.STATE_STOPPING ->
                R.string.tv_status_stopping to R.color.tv_status_stopping
            P2PTapVpnService.STATE_TIMEOUT ->
                R.string.tv_status_timeout to R.color.tv_status_timeout
            P2PTapVpnService.STATE_ERROR ->
                R.string.tv_status_error to R.color.tv_status_error
            else -> R.string.tv_status_idle to R.color.tv_status_disconnected
        }
        binding.tvStatus.text = getString(labelRes)
        binding.tvStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, dotRes)

        val running = state == P2PTapVpnService.STATE_RUNNING
        binding.tvToggleButton.text =
            if (running) getString(R.string.tv_disconnect) else getString(R.string.tv_connect)
        binding.tvToggleButton.isEnabled = state != P2PTapVpnService.STATE_STOPPING
        binding.tvToggleButton.requestFocus()

        // The addresses line doubles as the error line: a disconnected node has
        // no addresses to show, and the slot should not read as broken.
        val detail = message.trim()
        if (detail.isNotEmpty() && !running) binding.tvAddresses.text = detail

        pendingConnect = !running && state != P2PTapVpnService.STATE_STOPPING
        renderAutostart()
    }

    private fun renderMetrics(metrics: NodeMetrics) {
        binding.tvTxSpeed.text = TvFormat.speed(metrics.txSpeed)
        binding.tvRxSpeed.text = TvFormat.speed(metrics.rxSpeed)
        binding.tvTotalTx.text = getString(R.string.tv_total_tx_fmt, TvFormat.bytes(metrics.totalTx))
        binding.tvTotalRx.text = getString(R.string.tv_total_rx_fmt, TvFormat.bytes(metrics.totalRx))
        binding.tvPeerCount.text = resources.getQuantityString(
            R.plurals.tv_peer_count, metrics.peerCount, metrics.peerCount
        )
    }

    /**
     * Whether the set should bring the tunnel back after a reboot.
     *
     * Not a switch: a TV user should not have to think about it. It is a
     * footnote reporting what will happen, because the honest answer is
     * "whatever you last did" — which is exactly the point of persisting it.
     */
    private fun renderAutostart() {
        binding.tvAutostartStatus.text = getString(
            if (P2PBootFlagStore.isAutostart(this)) R.string.tv_autostart_on
            else R.string.tv_autostart_off
        )
    }

    // ── Identity and versions, all off the main thread ─────────────────────

    private fun loadIdentity() {
        lifecycleScope.launch(Dispatchers.Default) {
            val nodeName = try {
                AppConfigManager.load(this@TvHomeActivity).nodeName.trim()
            } catch (_: Exception) {
                ""
            }
            launch(Dispatchers.Main) {
                if (nodeName.isNotEmpty()) binding.tvNodeName.text = nodeName
            }
        }
        lifecycleScope.launch(Dispatchers.Default) {
            // Cached per session by the repository, so this is at most one JNI
            // call per VPN session rather than one per focus change.
            val addrs = P2PStateRepository.multiaddrs { P2PTap.getMultiaddrs() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            launch(Dispatchers.Main) {
                binding.tvAddresses.visibility = View.VISIBLE
                binding.tvAddresses.text = if (addrs == null) {
                    getString(R.string.tv_addresses_none)
                } else {
                    TvFormat.shorten(addrs, 56, 24)
                }
            }
        }
    }

    private fun loadVersions() {
        lifecycleScope.launch(Dispatchers.Default) {
            val engine = runCatching { P2PTap.version() }.getOrNull()?.trim().orEmpty()
            val appVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            }.getOrNull().orEmpty()
            launch(Dispatchers.Main) {
                if (engine.isNotEmpty()) {
                    binding.tvEngineVersion.text = getString(R.string.tv_version_engine_fmt, engine)
                }
                if (appVersion.isNotEmpty()) {
                    binding.tvAppVersion.text = getString(R.string.tv_version_app_fmt, appVersion)
                }
            }
        }
    }

    // ── The one button ──────────────────────────────────────────────────────

    private fun toggleVpn() {
        if (!binding.tvToggleButton.isEnabled) return
        if (pendingConnect) startVpn() else stopVpn()
    }

    /**
     * Ask the platform for VPN permission if it was never granted, then start
     * the service.
     *
     * `VpnService.prepare` returning null means permission already exists. The
     * non-null branch hands off to the system dialog and returns through
     * onResume, which re-enters here with pendingConnect still true.
     */
    private fun startVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            try {
                startActivity(prepare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(
                    this, getString(R.string.tv_status_error) + ": " + e.message, Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        startVpnService()
    }

    private fun startVpnService() {
        val intent = Intent(this, P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_START
        }
        // minSdk is 31, so the O branch is always taken and there is nothing to
        // fall back to. Keeping the branch would just be noise.
        startForegroundService(intent)
    }

    private fun stopVpn() {
        startService(
            Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_STOP
            }
        )
    }
}
