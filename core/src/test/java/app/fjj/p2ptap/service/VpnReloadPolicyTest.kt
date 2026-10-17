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

    @Test
    fun peerListsAndTransportStrategyRestartTheEngine() {
        val old = config()
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(transportStrategy = "redundant")))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(staticPeers = listOf("endpoint"))))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(bootstrapPeers = listOf("endpoint"))))
        // A backup can restore a new identity without changing any JSON fields.
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(), forceRestart = true))
    }

    @Test
    fun transportSwitchesRestartBecauseListenersCannotBeRemovedOnReload() {
        val old = config().copy(enableQuic = true, enableWebrtc = true, enableWebtransport = true, enableTcp = true)
        // applyHotReload publishes the config snapshot but never touches the
        // libp2p listeners, so each switch must force a full rebuild or the
        // listener bound at startup keeps accepting connections.
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(enableTcp = false)))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(enableQuic = false)))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(enableWebrtc = false)))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(enableWebtransport = false)))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(enableWebtransport = false, enableTcp = true)))
    }

    @Test
    fun disableRelayRestartsBecauseLibp2pOptionsAreSetAtHostConstruction() {
        // disable_relay is read once, when NewNode assembles the libp2p options
        // (EnableRelay, EnableHolePunching, EnableAutoRelayWithStaticRelays)
        // before libp2p.New. applyHotReload only swaps the config snapshot and
        // never touches the relay client, so classifying it HOT made the switch
        // save, show as on in the UI, and do nothing at runtime.
        val old = config()
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(disableRelay = true)))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(disableRelay = true, logLevel = "debug")))
    }

    @Test
    fun constructionOnlySettingsRestartBecauseApplyHotReloadNeverRebindsThem() {
        // Each of these is consumed once, when the node is built, so a hot save
        // swaps the config snapshot and changes nothing at runtime. The old
        // classifier kept all eight in the hot-reloadable set, which is why the
        // Android build let users save them and see the toggle move with no
        // effect.
        val old = config()
        // tls_server_name / tls_sni_suffix go through p2ptls global setters
        // called once before libp2p.New; they cannot be reset in-process.
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(tlsServerName = "cdn.example.com")))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(tlsSniSuffix = "cdn.example.com")))
        // SetupWebUI runs only at node creation, so the running server keeps the
        // bearer token it resolved at startup.
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(webUiEnable = true)))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(webUiToken = "rotated-token")))
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(webUiListenIpv6 = "::1")))
        // The MDNS discovery service is instantiated once, gated on the flag.
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(enableMdns = true)))
        // Becomes the libp2p ForceReachabilityPrivate host option.
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(forcePrivateReachability = true)))
        // Read once when the relay-only upgrade loop creates its ticker.
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(relayUpgradeInterval = 60000)))
        // Seeds the libp2p DCUtR DirectDialTimeout, so a change can only be
        // partially applied — restart beats a half-applied value.
        assertEquals(VpnReloadPlan.RESTART, planVpnReload(old, old.copy(holePunchTimeout = 20000)))
    }

    @Test
    fun constructionOnlySettingsRestartEvenAlongsideHotReloadableFields() {
        // A restart-worthy field must not be masked by hot-reloadable changes in
        // the same save; otherwise the restart is skipped.
        val old = config()
        assertEquals(
            VpnReloadPlan.RESTART,
            planVpnReload(old, old.copy(webUiToken = "rotated-token", logLevel = "debug", enableMdns = true))
        )
    }
}
