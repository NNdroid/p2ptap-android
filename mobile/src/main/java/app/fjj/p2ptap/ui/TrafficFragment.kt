package app.fjj.p2ptap.ui

import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.Toast
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.FragmentTrafficBinding
import app.fjj.p2ptap.databinding.ItemTrafficBarRowBinding
import app.fjj.p2ptap.databinding.ItemTrafficRowBinding
import app.fjj.p2ptap.i18n.UiMessages
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.StatsSnapshot
import app.fjj.p2ptap.viewmodel.MainViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Traffic tab: 21 counters, three groups.
 *
 * Straight port of the former TrafficDetailDialog render() with the dialog's
 * lifecycle swapped for the Fragment view lifecycle. Every counter keeps its
 * source key in
 * `snapshot.counters` — the mapping is the contract with the engine's metrics
 * snapshot, so it is copied verbatim rather than "tidied".
 *
 * Two things are deliberately *not* defaulted to zero:
 *   · an absent counter renders "No data", never 0 B — a missing key means the
 *     engine did not report it, which is not the same as a measured zero;
 *   · the per-peer encryption list only covers peers that are actually
 *     connected, so a stale negotiation entry cannot outlive its peer.
 */
class TrafficFragment : Fragment() {

    private var _binding: FragmentTrafficBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    /** Last snapshot, so becoming visible does not wait for the next tick. */
    private var latest: StatsSnapshot? = null

    /**
     * Whether this tab is the one on screen. 21 counter rows rebound every
     * 1.5s for a hidden view is pure waste; the snapshot is still stored while
     * hidden, so the tab is current the moment it is shown.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTrafficBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRefresh.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = viewModel.refreshStats()
                Toast.makeText(
                    requireContext(),
                    getString(if (ok) R.string.msg_traffic_refreshed else R.string.telemetry_refresh_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.telemetry, viewModel.state) { data, state -> data to state }
                    .collect { (data, state) ->
                        binding.btnRefresh.isEnabled =
                            state == P2PTapVpnService.STATE_RUNNING && !data.refreshing
                        binding.tvDataStatus.text = getString(
                            when {
                                state != P2PTapVpnService.STATE_RUNNING -> R.string.traffic_offline_hint
                                data.failed -> R.string.telemetry_refresh_failed
                                data.snapshot == null -> R.string.telemetry_loading
                                else -> R.string.telemetry_live
                            },
                        )
                        latest = if (state == P2PTapVpnService.STATE_RUNNING) data.snapshot else null
                        if (!isHidden) render(latest)
                    }
            }
        }
    }

    private fun render(snapshot: StatsSnapshot?) {
        val unknown = getString(R.string.telemetry_unknown)

        fun count(key: String) = snapshot?.counters?.get(key)

        fun packets(key: String) = count(key)?.let {
            resources.getQuantityString(R.plurals.stat_packets_unit, it.toInt(), it)
        } ?: unknown

        fun row(rowBinding: ItemTrafficRowBinding, label: Int, value: String) {
            rowBinding.tvLabel.text = getString(label)
            rowBinding.tvValue.text = value
        }

        // Protocol rows carry a share bar. The denominator is the sum of the
        // eight classified counters, not packets_recv: they are different
        // counters and mixing them would make the bars sum to something other
        // than 100% whenever the engine drops or reclassifies a frame.
        val protocolKeys = listOf("ipv4", "ipv6", "arp", "ndp", "other", "tcp", "udp", "icmp")
        val protocolTotal = protocolKeys.sumOf { count(it) ?: 0L }

        fun barRow(
            barBinding: ItemTrafficBarRowBinding,
            label: Int,
            key: String,
            tint: Int,
        ) {
            val value = count(key)
            barBinding.tvLabel.text = getString(label)
            barBinding.tvValue.text = value?.let {
                resources.getQuantityString(R.plurals.stat_packets_unit, it.toInt(), it)
            } ?: unknown
            barBinding.barFill.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), tint))
            // share is 0 when the total is 0, which leaves an empty track
            // rather than a full one for a protocol that saw nothing.
            val share = if (protocolTotal > 0 && value != null) {
                (value.toDouble() / protocolTotal).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            // Weights, not widths: both children are 0dp, so whatever the track
            // ends up measuring the split is already right on the first frame.
            (barBinding.barFill.layoutParams as LinearLayout.LayoutParams).apply {
                weight = share.toFloat()
                barBinding.barFill.layoutParams = this
            }
            (barBinding.barRest.layoutParams as LinearLayout.LayoutParams).apply {
                weight = (1.0 - share).toFloat()
                barBinding.barRest.layoutParams = this
            }
        }

        row(binding.rowTxSpeed, R.string.stat_live_tx_speed, count("tx_bytes_per_sec")?.let(P2PStateRepository::formatSpeed) ?: unknown)
        row(binding.rowRxSpeed, R.string.stat_live_rx_speed, count("rx_bytes_per_sec")?.let(P2PStateRepository::formatSpeed) ?: unknown)
        row(binding.rowTotalTx, R.string.stat_total_tx_bytes, count("bytes_sent")?.let(P2PStateRepository::formatBytes) ?: unknown)
        row(binding.rowTotalRx, R.string.stat_total_rx_bytes, count("bytes_recv")?.let(P2PStateRepository::formatBytes) ?: unknown)
        row(binding.rowTxPkts, R.string.stat_total_tx_pkts, packets("packets_sent"))
        row(binding.rowRxPkts, R.string.stat_total_rx_pkts, packets("packets_recv"))
        row(binding.rowDropped, R.string.stat_dropped_pkts, packets("dispatch_drops"))
        row(binding.rowDedup, R.string.telemetry_dedup, packets("dedup_count"))
        // Network layer vs transport layer vs link/other: three hues, because
        // the section is read as three groups and eight hues would encode
        // nothing. The bars compare within the row set, so a shared hue per
        // group is what keeps the comparison honest.
        barRow(binding.rowIpv4, R.string.stat_ipv4, "ipv4", R.color.md_primary)
        barRow(binding.rowIpv6, R.string.stat_ipv6, "ipv6", R.color.md_primary)
        barRow(binding.rowArp, R.string.stat_arp, "arp", R.color.md_on_surface_variant)
        barRow(binding.rowNdp, R.string.telemetry_ndp, "ndp", R.color.md_on_surface_variant)
        barRow(binding.rowOther, R.string.telemetry_other, "other", R.color.md_on_surface_variant)
        barRow(binding.rowTcp, R.string.stat_tcp, "tcp", R.color.status_connected)
        barRow(binding.rowUdp, R.string.stat_udp, "udp", R.color.status_connected)
        barRow(binding.rowIcmp, R.string.stat_icmp, "icmp", R.color.status_connected)
        row(binding.rowObf, R.string.stat_obf, snapshot?.let { UiMessages.obfuscation(requireContext(), it.obfuscation) } ?: unknown)
        row(binding.rowPsk, R.string.telemetry_psk, snapshot?.let { UiMessages.psk(requireContext(), it.pskStatus) } ?: unknown)

        val connected = snapshot?.peers.orEmpty()
            .filter { it.connState == "ok" || it.connState == "relay_ok" }
            .map { it.peerId }
            .toSet()
        val encryption = snapshot?.encryption.orEmpty().filter { it.peerId in connected }

        row(
            binding.rowAlgo, R.string.stat_algo,
            encryption.takeIf { it.isNotEmpty() }?.joinToString("\n") {
                "${it.peerId.takeLast(8)}: " + if (!it.negotiated) {
                    getString(R.string.telemetry_pending)
                } else if (!it.encrypted) {
                    getString(R.string.stat_obf_disabled)
                } else {
                    it.algorithm.ifBlank { unknown }
                }
            } ?: unknown,
        )
        row(
            binding.rowPfs, R.string.stat_pfs,
            encryption.takeIf { it.isNotEmpty() }?.joinToString("\n") {
                "${it.peerId.takeLast(8)}: " + when {
                    !it.negotiated || it.pfs == null -> unknown
                    it.pfs == true && it.encrypted -> getString(R.string.telemetry_enabled)
                    else -> getString(R.string.stat_obf_disabled)
                }
            } ?: unknown,
        )
        row(binding.rowReplay, R.string.telemetry_replay_drops, packets("replay_drops"))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * 21 counter rows rebound every 1.5s for a hidden view is pure waste; the
     * snapshot is still stored while hidden, so the tab is current the moment
     * it is shown.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) render(latest)
    }
}
