package app.fjj.p2ptap.service

import org.junit.Assert.*
import org.junit.Test

class P2PStateRepositoryTest {
    @Test fun statusPublishesMessageWithState() {
        P2PStateRepository.updateState("ERROR", "connection failed")
        assertEquals(VpnStatus("ERROR", "connection failed"), P2PStateRepository.status.value)
        P2PStateRepository.updateState("IDLE")
        assertEquals(VpnStatus(), P2PStateRepository.status.value)
    }

    @Test fun stoppingClearsMetricsAndInvalidatesPeerSnapshot() {
        P2PStateRepository.updateState("RUNNING")
        val revision = P2PStateRepository.sessionRevision
        P2PStateRepository.updateMetrics(1, 1, 0, 1024, 2048, 4096, 8192)
        P2PStateRepository.updateState("STOPPING")
        assertEquals(NodeMetrics(), P2PStateRepository.metrics.value)
        P2PStateRepository.updateState("STARTING")
        P2PStateRepository.updateState("RUNNING")
        assertTrue(P2PStateRepository.sessionRevision > revision)
        val peer = PeerItemData("old", "Old", "", "", true, false, "ok", "", "TCP", rtt = 0.0,
            rttMeasured = false, txBytes = 0, rxBytes = 0, os = "", version = "", isExitNode = false)
        P2PStateRepository.updatePeers(listOf(peer), revision)
        assertTrue(P2PStateRepository.peers.value.isEmpty())
        P2PStateRepository.updatePeers(listOf(peer))
        assertEquals(listOf(peer), P2PStateRepository.peers.value)
        P2PStateRepository.updateState("IDLE")
    }
}
