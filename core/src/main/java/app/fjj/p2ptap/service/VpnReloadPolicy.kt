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

    // Only fields that applyHotReload genuinely re-applies are excluded below.
    // Everything else needs a full VPN rebuild: applyHotReload publishes the new
    // config snapshot but never touches the libp2p host or its listeners, so a
    // field consumed at host construction keeps its boot value. Getting this list
    // wrong is silent — the change saves, the UI shows the new value, and nothing
    // moves at runtime.
    //
    // Transport switches: there is no RemoveListenAddr anywhere, so turning one
    // off only changes what the node dials, never what it binds. Flipping TCP off
    // would keep the listener bound at startup, which reads as "I disabled it and
    // peers can still connect".
    //
    // disableRelay is read exactly once, when NewNode assembles the libp2p options
    // (EnableRelay, EnableHolePunching, EnableAutoRelayWithStaticRelays) before
    // libp2p.New. Classified HOT, the switch saved, the UI showed it on, and the
    // running host kept its relay client, AutoRelay reservations and DCUtR anyway.
    //
    // tlsServerName / tlsSniSuffix go through p2ptls global setters called once
    // before libp2p.New and cannot be reset in-process. webUiEnable / webUiToken /
    // webUiListenIpv6 need SetupWebUI, which runs only at node creation. enableMdns
    // instantiates the MDNS discovery service once. forcePrivateReachability becomes
    // a libp2p host option. relayUpgradeInterval is read once when the relay-only
    // upgrade loop creates its ticker. holePunchTimeout seeds the libp2p DCUtR
    // DirectDialTimeout, so a change can only be partially applied.
    //
    // What IS excluded: obfuscation (applyObfuscationConfig re-applies the packer),
    // STUN/TURN (resolved per connection), strictKeyNegotiation, discoverBootMesh
    // and allowedSubnetPeers (all read the live snapshot on every use). logLevel
    // and exitNode are excluded too, but they are applied explicitly by the caller
    // through their own JNI calls — not by applyHotReload.
    //
    // Exclude Go-engine-only fields from the comparison.  If ANY non-excluded
    // (VPN-relevant) field differs, a full VPN rebuild is required.
    val vpnOnlyChanged = oldConfig.copy(
        // — VPN-relevant (NOT excluded → RESTART if changed) —
        // tapIp, tapIpv6, mtu, nodeName, acceptSubnets, advertisedSubnets, dnsServers
        // psk, transportStrategy, webUiPort, staticPeers, bootstrapPeers
        // listenAddrs, tapName, tapMac, driverType
        // enableQuic, enableWebrtc, enableWebtransport, enableTcp, disableRelay
        // holePunchTimeout, relayUpgradeInterval, enableMdns
        // tlsServerName, tlsSniSuffix, forcePrivateReachability
        // webUiEnable, webUiToken, webUiListenIpv6
        // exitNodeNatMasq, exitNodeWanInterface
        // aclEnable, aclDefaultAction, aclRulesJson

        // — Go-engine-only (excluded → HOT if changed) —
        engineConfig = newConfig.engineConfig,
        exitNode = newConfig.exitNode,
        logLevel = newConfig.logLevel,
        stunServers = newConfig.stunServers,
        turnServers = newConfig.turnServers,
        obfuscationEnable = newConfig.obfuscationEnable,
        obfuscationMode = newConfig.obfuscationMode,
        obfuscationAlgorithm = newConfig.obfuscationAlgorithm,
        strictKeyNegotiation = newConfig.strictKeyNegotiation,
        discoverBootMesh = newConfig.discoverBootMesh,
        allowedSubnetPeers = newConfig.allowedSubnetPeers,
        // Obfuscation tuning
        obfuscationFixedSize = newConfig.obfuscationFixedSize,
        obfuscationBlockSize = newConfig.obfuscationBlockSize,
        obfuscationJitterRange = newConfig.obfuscationJitterRange,
        obfuscationMinSize = newConfig.obfuscationMinSize,
        obfuscationMaxSize = newConfig.obfuscationMaxSize,
        obfuscationAutoDetectInterval = newConfig.obfuscationAutoDetectInterval,
        obfuscationAutoThresholdBytes = newConfig.obfuscationAutoThresholdBytes,
        obfuscationAllowModeSwitch = newConfig.obfuscationAllowModeSwitch,
        obfuscationMaxFragSize = newConfig.obfuscationMaxFragSize,
    ) != newConfig

    if (vpnOnlyChanged) return VpnReloadPlan.RESTART

    return if (oldConfig != newConfig) {
        VpnReloadPlan.HOT
    } else {
        VpnReloadPlan.NONE
    }
}
