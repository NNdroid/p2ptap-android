package app.fjj.p2ptap.config

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigPersistenceTest {
    @Test
    fun explicitEmptyBootstrapSurvivesExportImport() {
        val original = P2PConfig.fromJson("""{"bootstrap_peers":[],"static_peers":[],"transport_strategy":"fallback"}""")
        val restored = P2PConfig.fromJson(original.toExportJson())
        assertTrue(restored.bootstrapPeers.isEmpty())
        assertEquals("fallback", restored.transportStrategy)
        assertEquals(0, JSONObject(restored.toEngineJson("/app/node.key")).getJSONArray("bootstrap_peers").length())
    }

    @Test
    fun invalidImportedStrategyIsRejectedBeforeSave() {
        for (strategy in listOf("", "BEST_PATH", "typo")) {
            try {
                P2PConfig.fromJson("""{"transport_strategy":"$strategy"}""")
                fail("Accepted strategy $strategy")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun legacyInvalidValuesRemainAvailableForCorrection() {
        val restored = P2PConfig.fromJson("""{"node_name":"saved-node","transport_strategy":"typo","bootstrap_peers":[],"static_peers":["192.0.2.1"]}""", strict = false)
        assertEquals("saved-node", restored.nodeName)
        assertTrue(restored.bootstrapPeers.isEmpty())
        assertEquals(listOf("192.0.2.1"), restored.staticPeers)
        assertEquals("typo", restored.transportStrategy)
        try {
            restored.validateStrategy()
            fail("Legacy invalid strategy was allowed to start/save")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun webSaveKeepsAppOwnedSettingsAndEngineOptionsAcrossRestart() {
        val current = P2PConfig(nodeName = "old", exitNode = "exit-peer", dnsServers = listOf("1.1.1.1"))
        val endpoint = "/ip4/192.0.2.1/tcp/4001/p2p/QmNnooDu7bfjPFoTmoXMY5PeBKyy1EicV2g7HQ1b18423b"
        val json = """{
          "node_name":"new","transport_strategy":"redundant","bootstrap_peers":[],"static_peers":["$endpoint"],
          "listen_addrs":["/ip4/0.0.0.0/tcp/5010"],"exit_node":{"enable":false},
          "transports":{"tls_server_name":"example.net","enable_tcp_reuse":true},
          "obfuscation":{"mode":"block","block_size":256},"web_ui":{"pcap_sample_every":8}
        }"""
        val merged = AppConfigManager.mergeEngineConfig(current, json)
        val restored = P2PConfig.fromJson(merged.toExportJson())
        assertEquals("redundant", restored.transportStrategy)
        assertEquals(listOf(endpoint), restored.staticPeers)
        assertTrue(restored.bootstrapPeers.isEmpty())
        assertEquals("exit-peer", restored.exitNode)
        assertEquals(listOf("1.1.1.1"), restored.dnsServers)
        assertEquals("example.net", restored.tlsServerName)
        val engine = JSONObject(restored.toEngineJson("/app/node.key"))
        assertEquals(256, engine.getJSONObject("obfuscation").getInt("block_size"))
        assertEquals(8, engine.getJSONObject("web_ui").getInt("pcap_sample_every"))
        assertEquals("/ip4/0.0.0.0/tcp/5010", engine.getJSONArray("listen_addrs").getString(0))
        assertEquals("/app/node.key", engine.getString("node_key_file"))
    }

    @Test
    fun snapshotDoesNotShareMutablePeerLists() {
        val peers = mutableListOf("old")
        val cfg = P2PConfig(nodeName = "test", staticPeers = peers)
        val snapshot = cfg.snapshot()
        peers.add("new")
        assertEquals(listOf("old"), snapshot.staticPeers)
    }

    @Test
    fun relayUpgradeDurationsSurviveTheEngineJsonRoundTrip() {
        // toEngineJson builds the engine config field by field, so a knob that is
        // not declared there is silently dropped on every save from the phone and
        // Go's Validate resets it to its default. Values go out in nanoseconds to
        // match Go's time.Duration.
        val original = P2PConfig(
            nodeName = "n",
            relayUpgradeInterval = 30_000,
            relayUpgradeMaxBackoff = 1_800_000,
            relayUpgradeFlapWindow = 300_000
        )
        val engine = JSONObject(original.toEngineJson("/app/node.key"))
        assertEquals(30_000_000_000L, engine.getLong("relay_upgrade_interval"))
        assertEquals(1_800_000_000_000L, engine.getLong("relay_upgrade_max_backoff"))
        assertEquals(300_000_000_000L, engine.getLong("relay_upgrade_flap_window"))

        val restored = P2PConfig.fromJson(original.toExportJson())
        assertEquals(30_000L, restored.relayUpgradeInterval)
        assertEquals(1_800_000L, restored.relayUpgradeMaxBackoff)
        assertEquals(300_000L, restored.relayUpgradeFlapWindow)
        assertEquals(1_800_000L, restored.snapshot().relayUpgradeMaxBackoff)
        assertEquals(300_000L, restored.snapshot().relayUpgradeFlapWindow)
    }
}
