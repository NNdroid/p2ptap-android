package app.fjj.p2ptap.config

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PeerAddressInputTest {
    private val peerId = "12D3KooWSMGgiTzD7aGiLJhNGmPhtS5142U8P3oTWderqTV92NZj"
    private val certHash = "uEiDKR_5wfQ9sprRwawTIzwqAtqIuokkOmw2ngF7D6LgTyA"
    private val endpoints = listOf(
        "/ip4/43.138.143.71/udp/4002/webrtc-direct/certhash/$certHash/p2p/$peerId",
        "/ip4/43.138.143.71/udp/4001/quic-v1/p2p/$peerId",
        "/ip4/43.138.143.71/tcp/4001/p2p/$peerId",
        "/ip6/2402:4e00:c012:1100:561c:f1e2:fef4:1/udp/4002/webrtc-direct/certhash/$certHash/p2p/$peerId",
        "/ip6/2402:4e00:c012:1100:561c:f1e2:fef4:1/udp/4001/quic-v1/p2p/$peerId",
        "/ip6/2402:4e00:c012:1100:561c:f1e2:fef4:1/tcp/4001/p2p/$peerId"
    )

    @Test
    fun pastedSixAddressLineProducesSixUnchangedEndpoints() {
        assertEquals(endpoints, splitPeerAddresses(endpoints.joinToString(" ")))
    }

    @Test
    fun mixedSeparatorsBlanksAndDuplicatesKeepFirstOccurrenceOrder() {
        for (separator in listOf(" ", "\n", "\r\n", "\t", ",", ";", "\u00a0", "\u3000", ", ;\t\n")) {
            assertEquals(endpoints, splitPeerAddresses("$separator${endpoints.joinToString(separator)}$separator"))
        }
        assertEquals(endpoints, splitPeerAddresses(listOf("", endpoints.joinToString(" "), endpoints[0], "\n")))
    }

    @Test
    fun legacySingleEntrySplitsOnLoadExportAndEngineSerialization() {
        val root = JSONObject().put("bootstrap_peers", JSONArray().put(endpoints.joinToString(" ")))
            .put("static_peers", JSONArray().put(endpoints.joinToString("\t")))
        val restored = P2PConfig.fromJson(root.toString(), strict = false)
        assertEquals(endpoints, restored.bootstrapPeers)
        assertEquals(endpoints, restored.staticPeers)
        assertPeerArrays(JSONObject(restored.toEngineJson("/app/node.key")))
        assertPeerArrays(JSONObject(P2PConfig.fromJson(restored.toExportJson()).toExportJson()))
    }

    @Test
    fun directlyConstructedConfigAlsoSplitsBeforeSendingToGo() {
        val config = P2PConfig(bootstrapPeers = listOf(endpoints.joinToString(" ")),
            staticPeers = listOf(endpoints.joinToString(";")))
        assertPeerArrays(JSONObject(config.toEngineJson("/app/node.key")))
        assertPeerArrays(JSONObject(config.toExportJson()))
    }

    @Test
    fun emptyListsStayEmptyAndInvalidTokensRemainForValidation() {
        assertTrue(splitPeerAddresses(" ,;\t\n ").isEmpty())
        assertTrue(splitPeerAddresses(emptyList()).isEmpty())
        val bad = "not-a-peer-address"
        val config = P2PConfig.fromJson(JSONObject().put("bootstrap_peers", JSONArray())
            .put("static_peers", JSONArray().put("${endpoints[0]} $bad")).toString())
        val engine = JSONObject(config.toEngineJson("/app/node.key"))
        assertEquals(0, engine.getJSONArray("bootstrap_peers").length())
        assertEquals(bad, engine.getJSONArray("static_peers").getString(1))
    }

    private fun assertPeerArrays(root: JSONObject) {
        for (field in listOf("bootstrap_peers", "static_peers")) {
            val array = root.getJSONArray(field)
            assertEquals(endpoints, (0 until array.length()).map { array.getString(it) })
        }
    }
}
