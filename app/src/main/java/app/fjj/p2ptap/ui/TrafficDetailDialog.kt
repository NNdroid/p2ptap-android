package app.fjj.p2ptap.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.i18n.UiMessages
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.DialogTrafficDetailBinding
import app.fjj.p2ptap.databinding.ItemTrafficRowBinding
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.StatsSnapshot
import app.fjj.p2ptap.viewmodel.MainViewModel

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TrafficDetailDialog : LiveDataSheet() {
    companion object {
        const val TAG = "TrafficDetailDialog"
        fun newInstance() = TrafficDetailDialog()
    }
    private var _binding: DialogTrafficDetailBinding? = null
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return DialogTrafficDetailBinding.inflate(inflater, container, false).also { _binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding?.btnRefresh?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = viewModel.refreshStats()
                if (_binding != null) Toast.makeText(requireContext(), getString(
                    if (ok) R.string.msg_traffic_refreshed else R.string.telemetry_refresh_failed), Toast.LENGTH_SHORT).show()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.telemetry, viewModel.state) { data, state -> data to state }
                    .collect { (data, state) ->
                        val b = _binding ?: return@collect
                        b.btnRefresh.isEnabled = state == "RUNNING" && !data.refreshing
                        b.tvDataStatus.text = getString(when {
                            state != "RUNNING" -> R.string.traffic_offline_hint
                            data.failed -> R.string.telemetry_refresh_failed
                            data.snapshot == null -> R.string.telemetry_loading
                            else -> R.string.telemetry_live
                        })
                        render(if (state == "RUNNING") data.snapshot else null)
                    }
            }
        }
    }

    private fun render(snapshot: StatsSnapshot?) {
        val b = _binding ?: return
        val unknown = getString(R.string.telemetry_unknown)
        fun count(key: String) = snapshot?.counters?.get(key)
        fun packets(key: String) = count(key)?.let {
            resources.getQuantityString(R.plurals.stat_packets_unit, it.toInt(), it)
        } ?: unknown
        fun row(binding: ItemTrafficRowBinding, label: Int, value: String) {
            binding.tvLabel.text = getString(label)
            binding.tvValue.text = value
        }
        row(b.rowTxSpeed, R.string.stat_live_tx_speed, count("tx_bytes_per_sec")?.let(P2PStateRepository::formatSpeed) ?: unknown)
        row(b.rowRxSpeed, R.string.stat_live_rx_speed, count("rx_bytes_per_sec")?.let(P2PStateRepository::formatSpeed) ?: unknown)
        row(b.rowTotalTx, R.string.stat_total_tx_bytes, count("bytes_sent")?.let(P2PStateRepository::formatBytes) ?: unknown)
        row(b.rowTotalRx, R.string.stat_total_rx_bytes, count("bytes_recv")?.let(P2PStateRepository::formatBytes) ?: unknown)
        row(b.rowTxPkts, R.string.stat_total_tx_pkts, packets("packets_sent"))
        row(b.rowRxPkts, R.string.stat_total_rx_pkts, packets("packets_recv"))
        row(b.rowDropped, R.string.stat_dropped_pkts, packets("dispatch_drops"))
        row(b.rowDedup, R.string.telemetry_dedup, packets("dedup_count"))
        row(b.rowIpv4, R.string.stat_ipv4, packets("ipv4"))
        row(b.rowIpv6, R.string.stat_ipv6, packets("ipv6"))
        row(b.rowArp, R.string.stat_arp, packets("arp"))
        row(b.rowNdp, R.string.telemetry_ndp, packets("ndp"))
        row(b.rowOther, R.string.telemetry_other, packets("other"))
        row(b.rowTcp, R.string.stat_tcp, packets("tcp"))
        row(b.rowUdp, R.string.stat_udp, packets("udp"))
        row(b.rowIcmp, R.string.stat_icmp, packets("icmp"))
        row(b.rowObf, R.string.stat_obf, snapshot?.let { UiMessages.obfuscation(requireContext(), it.obfuscation) } ?: unknown)
        row(b.rowPsk, R.string.telemetry_psk, snapshot?.let { UiMessages.psk(requireContext(), it.pskStatus) } ?: unknown)
        val connected = snapshot?.peers.orEmpty().filter { it.connState == "ok" || it.connState == "relay_ok" }.map { it.peerId }.toSet()
        val encryption = snapshot?.encryption.orEmpty().filter { it.peerId in connected }
        row(b.rowAlgo, R.string.stat_algo, encryption.takeIf { it.isNotEmpty() }?.joinToString("\n") {
            "${it.peerId.takeLast(8)}: " + if (!it.negotiated) getString(R.string.telemetry_pending)
            else if (!it.encrypted) getString(R.string.stat_obf_disabled) else it.algorithm.ifBlank { unknown }
        } ?: unknown)
        row(b.rowPfs, R.string.stat_pfs, encryption.takeIf { it.isNotEmpty() }?.joinToString("\n") {
            "${it.peerId.takeLast(8)}: " + when {
                !it.negotiated || it.pfs == null -> unknown
                it.pfs && it.encrypted -> getString(R.string.telemetry_enabled)
                else -> getString(R.string.stat_obf_disabled)
            }
        } ?: unknown)
        row(b.rowReplay, R.string.telemetry_replay_drops, packets("replay_drops"))
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
