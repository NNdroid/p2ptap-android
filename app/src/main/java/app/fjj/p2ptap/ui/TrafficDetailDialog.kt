package app.fjj.p2ptap.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.DialogTrafficDetailBinding
import app.fjj.p2ptap.databinding.ItemTrafficRowBinding
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TrafficDetailDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "TrafficDetailDialog"
        fun newInstance(): TrafficDetailDialog = TrafficDetailDialog()
    }

    private var _binding: DialogTrafficDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTrafficDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRefresh.setOnClickListener {
            lifecycleScope.launch {
                refreshExtendedStats()
                if (_binding != null && context != null) {
                    Toast.makeText(requireContext(), getString(R.string.msg_traffic_refreshed), Toast.LENGTH_SHORT).show()
                }
            }
        }

        observeMetrics()
        startPeriodicStatsRefresh()
    }

    private fun observeMetrics() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                P2PStateRepository.metrics.collect { metrics ->
                    val b = _binding ?: return@collect
                    if (!P2PTapVpnService.isRunning()) {
                        b.contentContainer.visibility = View.GONE
                        return@collect
                    }
                    b.contentContainer.visibility = View.VISIBLE

                    setupRow(b.rowTxSpeed, getString(R.string.stat_live_tx_speed), P2PStateRepository.formatSpeed(metrics.txSpeed))
                    setupRow(b.rowRxSpeed, getString(R.string.stat_live_rx_speed), P2PStateRepository.formatSpeed(metrics.rxSpeed))
                    setupRow(b.rowTotalTx, getString(R.string.stat_total_tx_bytes), P2PStateRepository.formatBytes(metrics.totalTx))
                    setupRow(b.rowTotalRx, getString(R.string.stat_total_rx_bytes), P2PStateRepository.formatBytes(metrics.totalRx))
                }
            }
        }
    }

    private fun startPeriodicStatsRefresh() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshExtendedStats()
                    delay(1500)
                }
            }
        }
    }

    private suspend fun refreshExtendedStats() {
        if (!P2PTapVpnService.isRunning()) {
            withContext(Dispatchers.Main) {
                _binding?.contentContainer?.visibility = View.GONE
            }
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val statsJsonStr = P2PTap.getStatsJSON()
                if (statsJsonStr.isNullOrBlank()) return@withContext

                val statsJson = JSONObject(statsJsonStr)

                val pktObj = statsJson.optJSONObject("packet_stats")
                val pktsSent = pktObj?.optLong("packets_sent", 0L) ?: 0L
                val pktsRecv = pktObj?.optLong("packets_recv", 0L) ?: 0L
                val pktsDropped = pktObj?.optLong("dispatch_drops", pktObj.optLong("packets_dropped", 0L)) ?: 0L
                val dedupCount = pktObj?.optLong("dedup_count", 0L) ?: 0L

                val protoObj = statsJson.optJSONObject("protocol_stats")
                val ipv4Pkts = protoObj?.optLong("ipv4", protoObj.optLong("ipv4_packets", 0L)) ?: 0L
                val ipv6Pkts = protoObj?.optLong("ipv6", protoObj.optLong("ipv6_packets", 0L)) ?: 0L
                val arpPkts = protoObj?.optLong("arp", protoObj.optLong("arp_packets", 0L)) ?: 0L
                val tcpPkts = protoObj?.optLong("tcp", protoObj.optLong("tcp_packets", 0L)) ?: 0L
                val udpPkts = protoObj?.optLong("udp", protoObj.optLong("udp_packets", 0L)) ?: 0L
                val icmpPkts = protoObj?.optLong("icmp", protoObj.optLong("icmp_packets", 0L)) ?: 0L

                val secObj = statsJson.optJSONObject("security")
                val obfAlgo = secObj?.optString("obfuscation", secObj.optString("algorithm", "ChaCha20-Poly1305")) ?: "ChaCha20-Poly1305"
                val obfEnabled = (obfAlgo != "none" && obfAlgo.isNotBlank())
                val pskStatus = secObj?.optString("psk_status", "Active") ?: "Active"

                withContext(Dispatchers.Main) {
                    val b = _binding ?: return@withContext
                    b.contentContainer.visibility = View.VISIBLE

                    setupRow(b.rowTxPkts, getString(R.string.stat_total_tx_pkts), getString(R.string.stat_packets_unit, pktsSent))
                    setupRow(b.rowRxPkts, getString(R.string.stat_total_rx_pkts), getString(R.string.stat_packets_unit, pktsRecv))
                    setupRow(b.rowDropped, getString(R.string.stat_dropped_pkts), getString(R.string.stat_dropped_fmt, pktsDropped, dedupCount))

                    setupRow(b.rowIpv4, getString(R.string.stat_ipv4), getString(R.string.stat_packets_unit, ipv4Pkts))
                    setupRow(b.rowIpv6, getString(R.string.stat_ipv6), getString(R.string.stat_packets_unit, ipv6Pkts))
                    setupRow(b.rowArp, getString(R.string.stat_arp), getString(R.string.stat_packets_unit, arpPkts))
                    setupRow(b.rowTcp, getString(R.string.stat_tcp), getString(R.string.stat_packets_unit, tcpPkts))
                    setupRow(b.rowUdp, getString(R.string.stat_udp), getString(R.string.stat_packets_unit, udpPkts))
                    setupRow(b.rowIcmp, getString(R.string.stat_icmp), getString(R.string.stat_packets_unit, icmpPkts))

                    setupRow(b.rowObf, getString(R.string.stat_obf), if (obfEnabled) "$obfAlgo ($pskStatus)" else getString(R.string.stat_obf_disabled))
                    setupRow(b.rowAlgo, getString(R.string.stat_algo), obfAlgo)
                    setupRow(b.rowPfs, getString(R.string.stat_pfs), getString(R.string.stat_pfs_strict))
                    setupRow(b.rowReplay, getString(R.string.stat_replay), getString(R.string.stat_replay_desc))
                }
            } catch (_: Exception) {
                // Silently swallow background parse exceptions when engine is starting up to prevent Toast spam and UI lag
            }
        }
    }

    private fun setupRow(rowBinding: ItemTrafficRowBinding, label: String, value: String) {
        rowBinding.tvLabel.text = label
        rowBinding.tvValue.text = value
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
