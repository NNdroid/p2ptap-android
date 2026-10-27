package app.fjj.p2ptap.service

import android.content.Context
import app.fjj.p2ptap.config.P2PConfig
import com.p2ptap.P2PTap.P2PTap

enum class VpnReloadPlan {
    NONE,
    HOT,
    RESTART
}

/**
 * Asks the engine whether replacing [oldJson] with [newJson] needs the node
 * rebuilt. Returns "noop" | "reload" | "restart", or null when the engine could
 * not be asked — no engine running, or a stale AAR that predates the PlanReload
 * export. Injectable so unit tests can stub the JNI boundary without an Android
 * device; the try/catch is what makes a missing native symbol degrade to null
 * instead of throwing UnsatisfiedLinkError.
 */
internal var askEngine: (String?, String) -> String? = { old, new ->
    try {
        P2PTap.planReload(old ?: "", new)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Classifies a config change as NONE (no-op), HOT (Go-engine reload, no VPN
 * rebuild), or RESTART (full VpnService + engine teardown/rebuild).
 *
 * Which fields force a RESTART is decided by the Go engine, not here:
 * P2PTap.planReload delegates to config.RestartRequiredFields, the single
 * definition in the engine. Keeping a hand-written copy of that list in Kotlin
 * is what made this file drift — it demanded a restart for ACL and
 * advertised-subnet changes that the engine demonstrably hot-reloads through
 * its OnSubnetsChanged callback, so those settings restarted the VPN for no
 * reason.
 *
 * What stays here is genuinely Android-owned and invisible to the engine:
 * toggling exit-node mode rewrites the TUN default routes, and dnsServers is
 * applied by the VpnService rather than the engine.
 *
 * @param ctx Needed to serialise the two configs for the engine. Omitted by
 *   unit tests, which then exercise [planVpnReloadLocal].
 */
fun planVpnReload(
    oldConfig: P2PConfig?,
    newConfig: P2PConfig,
    forceRestart: Boolean = false,
    ctx: Context? = null,
): VpnReloadPlan {
    val verdict = if (ctx == null) {
        null
    } else {
        try {
            askEngine(oldConfig?.toJsonString(ctx), newConfig.toJsonString(ctx))
        } catch (_: Throwable) {
            null
        }
    }
    return decide(oldConfig, newConfig, forceRestart, verdict)
}

/**
 * Pure decision: no JNI, no Context, so it is unit-testable on its own.
 * [engineVerdict] is what askEngine reported, or null when the engine could not
 * be reached.
 */
internal fun decide(
    oldConfig: P2PConfig?,
    newConfig: P2PConfig,
    forceRestart: Boolean,
    engineVerdict: String?,
): VpnReloadPlan {
    if (forceRestart) return VpnReloadPlan.RESTART
    if (oldConfig == null) return VpnReloadPlan.RESTART

    // Android-owned: exit-node mode rewrites the TUN default routes, and
    // dnsServers is applied by the VpnService, so neither reaches the engine.
    if (oldConfig.exitNode.isBlank() != newConfig.exitNode.isBlank()) return VpnReloadPlan.RESTART

    // A peer→peer exit switch (both non-blank, different) changes only which
    // gateway the engine routes through — never the TUN default routes — so it
    // is a Go-engine hot reload, not a VPN rebuild. The engine reports "noop"
    // for it because exit_node_peer is not a Config field; without this the
    // switch would be silently dropped. Classify it here, before the engine
    // verdict, so it does not depend on which AAR is installed.
    if (oldConfig.exitNode != newConfig.exitNode) return VpnReloadPlan.HOT

    if (oldConfig.dnsServers != newConfig.dnsServers) return VpnReloadPlan.RESTART

    if (engineVerdict == null) return planVpnReloadLocal(oldConfig, newConfig)

    return when (engineVerdict) {
        "restart" -> VpnReloadPlan.RESTART
        "reload" -> VpnReloadPlan.HOT
        "noop" -> VpnReloadPlan.NONE
        // An unrecognised verdict is not grounds to believe a change is safe to
        // hot-reload.
        else -> VpnReloadPlan.RESTART
    }
}

/**
 * Local fallback for when the engine cannot be asked. Deliberately kept — it
 * is the second copy this file is meant to outgrow, and it errs safe: it
 * classifies as RESTART at least as often as the engine does, so a stale AAR
 * produces needless VPN rebuilds rather than silently-unapplied settings. New
 * restart-required fields go into the engine's RestartRequiredFields, never
 * here.
 */
internal fun planVpnReloadLocal(
    oldConfig: P2PConfig,
    newConfig: P2PConfig,
): VpnReloadPlan {
    // Only fields that applyHotReload genuinely re-applies are excluded below.
    // Everything else needs a full VPN rebuild: applyHotReload publishes the
    // new config snapshot but never touches the libp2p host or its listeners,
    // so a field consumed at host construction keeps its boot value. Getting
    // this list wrong is silent — the change saves, the UI shows the new value,
    // and nothing moves at runtime.
    //
    // Transport switches: there is no RemoveListenAddr anywhere, so turning one
    // off only changes what the node dials, never what it binds. Flipping TCP
    // off would keep the listener bound at startup, which reads as "I disabled
    // it and peers can still connect".
    //
    // The relay flags are read exactly once, when NewNode assembles the libp2p
    // options (EnableRelay, EnableHolePunching,
    // EnableAutoRelayWithStaticRelays) before libp2p.New. Classified HOT, the
    // switch saved, the UI showed it on, and the running host kept its relay
    // client, AutoRelay reservations and DCUtR anyway.
    //
    // tlsServerName / tlsSniSuffix go through p2ptls global setters called once
    // before libp2p.New and cannot be reset in-process. webUiEnable /
    // webUiToken / webUiListenIpv6 need SetupWebUI, which runs only at node
    // creation. enableMdns instantiates the MDNS discovery service once.
    // forcePrivateReachability becomes a libp2p host option.
    // relayUpgradeInterval is read once when the relay-only upgrade loop
    // creates its ticker. holePunchTimeout seeds the libp2p DCUtR
    // DirectDialTimeout, so a change can only be partially applied.
    //
    // The three relay-upgrade knobs are deliberately classified differently.
    // relay_upgrade_max_backoff and relay_upgrade_flap_window are read from the
    // live config snapshot on every loop pass, so a hot save takes effect on the
    // next attempt. Their sibling relay_upgrade_interval is frozen at loop start
    // because time.NewTicker cannot be resized; changing it mid-run would be
    // silently ignored until the node restarts. If the interval ever stops being
    // frozen (e.g. the loop is rewritten to recreate its ticker), the Go-side
    // restart-required test and this list must be updated together — the pair
    // must not be "tidied" in sync.
    //
    // What IS excluded: obfuscation (applyObfuscationConfig re-applies the
    // packer), STUN/TURN (resolved per connection), strictKeyNegotiation,
    // discoverBootMesh and allowedSubnetPeers (all read the live snapshot on
    // every use). logLevel and exitNode are excluded too, but they are applied
    // explicitly by the caller through their own JNI calls — not by
    // applyHotReload.
    val vpnOnlyChanged = oldConfig.copy(
        // — VPN-relevant (NOT excluded → RESTART if changed) —
        // tapIp, tapIpv6, mtu, nodeName, acceptSubnets, advertisedSubnets,
        // dnsServers, psk, transportStrategy, webUiPort, staticPeers,
        // bootstrapPeers, listenAddrs, tapName, tapMac, driverType,
        // enableQuic, enableWebrtc, enableWebtransport, enableTcp,
        // disableRelay, disableCircuitRelay, disableHolePunching,
        // holePunchTimeout, relayUpgradeInterval, enableMdns,
        // tlsServerName, tlsSniSuffix, forcePrivateReachability,
        // webUiEnable, webUiToken, webUiListenIpv6,
        // aclEnable, aclDefaultAction, aclRulesJson

        // — Go-engine-only (excluded → HOT if changed) —
        engineConfig = newConfig.engineConfig,
        exitNode = newConfig.exitNode,
        logLevel = newConfig.logLevel,
        stunServers = newConfig.stunServers,
        turnServers = newConfig.turnServers,
        relayUpgradeMaxBackoff = newConfig.relayUpgradeMaxBackoff,
        relayUpgradeFlapWindow = newConfig.relayUpgradeFlapWindow,
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
