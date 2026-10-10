package app.fjj.p2ptap.service

import org.json.JSONObject
import java.net.InetAddress

fun matchesVirtualAddress(target: String, address: String): Boolean {
    val left = target.substringBefore('/').trim()
    val right = address.substringBefore('/').trim()
    if (left.isBlank() || right.isBlank()) return false
    if (left == right) return true
    // Parse numeric IPv6 only; this comparison must never issue DNS requests.
    val ipv6 = Regex("[0-9a-fA-F:.]+")
    if (!left.contains(':') || !right.contains(':') || !left.matches(ipv6) || !right.matches(ipv6)) return false
    return runCatching { InetAddress.getByName(left).address.contentEquals(InetAddress.getByName(right).address) }.getOrDefault(false)
}

data class EncryptionData(val peerId: String, val negotiated: Boolean, val encrypted: Boolean,
    val algorithm: String, val pfs: Boolean?)

data class StatsSnapshot(
    val peers: List<PeerItemData>,
    val counters: Map<String, Long?>,
    val obfuscation: String,
    val pskStatus: String,
    val encryption: List<EncryptionData>,
    val activeExitPeerId: String,
    val activeExitIpv4: String,
    val activeExitIpv6: String,
    val natStatus: String
) {
    fun matchesExit(target: String): Boolean = target.isNotBlank() &&
        (target == activeExitPeerId || matchesVirtualAddress(target, activeExitIpv4) || matchesVirtualAddress(target, activeExitIpv6))
}

data class TelemetryState(val snapshot: StatsSnapshot? = null, val refreshing: Boolean = false,
    val failed: Boolean = false)

/** Go observer.StatsResponse contract. Missing values are unknown, never synthetic zeroes. */
object StatsSnapshotParser {
    fun parse(json: String): StatsSnapshot {
        val root = JSONObject(json)
        require(root.optJSONArray("active_peers") != null && root.optJSONObject("packet_stats") != null) { "No running engine snapshot" }
        val metadata = root.optJSONArray("peer_metas")
        val metas = (0 until (metadata?.length() ?: 0)).mapNotNull { metadata?.optJSONObject(it) }
            .associateBy { it.text("peer_id") }
        val array = root.optJSONArray("active_peers")
        val peers = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val p = array?.optJSONObject(index) ?: return@mapNotNull null
            val id = p.text("peer_id")
            if (id.isBlank()) return@mapNotNull null
            val meta = metas[id]
            fun text(key: String): String = p.text(key).ifBlank { meta?.text(key).orEmpty() }
            val state = p.text("conn_state").ifBlank { "unknown" }
            val relay = p.optBoolean("is_relayed", state == "relay_ok")
            val rtt = p.optDouble("rtt_ms", Double.NaN)
            val measured = p.optBoolean("rtt_measured", false) && rtt.isFinite() && rtt >= 0
            val addresses = p.optJSONArray("all_addrs")
            val addr = p.text("addr").takeUnless { it == "unknown" }.orEmpty().ifBlank {
                (0 until (addresses?.length() ?: 0)).map { addresses?.optString(it).orEmpty() }
                    .firstOrNull { it.isNotBlank() }.orEmpty()
            }
            PeerItemData(id, text("node_name").ifBlank { id }, text("tap_ip"), text("tap_ipv6"),
                state == "ok" && !relay, relay, state, addr, p.text("transport"),
                p.optInt("transport_score", 999), p.text("transport_priority"),
                if (measured) rtt else 0.0, measured,
                p.counter("link_total_tx") ?: 0, p.counter("link_total_rx") ?: 0,
                text("os_arch"), text("version"),
                p.optBoolean("is_exit_node", meta?.optBoolean("is_exit_node", false) ?: false),
                txSpeed = if (p.optBoolean("link_speed_measured", false)) p.counter("tx_speed") else null,
                rxSpeed = if (p.optBoolean("link_speed_measured", false)) p.counter("rx_speed") else null,
                linkTrafficAvailable = p.counter("link_total_tx") != null && p.counter("link_total_rx") != null,
                remoteTxBytes = p.counter("total_tx"), remoteRxBytes = p.counter("total_rx"),
                rttSource = p.text("rtt_source"), connDetail = p.text("conn_detail"))
        }.distinctBy { it.peerId }.sortedBy { it.peerId }
        val counters = linkedMapOf<String, Long?>()
        fun read(section: String, vararg keys: String) {
            val obj = root.optJSONObject(section)
            keys.forEach { counters[it] = obj?.counter(it) }
        }
        read("speed", "tx_bytes_per_sec", "rx_bytes_per_sec")
        read("packet_stats", "bytes_sent", "bytes_recv", "packets_sent", "packets_recv", "dispatch_drops", "dedup_count")
        read("protocol_stats", "ipv4", "ipv6", "arp", "ndp", "tcp", "udp", "icmp", "other")
        read("seq_stats", "replay_drops", "synced_peers")
        val security = root.optJSONObject("security")
        val enc = security?.optJSONArray("encryption")
        val encryption = (0 until (enc?.length() ?: 0)).mapNotNull {
            val p = enc?.optJSONObject(it) ?: return@mapNotNull null
            EncryptionData(p.text("peer_id"), p.optBoolean("negotiated"), p.optBoolean("encrypted"),
                p.text("algo"), if (p.has("pfs") && !p.isNull("pfs")) p.optBoolean("pfs") else null)
        }
        val exit = root.optJSONObject("exit_node")
        return StatsSnapshot(peers, counters, security?.text("obfuscation").orEmpty(),
            security?.text("psk_status").orEmpty(), encryption, exit?.text("active_peer_id").orEmpty(),
            exit?.text("active_exit_ip").orEmpty(), exit?.text("active_exit_tap_ipv6").orEmpty(),
            root.text("nat_status"))
    }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "").trim()
    private fun JSONObject.counter(key: String): Long? = if (!has(key) || isNull(key)) null
        else optLong(key, -1).takeIf { it >= 0 }
}
