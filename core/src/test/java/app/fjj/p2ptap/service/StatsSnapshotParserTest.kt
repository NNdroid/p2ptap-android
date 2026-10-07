package app.fjj.p2ptap.service

import org.junit.Assert.*
import org.junit.Test

class StatsSnapshotParserTest {
    @Test fun parsesEngineCountersAndNegotiatedSecurityWithoutMixingRemoteTotals() {
        val snapshot = StatsSnapshotParser.parse("""{
            "packet_stats":{"bytes_sent":8192,"dispatch_drops":3,"dedup_count":7},
            "speed":{"tx_bytes_per_sec":512}, "protocol_stats":{"ndp":19,"tcp":12},
            "seq_stats":{"replay_drops":5},
            "active_peers":[{"peer_id":"peerA","conn_state":"relay_ok","is_relayed":true,
                "total_tx":999999,"total_rx":888888,"link_total_tx":120,"link_total_rx":60,
                "tx_speed":25,"rx_speed":10,"link_speed_measured":true,
                "rtt_ms":0.4,"rtt_measured":true,"rtt_source":"tap-icmp"}],
            "security":{"obfuscation":"auto","psk_status":"disabled","encryption":[
                {"peer_id":"peerA","negotiated":true,"encrypted":true,"algo":"aes-gcm","pfs":false}]},
            "exit_node":{"active_peer_id":"peerA","active_exit_tap_ipv6":"fd00::1"}
        }""")
        assertEquals(8192L, snapshot.counters["bytes_sent"])
        assertEquals(7L, snapshot.counters["dedup_count"])
        assertEquals(5L, snapshot.counters["replay_drops"])
        assertEquals(19L, snapshot.counters["ndp"])
        val peer = snapshot.peers.single()
        assertEquals(120L, peer.txBytes)
        assertEquals(999999L, peer.remoteTxBytes)
        assertEquals(25L, peer.txSpeed)
        assertTrue(peer.linkTrafficAvailable)
        assertTrue(peer.rttMeasured)
        assertEquals(0.4, peer.rtt, 0.0001)
        assertFalse(peer.isDirect)
        assertTrue(peer.isRelayed)
        assertEquals("aes-gcm", snapshot.encryption.single().algorithm)
        assertEquals(false, snapshot.encryption.single().pfs)
        assertTrue(snapshot.matchesExit("fd00::1"))
        assertTrue(snapshot.matchesExit("fd00:0:0:0:0:0:0:1"))
        assertFalse(snapshot.matchesExit("/24"))
        assertFalse(snapshot.matchesExit(""))
        assertFalse(snapshot.matchesExit("peerB"))
    }

    @Test fun unknownValuesStayUnknownAndMetadataSuppliesMissingIdentity() {
        val snapshot = StatsSnapshotParser.parse("""{"packet_stats":{},"active_peers":[
            {"peer_id":"peerA","node_name":null,"rtt_ms":10,"rtt_measured":false,
                "addr":"unknown","all_addrs":["/ip4/192.0.2.1/tcp/1234"]}],
            "peer_metas":[{"peer_id":"peerA","node_name":"Gateway","tap_ipv6":"fd00::1/64",
                "os_arch":"linux/arm64","is_exit_node":true}] }""")
        val peer = snapshot.peers.single()
        assertEquals("Gateway", peer.nodeName)
        assertEquals("fd00::1/64", peer.tapIpv6)
        assertEquals("linux/arm64", peer.os)
        assertTrue(peer.isExitNode)
        assertFalse(peer.rttMeasured)
        assertFalse(peer.linkTrafficAvailable)
        assertNull(peer.txSpeed)
        assertNull(snapshot.counters["tcp"])
        assertEquals("", snapshot.pskStatus)
        assertTrue(snapshot.encryption.isEmpty())
        assertEquals("/ip4/192.0.2.1/tcp/1234", peer.multiaddr)
    }

    @Test fun rejectsMissingEngineSnapshot() {
        assertThrows(IllegalArgumentException::class.java) { StatsSnapshotParser.parse("{}") }
    }

    @Test fun stoppingAndRefreshFailureClearEverySheetAndRejectOldSessions() {
        P2PStateRepository.updateState("IDLE")
        P2PStateRepository.updateState("RUNNING")
        val revision = P2PStateRepository.sessionRevision
        val snapshot = StatsSnapshotParser.parse("""{"packet_stats":{},"active_peers":[]}""")
        assertTrue(P2PStateRepository.beginRefresh(revision))
        assertTrue(P2PStateRepository.finishRefresh(snapshot, revision))
        assertNotNull(P2PStateRepository.telemetry.value.snapshot)
        P2PStateRepository.finishRefresh(null, revision)
        assertTrue(P2PStateRepository.telemetry.value.failed)
        assertNull(P2PStateRepository.telemetry.value.snapshot)
        P2PStateRepository.updateState("STOPPING")
        P2PStateRepository.updateState("RUNNING")
        assertFalse(P2PStateRepository.finishRefresh(snapshot, revision))
        assertEquals(TelemetryState(), P2PStateRepository.telemetry.value)
        P2PStateRepository.updateState("IDLE")
    }
}
