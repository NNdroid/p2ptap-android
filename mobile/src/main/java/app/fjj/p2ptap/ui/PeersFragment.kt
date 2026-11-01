package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.FragmentPeersBinding
import app.fjj.p2ptap.databinding.ItemPeerDetailBinding
import app.fjj.p2ptap.i18n.UiMessages
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.PeerItemData
import app.fjj.p2ptap.viewmodel.MainViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Peers tab: the live list of overlay peers, as a Fragment.
 *
 * This is the former PeersDetailDialog render logic, moved rather than
 * rewritten. The sheet was promoted to a destination of its own (see
 * fragment_peers.xml) and the per-peer field mapping carried over untouched,
 * field for field: each of
 * the eighteen facts on a card comes from a specific [PeerItemData] member,
 * and several of them have a fallback that is not "0" —
 *
 *   · RTT      reads "unmeasured" unless rttMeasured, never a fabricated value
 *   · jitter   hidden unless jitterMeasured AND > 0
 *   · loss     hidden unless lossMeasured AND > 0
 *   · IPv6     only when the peer actually has one
 *
 * Added over the sheet: the search field. With more than a screenful of peers
 * the list stopped being scannable, so [applyFilter] matches name, Peer ID,
 * and both virtual IPs, case-insensitively.
 */
class PeersFragment : Fragment() {

    private var _binding: FragmentPeersBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    /** Last snapshot, kept so typing can re-render without waiting for telemetry. */
    private var peers: List<PeerItemData> = emptyList()

    /** What the engine last said; drives the summary line and the empty state. */
    private var engineRunning = false

    private var query: String = ""

    /**
     * Whether this tab is the one on screen.
     *
     * Telemetry keeps ticking when another tab is showing, and rebinding a
     * screenful of peer cards for a hidden view is the most expensive thing
     * this screen does. While hidden the snapshot is still stored, so becoming
     * visible again is one [applyFilter] away — no waiting for the next tick.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPeersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRefresh.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = viewModel.refreshStats()
                Toast.makeText(
                    requireContext(),
                    getString(if (ok) R.string.msg_peers_refreshed else R.string.telemetry_refresh_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        binding.etPeerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val next = s?.toString().orEmpty().trim()
                if (next == query) return
                query = next
                applyFilter()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.telemetry, viewModel.state) { data, state -> data to state }
                    .collect { (data, state) ->
                        binding.btnRefresh.isEnabled = state == P2PTapVpnService.STATE_RUNNING && !data.refreshing
                        engineRunning = P2PTapVpnService.isRunning()
                        peers = data.snapshot?.peers.orEmpty()
                        if (engineRunning && data.snapshot == null) {
                            binding.tvSummary.text = getString(
                                if (data.failed) R.string.telemetry_refresh_failed else R.string.telemetry_loading,
                            )
                        }
                        if (!isHidden) applyFilter()
                    }
            }
        }
    }

    /** Re-renders from [peers] under the current [query]. */
    private fun applyFilter() {
        if (!engineRunning) {
            binding.tvSummary.text = getString(R.string.peers_offline_hint)
            binding.peersContainer.removeAllViews()
            return
        }
        if (peers.isEmpty()) {
            binding.peersContainer.removeAllViews()
            binding.tvSummary.text = getString(R.string.peers_waiting_hint)
            return
        }

        val visible = if (query.isBlank()) peers else peers.filter { matches(it, query) }
        render(visible)
    }

    private fun matches(item: PeerItemData, q: String): Boolean {
        val needle = q.lowercase(Locale.getDefault())
        return item.nodeName.lowercase(Locale.getDefault()).contains(needle) ||
            item.peerId.lowercase(Locale.getDefault()).contains(needle) ||
            item.tapIp.contains(needle) ||
            item.tapIpv6.contains(needle)
    }

    private fun render(list: List<PeerItemData>) {
        val ctx = requireContext()

        var directCount = 0
        var relayCount = 0
        for (p in list) {
            when (p.connState) {
                "ok" -> if (p.isDirect) directCount++ else if (p.isRelayed) relayCount++
                "relay_ok" -> relayCount++
            }
        }
        val totalPeers = directCount + relayCount

        // The container is reused across ticks: inflating a card every 1.5s for
        // every peer was the sheet's biggest source of garbage. Views are only
        // added or removed when the count changes, then rebound in place.
        val currentChildCount = binding.peersContainer.childCount
        when {
            currentChildCount > list.size ->
                binding.peersContainer.removeViews(list.size, currentChildCount - list.size)

            currentChildCount < list.size -> {
                for (i in currentChildCount until list.size) {
                    ItemPeerDetailBinding.inflate(LayoutInflater.from(ctx), binding.peersContainer, true)
                }
            }
        }

        for (i in list.indices) {
            bindItem(ItemPeerDetailBinding.bind(binding.peersContainer.getChildAt(i)), list[i], ctx)
        }

        // The summary describes what is on screen, not the whole mesh: when a
        // filter is active, "Known" counts the visible cards. A second line
        // for the hidden remainder would be more precise and less readable.
        binding.tvSummary.text =
            getString(R.string.telemetry_peer_summary, totalPeers, directCount, relayCount, list.size)
    }

    private fun bindItem(itemBinding: ItemPeerDetailBinding, item: PeerItemData, ctx: Context) {
        itemBinding.tvNodeName.text = item.nodeName

        // Connection state badge. Soft container + its matching on-colour, one
        // pair per state: the silhouette never changes, so a card does not
        // relayout when a peer's state changes, but the hue does not get to
        // drift either — coloured text on the neutral fill only reached 2.3:1.
        itemBinding.tvBadge.apply {
            when (item.connState) {
                "ok" -> {
                    if (item.isDirect) {
                        text = getString(R.string.badge_direct)
                        paintTag(this, ctx, R.drawable.bg_tag_green, R.color.md_on_tertiary_container, R.drawable.ic_bolt_12)
                    } else if (item.isRelayed) {
                        text = getString(R.string.badge_relay)
                        paintTag(this, ctx, R.drawable.bg_tag_blue, R.color.md_on_primary_container, R.drawable.ic_relay_12)
                    } else {
                        text = getString(R.string.badge_error)
                        paintTag(this, ctx, R.drawable.bg_tag_red, R.color.md_on_error_container)
                    }
                }

                "relay_ok" -> {
                    text = getString(R.string.badge_relay)
                    paintTag(this, ctx, R.drawable.bg_tag_blue, R.color.md_on_primary_container, R.drawable.ic_relay_12)
                }

                "connecting" -> {
                    text = getString(R.string.badge_connecting)
                    paintTag(this, ctx, R.drawable.bg_tag_amber, R.color.m3_on_amber_container)
                }

                "obf_failed", "proto_mismatch" -> {
                    text = getString(
                        if (item.connState == "obf_failed") R.string.telemetry_crypto_failed
                        else R.string.telemetry_proto_mismatch,
                    )
                    paintTag(this, ctx, R.drawable.bg_tag_red, R.color.md_on_error_container)
                }

                else -> {
                    text = getString(
                        if (item.connState == "unreachable") R.string.telemetry_unreachable
                        else R.string.telemetry_unknown,
                    )
                    paintTag(this, ctx, R.drawable.bg_tag_red, R.color.md_on_error_container)
                }
            }
        }

        // Encryption badge. Neutral by default: an encrypted peer is the normal
        // case, and a card full of green tags hides the one that matters. The
        // exception — connected but NOT encrypted — is the one that turns red.
        itemBinding.tvEncBadge.apply {
            if (item.obfEncrypted && item.obfAlgo.isNotBlank()) {
                text = UiMessages.encryption(ctx, item.obfAlgo, true)
                paintTag(this, ctx, R.drawable.bg_tag_neutral, R.color.md_on_surface_variant, R.drawable.ic_lock_12)
                visibility = View.VISIBLE
            } else if (!item.obfEncrypted && item.connState == "ok") {
                text = UiMessages.encryption(ctx, "", false)
                paintTag(this, ctx, R.drawable.bg_tag_red, R.color.md_on_error_container)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // Role badge
        itemBinding.tvRoleBadge.apply {
            if (item.role.isNotBlank() && item.role != "Peer") {
                text = UiMessages.role(ctx, item.role)
                paintTag(this, ctx, R.drawable.bg_tag_neutral, R.color.md_on_surface_variant)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // Geo location
        itemBinding.tvGeoLocation.apply {
            if (item.geoLocation.isNotBlank()) {
                text = item.geoLocation
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // RTT. The source is folded into the content description rather than
        // shown: three sources (TAP-ICMP, libp2p-ping, p2p-echo) in a 12sp row
        // would be noise, but a screen reader should still say where the number
        // came from.
        itemBinding.tvRtt.text = if (item.rttMeasured) {
            getString(R.string.label_rtt_ms, item.rtt)
        } else {
            getString(R.string.telemetry_unmeasured)
        }
        itemBinding.tvRtt.contentDescription =
            "${itemBinding.tvRtt.text} ${UiMessages.rttSource(ctx, item.rttSource)}"

        // Jitter
        itemBinding.tvJitter.apply {
            if (item.jitterMeasured && item.jitterMs > 0) {
                text = getString(R.string.label_jitter_ms, item.jitterMs)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // Loss
        itemBinding.tvLoss.apply {
            if (item.lossMeasured && item.lossRatePercent > 0) {
                text = getString(R.string.label_loss_pct, item.lossRatePercent)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // Connection duration + return path
        val durationText = buildString {
            if (item.connectedSince.isNotBlank()) {
                append(getString(R.string.label_connected_since) + ": " + item.connectedSince)
            }
            if (item.returnPath.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append(UiMessages.returnPath(ctx, item.returnPath))
            }
        }
        itemBinding.layoutDuration.apply {
            if (durationText.isNotBlank()) {
                itemBinding.tvConnectedSince.text = item.connectedSince.ifBlank { "" }
                itemBinding.tvReturnPath.text =
                    if (item.returnPath.isNotBlank()) UiMessages.returnPath(ctx, item.returnPath) else ""
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // Return-path medium. Observed from the peer's inbound frames, so it may
        // legitimately differ from the forward badge above — that difference is
        // exactly the asymmetric routing worth surfacing.
        itemBinding.tvReturnTransport.apply {
            val label = UiMessages.transportLabel(ctx, item.returnTransport)
            if (label.isNotBlank()) {
                val hop = item.returnRelayHop.ifBlank { "" }
                text = hop.takeIf { it.isNotBlank() }?.let { "$label · via $it" } ?: label
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // TAP IPs — tap to copy
        val ipText = buildString {
            if (item.tapIp.isNotBlank()) append(getString(R.string.label_ipv4_colon, item.tapIp) + "  ")
            if (item.tapIpv6.isNotBlank()) append(getString(R.string.label_ipv6_colon, item.tapIpv6))
        }
        itemBinding.tvIpAddresses.text = ipText.ifBlank { getString(R.string.telemetry_unknown) }
        itemBinding.tvIpAddresses.visibility = View.VISIBLE
        itemBinding.tvIpAddresses.setOnClickListener {
            val ip = listOf(item.tapIp, item.tapIpv6).filter { it.isNotBlank() }.joinToString("\n")
            if (ip.isBlank()) return@setOnClickListener
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_tap_ip), ip))
            Toast.makeText(ctx, getString(R.string.msg_copied_clipboard) + ": $ip", Toast.LENGTH_SHORT).show()
        }

        // Peer ID — tap to copy
        itemBinding.tvPeerId.text = getString(R.string.peer_id_fmt, item.peerId)
        itemBinding.layoutPeerId.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_peer_id), item.peerId))
            Toast.makeText(
                ctx,
                getString(R.string.msg_peer_id_copied_fmt, item.peerId),
                Toast.LENGTH_SHORT,
            ).show()
        }

        // Endpoint
        val endpointText = buildString {
            if (item.multiaddr.isNotBlank()) {
                append(getString(R.string.label_endpoint_prefix))
                append(item.multiaddr)
            } else if (item.transport.isNotBlank()) {
                append(getString(R.string.label_endpoint_prefix))
                append("(${item.transport})")
            }
            if (item.transportPriority.isNotBlank()) {
                append(" [${UiMessages.priority(ctx, item.transportScore)}]")
            }
        }
        itemBinding.tvEndpoint.text = endpointText
        itemBinding.tvEndpoint.visibility = if (endpointText.isBlank()) View.GONE else View.VISIBLE

        // Traffic + diagnostics
        val unknown = getString(R.string.telemetry_unknown)
        val tx = if (item.linkTrafficAvailable) P2PStateRepository.formatBytes(item.txBytes) else unknown
        val rx = if (item.linkTrafficAvailable) P2PStateRepository.formatBytes(item.rxBytes) else unknown
        itemBinding.tvTraffic.text = buildString {
            append(getString(R.string.telemetry_link_traffic, tx, rx))
            append("\n↑ ${item.txSpeed?.let(P2PStateRepository::formatSpeed) ?: unknown}  " +
                "↓ ${item.rxSpeed?.let(P2PStateRepository::formatSpeed) ?: unknown}")
            append("\n${item.os.ifBlank { unknown }} · ${item.version.ifBlank { unknown }}")
            append("\n${UiMessages.rttSource(ctx, item.rttSource)}")
            if (item.isExitNode) append("\n${getString(R.string.telemetry_exit_gateway)}")
            if (item.exitNat) append(" · ${getString(R.string.label_nat_status)}")
            if (item.relayOnly) append("\n· ${getString(R.string.telemetry_relay_only)}")
            if (item.replayDrops > 0) append("\n${getString(R.string.label_replay_drops)}: ${item.replayDrops}")
            if (item.dedupDrops > 0) append("\n${getString(R.string.label_dedup_drops)}: ${item.dedupDrops}")
            if (item.obfDecryptErrs > 0) append("\n${getString(R.string.label_decrypt_errors)}: ${item.obfDecryptErrs}")
            if (item.obfLastRekeyAgo.isNotBlank()) append("\n${getString(R.string.label_rekey_ago)}: ${item.obfLastRekeyAgo}")
            if (item.connDetail.isNotBlank()) append("\n${UiMessages.connectionDetail(ctx, item.connDetail)}")
        }

        // TAP MAC mismatch warning
        itemBinding.tvTapMacWarning.apply {
            if (item.tapMacMismatch) {
                text = getString(R.string.telemetry_tap_mac_mismatch)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    /**
     * Applies a tag's fill and its matching on-colour together.
     *
     * The pair is always passed as an argument rather than looked up from the
     * text colour, because the two are chosen for contrast against each other —
     * picking one without the other is how the badge ended up with 2.3:1 text.
     */
    private fun paintTag(view: TextView, ctx: Context, background: Int, foreground: Int, icon: Int = 0) {
        view.setBackgroundResource(background)
        val ink = ContextCompat.getColor(ctx, foreground)
        view.setTextColor(ink)
        // The glyph carries the same colour as the text, always: a tag whose
        // icon and label disagree reads as two different states.
        if (icon != 0) {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            view.compoundDrawablePadding = (4 * ctx.resources.displayMetrics.density).toInt()
            view.compoundDrawableTintList = ColorStateList.valueOf(ink)
        } else {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * A hidden tab keeps receiving telemetry, but rebinding a screenful of
     * cards nobody can see is the most expensive thing this screen does. The
     * snapshot is still stored, so becoming visible is one [applyFilter] away.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) applyFilter()
    }
}
