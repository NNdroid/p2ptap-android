package app.fjj.p2ptap.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fjj.p2ptap.service.NodeMetrics
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.PeerItemData
import app.fjj.p2ptap.service.P2PTapVpnService
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainViewModel : ViewModel() {
    val state: StateFlow<String> = P2PStateRepository.state
    val message: StateFlow<String> = P2PStateRepository.message
    val metrics: StateFlow<NodeMetrics> = P2PStateRepository.metrics
    val peers: StateFlow<List<PeerItemData>> = P2PStateRepository.peers

    fun refreshPeers() {
        if (!P2PTapVpnService.isRunning()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val statsJsonStr = P2PTap.getStatsJSON()
                if (statsJsonStr.isNullOrBlank()) return@launch
                val statsJson = JSONObject(statsJsonStr)
                val peersArray = statsJson.optJSONArray("active_peers") ?: return@launch
                val list = mutableListOf<PeerItemData>()
                for (i in 0 until peersArray.length()) {
                    val peer = peersArray.getJSONObject(i)
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
                    val transportScore = peer.optInt("transport_score", 999)
                    val transportPriority = peer.optString("transport_priority", "")
                    val rtt = peer.optDouble("rtt_ms", 0.0)
                    val txBytes = peer.optLong("total_tx", peer.optLong("tx_bytes", 0L))
                    val rxBytes = peer.optLong("total_rx", peer.optLong("rx_bytes", 0L))
                    val osArch = peer.optString("os_arch", peer.optString("os", ""))
                    val version = peer.optString("version", "")
                    val isExitNode = peer.optBoolean("is_exit_node", false)
                    val isDirect = (connState == "ok" || (!peer.optBoolean("is_relayed", false) && connState != "relay_ok"))

                    list.add(
                        PeerItemData(
                            peerId = peerId,
                            nodeName = nodeName,
                            tapIp = tapIp,
                            tapIpv6 = tapIpv6,
                            isDirect = isDirect,
                            connState = connState,
                            multiaddr = addr,
                            transport = transport,
                            transportScore = transportScore,
                            transportPriority = transportPriority,
                            rtt = rtt,
                            txBytes = txBytes,
                            rxBytes = rxBytes,
                            os = osArch,
                            version = version,
                            isExitNode = isExitNode
                        )
                    )
                }
                P2PStateRepository.updatePeers(list)
            } catch (_: Exception) {}
        }
    }
}
