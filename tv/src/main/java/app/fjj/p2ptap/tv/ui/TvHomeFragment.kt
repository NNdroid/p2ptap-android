package app.fjj.p2ptap.tv.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.config.AppConfigManager
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
 * A TV user opens P2PTap to answer one question — "is my mesh on?" — and then
 * possibly take one action — "turn it off / back on". The hero answers the
 * question at a glance and holds the action; the right column adds the two
 * things a viewer actually wants next (how fast, how much).
 *
 * Navigation is not this screen's job any more. The old layout carried four
 * quick-nav cards that duplicated four of the seven rail destinations, which
 * cost ~240dp of a 540dp-tall screen to save a focus hop that was already one
 * press away. That space now shows throughput and session counters.
 *
 * All state comes from [P2PStateRepository], a single object in :core shared
 * by the VPN service, the peers screen and every other destination. The
 * fragment only observes and renders — it never talks to the engine directly
 * except through the repository, which is what keeps it deterministic.
 */
class TvHomeFragment : Fragment() {

    private var _binding: FragmentTvHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Whether the next press of the toggle will bring the VPN up.
     *
     * Kept as an intent rather than derived from the state each time: the
     * window in which the state lags behind the user's press is exactly the
     * window in which a double press would send two opposite requests to the
     * engine.
     */
    private var pendingConnect = true

    private var cachedAddresses: String? = null

    /** Guards against re-inflating the hero drawable on every status tick. */
    private var heroBackgroundRes = 0

    // The subtitle is assembled from two independent flows — status carries
    // the engine's message, metrics carries the peer breakdown — so both
    // renderers stash what they know and rebuild the line through one path.
    private var lastState = P2PTapVpnService.STATE_IDLE
    private var lastMessage = ""
    private var lastMetrics: NodeMetrics? = null

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

        binding.tvHeroToggle.setOnClickListener { toggleVpn() }
        TvFocusAnimation.attachTo(binding.tvHeroToggle, TvFocusAnimation.SCALE_BUTTON)
    }

    override fun onResume() {
        super.onResume()
        // The user who opens the app wants to press the button, not hunt for
        // it. Only the toggle is focusable on this screen, so a focused rail
        // item and a focused button are the whole focus graph.
        binding.tvHeroToggle.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
        val labelRes: Int
        val dotRes: Int
        val heroRes: Int
        when (state) {
            P2PTapVpnService.STATE_RUNNING -> {
                labelRes = R.string.tv_status_running
                dotRes = R.color.tv_status_connected
                heroRes = R.drawable.tv_bg_hero_connected
            }
            P2PTapVpnService.STATE_STARTING -> {
                labelRes = R.string.tv_status_starting
                dotRes = R.color.tv_status_connecting
                heroRes = R.drawable.tv_bg_hero_connecting
            }
            P2PTapVpnService.STATE_STOPPING -> {
                labelRes = R.string.tv_status_stopping
                dotRes = R.color.tv_status_stopping
                heroRes = R.drawable.tv_bg_hero_connecting
            }
            P2PTapVpnService.STATE_TIMEOUT -> {
                labelRes = R.string.tv_status_timeout
                dotRes = R.color.tv_status_timeout
                heroRes = R.drawable.tv_bg_hero_error
            }
            P2PTapVpnService.STATE_ERROR -> {
                labelRes = R.string.tv_status_error
                dotRes = R.color.tv_status_error
                heroRes = R.drawable.tv_bg_hero_error
            }
            else -> {
                labelRes = R.string.tv_status_idle
                dotRes = R.color.tv_status_disconnected
                heroRes = R.drawable.tv_bg_hero_idle
            }
        }

        binding.tvStatusText.setText(labelRes)
        binding.tvStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), dotRes)

        // Re-inflating a four-layer drawable at status-tick rate is wasteful;
        // StateFlow already de-duplicates equal emissions, but a TIMEOUT that
        // flickers between TIMEOUT and ERROR would still swap it every frame.
        if (heroBackgroundRes != heroRes) {
            binding.tvHeroCard.setBackgroundResource(heroRes)
            heroBackgroundRes = heroRes
        }

        val running = state == P2PTapVpnService.STATE_RUNNING
        renderToggle(state)
        pendingConnect = !running && state != P2PTapVpnService.STATE_STOPPING

        lastState = state
        lastMessage = message
        renderSubtitle()

        binding.tvAddresses.text = cachedAddresses ?: getString(R.string.tv_addresses_none)
    }

    /**
     * The line under the state word.
     *
     * It carries the engine's message when there is one — that is how an error
     * explains itself without a second row — and otherwise falls back to the
     * peer breakdown, which is the most useful thing to know while connected.
     * It hides entirely rather than sitting there empty: an empty slot under
     * the state word reads as broken.
     */
    private fun renderSubtitle() {
        val metrics = lastMetrics
        val detail = lastMessage.trim()
        val text = detail.ifBlank {
            if (lastState == P2PTapVpnService.STATE_RUNNING && metrics != null &&
                metrics.peerCount > 0
            ) {
                getString(
                    R.string.tv_peers_summary_fmt,
                    resources.getQuantityString(
                        R.plurals.tv_peer_count, metrics.peerCount, metrics.peerCount
                    ),
                    getString(R.string.tv_path_direct), metrics.directPeers,
                    getString(R.string.tv_path_relay), metrics.relayPeers,
                )
            } else {
                ""
            }
        }
        binding.tvStatusSub.text = text
        binding.tvStatusSub.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * The toggle changes both its label and its colour per state.
     *
     * Colour encodes intent, not status: red is destructive (pressing it ends
     * a working tunnel), the neutral tone is a way out (cancel a connection
     * that has not landed yet), and primary is "do the thing". Painting the
     * cancel state brand-cyan made the one button on screen look like the
     * primary action while it did the opposite.
     */
    private fun renderToggle(state: String) {
        val ctx = requireContext()
        val labelRes: Int
        val bgRes: Int
        val fgRes: Int
        when (state) {
            P2PTapVpnService.STATE_RUNNING -> {
                labelRes = R.string.tv_disconnect
                bgRes = R.color.tv_status_error
                fgRes = R.color.md_theme_onError
            }
            P2PTapVpnService.STATE_STARTING -> {
                labelRes = R.string.tv_cancel
                bgRes = R.color.md_theme_secondaryContainer
                fgRes = R.color.md_theme_onSecondaryContainer
            }
            P2PTapVpnService.STATE_STOPPING -> {
                labelRes = R.string.tv_status_stopping
                bgRes = R.color.md_theme_secondaryContainer
                fgRes = R.color.md_theme_onSecondaryContainer
            }
            P2PTapVpnService.STATE_TIMEOUT,
            P2PTapVpnService.STATE_ERROR -> {
                labelRes = R.string.tv_retry
                bgRes = R.color.md_theme_primary
                fgRes = R.color.md_theme_onPrimary
            }
            else -> {
                labelRes = R.string.tv_connect
                bgRes = R.color.md_theme_primary
                fgRes = R.color.md_theme_onPrimary
            }
        }

        binding.tvHeroToggle.setText(labelRes)
        binding.tvHeroToggle.isEnabled = state != P2PTapVpnService.STATE_STOPPING
        val bg = ContextCompat.getColorStateList(ctx, bgRes)
        val fg = ContextCompat.getColor(ctx, fgRes)
        binding.tvHeroToggle.backgroundTintList = bg
        binding.tvHeroToggle.setTextColor(fg)
        binding.tvHeroToggle.iconTint = ColorStateList.valueOf(fg)
    }

    private fun renderMetrics(metrics: NodeMetrics) {
        lastMetrics = metrics
        binding.tvTxSpeed.text = TvFormat.speed(metrics.txSpeed)
        binding.tvRxSpeed.text = TvFormat.speed(metrics.rxSpeed)
        binding.tvSessionTx.text = TvFormat.bytes(metrics.totalTx)
        binding.tvSessionRx.text = TvFormat.bytes(metrics.totalRx)
        binding.tvSessionPeers.text = metrics.peerCount.toString()
        // The subtitle may be showing the peer breakdown, so it has to follow.
        renderSubtitle()
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
                cachedAddresses = addrs
                    ?.split(Regex("\\s+"))
                    ?.filter { it.isNotBlank() }
                    ?.take(2)
                    ?.joinToString("\n") { TvFormat.shorten(it, 26, 14) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: getString(R.string.tv_addresses_none)
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
