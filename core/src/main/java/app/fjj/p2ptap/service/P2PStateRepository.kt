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
    val totalRx: Long = 0,      // total bytes
    val uptimeSeconds: Long = 0,
    val heapAllocMB: Float = 0f,
    val goroutines: Int = 0,
    val gcPauseMS: Float = 0f
)

data class SystemHealthData(
    val uptimeSeconds: Long = 0,
    val heapAllocMB: Float = 0f,
    val heapObjects: Long = 0,
    val stackInuseMB: Float = 0f,
    val goroutines: Int = 0,
    val gcCount: Int = 0,
    val lastGcPauseMS: Float = 0f,
    val gcCpuFraction: Float = 0f,
    val numCpu: Int = 0,
    val goMaxProcs: Int = 0
)

data class ProtocolStatsData(
    val ipv4: Long = 0,
    val ipv6: Long = 0,
    val arp: Long = 0,
    val ndp: Long = 0,
    val tcp: Long = 0,
    val udp: Long = 0,
    val icmp: Long = 0,
    val other: Long = 0
) {
    val total: Long get() = ipv4 + ipv6 + arp + ndp + tcp + udp + icmp + other
    fun percentOf(key: Long): Int = if (total == 0L) 0 else ((key * 100L) / total).toInt()
}

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
    val isExitNode: Boolean,
    val txSpeed: Long? = null,
    val rxSpeed: Long? = null,
    val linkTrafficAvailable: Boolean = false,
    val remoteTxBytes: Long? = null,
    val remoteRxBytes: Long? = null,
    val rttSource: String = "",
    val connDetail: String = "",
    // ── P0: connection quality ──────────────────────────────────────────────
    val connectedSince: String = "",
    val connectedAt: String = "",
    val uptime: String = "",
    val returnPath: String = "",        // ok | dead | idle
    val returnPathDetail: String = "",
    // ── P0: asymmetric return medium ────────────────────────────────────────
    // The transport the peer's inbound frames most recently arrived over, which
    // is the return-side twin of [transport] (the path WE send over). Each node
    // picks its own egress, so these legitimately differ — a peer may send us
    // over an overlay relay while we send it over a live direct link. Empty
    // means no inbound frame was seen recently, i.e. the medium is unknown.
    val returnTransport: String = "",   // "" | direct | circuit-relay | overlay-relay
    val returnRelayHop: String = "",
    val jitterMs: Double = 0.0,
    val jitterMeasured: Boolean = false,
    val lossRatePercent: Double = 0.0,
    val lossMeasured: Boolean = false,
    // ── P0: encryption ─────────────────────────────────────────────────────
    val obfAlgo: String = "",
    val obfEncrypted: Boolean = false,
    val obfDecryptErrs: Long = 0,
    val obfLastRekeyAgo: String = "",
    // ── P0: TAP integrity ───────────────────────────────────────────────────
    val tapMacMismatch: Boolean = false,
    // ── P1: link diagnostics ────────────────────────────────────────────────
    val replayDrops: Long = 0,
    val dedupDrops: Long = 0,
    // ── P1: metadata ────────────────────────────────────────────────────────
    val geoLocation: String = "",
    val reachability: String = "",
    val role: String = "",
    val relayOnly: Boolean = false,
    val exitNat: Boolean = false
)

data class VpnStatus(val state: String = "IDLE", val message: String = "")

object P2PStateRepository {
    private val _telemetry = MutableStateFlow(TelemetryState())
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()
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
            _telemetry.value = TelemetryState()
        }
    }

    @Synchronized
    fun updatePeers(newPeers: List<PeerItemData>, revision: Long = sessionRevision) {
        if (revision != sessionRevision || _state.value != "RUNNING") return
        _peers.value = newPeers
    }

    @Synchronized
    fun beginRefresh(revision: Long): Boolean {
        if (revision != sessionRevision || _state.value != "RUNNING") return false
        _telemetry.value = _telemetry.value.copy(refreshing = true)
        return true
    }

    @Synchronized
    fun finishRefresh(snapshot: StatsSnapshot?, revision: Long): Boolean {
        if (revision != sessionRevision || _state.value != "RUNNING") return false
        _telemetry.value = TelemetryState(snapshot = snapshot, failed = snapshot == null)
        _peers.value = snapshot?.peers.orEmpty()
        return snapshot != null
    }

    @Synchronized
    fun updateMetrics(
        peerCount: Int,
        directPeers: Int,
        relayPeers: Int,
        txSpeed: Long,
        rxSpeed: Long,
        totalTx: Long,
        totalRx: Long,
        uptimeSeconds: Long = _metrics.value.uptimeSeconds,
        heapAllocMB: Float = _metrics.value.heapAllocMB,
        goroutines: Int = _metrics.value.goroutines,
        gcPauseMS: Float = _metrics.value.gcPauseMS
    ) {
        if (_state.value != "RUNNING") return
        _metrics.value = NodeMetrics(
            peerCount = peerCount,
            directPeers = directPeers,
            relayPeers = relayPeers,
            txSpeed = txSpeed,
            rxSpeed = rxSpeed,
            totalTx = totalTx,
            totalRx = totalRx,
            uptimeSeconds = uptimeSeconds,
            heapAllocMB = heapAllocMB,
            goroutines = goroutines,
            gcPauseMS = gcPauseMS
        )
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.getDefault(), "%.1f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /** Format an uptime in seconds as a human-readable duration string. */
    fun formatUptime(seconds: Long): String {
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> String.format(Locale.getDefault(), "%dm %ds", seconds / 60, seconds % 60)
            else -> String.format(Locale.getDefault(), "%dh %dm", seconds / 3600, (seconds % 3600) / 60)
        }
    }

    /** Format a memory value in MB with one decimal place. */
    fun formatMemoryMB(mb: Float): String {
        return String.format(Locale.getDefault(), "%.1f MB", mb)
    }

    // ── Caches for data that is expensive to obtain but rarely changes ──────
    //
    // Everything here crosses into the Go engine over JNI, which blocks until
    // the engine answers — and while it is still starting up a call can be
    // slow enough to be visible as a stutter on the main screen. The values
    // below change at most once per VPN session, so they are captured once
    // and reused, and dropped whenever sessionRevision moves (a state change
    // means a new session, or at least a restarted engine).

    @Volatile
    private var cachedMultiaddrs: String? = null

    @Volatile
    private var cachedMultiaddrsRevision: Long = -1

    /**
     * Multiaddrs already captured for this session, without touching JNI.
     *
     * This is a pure read: it never populates the cache, so callers can use it
     * to render instantly and leave the actual fetch to whoever needs it.
     */
    @Synchronized
    fun multiaddrsOrNull(): String? = cachedMultiaddrs

    /**
     * Multiaddrs for this session, fetching them if not known yet.
     *
     * [fetch] runs at most once per session revision. A failed or blank fetch
     * is remembered as "known to be unavailable" for this revision, so a
     * still-starting engine does not get hammered on every resume.
     */
    @Synchronized
    fun multiaddrs(fetch: () -> String?): String? {
        if (cachedMultiaddrsRevision != sessionRevision) {
            cachedMultiaddrs = try {
                fetch()?.trim()?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
            cachedMultiaddrsRevision = sessionRevision
        }
        return cachedMultiaddrs
    }

    /** Peers shown on the home screen, cached for the same reason. */
    @Synchronized
    fun peersOrNull(): List<PeerItemData>? = _peers.value.takeIf { it.isNotEmpty() }

    /**
     * Age of the last successful telemetry snapshot in ms, or Long.MAX_VALUE
     * when there is none. Used to skip redundant getStatsJSON round-trips.
     */
    @Volatile
    private var lastSnapshotAtMs: Long = 0

    @Volatile
    private var lastSnapshotRevision: Long = -1

    /** True when the cached telemetry is fresh enough to reuse (see TELEMETRY_TTL_MS). */
    @Synchronized
    fun telemetryFresh(nowMs: Long, ttlMs: Long): Boolean {
        if (lastSnapshotRevision != sessionRevision) return false
        if (lastSnapshotAtMs == 0L) return false
        return nowMs - lastSnapshotAtMs < ttlMs
    }

    /** Record that a snapshot was just parsed, starting a fresh TTL window. */
    @Synchronized
    fun markSnapshotFresh(nowMs: Long) {
        lastSnapshotAtMs = nowMs
        lastSnapshotRevision = sessionRevision
    }
}
