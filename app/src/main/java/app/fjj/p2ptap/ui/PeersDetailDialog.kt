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
            lifecycleScope.launch {
                renderPeers()
                if (_binding != null && context != null) {
                    Toast.makeText(requireContext(), getString(R.string.msg_peers_refreshed), Toast.LENGTH_SHORT).show()
                }
            }
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

    private suspend fun renderPeers() {
        if (!P2PTapVpnService.isRunning()) {
            withContext(Dispatchers.Main) {
                val b = _binding ?: return@withContext
                b.tvSummary.text = getString(R.string.peers_offline_hint)
                b.peersContainer.removeAllViews()
            }
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val statsJsonStr = P2PTap.getStatsJSON()
                if (statsJsonStr.isNullOrBlank()) return@withContext

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
                        val rawNodeName = peer.optString("node_name", "")
                        val nodeName = if (rawNodeName.isNotBlank()) {
                            rawNodeName
                        } else if (peerId.length > 8) {
                            "Peer-${peerId.takeLast(6)}"
                        } else {
                            "Peer-$i"
                        }
                        val tapIp = peer.optString("tap_ip", "")
                        val tapIpv6 = peer.optString("tap_ipv6", "")
                        val connState = peer.optString("conn_state", "ok")
                        val addr = peer.optString("addr", peer.optString("multiaddr", ""))
                        val transport = peer.optString("transport", "P2P")
                        val rtt = peer.optDouble("rtt_ms", 0.0)
                        val txBytes = peer.optLong("total_tx", peer.optLong("tx_bytes", 0L))
                        val rxBytes = peer.optLong("total_rx", peer.optLong("rx_bytes", 0L))
                        val osArch = peer.optString("os_arch", peer.optString("os", ""))
                        val version = peer.optString("version", "")
                        val isExitNode = peer.optBoolean("is_exit_node", false)

                        val isDirect = (connState == "ok" || (!peer.optBoolean("is_relayed", false) && connState != "relay_ok"))
                        if (isDirect) directCount++ else relayCount++

                        peerDataList.add(
                            PeerItemData(
                                peerId = peerId,
                                nodeName = nodeName,
                                tapIp = tapIp,
                                tapIpv6 = tapIpv6,
                                isDirect = isDirect,
                                connState = connState,
                                multiaddr = addr,
                                transport = transport,
                                rtt = rtt,
                                txBytes = txBytes,
                                rxBytes = rxBytes,
                                os = osArch,
                                version = version,
                                isExitNode = isExitNode
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    val b = _binding ?: return@withContext
                    val ctx = context ?: return@withContext

                    if (totalPeers == 0) {
                        b.peersContainer.removeAllViews()
                        b.tvSummary.text = getString(R.string.peers_summary_fmt, 0, 0, 0)
                        return@withContext
                    }

                    // Efficient View In-Place Re-use (prevents Layout Inflation thrashing and GC pauses)
                    val currentChildCount = b.peersContainer.childCount
                    val targetCount = peerDataList.size

                    if (currentChildCount > targetCount) {
                        b.peersContainer.removeViews(targetCount, currentChildCount - targetCount)
                    } else if (currentChildCount < targetCount) {
                        for (c in currentChildCount until targetCount) {
                            ItemPeerDetailBinding.inflate(LayoutInflater.from(ctx), b.peersContainer, true)
                        }
                    }

                    for (i in 0 until targetCount) {
                        val childView = b.peersContainer.getChildAt(i)
                        val itemBinding = ItemPeerDetailBinding.bind(childView)
                        val item = peerDataList[i]

                        itemBinding.tvNodeName.text = item.nodeName
                        itemBinding.tvBadge.apply {
                            when (item.connState) {
                                "ok" -> {
                                    text = getString(R.string.badge_direct)
                                    setTextColor(Color.parseColor("#059669"))
                                }
                                "relay_ok" -> {
                                    text = getString(R.string.badge_relay)
                                    setTextColor(Color.parseColor("#0284C7"))
                                }
                                "connecting" -> {
                                    text = "Connecting"
                                    setTextColor(Color.parseColor("#D97706"))
                                }
                                "obf_failed", "proto_mismatch" -> {
                                    text = "Error"
                                    setTextColor(Color.parseColor("#DC2626"))
                                }
                                else -> {
                                    text = if (item.isDirect) getString(R.string.badge_direct) else getString(R.string.badge_relay)
                                    setTextColor(if (item.isDirect) Color.parseColor("#059669") else Color.parseColor("#D97706"))
                                }
                            }
                        }

                        itemBinding.tvRtt.text = if (item.rtt > 0) "📶 ${item.rtt.toInt()}ms" else ""

                        val ipText = buildString {
                            if (item.tapIp.isNotBlank()) append("IPv4: ${item.tapIp}  ")
                            if (item.tapIpv6.isNotBlank()) append("IPv6: ${item.tapIpv6}")
                        }
                        itemBinding.tvIpAddresses.text = ipText
                        itemBinding.tvIpAddresses.visibility = if (ipText.isBlank()) View.GONE else View.VISIBLE
                        itemBinding.tvIpAddresses.setOnClickListener {
                            val ip = if (item.tapIp.isNotBlank()) item.tapIp else item.tapIpv6
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Peer IP", ip))
                            Toast.makeText(ctx, getString(R.string.msg_copied_clipboard) + ": $ip", Toast.LENGTH_SHORT).show()
                        }

                        itemBinding.tvPeerId.text = "ID: ${item.peerId}"
                        itemBinding.layoutPeerId.setOnClickListener {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Peer ID", item.peerId))
                            Toast.makeText(ctx, getString(R.string.msg_peer_id_copied_fmt, item.peerId), Toast.LENGTH_SHORT).show()
                        }

                        val endpointText = buildString {
                            if (item.multiaddr.isNotBlank()) {
                                append(getString(R.string.label_endpoint_prefix))
                                append(item.multiaddr)
                            } else if (item.transport.isNotBlank()) {
                                append(getString(R.string.label_endpoint_prefix))
                                append("(${item.transport})")
                            }
                        }
                        itemBinding.tvEndpoint.text = endpointText
                        itemBinding.tvEndpoint.visibility = if (endpointText.isBlank()) View.GONE else View.VISIBLE

                        val tx = P2PStateRepository.formatBytes(item.txBytes)
                        val rx = P2PStateRepository.formatBytes(item.rxBytes)
                        val sys = if (item.os.isNotBlank()) " • ${item.os} ${item.version}".trim() else ""
                        val exitBadge = if (item.isExitNode) " • 🚀 Exit Gateway" else ""
                        itemBinding.tvTraffic.text = getString(R.string.label_traffic_prefix) + "↑ $tx  ↓ $rx$sys$exitBadge"
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
        val connState: String,
        val multiaddr: String,
        val transport: String,
        val rtt: Double,
        val txBytes: Long,
        val rxBytes: Long,
        val os: String,
        val version: String,
        val isExitNode: Boolean
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
