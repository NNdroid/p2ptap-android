package app.fjj.p2ptap.tv.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PBootFlagStore
import app.fjj.p2ptap.service.NodeMetrics
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.TvFormat
import app.fjj.p2ptap.tv.databinding.FragmentTvHomeBinding
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S1, the home screen.
 *
 * The whole app's primary action surface. A TV user opens P2PTap to answer
 * one question — "is my mesh on?" — and then possibly one action — "turn it
 * off / back on". Everything else is one focus hop away on a dedicated screen,
 * which is why the hero card dominates and the nav grid below is small.
 *
 * All the state comes from [P2PStateRepository], which is a single object in
 * :core shared by the VPN service, the peers screen and every other
 * destination. The fragment only observes and renders — it never talks to the
 * engine directly except through the repository, which is what keeps it
 * deterministic.
 */
class TvHomeFragment : Fragment() {

    private var _binding: FragmentTvHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Whether the next press of the toggle button will bring the VPN up.
     *
     * Kept as an intent rather than derived from the state each time: the
     * state lagging behind the user's action is exactly the window in which a
     * double press would send two opposite requests to the engine.
     */
    private var pendingConnect = true
    private var cachedAddresses: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeRepository()
        loadIdentity()
        loadVersions()
        wireUpNav()

        binding.tvHeroToggle.setOnClickListener { toggleVpn() }

        // Attach the focus animation to the hero card, the toggle, and each
        // nav card. The rail's animation is handled by the adapter.
        TvFocusAnimation.attachTo(binding.tvHeroToggle, TvFocusAnimation.SCALE_BUTTON)
        TvFocusAnimation.attachTo(binding.tvHeroCard, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvNavPeers, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvNavTransfer, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvNavSettings, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvNavDiagnostics, TvFocusAnimation.SCALE_CARD)
    }

    override fun onResume() {
        super.onResume()
        // Focus the toggle when the fragment becomes visible. The user who
        // opens the app wants to press the button, not hunt for it.
        binding.tvHeroToggle.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun wireUpNav() {
        val navController = findNavController()
        binding.tvNavPeers.setOnClickListener {
            if (navController.currentDestination?.id != R.id.tvPeersFragment) {
                navController.navigate(R.id.tvPeersFragment)
            }
        }
        binding.tvNavTransfer.setOnClickListener {
            if (navController.currentDestination?.id != R.id.tvTransferFragment) {
                navController.navigate(R.id.tvTransferFragment)
            }
        }
        binding.tvNavSettings.setOnClickListener {
            if (navController.currentDestination?.id != R.id.tvSettingsFragment) {
                navController.navigate(R.id.tvSettingsFragment)
            }
        }
        binding.tvNavDiagnostics.setOnClickListener {
            if (navController.currentDestination?.id != R.id.tvDiagnosticsFragment) {
                navController.navigate(R.id.tvDiagnosticsFragment)
            }
        }
    }

    // ── Repository observation ──────────────────────────────────────────────

    private fun observeRepository() {
        viewLifecycleOwner.lifecycleScope.launch {
            P2PStateRepository.status.collect { status ->
                renderState(status.state, status.message)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
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
        binding.tvStatusText.text = getString(labelRes)
        binding.tvStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), dotRes)

        val running = state == P2PTapVpnService.STATE_RUNNING
        binding.tvHeroToggle.text =
            if (running) getString(R.string.tv_disconnect) else getString(R.string.tv_connect)
        binding.tvHeroToggle.isEnabled = state != P2PTapVpnService.STATE_STOPPING

        // The addresses line doubles as the error line: a disconnected node
        // has no addresses to show, and the slot should not read as broken.
        // renderState is the single source of truth for this line; loadIdentity
        // only updates cachedAddresses and re-invokes renderState.
        val detail = message.trim()
        if (detail.isNotEmpty() && !running) {
            binding.tvAddresses.visibility = View.VISIBLE
            binding.tvAddresses.text = detail
        } else {
            binding.tvAddresses.visibility = View.VISIBLE
            binding.tvAddresses.text = cachedAddresses ?: getString(R.string.tv_addresses_none)
        }

        pendingConnect = !running && state != P2PTapVpnService.STATE_STOPPING
    }

    private fun renderMetrics(metrics: NodeMetrics) {
        binding.tvTxSpeed.text = TvFormat.speed(metrics.txSpeed)
        binding.tvRxSpeed.text = TvFormat.speed(metrics.rxSpeed)
        binding.tvTotalTx.text =
            getString(R.string.tv_total_tx_fmt, TvFormat.bytes(metrics.totalTx))
        binding.tvPeerCount.text = resources.getQuantityString(
            R.plurals.tv_peer_count, metrics.peerCount, metrics.peerCount
        )
    }

    // ── Identity and versions, all off the main thread ─────────────────────

    private fun loadIdentity() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val nodeName = try {
                AppConfigManager.load(requireContext()).nodeName.trim()
            } catch (_: Exception) {
                ""
            }
            launch(Dispatchers.Main) {
                if (nodeName.isNotEmpty()) binding.tvNodeName.text = nodeName
            }
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            // Cached per session by the repository, so this is at most one JNI
            // call per VPN session rather than one per focus change.
            val addrs = P2PStateRepository.multiaddrs { P2PTap.getMultiaddrs() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            launch(Dispatchers.Main) {
                cachedAddresses = addrs?.let { TvFormat.shorten(it, 56, 24) }
                    ?: getString(R.string.tv_addresses_none)
                // Re-render so renderState picks up the new cachedAddresses.
                val s = P2PStateRepository.status.value
                renderState(s.state, s.message)
            }
        }
    }

    private fun loadVersions() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val engine = runCatching { P2PTap.version() }.getOrNull()?.trim().orEmpty()
            val appVersion = runCatching {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName.orEmpty()
            }.getOrNull().orEmpty()
            launch(Dispatchers.Main) {
                val parts = mutableListOf<String>()
                if (appVersion.isNotEmpty()) parts.add(appVersion)
                if (engine.isNotEmpty()) parts.add("engine $engine")
                if (parts.isNotEmpty()) {
                    binding.tvHeroVersion.text = "v" + parts.joinToString(" · ")
                }
            }
        }
    }

    // ── The one button ──────────────────────────────────────────────────────

    private fun toggleVpn() {
        if (!binding.tvHeroToggle.isEnabled) return
        if (pendingConnect) startVpn() else stopVpn()
    }

    /**
     * Ask the platform for VPN permission if it was never granted, then start
     * the service.
     *
     * `VpnService.prepare` returning null means permission already exists.
     * The non-null branch hands off to the system dialog and returns through
     * onResume, which re-enters here with pendingConnect still true.
     */
    private fun startVpn() {
        val ctx = requireContext()
        val prepare = VpnService.prepare(ctx)
        if (prepare != null) {
            try {
                ctx.startActivity(prepare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(ctx, getString(R.string.tv_status_error) + ": " + e.message,
                    Toast.LENGTH_LONG).show()
            }
            return
        }
        startVpnService()
    }

    private fun startVpnService() {
        val intent = Intent(requireContext(), P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_START
        }
        // minSdk is 31, so the O branch is always taken and there is nothing
        // to fall back to.
        requireContext().startForegroundService(intent)
    }

    private fun stopVpn() {
        requireContext().startService(
            Intent(requireContext(), P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_STOP
            }
        )
    }
}
