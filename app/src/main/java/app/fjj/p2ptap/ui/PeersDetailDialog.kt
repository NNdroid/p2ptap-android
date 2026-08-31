package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.DialogPeersDetailBinding
import app.fjj.p2ptap.databinding.ItemPeerDetailBinding
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

class PeersDetailDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "PeersDetailDialog"
        fun newInstance(): PeersDetailDialog = PeersDetailDialog()
    }

    private var _binding: DialogPeersDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPeersDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRefresh.setOnClickListener {
            renderPeers()
            Toast.makeText(requireContext(), getString(R.string.msg_peers_refreshed), Toast.LENGTH_SHORT).show()
        }

        startPeriodicPeersRefresh()
    }

    private fun startPeriodicPeersRefresh() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    renderPeers()
                    delay(2000)
                }
            }
        }
    }

    private fun renderPeers() {
        if (!P2PTapVpnService.isRunning()) {
            binding.tvSummary.text = getString(R.string.peers_offline_hint)
            binding.peersContainer.removeAllViews()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val statsJsonStr = P2PTap.getStatsJSON()
                val statsJson = JSONObject(statsJsonStr)
                val peersArray = statsJson.optJSONArray("active_peers")

                val totalPeers = peersArray?.length() ?: 0
                var directCount = 0
                var relayCount = 0

                val peerDataList = mutableListOf<PeerItemData>()

                if (totalPeers > 0) {
                    for (i in 0 until totalPeers) {
                        val peer = peersArray!!.getJSONObject(i)
                        val peerId = peer.optString("peer_id", "-")
                        val nodeName = peer.optString("node_name", "Peer-$i")
                        val tapIp = peer.optString("tap_ip", "")
                        val tapIpv6 = peer.optString("tap_ipv6", "")
                        val connState = peer.optString("conn_state", "ok")
                        val multiaddr = peer.optString("multiaddr", "")
                        val rtt = peer.optDouble("rtt_ms", 0.0)
                        val txBytes = peer.optLong("tx_bytes", 0L)
                        val rxBytes = peer.optLong("rx_bytes", 0L)
                        val os = peer.optString("os", "")
                        val version = peer.optString("version", "")

                        val isDirect = (connState == "ok")
                        if (isDirect) directCount++ else relayCount++

                        peerDataList.add(
                            PeerItemData(
                                peerId = peerId,
                                nodeName = nodeName,
                                tapIp = tapIp,
                                tapIpv6 = tapIpv6,
                                isDirect = isDirect,
                                multiaddr = multiaddr,
                                rtt = rtt,
                                txBytes = txBytes,
                                rxBytes = rxBytes,
                                os = os,
                                version = version
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext

                    val ctx = context ?: return@withContext
                    binding.peersContainer.removeAllViews()

                    if (totalPeers == 0) {
                        binding.tvSummary.text = getString(R.string.peers_summary_fmt, 0, 0, 0)
                        return@withContext
                    }

                    for (item in peerDataList) {
                        val itemBinding = ItemPeerDetailBinding.inflate(LayoutInflater.from(ctx), binding.peersContainer, true)

                        itemBinding.tvNodeName.text = item.nodeName
                        itemBinding.tvBadge.apply {
                            text = if (item.isDirect) getString(R.string.badge_direct) else getString(R.string.badge_relay)
                            setTextColor(if (item.isDirect) Color.parseColor("#059669") else Color.parseColor("#D97706"))
                        }

                        itemBinding.tvRtt.text = if (item.rtt > 0) "📶 ${item.rtt.toInt()}ms" else ""

                        val ipText = buildString {
                            if (item.tapIp.isNotBlank()) append("IPv4: ${item.tapIp}  ")
                            if (item.tapIpv6.isNotBlank()) append("IPv6: ${item.tapIpv6}")
                        }
                        itemBinding.tvIpAddresses.text = ipText
                        itemBinding.tvIpAddresses.visibility = if (ipText.isBlank()) View.GONE else View.VISIBLE

                        itemBinding.tvPeerId.text = "ID: ${item.peerId}"
                        itemBinding.layoutPeerId.setOnClickListener {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Peer ID", item.peerId))
                            Toast.makeText(ctx, getString(R.string.msg_peer_id_copied_fmt, item.peerId), Toast.LENGTH_SHORT).show()
                        }

                        itemBinding.tvEndpoint.text = if (item.multiaddr.isNotBlank()) getString(R.string.label_endpoint_prefix) + item.multiaddr else ""
                        itemBinding.tvEndpoint.visibility = if (item.multiaddr.isBlank()) View.GONE else View.VISIBLE

                        val tx = P2PStateRepository.formatBytes(item.txBytes)
                        val rx = P2PStateRepository.formatBytes(item.rxBytes)
                        val sys = if (item.os.isNotBlank()) " • ${item.os} ${item.version}" else ""
                        itemBinding.tvTraffic.text = getString(R.string.label_traffic_prefix) + "↑ $tx  ↓ $rx$sys"
                    }

                    binding.tvSummary.text = getString(R.string.peers_summary_fmt, totalPeers, directCount, relayCount)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.tvSummary.text = getString(R.string.err_parse_peers) + e.message
                    }
                }
            }
        }
    }

    private data class PeerItemData(
        val peerId: String,
        val nodeName: String,
        val tapIp: String,
        val tapIpv6: String,
        val isDirect: Boolean,
        val multiaddr: String,
        val rtt: Double,
        val txBytes: Long,
        val rxBytes: Long,
        val os: String,
        val version: String
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
