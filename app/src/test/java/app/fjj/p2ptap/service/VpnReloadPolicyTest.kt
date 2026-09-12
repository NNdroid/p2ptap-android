package app.fjj.p2ptap.service

import app.fjj.p2ptap.config.P2PConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnReloadPolicyTest {
    private fun config() = P2PConfig(nodeName = "test-node")

    @Test
    fun unchangedConfigNeedsNoWork() {
        val config = config()
        assertEquals(VpnReloadPlan.NONE, planVpnReload(config, config.copy()))
    }

    @Test
    fun exitTargetAndLogLevelCanHotReload() {
        val old = config().copy(exitNode = "peer-a")
        val updated = old.copy(exitNode = "peer-b", logLevel = "debug")
        assertEquals(VpnReloadPlan.HOT, planVpnReload(old, updated))
    }

    @Test
    fun enteringOrLeavingExitModeRestartsForRouteChanges() {
        val old = config()
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(exitNode = "peer-a")))
    }

    @Test
    fun engineAndTunSettingsRestart() {
        val old = config()
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(psk = "new-key")))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(mtu = 1280)))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(dnsServers = listOf("1.1.1.1"))))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(webUiPort = 18080)))
    }
}
