package app.fjj.p2ptap.service

import app.fjj.p2ptap.config.P2PConfig

enum class VpnReloadPlan {
    NONE,
    HOT,
    RESTART
}

/**
 * Classifies a config change as NONE (no-op), HOT (Go-engine reload, no VPN
 * rebuild), or RESTART (full VpnService + engine teardown/rebuild).
 *
 * Fields excluded from the equality check below are HOT-reloadable via
 * P2PTap.applyHotReload().  Fields NOT excluded are VPN-relevant and
 * require a full VPN rebuild.
 */
fun planVpnReload(oldConfig: P2PConfig?, newConfig: P2PConfig, forceRestart: Boolean = false): VpnReloadPlan {
    if (forceRestart) return VpnReloadPlan.RESTART
    if (oldConfig == null) return VpnReloadPlan.RESTART

    // Exit-node mode change (empty ↔ non-empty) toggles default routes → RESTART.
    val exitModeChanged = oldConfig.exitNode.isBlank() != newConfig.exitNode.isBlank()
    if (exitModeChanged) return VpnReloadPlan.RESTART

    // Exclude Go-engine-only fields from the comparison.  If ANY non-excluded
    // (VPN-relevant) field differs, a full VPN rebuild is required.
    val vpnOnlyChanged = oldConfig.copy(
        // — VPN-relevant (NOT excluded → RESTART if changed) —
        // tapIp, tapIpv6, mtu, nodeName, acceptSubnets, advertisedSubnets, dnsServers

        // — Go-engine-only (excluded → HOT if changed) —
        exitNode = newConfig.exitNode,
        logLevel = newConfig.logLevel,
        holePunchTimeout = newConfig.holePunchTimeout,
        stunServers = newConfig.stunServers,
        relayUpgradeInterval = newConfig.relayUpgradeInterval,
        obfuscationEnable = newConfig.obfuscationEnable,
        obfuscationMode = newConfig.obfuscationMode,
        obfuscationAlgorithm = newConfig.obfuscationAlgorithm,
        strictKeyNegotiation = newConfig.strictKeyNegotiation,
        discoverBootMesh = newConfig.discoverBootMesh,
        allowedSubnetPeers = newConfig.allowedSubnetPeers,
        enableMdns = newConfig.enableMdns,
        psk = newConfig.psk,
        transportStrategy = newConfig.transportStrategy,
        enableQuic = newConfig.enableQuic,
        enableWebrtc = newConfig.enableWebrtc,
        enableWebtransport = newConfig.enableWebtransport,
        enableTcp = newConfig.enableTcp,
        disableRelay = newConfig.disableRelay,
        tlsServerName = newConfig.tlsServerName,
        tlsSniSuffix = newConfig.tlsSniSuffix,
        webUiEnable = newConfig.webUiEnable,
        webUiPort = newConfig.webUiPort,
        webUiToken = newConfig.webUiToken,
    ) != newConfig

    if (vpnOnlyChanged) return VpnReloadPlan.RESTART

    return if (oldConfig != newConfig) {
        VpnReloadPlan.HOT
    } else {
        VpnReloadPlan.NONE
    }
}
