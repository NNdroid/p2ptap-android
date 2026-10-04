package app.fjj.p2ptap.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class QrPeerAddressInputTest {
    private val peerId = "12D3KooWSMGgiTzD7aGiLJhNGmPhtS5142U8P3oTWderqTV92NZj"
    private val endpoints = listOf(
        "/ip4/43.138.143.71/udp/4001/quic-v1/p2p/$peerId",
        "/ip6/2402:4e00:c012:1100:561c:f1e2:fef4:1/tcp/4001/p2p/$peerId"
    )

    @Test
    fun rawCopiedAddressQrSplitsWhitespace() {
        assertEquals(endpoints, QrImportHelper.extractAddresses(endpoints.joinToString(" \t")))
    }

    @Test
    fun jsonAddressQrSplitsJoinedArrayEntries() {
        val json = JSONObject().put("peer_id", peerId)
            .put("addrs", JSONArray().put(endpoints.joinToString(" "))).toString()
        assertEquals(endpoints, QrImportHelper.extractAddresses(json))
    }

    @Test
    fun uriAddressQrSplitsAfterUrlDecoding() {
        val encoded = URLEncoder.encode(endpoints.joinToString("\n; "), "UTF-8")
        assertEquals(endpoints, QrImportHelper.extractAddresses("p2ptap://remote?peerid=$peerId&addrs=$encoded"))
    }

    @Test
    fun fullBackupRepairsListsWithoutTreatingThemAsNodeEndpoints() {
        val config = JSONObject().put("bootstrap_peers", JSONArray().put(endpoints.joinToString(" ")))
            .put("static_peers", JSONArray().put(endpoints.joinToString(" ")))
        val info = QrImportHelper.parse(JSONObject().put("config", config).put("peer_id", peerId).toString())!!
        assertTrue(info.isFullConfig)
        assertTrue(info.addrs.isEmpty())
        assertEquals(endpoints, info.fullConfig!!.bootstrapPeers)
        assertEquals(endpoints, info.fullConfig!!.staticPeers)
    }
}
