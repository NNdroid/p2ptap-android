package app.fjj.p2ptap.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.MainActivity
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.databinding.FragmentHomeBinding
import app.fjj.p2ptap.i18n.UiMessages
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.ProtocolStatsData
import app.fjj.p2ptap.viewmodel.MainViewModel
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home tab: the hero, the three metric cells, node identity, exit gateway,
 * the operator disclosure and the about block.
 *
 * This is MainActivity's home logic moved wholesale, not rewritten. The rule
 * that made the move worth doing: everything here is a *view* over the one
 * shared MainViewModel plus the engine's static state, so nothing had to be
 * threaded back through the Activity. What genuinely belongs to the Activity
 * — the bottom bar, the VPN permission contract's owner, the foreground /
 * background service notifications — stays there, and the only thing this
 * Fragment asks back is "switch to another tab".
 *
 * Three behaviours are load-bearing and easy to break:
 *
 *   · the metric cells read the *pushed* metrics (1 Hz from Go), never a
 *     recount of snapshot.peers — the two can disagree and only one is fresh;
 *   · the advanced badge counts cells that actually hold a value, so a stopped
 *     engine shows 0 rather than a fixed 9;
 *   · every state transition repaints fill + ink + icon together. Setting only
 *     the fill leaves white text on amber, which is the exact case where the
 *     contrast collapses.
 */
class HomeFragment : Fragment() {

    /**
     * The one thing a tab cannot do for itself.
     *
     * Implemented by MainActivity, which owns the bar and the FragmentManager
     * the three bodies live in.
     */
    interface Host {
        /** Jump to another bottom-nav destination, indexed with MainActivity.TAB_*. */
        fun navigateToTab(tab: Int)
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var advancedExpanded = false
    private var statusPulseAnimator: ObjectAnimator? = null

    /**
     * VpnService.prepare is an Activity result, so the contract lives here.
     *
     * Registered as a field initialiser — Fragment requires it before onCreate,
     * and the callback can land after the view is gone (rotation during the
     * system consent dialog), hence the null check.
     */
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (_binding == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            updateUiState(P2PTapVpnService.currentState, P2PTapVpnService.lastErrorMessage)
            toast(getString(R.string.vpn_permission_denied))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        setupAdvancedSection()
        // Seed the badge before the first telemetry tick, otherwise the chip
        // renders as an empty pill for the first 1.5 seconds.
        updateAdvancedCount()
        observeMetrics()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        loadConfigDisplay()
        updateUiState(P2PTapVpnService.currentState, P2PTapVpnService.lastErrorMessage)
    }

    override fun onPause() {
        stopPulseAnimation()
        super.onPause()
    }

    override fun onDestroyView() {
        // The elapsed ticker is a plain Handler loop, not a lifecycle-scoped
        // coroutine, so it has to be cancelled explicitly or it keeps posting
        // against a dead binding.
        elapsedHandler.removeCallbacks(elapsedTicker)
        stopPulseAnimation()
        _binding = null
        super.onDestroyView()
    }

    /** Re-selecting the home tab scrolls back to the hero. */
    fun scrollToTop() {
        _binding?.root?.smoothScrollTo(0, 0)
    }

    // ── Telemetry ──────────────────────────────────────────────────────────

    private fun observeMetrics() {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Telemetry polling lives in MainActivity.startTelemetryPolling
                // now, so the shared snapshot stays fresh even when this
                // Fragment was never created (process restored onto another
                // tab). This block only observes and renders.
                kotlinx.coroutines.flow.combine(viewModel.telemetry, viewModel.state) { data, state -> data to state }
                    .collect { (data, state) ->
                        if (_binding == null) return@collect
                        val snapshot = data.snapshot
                        val content = binding
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
                            // The cell is one third of the row wide, so it only
                            // carries the count — the direct/relay split moved
                            // to the hero subtitle, where there is room for it.
                            content.tvActivePeers.text = activeCount.toString()
                            content.tvTxSpeed.text = P2PStateRepository.formatSpeed(m.txSpeed)
                            content.tvRxSpeed.text = P2PStateRepository.formatSpeed(m.rxSpeed)
                            content.tvTraffic.text = "↑ ${P2PStateRepository.formatBytes(m.totalTx)}  ↓ ${P2PStateRepository.formatBytes(m.totalRx)}"
                            // The subtitle carries the peer split rather than the
                            // static "mesh is active" hint: once connected this is
                            // the number the user came to the screen to check.
                            content.tvStatusSubtitle.text = if (activeCount > 0) {
                                getString(R.string.status_reason_connected_fmt, direct, relay)
                            } else {
                                getString(R.string.status_hint_connected)
                            }
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
                            content.tvTxSpeed.text = unknown
                            content.tvRxSpeed.text = unknown
                            content.tvTraffic.text = "↑ $unknown  ↓ $unknown"
                            content.tvNatStatus.text = unknown
                            content.tvUptime.text = unknown
                            content.tvHeap.text = unknown
                            content.tvGoroutines.text = unknown
                            content.tvGcPause.text = unknown
                            content.protoContainer.removeAllViews()
                        }
                        refreshExitNodeDisplay()
                        updateAdvancedCount()
                    }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.status.collect { status ->
                    if (_binding == null || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@collect
                    val state = status.state
                    updateUiState(state, status.message)
                    if (state == P2PTapVpnService.STATE_RUNNING || state == P2PTapVpnService.STATE_IDLE) {
                        refreshPeerId()
                        refreshMultiaddrs()
                        // Pulls the overlay peer list once per state change. The
                        // Peers tab reads the same repository, so it does not
                        // need a trigger of its own.
                        viewModel.refreshPeers()
                    }
                }
            }
        }
    }

    // ── Listeners ──────────────────────────────────────────────────────────

    private fun setupListeners() {
        val content = binding

        content.btnConnect.setOnClickListener {
            when (P2PTapVpnService.currentState) {
                // STARTING is cancellable. It used to be disabled outright, which
                // left the user watching "Connecting…" with no exit but the
                // timeout — the state machine has always supported a stop here.
                P2PTapVpnService.STATE_STARTING -> stopVpnService()
                P2PTapVpnService.STATE_STOPPING -> Unit
                else -> {
                    if (P2PTapVpnService.isRunning()) {
                        stopVpnService()
                    } else {
                        content.btnConnect.isEnabled = false
                        prepareAndStartVpn()
                    }
                }
            }
        }

        // Settings and logs no longer live behind an app-bar menu: Settings is
        // the fourth bottom-nav destination and Logs is a row in the settings
        // page's diagnostics group. The web console stays on the home screen
        // because it needs a running engine, which is the home screen's state.
        content.btnOpenWebUI.setOnClickListener {
            if (!P2PTapVpnService.isRunning()) {
                toast(getString(R.string.prompt_vpn_not_running))
                return@setOnClickListener
            }
            val config = P2PTapVpnService.runningConfiguration() ?: AppConfigManager.load(requireContext())
            val tokenParam = if (config.webUiToken.isNotBlank()) {
                "?token=" + java.net.URLEncoder.encode(config.webUiToken.trim(), "UTF-8")
            } else {
                ""
            }
            val url = "http://127.0.0.1:${config.webUiPort}/$tokenParam"
            openUrl(url)
        }

        content.btnShareQr.setOnClickListener {
            QrDialog.newInstance().show(parentFragmentManager, QrDialog.TAG)
        }

        // The three metric cells are shortcuts into the tabs that expand on
        // them, not dialogs any more: peers and traffic each own a destination.
        content.cellUpload.setOnClickListener { host()?.navigateToTab(MainActivity.TAB_TRAFFIC) }

        content.cellDownload.setOnClickListener { host()?.navigateToTab(MainActivity.TAB_TRAFFIC) }

        content.cardPeers.setOnClickListener { host()?.navigateToTab(MainActivity.TAB_PEERS) }

        content.cardExitNode.setOnClickListener {
            ExitNodeSelectorDialog.newInstance {
                refreshExitNodeDisplay()
            }.show(parentFragmentManager, ExitNodeSelectorDialog.TAG)
        }

        content.btnQuickBackup.setOnClickListener {
            BackupDialog.newInstance {
                loadConfigDisplay()
            }.show(parentFragmentManager, BackupDialog.TAG)
        }

        content.layoutPeerId.setOnClickListener {
            val pid = content.tvPeerId.text?.toString() ?: ""
            if (pid.isNotEmpty() && pid != "-") {
                copyToClipboard(getString(R.string.label_peer_id), pid)
                toast(getString(R.string.msg_peer_id_copied))
            }
        }

        // Tap still copies (the common case). Long-press opens the actions that
        // do not fit a single tap: reveal the untruncated ID, share it as a QR
        // code, or jump to the peer list.
        content.layoutPeerId.setOnLongClickListener {
            showPeerIdMenu()
            true
        }

        content.layoutTapIp.setOnClickListener {
            val ip = content.tvTapIp.text?.toString() ?: ""
            if (ip.isNotEmpty()) {
                copyToClipboard(getString(R.string.label_tap_ip), ip)
                toast(getString(R.string.msg_copied_clipboard) + ": $ip")
            }
        }

        content.layoutTapIpv6.setOnClickListener {
            val v6 = content.tvTapIpv6.text?.toString() ?: ""
            if (v6.isNotEmpty()) {
                copyToClipboard(getString(R.string.label_tap_ipv6), v6)
                toast(getString(R.string.msg_copied_clipboard) + ": $v6")
            }
        }

        content.btnCopyMultiaddrs.setOnClickListener { copyMultiaddrsToClipboard() }
        content.tvMultiaddrs.setOnClickListener { copyMultiaddrsToClipboard() }

        content.btnOpenGithubRepo.setOnClickListener {
            openUrl("https://github.com/NNdroid/p2ptap")
        }

        content.btnOpenDeveloper.setOnClickListener {
            openUrl("https://github.com/NNdroid")
        }
    }

    // ── Advanced disclosure ────────────────────────────────────────────────

    private fun setupAdvancedSection() {
        binding.layoutAdvancedHeader.setOnClickListener { toggleAdvanced() }
    }

    /**
     * Expand or collapse the operator-only block.
     *
     * On expand the body is made VISIBLE before animating so the first frame
     * has a measured height; on collapse the fade runs first and GONE lands in
     * the end action, otherwise the card snaps shut with no transition.
     */
    private fun toggleAdvanced() {
        val content = binding
        advancedExpanded = !advancedExpanded
        val body = content.layoutAdvancedBody
        val chevron = content.ivAdvancedChevron
        if (advancedExpanded) {
            body.visibility = View.VISIBLE
            body.alpha = 0f
            body.animate().alpha(1f).setDuration(160L).start()
            chevron.animate().rotation(180f).setDuration(160L).start()
        } else {
            body.animate().alpha(0f).setDuration(140L).withEndAction {
                body.visibility = View.GONE
                body.alpha = 1f
            }.start()
            chevron.animate().rotation(0f).setDuration(160L).start()
        }
    }

    /**
     * Count the advanced entries that actually hold a value.
     *
     * The badge is cosmetic, but it should not claim "9" while the engine is
     * stopped and every cell reads "No data" — so it is derived from the same
     * strings the block renders rather than being a fixed number.
     */
    private fun updateAdvancedCount() {
        val content = _binding ?: return
        val unknown = getString(R.string.telemetry_unknown)
        fun hasValue(v: TextView) = v.text.toString().let { it.isNotBlank() && it != unknown }

        var n = 0
        if (hasValue(content.tvTraffic)) n++
        if (hasValue(content.tvNatStatus)) n++
        if (content.cardSystemHealth.visibility == View.VISIBLE) {
            if (hasValue(content.tvUptime)) n++
            if (hasValue(content.tvHeap)) n++
            if (hasValue(content.tvGoroutines)) n++
            if (hasValue(content.tvGcPause)) n++
        }
        n += content.protoContainer.childCount
        val addrs = content.tvMultiaddrs.text?.toString().orEmpty()
        if (addrs.isNotBlank() && addrs != getString(R.string.multiaddrs_empty_hint)) n++

        content.tvAdvancedCount.text = n.toString()
    }

    // ── Peer ID actions ────────────────────────────────────────────────────

    private fun showPeerIdMenu() {
        val popup = PopupMenu(requireContext(), binding.layoutPeerId)
        popup.menu.add(Menu.NONE, MENU_COPY_ID, 0, R.string.menu_copy_full_id)
        popup.menu.add(Menu.NONE, MENU_SHARE_QR, 1, R.string.menu_share_qr)
        popup.menu.add(Menu.NONE, MENU_VIEW_PEERS, 2, R.string.menu_view_peers)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_COPY_ID -> {
                    copyPeerId()
                    true
                }
                MENU_SHARE_QR -> {
                    QrDialog.newInstance().show(parentFragmentManager, QrDialog.TAG)
                    true
                }
                MENU_VIEW_PEERS -> {
                    host()?.navigateToTab(MainActivity.TAB_PEERS)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun copyPeerId() {
        // Prefer the persisted identity: tvPeerId reads "-" until the engine
        // reports one, but the key pair already exists in config by then.
        val pid = AppConfigManager.getPeerId(requireContext()).ifBlank {
            binding.tvPeerId.text?.toString().orEmpty()
        }
        if (pid.isBlank() || pid == "-") return
        copyToClipboard(getString(R.string.label_peer_id), pid)
        toast(getString(R.string.msg_peer_id_copied))
    }

    // ── Connect elapsed timer ──────────────────────────────────────────────

    // The engine reports STARTING as a single opaque state — there is no
    // per-stage callback between "starting" and "running". Rather than invent
    // a progress bar, the screen shows how long the wait has actually been,
    // which is both truthful and enough to tell "slow" from "stuck".
    private val elapsedHandler = Handler(Looper.getMainLooper())
    private var startingSince = 0L

    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (P2PTapVpnService.currentState != P2PTapVpnService.STATE_STARTING) return
            val b = _binding ?: return
            val secs = ((SystemClock.elapsedRealtime() - startingSince) / 1000L).toInt()
            b.tvStatusElapsed.text = getString(R.string.status_elapsed_fmt, secs)
            elapsedHandler.postDelayed(this, 1000L)
        }
    }

    private fun startElapsedTicker() {
        if (startingSince == 0L) startingSince = SystemClock.elapsedRealtime()
        binding.tvStatusElapsed.visibility = View.VISIBLE
        binding.tvStatusElapsed.text = getString(R.string.status_elapsed_fmt, 0)
        elapsedHandler.removeCallbacks(elapsedTicker)
        elapsedHandler.postDelayed(elapsedTicker, 1000L)
    }

    private fun stopElapsedTicker() {
        elapsedHandler.removeCallbacks(elapsedTicker)
        startingSince = 0L
        _binding?.tvStatusElapsed?.visibility = View.GONE
    }

    /**
     * Alpha-only pulse.
     *
     * The glass hero this replaced also scaled the ring 1.0 → 1.22. That worked
     * with an 84dp ring in an 84dp frame, but the M3 ring is a 56dp tonal disc
     * sitting in a 56dp frame, so any scale past ~1.05 gets clipped by the
     * FrameLayout and reads as a square edge. A brightness breath on a fixed
     * disc carries the same "still working" signal without the clip.
     */
    private fun startPulseAnimation() {
        if (!ValueAnimator.areAnimatorsEnabled() || statusPulseAnimator?.isRunning == true) return
        val ring = _binding?.viewStatusRing ?: return
        stopPulseAnimation()
        ring.alpha = PULSE_MIN_ALPHA

        statusPulseAnimator = ObjectAnimator.ofFloat(ring, View.ALPHA, PULSE_MIN_ALPHA, 1f).apply {
            duration = 2000L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulseAnimation() {
        statusPulseAnimator?.cancel()
        statusPulseAnimator = null
        _binding?.viewStatusRing?.alpha = 1f
    }

    private fun copyMultiaddrsToClipboard() {
        val addrs = binding.tvMultiaddrs.text?.toString() ?: ""
        if (addrs.isNotEmpty() && addrs != getString(R.string.multiaddrs_empty_hint) && addrs != getString(R.string.status_connecting)) {
            copyToClipboard(getString(R.string.label_multiaddrs), addrs)
            toast(getString(R.string.msg_multiaddrs_copied))
        }
    }

    private fun loadConfigDisplay() {
        val b = _binding ?: return
        val ctx = requireContext()
        val config = AppConfigManager.load(ctx)
        b.tvNodeName.text = config.nodeName
        b.tvTapIp.text = config.tapIp

        // Load and format app/engine version display. The context is captured
        // before the hop to Dispatchers.Default: requireContext() inside the
        // coroutine throws if the view is torn down while it is in flight.
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val appVer = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
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
                _binding?.tvAppVersion?.text = finalDisplayVer
            }
        }

        refreshPeerId()
        refreshMultiaddrs()
        refreshExitNodeDisplay()

        // Only spend a row when the config actually carries a v6 address. The
        // old code forced the row VISIBLE, so a v4-only node still showed a
        // "No data" line that could never fill in.
        if (config.tapIpv6.isNotBlank()) {
            b.tvTapIpv6.text = config.tapIpv6
            b.layoutTapIpv6.visibility = View.VISIBLE
        } else {
            b.layoutTapIpv6.visibility = View.GONE
        }
    }

    /**
     * Protocol mix, one row per protocol: name | bar | share.
     *
     * The row used to be a stack (name + share above a full-width bar) in seven
     * different pastel colours. Both changed: a side-by-side row keeps all
     * seven bars on one optical baseline so their lengths are actually
     * comparable, and the colour moved to a single accent because seven hues
     * encoded nothing — the labels already say which protocol is which, and
     * pastel greens/yellows on a light card sat at 2:1 contrast.
     */
    private fun renderProtocolStats(stats: ProtocolStatsData) {
        val container = _binding?.protoContainer ?: return
        container.removeAllViews()
        if (stats.total == 0L) return

        val entries = listOf(
            getString(R.string.proto_ipv4) to stats.ipv4,
            getString(R.string.proto_ipv6) to stats.ipv6,
            getString(R.string.proto_tcp) to stats.tcp,
            getString(R.string.proto_udp) to stats.udp,
            getString(R.string.proto_icmp) to stats.icmp,
            getString(R.string.proto_arp) to stats.arp,
            getString(R.string.proto_other) to stats.other,
        )

        val ctx = requireContext()
        val accent = ContextCompat.getColor(ctx, R.color.md_primary)
        val track = ContextCompat.getColor(ctx, R.color.m3_tag_neutral)
        val labelInk = ContextCompat.getColor(ctx, R.color.md_on_surface_variant)
        val valueInk = ContextCompat.getColor(ctx, R.color.md_on_surface)

        for ((name, value) in entries) {
            if (value <= 0L) continue
            val pct = ((value * 100L) / stats.total).toInt()

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dpToPx(3)
                    bottomMargin = dpToPx(3)
                }
            }

            // Fixed 56dp name column: the seven names have very different
            // widths ("ARP" vs "ICMP"), and a wrap_content column would start
            // every bar at a different x.
            row.addView(TextView(ctx).apply {
                text = name
                textSize = 11f
                setTextColor(labelInk)
                layoutParams = LinearLayout.LayoutParams(dpToPx(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            row.addView(com.google.android.material.progressindicator.LinearProgressIndicator(ctx).apply {
                max = 100
                progress = pct
                setIndicatorColor(accent)
                setTrackColor(track)
                trackThickness = dpToPx(6)
                trackCornerRadius = dpToPx(3)
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(6), 1f).apply {
                    marginStart = dpToPx(8)
                    marginEnd = dpToPx(8)
                }
            })

            row.addView(TextView(ctx).apply {
                text = "$pct%"
                textSize = 11f
                gravity = Gravity.END
                setTextColor(valueInk)
                fontFeatureSettings = "tnum"
                layoutParams = LinearLayout.LayoutParams(dpToPx(38), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            container.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun refreshExitNodeDisplay() {
        val b = _binding ?: return
        // Read through AppConfigManager every time: the exit node can be changed
        // and hot-reloaded without the VPN state changing, so caching it against
        // sessionRevision would keep showing the previous gateway. The config
        // itself is already memoised, so this is a cheap read.
        val config = AppConfigManager.load(requireContext())
        if (config.exitNode.isBlank()) {
            b.tvExitNodeStatus.text = getString(if (
                viewModel.telemetry.value.snapshot?.let { it.activeExitPeerId.isNotBlank() || it.activeExitIpv4.isNotBlank() || it.activeExitIpv6.isNotBlank() } == true
            ) R.string.telemetry_disabling else R.string.exit_node_auto_display)
        } else {
            val node = config.exitNode.trim()
            val shortNode = if (node.length > 20) node.take(8) + "..." + node.takeLast(6) else node
            val snap = viewModel.telemetry.value.snapshot
            b.tvExitNodeStatus.text = when {
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
        val b = _binding ?: return
        val ctx = requireContext()
        val cachedPid = AppConfigManager.getPeerId(ctx)
        if (cachedPid.isNotBlank()) {
            b.tvPeerId.text = cachedPid
        }

        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var pid = com.p2ptap.P2PTap.P2PTap.getPeerID()
            if (pid.isNullOrEmpty()) {
                pid = AppConfigManager.getPeerId(ctx)
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                _binding?.tvPeerId?.text = if (!pid.isNullOrEmpty()) pid else "-"
            }
        }
    }

    private fun refreshMultiaddrs() {
        val b = _binding ?: return
        if (!P2PTapVpnService.isRunning()) {
            b.tvMultiaddrs.text = getString(R.string.multiaddrs_empty_hint)
            return
        }

        // Render whatever the last session produced straight away, then let the
        // repository fetch only if it has nothing yet. getMultiaddrs() blocks on
        // the Go engine, so calling it on every onResume was the most visible
        // startup stall; the listen addresses only change with a new session.
        val cached = P2PStateRepository.multiaddrsOrNull()
        if (!cached.isNullOrEmpty()) {
            b.tvMultiaddrs.text = cached
            return
        }
        b.tvMultiaddrs.text = getString(R.string.status_connecting)

        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val addrs = P2PStateRepository.multiaddrs {
                com.p2ptap.P2PTap.P2PTap.getMultiaddrs()
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                _binding?.tvMultiaddrs?.text =
                    if (addrs.isNullOrEmpty()) getString(R.string.status_connecting) else addrs
            }
        }
    }

    // ── Engine control ─────────────────────────────────────────────────────

    private fun prepareAndStartVpn() {
        val vpnIntent = VpnService.prepare(requireContext())
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val ctx = requireContext()
        val intent = Intent(ctx, P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun stopVpnService() {
        val ctx = requireContext()
        val intent = Intent(ctx, P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_STOP
        }
        ctx.startService(intent)
    }

    private fun updateUiState(state: String, message: String) {
        val content = _binding ?: return
        content.btnOpenWebUI.isEnabled = state == P2PTapVpnService.STATE_RUNNING
        // The health grid reports what the running engine reported, so outside
        // RUNNING every one of its four cells shows "unknown". Keep the block
        // only when it has something real to show.
        content.cardSystemHealth.visibility =
            if (state == P2PTapVpnService.STATE_RUNNING) View.VISIBLE else View.GONE

        // The hero glow carries the state: green routing, amber working on it,
        // red failed, neutral idle. Same silhouette in all four, so the card
        // never jumps between states.
        when (state) {
            P2PTapVpnService.STATE_RUNNING -> {
                content.layoutHero.setBackgroundResource(R.drawable.bg_hero_connected)
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_connected)
                content.tvStatusTitle.text = getString(R.string.status_connected)
                content.tvStatusSubtitle.text = getString(R.string.status_hint_connected)
                // M3 destructive affordance: a neutral container with red ink,
                // not a red slab. A full-width red button on the main screen
                // reads as an alarm even though stopping is the normal action
                // once the tunnel is up.
                styleConnectButton(
                    background = R.color.m3_card_press,
                    foreground = R.color.status_error,
                    icon = R.drawable.ic_stop,
                    label = getString(R.string.btn_disconnect),
                    enabled = true,
                )
                stopElapsedTicker()
                startPulseAnimation()
            }
            P2PTapVpnService.STATE_STARTING -> {
                content.layoutHero.setBackgroundResource(R.drawable.bg_hero_connecting)
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_connecting)
                content.tvStatusTitle.text = getString(R.string.status_connecting)
                content.tvStatusSubtitle.text = getString(R.string.status_hint_starting)
                // Cancellable: the button stays live and means "give up", not
                // "wait". A start that hangs used to leave the user no exit
                // short of the timeout.
                styleConnectButton(
                    background = R.color.status_connecting,
                    foreground = R.color.m3_on_amber,
                    icon = R.drawable.ic_stop,
                    label = getString(R.string.btn_cancel),
                    enabled = true,
                )
                startElapsedTicker()
                startPulseAnimation()
            }
            P2PTapVpnService.STATE_STOPPING -> {
                content.layoutHero.setBackgroundResource(R.drawable.bg_hero_connecting)
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_connecting)
                content.tvStatusTitle.text = getString(R.string.status_stopping)
                content.tvStatusSubtitle.text = getString(R.string.status_hint_stopping)
                styleConnectButton(
                    background = R.color.status_connecting,
                    foreground = R.color.m3_on_amber,
                    icon = R.drawable.ic_refresh,
                    label = getString(R.string.status_stopping),
                    enabled = false,
                )
                stopElapsedTicker()
                stopPulseAnimation()
            }
            P2PTapVpnService.STATE_TIMEOUT -> {
                content.layoutHero.setBackgroundResource(R.drawable.bg_hero_error)
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_connecting)
                content.tvStatusTitle.text = getString(R.string.status_timeout)
                content.tvStatusSubtitle.text = if (message.isNotBlank()) message else getString(R.string.status_hint_timeout)
                // Retry is the primary action again — the accent fill says
                // "this is what to press", which is the opposite of the red
                // fill it used to carry (red means "dangerous", and pressing
                // retry is not).
                styleConnectButton(
                    background = R.color.md_primary,
                    foreground = R.color.m3_on_accent,
                    icon = R.drawable.ic_refresh,
                    label = getString(R.string.btn_retry),
                    enabled = true,
                )
                stopElapsedTicker()
                stopPulseAnimation()
            }
            P2PTapVpnService.STATE_ERROR -> {
                content.layoutHero.setBackgroundResource(R.drawable.bg_hero_error)
                // Dedicated red ring: a failed start used to share the grey
                // "disconnected" ring with a clean shutdown.
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_error)
                content.tvStatusTitle.text = getString(R.string.status_error)
                content.tvStatusSubtitle.text = if (message.isNotEmpty()) message else getString(R.string.status_error)
                styleConnectButton(
                    background = R.color.md_primary,
                    foreground = R.color.m3_on_accent,
                    icon = R.drawable.ic_refresh,
                    label = getString(R.string.btn_retry),
                    enabled = true,
                )
                stopElapsedTicker()
                stopPulseAnimation()
            }
            else -> { // STATE_IDLE
                content.layoutHero.setBackgroundResource(R.drawable.bg_hero_idle)
                content.viewStatusRing.setBackgroundResource(R.drawable.bg_status_disconnected)
                content.tvStatusTitle.text = getString(R.string.status_disconnected)
                content.tvStatusSubtitle.text = getString(R.string.status_hint_idle)
                styleConnectButton(
                    background = R.color.md_primary,
                    foreground = R.color.m3_on_accent,
                    icon = R.drawable.ic_play,
                    label = getString(R.string.btn_connect),
                    enabled = true,
                )
                stopElapsedTicker()
                stopPulseAnimation()
            }
        }
    }

    /**
     * The CTA carries state through three attributes at once — fill, ink and
     * icon. Setting only the fill (what the old code did) left white text and a
     * white icon on an amber or neutral background, which is exactly the case
     * where the contrast collapses.
     */
    private fun styleConnectButton(
        background: Int,
        foreground: Int,
        icon: Int,
        label: String,
        enabled: Boolean,
    ) {
        val button = binding.btnConnect
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), background))
        val ink = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), foreground))
        button.setTextColor(ink)
        button.iconTint = ink
        button.setIconResource(icon)
        button.text = label
        button.isEnabled = enabled
    }

    // ── Small shared helpers ───────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun host(): Host? = activity as? Host

    private fun toast(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.err_open_browser, UiMessages.describe(requireContext(), e)))
        }
    }

    private companion object {
        // PopupMenu item ids. Group NONE + id, so they only need to be unique
        // inside this menu.
        const val MENU_COPY_ID = 1001
        const val MENU_SHARE_QR = 1002
        const val MENU_VIEW_PEERS = 1003

        /** Bottom of the status-ring breathing range; 1.0 is the top. */
        const val PULSE_MIN_ALPHA = 0.55f
    }
}
