package app.fjj.p2ptap.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class NodeMetrics(
    val peerCount: Int = 0,
    val directPeers: Int = 0,
    val relayPeers: Int = 0,
    val txSpeed: Long = 0,      // bytes/sec
    val rxSpeed: Long = 0,      // bytes/sec
    val totalTx: Long = 0,      // total bytes
    val totalRx: Long = 0       // total bytes
)

data class PeerItemData(
    val peerId: String,
    val nodeName: String,
    val tapIp: String,
    val tapIpv6: String,
    val isDirect: Boolean,
    val isRelayed: Boolean,
    val connState: String,
    val multiaddr: String,
    val transport: String,
    val transportScore: Int = 999,
    val transportPriority: String = "",
    val rtt: Double,
    val rttMeasured: Boolean,
    val txBytes: Long,
    val rxBytes: Long,
    val os: String,
    val version: String,
    val isExitNode: Boolean
)

data class VpnStatus(val state: String = "IDLE", val message: String = "")

object P2PStateRepository {
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()
    @Volatile var sessionRevision: Long = 0
        private set
    private val _state = MutableStateFlow("IDLE")
    val state: StateFlow<String> = _state.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _metrics = MutableStateFlow(NodeMetrics())
    val metrics: StateFlow<NodeMetrics> = _metrics.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerItemData>>(emptyList())
    val peers: StateFlow<List<PeerItemData>> = _peers.asStateFlow()

    @Synchronized
    fun updateState(newState: String, newMsg: String = "") {
        if (_state.value != newState) sessionRevision++
        _state.value = newState
        _message.value = newMsg
        _status.value = VpnStatus(newState, newMsg)
        if (newState == "STARTING" || newState == "STOPPING" || newState == "IDLE" || newState == "ERROR" || newState == "TIMEOUT") {
            _metrics.value = NodeMetrics()
            _peers.value = emptyList()
        }
    }

    @Synchronized
    fun updatePeers(newPeers: List<PeerItemData>, revision: Long = sessionRevision) {
        if (revision != sessionRevision || _state.value != "RUNNING") return
        _peers.value = newPeers
    }

    @Synchronized
    fun updateMetrics(
        peerCount: Int,
        directPeers: Int,
        relayPeers: Int,
        txSpeed: Long,
        rxSpeed: Long,
        totalTx: Long,
        totalRx: Long
    ) {
        if (_state.value != "RUNNING") return
        _metrics.value = NodeMetrics(
            peerCount = peerCount,
            directPeers = directPeers,
            relayPeers = relayPeers,
            txSpeed = txSpeed,
            rxSpeed = rxSpeed,
            totalTx = totalTx,
            totalRx = totalRx
        )
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
