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
            refreshExtendedStats()
            Toast.makeText(requireContext(), getString(R.string.msg_traffic_refreshed), Toast.LENGTH_SHORT).show()
        }

        observeMetrics()
        startPeriodicStatsRefresh()
    }

    private fun observeMetrics() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                P2PStateRepository.metrics.collect { metrics ->
                    if (!P2PTapVpnService.isRunning()) {
                        binding.contentContainer.visibility = View.GONE
                        return@collect
                    }
                    binding.contentContainer.visibility = View.VISIBLE

                    setupRow(binding.rowTxSpeed, getString(R.string.stat_live_tx_speed), P2PStateRepository.formatSpeed(metrics.txSpeed))
                    setupRow(binding.rowRxSpeed, getString(R.string.stat_live_rx_speed), P2PStateRepository.formatSpeed(metrics.rxSpeed))
                    setupRow(binding.rowTotalTx, getString(R.string.stat_total_tx_bytes), P2PStateRepository.formatBytes(metrics.totalTx))
                    setupRow(binding.rowTotalRx, getString(R.string.stat_total_rx_bytes), P2PStateRepository.formatBytes(metrics.totalRx))
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

    private fun refreshExtendedStats() {
        if (!P2PTapVpnService.isRunning()) {
            binding.contentContainer.visibility = View.GONE
            return
        }
        binding.contentContainer.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val statsJsonStr = P2PTap.getStatsJSON()
                val statsJson = JSONObject(statsJsonStr)

                val pktObj = statsJson.optJSONObject("packet_stats")
                val pktsSent = pktObj?.optLong("packets_sent", 0L) ?: 0L
                val pktsRecv = pktObj?.optLong("packets_recv", 0L) ?: 0L
                val pktsDropped = pktObj?.optLong("packets_dropped", 0L) ?: 0L
                val cryptoErrors = pktObj?.optLong("crypto_errors", 0L) ?: 0L

                val protoObj = statsJson.optJSONObject("protocol_stats")
                val ipv4Pkts = protoObj?.optLong("ipv4_packets", 0L) ?: 0L
                val ipv6Pkts = protoObj?.optLong("ipv6_packets", 0L) ?: 0L
                val arpPkts = protoObj?.optLong("arp_packets", 0L) ?: 0L
                val tcpPkts = protoObj?.optLong("tcp_packets", 0L) ?: 0L
                val udpPkts = protoObj?.optLong("udp_packets", 0L) ?: 0L
                val icmpPkts = protoObj?.optLong("icmp_packets", 0L) ?: 0L

                val secObj = statsJson.optJSONObject("security")
                val obfEnabled = secObj?.optBoolean("obfuscation_enabled", true) ?: true
                val obfAlgo = secObj?.optString("algorithm", "ChaCha20-Poly1305") ?: "ChaCha20-Poly1305"
                val pfsEnabled = secObj?.optBoolean("pfs_enabled", true) ?: true

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext

                    setupRow(binding.rowTxPkts, getString(R.string.stat_total_tx_pkts), getString(R.string.stat_packets_unit, pktsSent))
                    setupRow(binding.rowRxPkts, getString(R.string.stat_total_rx_pkts), getString(R.string.stat_packets_unit, pktsRecv))
                    setupRow(binding.rowDropped, getString(R.string.stat_dropped_pkts), getString(R.string.stat_dropped_fmt, pktsDropped, cryptoErrors))

                    setupRow(binding.rowIpv4, getString(R.string.stat_ipv4), getString(R.string.stat_packets_unit, ipv4Pkts))
                    setupRow(binding.rowIpv6, getString(R.string.stat_ipv6), getString(R.string.stat_packets_unit, ipv6Pkts))
                    setupRow(binding.rowArp, getString(R.string.stat_arp), getString(R.string.stat_packets_unit, arpPkts))
                    setupRow(binding.rowTcp, getString(R.string.stat_tcp), getString(R.string.stat_packets_unit, tcpPkts))
                    setupRow(binding.rowUdp, getString(R.string.stat_udp), getString(R.string.stat_packets_unit, udpPkts))
                    setupRow(binding.rowIcmp, getString(R.string.stat_icmp), getString(R.string.stat_packets_unit, icmpPkts))

                    setupRow(binding.rowObf, getString(R.string.stat_obf), if (obfEnabled) getString(R.string.stat_obf_enabled) else getString(R.string.stat_obf_disabled))
                    setupRow(binding.rowAlgo, getString(R.string.stat_algo), obfAlgo)
                    setupRow(binding.rowPfs, getString(R.string.stat_pfs), if (pfsEnabled) getString(R.string.stat_pfs_strict) else getString(R.string.stat_pfs_shared))
                    setupRow(binding.rowReplay, getString(R.string.stat_replay), getString(R.string.stat_replay_desc))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        Toast.makeText(requireContext(), getString(R.string.err_parse_traffic) + e.message, Toast.LENGTH_SHORT).show()
                    }
                }
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
