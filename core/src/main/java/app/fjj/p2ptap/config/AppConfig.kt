package app.fjj.p2ptap.config

import app.fjj.p2ptap.core.R
import app.fjj.p2ptap.i18n.LocalizedException
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import com.p2ptap.P2PTap.P2PTap
import app.fjj.p2ptap.service.P2PTapVpnService

data class P2PConfig(
    var nodeName: String = "",
    var tapIp: String = "",
    var tapIpv6: String = "",
    var mtu: Int = 0,
    var bootstrapPeers: List<String> = emptyList(),
    var staticPeers: List<String> = emptyList(),
    var psk: String = "",
    var enableMdns: Boolean = false,
    var obfuscationEnable: Boolean = false,
    var obfuscationMode: String = "",
    var obfuscationAlgorithm: String = "",
    var strictKeyNegotiation: Boolean = false,
    var enableQuic: Boolean = false,
    var enableWebrtc: Boolean = false,
    var enableWebtransport: Boolean = false,
    var enableTcp: Boolean = false,
    var disableRelay: Boolean = false,
    // The engine's disable_relay is a legacy umbrella covering the two below. These
    // split it so only one side of NAT traversal can be disabled.
    var disableCircuitRelay: Boolean = false,
    var disableHolePunching: Boolean = false,
    var tlsServerName: String = "",
    var tlsSniSuffix: String = "",
    var acceptSubnets: Boolean = false,
    var advertisedSubnets: List<String> = emptyList(),
    var allowedSubnetPeers: List<String> = emptyList(),
    // Must be a valid enum: Go Validate() rejects empty string.
    var transportStrategy: String = "best_path",
    var discoverBootMesh: Boolean = false,
    var exitNode: String = "",
    var webUiEnable: Boolean = false,
    // Android-specific port: Go DefaultConfig uses 80, but Android needs a high port.
    // load() remaps any value outside 1024..65535 to 15858.
    var webUiPort: Int = 15858,
    var webUiToken: String = "",
    var logLevel: String = "",
    var dnsServers: List<String> = emptyList(),
    var holePunchTimeout: Long = 0,
    var stunServers: List<String> = emptyList(),
    var turnServers: List<String> = emptyList(),
    var relayUpgradeInterval: Long = 0,
    var relayUpgradeMaxBackoff: Long = 0,
    var relayUpgradeFlapWindow: Long = 0,
    var forcePrivateReachability: Boolean = false,
    var listenAddrs: List<String> = emptyList(),
    var tapName: String = "",
    var tapMac: String = "",
    var driverType: String = "",
    var webUiListenIpv6: String = "",
    var obfuscationFixedSize: Int = 0,
    var obfuscationBlockSize: Int = 0,
    var obfuscationJitterRange: Int = 0,
    var obfuscationMinSize: Int = 0,
    var obfuscationMaxSize: Int = 0,
    var obfuscationAutoDetectInterval: Int = 0,
    var obfuscationAutoThresholdBytes: Int = 0,
    var obfuscationAllowModeSwitch: Boolean = false,
    var obfuscationMaxFragSize: Int = 0,
    var aclEnable: Boolean = false,
    var aclDefaultAction: String = "",
    var aclRulesJson: String = "",
    // Preserve Go settings that have no native Android editor when saving in WebUI.
    var engineConfig: String = ""
) {
    fun snapshot(): P2PConfig = copy(
        bootstrapPeers = bootstrapPeers.toList(), staticPeers = staticPeers.toList(),
        advertisedSubnets = advertisedSubnets.toList(), allowedSubnetPeers = allowedSubnetPeers.toList(),
        dnsServers = dnsServers.toList(), holePunchTimeout = holePunchTimeout, stunServers = stunServers.toList(),
        turnServers = turnServers.toList(), relayUpgradeInterval = relayUpgradeInterval,
        relayUpgradeMaxBackoff = relayUpgradeMaxBackoff,
        relayUpgradeFlapWindow = relayUpgradeFlapWindow, listenAddrs = listenAddrs.toList()
    )

    fun validateStrategy() {
        if (transportStrategy !in setOf("best_path", "multipath", "redundant", "fallback")) {
            throw LocalizedException(R.string.error_strategy)
        }
    }

    /**
     * Converts to JSON string matching p2ptap domain config schema for Go engine
     */
    fun toJsonString(context: Context): String = toEngineJson(context.getFileStreamPath("node.key").absolutePath)

    internal fun toEngineJson(nodeKeyPath: String): String {
        val root = if (engineConfig.isBlank()) JSONObject() else JSONObject(engineConfig)
        root.put("node_name", nodeName)
        root.put("tap_ip", tapIp)
        if (tapIpv6.isNotBlank()) {
            root.put("tap_ipv6", tapIpv6.trim())
        } else {
            root.put("tap_ipv6", "")
        }
        root.put("mtu", if (mtu in 576..9000) mtu else 1500)
        root.put("psk", psk)
        root.put("log_level", logLevel)
        root.put("enable_mdns", enableMdns)
        root.put("accept_advertised_subnets", acceptSubnets)
        root.put("transport_strategy", transportStrategy)
        root.put("discover_boot_mesh", discoverBootMesh)
        root.put("force_private_reachability", forcePrivateReachability)

        // Load-bearing, not a dead write: pkg/android reads it back out of this
        // JSON at node start (android.go, configureExitNode) to bring up the exit
        // gateway before the first config change. P2PTap.setExitNode only fires on
        // a later runtime change, so without this the app would come up on a cold
        // start with no exit gateway configured at all.
        val targetNode = exitNode.trim()
        root.put("exit_node_peer", targetNode)

        // ExitNode.Enable stays false: pkg/android rejects it outright ("exit node
        // server is not supported on this build"), and Android routes exit traffic
        // through exit_node_peer / setExitNode instead. Enable is the only key the
        // engine reads off this object, so nothing else goes here: nat_masquerade
        // and wan_interface are only honoured when Enable is true
        // (nftables_linux.go guards on !cfg.Enable), and "target" was never a field
        // of ExitNodeConfig at all, so encoding/json silently dropped it.
        root.put("exit_node", JSONObject().put("enable", false))

        // Transports
        val tr = root.optJSONObject("transports") ?: JSONObject()
        tr.put("enable_quic_reuse", enableQuic)
        tr.put("enable_webrtc", enableWebrtc)
        tr.put("enable_webtransport", enableWebtransport)
        tr.put("enable_tcp_reuse", enableTcp)
        tr.put("disable_relay", disableRelay)
        tr.put("disable_circuit_relay", disableCircuitRelay)
        tr.put("disable_hole_punching", disableHolePunching)
        tr.put("tls_server_name", tlsServerName)
        tr.put("tls_sni_suffix", tlsSniSuffix)
        // The engine's TransportsConfig has no tcp_brutal fields — encoding/json
        // drops the unknown keys silently, so writing them produced a setting
        // that saved, exported and round-tripped without ever taking effect.
        root.put("transports", tr)

        // Listen addrs (empty = engine uses defaults)
        if (listenAddrs.isNotEmpty()) {
            val laArray = JSONArray()
            listenAddrs.forEach { laArray.put(it.trim()) }
            root.put("listen_addrs", laArray)
        }

        root.put("tap_name", tapName)
        if (tapMac.isNotBlank()) root.put("tap_mac", tapMac)
        root.put("driver_type", driverType)

        // Store private node key inside app internal storage
        root.put("node_key_file", nodeKeyPath)

        // Bootstrap peers
        val bsArray = JSONArray()
        for (peer in splitPeerAddresses(bootstrapPeers)) {
            val trimmed = peer.trim()
            if (trimmed.isNotEmpty()) {
                bsArray.put(trimmed)
            }
        }
        root.put("bootstrap_peers", bsArray)

        // Static peers
        val stArray = JSONArray()
        for (peer in splitPeerAddresses(staticPeers)) {
            val trimmed = peer.trim()
            if (trimmed.isNotEmpty()) {
                stArray.put(trimmed)
            }
        }
        root.put("static_peers", stArray)

        // Advertised subnets
        val advArray = JSONArray()
        for (subnet in advertisedSubnets) {
            val trimmed = subnet.trim()
            if (trimmed.isNotEmpty()) {
                advArray.put(trimmed)
            }
        }
        root.put("advertised_subnets", advArray)

        // Allowed Subnet Peers
        val aspArray = JSONArray()
        for (p in allowedSubnetPeers) {
            val trimmed = p.trim()
            if (trimmed.isNotEmpty()) {
                aspArray.put(trimmed)
            }
        }
        root.put("allowed_subnet_peers", aspArray)

        // dnsServers is intentionally NOT written here: the engine's Config has no
        // dns_servers field, so encoding/json dropped the key silently. The list
        // is still live — VpnService uses it to addAddress() the DNS servers so
        // their traffic is routed through the tunnel.
        // Hole punching configuration — Go expects time.Duration (int64 nanoseconds)
        root.put("hole_punch_timeout", holePunchTimeout * 1_000_000)
        root.put("relay_upgrade_interval", relayUpgradeInterval * 1_000_000)
        root.put("relay_upgrade_max_backoff", relayUpgradeMaxBackoff * 1_000_000)
        root.put("relay_upgrade_flap_window", relayUpgradeFlapWindow * 1_000_000)
        val stunArray = JSONArray()
        for (server in stunServers) {
            val trimmed = server.trim()
            if (trimmed.isNotEmpty()) {
                stunArray.put(trimmed)
            }
        }
        root.put("stun_servers", stunArray)

        val turnArray = JSONArray()
        for (server in turnServers) {
            val trimmed = server.trim()
            if (trimmed.isNotEmpty()) {
                turnArray.put(trimmed)
            }
        }
        root.put("turn_servers", turnArray)

        // Obfuscation
        val obf = root.optJSONObject("obfuscation") ?: JSONObject()
        obf.put("enable", obfuscationEnable)
        obf.put("mode", obfuscationMode)
        obf.put("algorithm", obfuscationAlgorithm)
        obf.put("strict_key_negotiation", strictKeyNegotiation)
        obf.put("fixed_size", obfuscationFixedSize)
        obf.put("block_size", obfuscationBlockSize)
        obf.put("jitter_range", obfuscationJitterRange)
        obf.put("min_size", obfuscationMinSize)
        obf.put("max_size", obfuscationMaxSize)
        obf.put("auto_detect_interval", obfuscationAutoDetectInterval)
        obf.put("auto_threshold_bytes", obfuscationAutoThresholdBytes)
        obf.put("allow_mode_switch", obfuscationAllowModeSwitch)
        obf.put("max_frag_size", obfuscationMaxFragSize)
        root.put("obfuscation", obf)

        // WebUI config (native HTTP server)
        val webUi = root.optJSONObject("web_ui") ?: JSONObject()
        webUi.put("enable", webUiEnable)
        webUi.put("listen_ip", "127.0.0.1")
        webUi.put("port", webUiPort)
        if (webUiListenIpv6.isNotBlank()) webUi.put("listen_ipv6", webUiListenIpv6)
        if (webUiToken.isNotBlank()) {
            webUi.put("auth_token", webUiToken.trim())
        }
        root.put("web_ui", webUi)

        // ACL
        // Always emit the ACL block — skipping it when disabled leaves a
        // stale "acl" from a previous engineConfig seed, so the engine
        // keeps enforcing rules the user already turned off.
        val aclObj = JSONObject()
        aclObj.put("enable", aclEnable)
        aclObj.put("default_action", aclDefaultAction)
        if (aclRulesJson.isNotBlank()) {
            aclObj.put("rules", JSONArray(aclRulesJson))
        }
        root.put("acl", aclObj)

        return root.toString(2)
    }

    /**
     * Serializes to exportable JSON
     */
    fun toExportJson(): String {
        val root = JSONObject()
        root.put("node_name", nodeName)
        root.put("tap_ip", tapIp)
        root.put("tap_ipv6", tapIpv6)
        root.put("mtu", if (mtu in 576..9000) mtu else 1500)
        root.put("psk", psk)
        root.put("log_level", logLevel)
        root.put("enable_mdns", enableMdns)
        root.put("accept_advertised_subnets", acceptSubnets)
        root.put("transport_strategy", transportStrategy)
        root.put("discover_boot_mesh", discoverBootMesh)
        root.put("force_private_reachability", forcePrivateReachability)
        root.put("exit_node", exitNode)
        root.put("obfuscation_enable", obfuscationEnable)
        root.put("obfuscation_mode", obfuscationMode)
        root.put("obfuscation_algorithm", obfuscationAlgorithm)
        root.put("strict_key_negotiation", strictKeyNegotiation)
        val transports = JSONObject()
        transports.put("enable_quic_reuse", enableQuic)
        transports.put("enable_webrtc", enableWebrtc)
        transports.put("enable_webtransport", enableWebtransport)
        transports.put("enable_tcp_reuse", enableTcp)
        transports.put("disable_relay", disableRelay)
        transports.put("disable_circuit_relay", disableCircuitRelay)
        transports.put("disable_hole_punching", disableHolePunching)
        transports.put("tls_server_name", tlsServerName)
        transports.put("tls_sni_suffix", tlsSniSuffix)
        root.put("transports", transports)
        root.put("webui_enable", webUiEnable)
        root.put("webui_port", webUiPort)
        root.put("webui_token", webUiToken)
        root.put("tap_name", tapName)
        if (tapMac.isNotBlank()) root.put("tap_mac", tapMac)
        root.put("driver_type", driverType)
        if (webUiListenIpv6.isNotBlank()) root.put("webui_listen_ipv6", webUiListenIpv6)
        root.put("acl_enable", aclEnable)
        root.put("acl_default_action", aclDefaultAction)
        if (aclRulesJson.isNotBlank()) root.put("acl_rules", JSONArray(aclRulesJson))
        if (engineConfig.isNotBlank()) root.put("_engine_config", JSONObject(engineConfig))

        val bsArray = JSONArray()
        splitPeerAddresses(bootstrapPeers).forEach { bsArray.put(it) }
        root.put("bootstrap_peers", bsArray)

        val stArray = JSONArray()
        splitPeerAddresses(staticPeers).forEach { stArray.put(it) }
        root.put("static_peers", stArray)

        val advArray = JSONArray()
        advertisedSubnets.forEach { advArray.put(it) }
        root.put("advertised_subnets", advArray)

        val aspArray = JSONArray()
        allowedSubnetPeers.forEach { aspArray.put(it) }
        root.put("allowed_subnet_peers", aspArray)

        val dnsArray = JSONArray()
        dnsServers.forEach { dnsArray.put(it) }
        root.put("dns_servers", dnsArray)

        // CRITICAL: stun_servers was previously missing from export!
        val stunArray = JSONArray()
        stunServers.forEach { stunArray.put(it.trim()) }
        root.put("stun_servers", stunArray)

        val turnArray = JSONArray()
        turnServers.forEach { turnArray.put(it.trim()) }
        root.put("turn_servers", turnArray)

        // Listen addrs
        if (listenAddrs.isNotEmpty()) {
            val laArray = JSONArray()
            listenAddrs.forEach { laArray.put(it.trim()) }
            root.put("listen_addrs", laArray)
        }

        // Durations are emitted in nanoseconds to match toEngineJson and the Go
        // engine. Milliseconds here would round-trip through Android's own
        // readDurationMs but a desktop p2ptap reading the export would see
        // e.g. 60s as 60000ns, which its Validate clamps back to the 15s default —
        // a silent reset of a value the user deliberately set.
        root.put("hole_punch_timeout", holePunchTimeout * 1_000_000)
        root.put("relay_upgrade_interval", relayUpgradeInterval * 1_000_000)
        root.put("relay_upgrade_max_backoff", relayUpgradeMaxBackoff * 1_000_000)
        root.put("relay_upgrade_flap_window", relayUpgradeFlapWindow * 1_000_000)

        // Obfuscation detail fields
        root.put("obfuscation_fixed_size", obfuscationFixedSize)
        root.put("obfuscation_block_size", obfuscationBlockSize)
        root.put("obfuscation_jitter_range", obfuscationJitterRange)
        root.put("obfuscation_min_size", obfuscationMinSize)
        root.put("obfuscation_max_size", obfuscationMaxSize)
        root.put("obfuscation_auto_detect_interval", obfuscationAutoDetectInterval)
        root.put("obfuscation_auto_threshold_bytes", obfuscationAutoThresholdBytes)
        root.put("obfuscation_allow_mode_switch", obfuscationAllowModeSwitch)
        root.put("obfuscation_max_frag_size", obfuscationMaxFragSize)

        return root.toString(2)
    }

    companion object {
        /**
         * Parses from export JSON or standard p2ptap config.json
         */
        @JvmOverloads
        fun fromJson(jsonStr: String, strict: Boolean = true): P2PConfig {
            val root = JSONObject(jsonStr)
            val cfg = P2PConfig()
            cfg.engineConfig = root.optJSONObject("_engine_config")?.toString() ?: ""

            if (root.has("node_name")) cfg.nodeName = root.getString("node_name")
            if (root.has("tap_ip")) cfg.tapIp = root.getString("tap_ip")
            if (root.has("tap_ipv6")) cfg.tapIpv6 = root.getString("tap_ipv6")
            if (root.has("mtu")) cfg.mtu = root.getInt("mtu")
            if (root.has("psk")) cfg.psk = root.getString("psk")
            if (root.has("log_level")) cfg.logLevel = root.getString("log_level")
            if (root.has("enable_mdns")) cfg.enableMdns = root.getBoolean("enable_mdns")
            if (root.has("accept_advertised_subnets")) cfg.acceptSubnets = root.getBoolean("accept_advertised_subnets")
            if (root.has("transport_strategy")) cfg.transportStrategy = root.getString("transport_strategy")
            if (root.has("discover_boot_mesh")) cfg.discoverBootMesh = root.getBoolean("discover_boot_mesh")
            if (root.has("force_private_reachability")) cfg.forcePrivateReachability = root.getBoolean("force_private_reachability")
            if (root.has("tap_name")) cfg.tapName = root.getString("tap_name")
            if (root.has("tap_mac")) cfg.tapMac = root.getString("tap_mac")
            if (root.has("driver_type")) cfg.driverType = root.getString("driver_type")
            if (root.has("exit_node")) {
                val exitVal = root.get("exit_node")
                if (exitVal is JSONObject) {
                    cfg.exitNode = exitVal.optString("target", exitVal.optString("peer_id", ""))
                    // nat_masquerade / wan_interface from an imported desktop
                    // config are ignored on purpose: they only take effect when
                    // exit_node.enable is true, which Android refuses.
                } else if (exitVal is String) {
                    cfg.exitNode = exitVal
                }
            }

            if (root.has("transports")) {
                val tr = root.getJSONObject("transports")
                if (tr.has("enable_quic_reuse")) cfg.enableQuic = tr.getBoolean("enable_quic_reuse")
                if (tr.has("enable_webrtc")) cfg.enableWebrtc = tr.getBoolean("enable_webrtc")
                if (tr.has("enable_webtransport")) cfg.enableWebtransport = tr.getBoolean("enable_webtransport")
                if (tr.has("enable_tcp_reuse")) cfg.enableTcp = tr.getBoolean("enable_tcp_reuse")
                if (tr.has("disable_relay")) cfg.disableRelay = tr.getBoolean("disable_relay")
                if (tr.has("disable_circuit_relay")) cfg.disableCircuitRelay = tr.getBoolean("disable_circuit_relay")
                if (tr.has("disable_hole_punching")) cfg.disableHolePunching = tr.getBoolean("disable_hole_punching")
                if (tr.has("tls_server_name")) cfg.tlsServerName = tr.getString("tls_server_name")
                if (tr.has("tls_sni_suffix")) cfg.tlsSniSuffix = tr.getString("tls_sni_suffix")
            } else {
                if (root.has("enable_quic")) cfg.enableQuic = root.getBoolean("enable_quic")
                if (root.has("enable_webrtc")) cfg.enableWebrtc = root.getBoolean("enable_webrtc")
                if (root.has("enable_webtransport")) cfg.enableWebtransport = root.getBoolean("enable_webtransport")
                if (root.has("enable_tcp")) cfg.enableTcp = root.getBoolean("enable_tcp")
                if (root.has("disable_relay")) cfg.disableRelay = root.getBoolean("disable_relay")
                if (root.has("disable_circuit_relay")) cfg.disableCircuitRelay = root.getBoolean("disable_circuit_relay")
                if (root.has("disable_hole_punching")) cfg.disableHolePunching = root.getBoolean("disable_hole_punching")
                if (root.has("tls_server_name")) cfg.tlsServerName = root.getString("tls_server_name")
                if (root.has("tls_sni_suffix")) cfg.tlsSniSuffix = root.getString("tls_sni_suffix")
            }

            if (root.has("obfuscation")) {
                val obf = root.getJSONObject("obfuscation")
                if (obf.has("enable")) cfg.obfuscationEnable = obf.getBoolean("enable")
                if (obf.has("mode")) cfg.obfuscationMode = obf.getString("mode")
                if (obf.has("algorithm")) cfg.obfuscationAlgorithm = obf.getString("algorithm")
                if (obf.has("strict_key_negotiation")) cfg.strictKeyNegotiation = obf.getBoolean("strict_key_negotiation")
                if (obf.has("fixed_size")) cfg.obfuscationFixedSize = obf.getInt("fixed_size")
                if (obf.has("block_size")) cfg.obfuscationBlockSize = obf.getInt("block_size")
                if (obf.has("jitter_range")) cfg.obfuscationJitterRange = obf.getInt("jitter_range")
                if (obf.has("min_size")) cfg.obfuscationMinSize = obf.getInt("min_size")
                if (obf.has("max_size")) cfg.obfuscationMaxSize = obf.getInt("max_size")
                if (obf.has("auto_detect_interval")) cfg.obfuscationAutoDetectInterval = obf.getInt("auto_detect_interval")
                if (obf.has("auto_threshold_bytes")) cfg.obfuscationAutoThresholdBytes = obf.getInt("auto_threshold_bytes")
                if (obf.has("allow_mode_switch")) cfg.obfuscationAllowModeSwitch = obf.getBoolean("allow_mode_switch")
                if (obf.has("max_frag_size")) cfg.obfuscationMaxFragSize = obf.getInt("max_frag_size")
            } else {
                if (root.has("obfuscation_enable")) cfg.obfuscationEnable = root.getBoolean("obfuscation_enable")
                if (root.has("obfuscation_mode")) cfg.obfuscationMode = root.getString("obfuscation_mode")
                if (root.has("obfuscation_algorithm")) cfg.obfuscationAlgorithm = root.getString("obfuscation_algorithm")
                if (root.has("strict_key_negotiation")) cfg.strictKeyNegotiation = root.getBoolean("strict_key_negotiation")
            }

            if (root.has("web_ui")) {
                val webUi = root.getJSONObject("web_ui")
                if (webUi.has("enable")) cfg.webUiEnable = webUi.getBoolean("enable")
                if (webUi.has("port")) cfg.webUiPort = webUi.getInt("port")
                if (webUi.has("auth_token")) cfg.webUiToken = webUi.getString("auth_token")
                if (webUi.has("listen_ipv6")) cfg.webUiListenIpv6 = webUi.getString("listen_ipv6")
            } else {
                if (root.has("webui_enable")) cfg.webUiEnable = root.getBoolean("webui_enable")
                if (root.has("webui_port")) cfg.webUiPort = root.getInt("webui_port")
                if (root.has("webui_token")) cfg.webUiToken = root.getString("webui_token")
                if (root.has("auth_token")) cfg.webUiToken = root.getString("auth_token")
                if (root.has("webui_listen_ipv6")) cfg.webUiListenIpv6 = root.getString("webui_listen_ipv6")
            }

            if (root.has("bootstrap_peers")) {
                val arr = root.getJSONArray("bootstrap_peers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.bootstrapPeers = splitPeerAddresses(list)
            }

            if (root.has("static_peers")) {
                val arr = root.getJSONArray("static_peers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.staticPeers = splitPeerAddresses(list)
            }

            if (root.has("advertised_subnets")) {
                val arr = root.getJSONArray("advertised_subnets")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.advertisedSubnets = list
            }

            if (root.has("allowed_subnet_peers")) {
                val arr = root.getJSONArray("allowed_subnet_peers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.allowedSubnetPeers = list
            }

            if (root.has("dns_servers")) {
                val arr = root.getJSONArray("dns_servers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.dnsServers = list
            }
            if (root.has("hole_punch_timeout")) {
                cfg.holePunchTimeout = readDurationMs(root, "hole_punch_timeout", 15000)
            }
            if (root.has("relay_upgrade_interval")) {
                cfg.relayUpgradeInterval = readDurationMs(root, "relay_upgrade_interval", 30000)
            }
            if (root.has("relay_upgrade_max_backoff")) {
                cfg.relayUpgradeMaxBackoff = readDurationMs(root, "relay_upgrade_max_backoff", 1_800_000)
            }
            if (root.has("relay_upgrade_flap_window")) {
                cfg.relayUpgradeFlapWindow = readDurationMs(root, "relay_upgrade_flap_window", 300_000)
            }
            if (root.has("stun_servers")) {
                val arr = root.getJSONArray("stun_servers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.stunServers = list
            }
            if (root.has("turn_servers")) {
                val arr = root.getJSONArray("turn_servers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.turnServers = list
            }

            // Listen addrs
            if (root.has("listen_addrs")) {
                val arr = root.getJSONArray("listen_addrs")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.listenAddrs = list
            }

            // Flat key obfuscation detail fields
            if (root.has("obfuscation_fixed_size")) cfg.obfuscationFixedSize = root.getInt("obfuscation_fixed_size")
            if (root.has("obfuscation_block_size")) cfg.obfuscationBlockSize = root.getInt("obfuscation_block_size")
            if (root.has("obfuscation_jitter_range")) cfg.obfuscationJitterRange = root.getInt("obfuscation_jitter_range")
            if (root.has("obfuscation_min_size")) cfg.obfuscationMinSize = root.getInt("obfuscation_min_size")
            if (root.has("obfuscation_max_size")) cfg.obfuscationMaxSize = root.getInt("obfuscation_max_size")
            if (root.has("obfuscation_auto_detect_interval")) cfg.obfuscationAutoDetectInterval = root.getInt("obfuscation_auto_detect_interval")
            if (root.has("obfuscation_auto_threshold_bytes")) cfg.obfuscationAutoThresholdBytes = root.getInt("obfuscation_auto_threshold_bytes")
            if (root.has("obfuscation_allow_mode_switch")) cfg.obfuscationAllowModeSwitch = root.getBoolean("obfuscation_allow_mode_switch")
            if (root.has("obfuscation_max_frag_size")) cfg.obfuscationMaxFragSize = root.getInt("obfuscation_max_frag_size")

            // ACL
            if (root.has("acl")) {
                val aclObj = root.getJSONObject("acl")
                if (aclObj.has("enable")) cfg.aclEnable = aclObj.getBoolean("enable")
                if (aclObj.has("default_action")) cfg.aclDefaultAction = aclObj.getString("default_action")
                if (aclObj.has("rules")) cfg.aclRulesJson = aclObj.getJSONArray("rules").toString()
            } else {
                if (root.has("acl_enable")) cfg.aclEnable = root.getBoolean("acl_enable")
                if (root.has("acl_default_action")) cfg.aclDefaultAction = root.getString("acl_default_action")
                if (root.has("acl_rules")) cfg.aclRulesJson = root.getJSONArray("acl_rules").toString()
            }

            if (strict) cfg.validateStrategy()
            return cfg
        }

        /**
         * Read a duration field that may be stored as:
         *  - milliseconds (Number < 1 000 000) — Android export format
         *  - nanoseconds (Number ≥ 1 000 000) — Go engine / WebUI format
         *  - "Ns" string — legacy standard p2ptap config format
         */
        private fun readDurationMs(root: JSONObject, key: String, defaultMs: Long): Long {
            val raw = root.opt(key) ?: return defaultMs
            return when (raw) {
                is Number -> {
                    val num = raw.toLong()
                    if (num >= 1_000_000) num / 1_000_000 else num
                }
                is String -> {
                    val sec = raw.trim().trimEnd('s', 'S').toLongOrNull()
                    sec?.times(1000) ?: defaultMs
                }
                else -> defaultMs
            }
        }
    }
}

object AppConfigManager {
    private const val PREFS_NAME = "p2ptap_settings"
    private const val KEY_CONFIG_JSON = "full_config_json"

    // Secrets live in their OWN file, never inside the exported config JSON.
    // p2ptap_settings holds mesh topology that is worth restoring from a cloud
    // backup; psk and webui_token are not, and the backup rules exclude both
    // this file and node.key so they never reach Google Auto Backup or a device
    // transfer. The rest of the app only ever sees the merged view from load().
    private const val SECRETS_PREFS = "p2ptap_secrets"
    private const val KEY_PSK = "psk"
    private const val KEY_WEBUI_TOKEN = "webui_token"

    // Minimum length enforced by the engine's validateSecretEntropy for both
    // fields; keep in step with it or fresh installs fail to start.
    private const val SECRET_MIN_LENGTH = 24

    // The token older builds stamped onto a blank webui_token. During the split
    // migration it is treated as "never configured" rather than as a credential
    // worth keeping, so upgrading replaces it with a generated secret.
    private const val LEGACY_DEFAULT_TOKEN = "p2ptap-admin"

    @Volatile
    private var cachedConfig: P2PConfig? = null

    /**
     * Generates a random secret that satisfies the engine's entropy policy
     * (>= SECRET_MIN_LENGTH chars and >= 2 distinct character classes). It is
     * drawn from four disjoint pools so the character-class condition is a
     * matter of construction rather than luck.
     */
    private fun generateSecret(): String {
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val digits = "23456789"
        val sep = "._-+"
        val all = lower + upper + digits + sep
        val rnd = SecureRandom()
        val sb = StringBuilder(SECRET_MIN_LENGTH)
        // One guaranteed pick per class so the >=2-class rule cannot fail.
        sb.append(lower[rnd.nextInt(lower.length)])
        sb.append(upper[rnd.nextInt(upper.length)])
        sb.append(digits[rnd.nextInt(digits.length)])
        sb.append(sep[rnd.nextInt(sep.length)])
        while (sb.length < SECRET_MIN_LENGTH) {
            sb.append(all[rnd.nextInt(all.length)])
        }
        // Shuffle so the class guarantees are not visible at fixed positions.
        val chars = sb.toString().toCharArray()
        for (i in chars.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }

    private fun secretsPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)

    /**
     * Reads the secrets, generating and persisting whichever are missing.
     *
     * `contains()` is the discriminator, not emptiness: the UI can legitimately
     * clear the PSK to run an open mesh, so "the key holds an empty string" is an
     * explicit decision that must survive verbatim, while "the key is absent"
     * means the store has never been seeded for it.
     *
     * `legacyJson` is the pre-split config JSON, which always carried both
     * fields (`toExportJson` emits them unconditionally). Its presence therefore
     * carries the same meaning as an explicit clearing, and a legacy open mesh
     * keeps its empty PSK instead of silently receiving a generated one — which
     * would lock out every peer still running open.
     */
    private fun loadOrMigrateSecrets(
        context: Context,
        legacyPsk: String,
        legacyToken: String,
        legacyJson: JSONObject,
    ): Pair<String, String> {
        val prefs = secretsPrefs(context)
        val psk = if (prefs.contains(KEY_PSK)) {
            prefs.getString(KEY_PSK, "")?.trim().orEmpty()
        } else if (legacyJson.has(KEY_PSK)) {
            legacyPsk.trim()
        } else {
            generateSecret()
        }
        // The old hardcoded default is a known-bad credential, not a user choice.
        val token = if (prefs.contains(KEY_WEBUI_TOKEN)) {
            prefs.getString(KEY_WEBUI_TOKEN, "")?.trim().orEmpty()
        } else if (legacyJson.has(KEY_WEBUI_TOKEN) && legacyToken.trim() != LEGACY_DEFAULT_TOKEN) {
            legacyToken.trim()
        } else {
            generateSecret()
        }
        // Always end with both keys present so the next load reads the store
        // directly instead of re-running the legacy branch.
        if (!prefs.contains(KEY_PSK) || !prefs.contains(KEY_WEBUI_TOKEN)) {
            prefs.edit()
                .putString(KEY_PSK, psk)
                .putString(KEY_WEBUI_TOKEN, token)
                .apply()
        }
        return Pair(psk, token)
    }

    /** Serialises the config for persistence, dropping the secret fields. */
    private fun P2PConfig.toNonSecretJson(): String {
        val root = JSONObject(toExportJson())
        root.remove(KEY_PSK)
        root.remove("webui_token")
        return root.toString(2)
    }

    /**
     * Loads default configuration from the Go engine (p2ptap-core).
     * The Go engine is the single source of truth for all defaults —
     * STUN/TURN servers, transport settings, obfuscation params, etc.
     * Throws if the native call fails: there is no Kotlin fallback.
     */
    private fun loadDefaultConfig(): P2PConfig {
        val goDefaultJson = com.p2ptap.P2PTap.P2PTap.getDefaultConfigJSON()
        if (goDefaultJson.isEmpty()) {
            throw LocalizedException(R.string.error_invalid_configuration)
        }
        val cfg = P2PConfig.fromJson(goDefaultJson, strict = false)
        android.util.Log.i("AppConfig", "Loaded Go engine defaults (${goDefaultJson.length} bytes)")
        return cfg
    }

    fun getNodeKeyPath(context: Context): String {
        return context.getFileStreamPath("node.key").absolutePath
    }

    fun getPeerId(context: Context): String {
        val keyPath = getNodeKeyPath(context)
        val pid = try {
            com.p2ptap.P2PTap.P2PTap.getPeerIDFromKey(keyPath) ?: ""
        } catch (_: Exception) {
            ""
        }
        if (pid.isNotBlank()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("cached_peer_id", pid).apply()
            return pid
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("cached_peer_id", "") ?: ""
    }

    fun exportIdentityKeyBase64(context: Context): String {
        val keyPath = getNodeKeyPath(context)
        return com.p2ptap.P2PTap.P2PTap.exportIdentityKeyBase64(keyPath)
    }

    fun importIdentityKeyBase64(context: Context, base64Key: String): String {
        val keyPath = getNodeKeyPath(context)
        return com.p2ptap.P2PTap.P2PTap.importIdentityKeyBase64(keyPath, base64Key)
    }

    fun generateNewIdentityKey(context: Context): String {
        val keyPath = getNodeKeyPath(context)
        return com.p2ptap.P2PTap.P2PTap.generateNewIdentityKey(keyPath)
    }

    /**
     * Exports a complete backup bundle including node configuration, identity key, and metadata
     */
    fun exportFullBackupBundle(context: Context): String {
        val cfg = load(context)
        val root = JSONObject()
        root.put("bundle_type", "p2ptap_full_backup")
        root.put("bundle_version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val keyPath = getNodeKeyPath(context)
        val peerId = getPeerId(context)
        root.put("peer_id", peerId)

        try {
            val keyB64 = exportIdentityKeyBase64(context)
            root.put("node_key_base64", keyB64)
        } catch (e: Exception) {
            // A keyless backup restores a NEW identity on import, silently
            // dropping the user's mesh membership. Flag it so the UI can
            // warn, and log so the operator knows what happened.
            android.util.Log.w("AppConfig", "Backup contains configuration only; identity key export failed", e)
            root.put("identity_key_export_error", true)
        }

        root.put("config", JSONObject(cfg.toExportJson()))
        return root.toString(2)
    }

    /**
     * Imports a configuration from JSON text (handles both full backup bundles and plain config JSON)
     * Returns Pair(P2PConfig, restoredPeerIdOrNull)
     */
    fun importBackupOrConfig(context: Context, jsonStr: String): Pair<P2PConfig, String?> {
        val trimmed = jsonStr.trim()
        val root = JSONObject(trimmed)

        var restoredPeerId: String? = null

        // Check if this is a full bundle with node_key_base64
        if (root.optString("bundle_type") == "p2ptap_full_backup" || root.has("node_key_base64")) {
            val configObj = root.optJSONObject("config")
            val cfg = if (configObj != null) {
                P2PConfig.fromJson(configObj.toString())
            } else {
                P2PConfig.fromJson(trimmed)
            }
            validate(context, cfg)
            val keyB64 = root.optString("node_key_base64")
            if (keyB64.isNotBlank()) {
                restoredPeerId = importIdentityKeyBase64(context, keyB64)
            }
            save(context, cfg)
            return Pair(cfg, restoredPeerId)
        }

        // Plain config JSON
        val cfg = P2PConfig.fromJson(trimmed)
        save(context, cfg)
        return Pair(cfg, null)
    }

    fun ensureIdentityKey(context: Context) {
        val keyPath = getNodeKeyPath(context)
        val keyFile = java.io.File(keyPath)
        if (!keyFile.exists() || keyFile.length() == 0L) {
            try {
                generateNewIdentityKey(context)
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun load(context: Context): P2PConfig {
        cachedConfig?.let { return it.snapshot() }
        ensureIdentityKey(context)
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONFIG_JSON, null)
        val cfg = if (!jsonStr.isNullOrEmpty()) {
            try {
                // Keep legacy invalid values visible in the editor. Saving and
                // starting still validate; do not replace an invalid strategy
                // with a different mesh's default configuration.
                val parsed = P2PConfig.fromJson(jsonStr, strict = false)
                var changed = false
                if (parsed.tapIpv6.isBlank()) {
                    val lastOctet = parsed.tapIp.substringBefore("/").substringAfterLast(".", "88")
                    parsed.tapIpv6 = "fd00::$lastOctet/64"
                    changed = true
                }
                // Ports below 1024 need CAP_NET_BIND_SERVICE, which Android does
                // not grant, so they can never bind — remap those. Every other
                // value is honoured, including 8080: rewriting a port the user
                // deliberately chose and saving the rewrite back made the editor
                // silently disagree with what the operator asked for.
                if (parsed.webUiPort !in 1024..65535) {
                    parsed.webUiPort = 15858
                    changed = true
                }
                if (changed) {
                    try {
                        // Bookkeeping only: the secrets have not been resolved yet,
                        // so this must not touch the secrets store.
                        save(context, parsed, persistSecrets = false)
                    } catch (e: Exception) {
                        android.util.Log.w("AppConfig", "Stored configuration needs correction", e)
                    }
                }
                parsed
            } catch (e: Exception) {
                android.util.Log.w("AppConfig", "Stored configuration unreadable; falling back to engine defaults", e)
                val defaultCfg = loadDefaultConfig()
                try { save(context, defaultCfg, persistSecrets = false) } catch (_: Exception) { }
                defaultCfg
            }
        } else {
            val defaultCfg = loadDefaultConfig()
            // Secrets are not seeded yet, so the JSON is written without them and
            // the store is left for loadOrMigrateSecrets below to fill in.
            save(context, defaultCfg, persistSecrets = false)
            defaultCfg
        }
        // Secrets are merged before the cached snapshot is taken so save() never
        // has to guess whether an empty value means "cleared" or "unseeded" —
        // the store's contains() bit is the only thing that can say.
        val (psk, token) = loadOrMigrateSecrets(
            context, cfg.psk, cfg.webUiToken,
            if (jsonStr.isNullOrEmpty()) JSONObject() else JSONObject(jsonStr)
        )
        cfg.psk = psk
        cfg.webUiToken = token
        cachedConfig = cfg.snapshot()
        return cfg.snapshot()
    }

    fun validate(context: Context, config: P2PConfig) {
        config.validateStrategy()
        P2PTap.validateConfig(config.toJsonString(context))
    }

    fun normalizePeerAddresses(addresses: List<String>): List<String> = splitPeerAddresses(addresses).mapIndexed { index, address ->
        try { P2PTap.normalizePeerAddress(address.trim()) }
        catch (e: Exception) { throw LocalizedException(R.string.error_peer_address_fmt, listOf(index + 1), e) }
    }.distinct()

    @Synchronized
    fun save(context: Context, config: P2PConfig, persistSecrets: Boolean = true) {
        val candidate = config.snapshot().apply {
            staticPeers = normalizePeerAddresses(staticPeers)
            bootstrapPeers = normalizePeerAddresses(bootstrapPeers)
        }
        validate(context, candidate)
        // Secrets go to their own, backup-excluded prefs file; the exported
        // JSON never carries them, so restoring p2ptap_settings from a cloud
        // backup cannot resurrect a credential into this device.
        //
        // persistSecrets=false is for load()'s own bookkeeping saves: those run
        // before the secrets are resolved, so candidate.psk is still the JSON's
        // empty value and writing it would stamp the store as deliberately
        // cleared, suppressing the first-run generation.
        if (persistSecrets) {
            secretsPrefs(context).edit()
                .putString(KEY_PSK, candidate.psk)
                .putString(KEY_WEBUI_TOKEN, candidate.webUiToken)
                .apply()
        }
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // apply() queues the write without blocking the caller — commit()
        // performs a synchronous disk I/O that caused ANR on locked-down
        // set-top-box hardware when called from the main thread.
        prefs.edit().putString(KEY_CONFIG_JSON, candidate.toNonSecretJson()).apply()
        cachedConfig = candidate.snapshot()
    }

    // The engine's exit_node is a server setting; the app separately owns its
    // selected exit peer and Android DNS. Do not overwrite those on WebUI save.
    internal fun mergeEngineConfig(current: P2PConfig, json: String): P2PConfig =
        P2PConfig.fromJson(json).apply {
            exitNode = current.exitNode
            dnsServers = current.dnsServers.toList()
            engineConfig = json
        }

    @Synchronized
    fun saveEngineConfig(context: Context, json: String) {
        P2PTap.validateConfig(json)
        save(context, mergeEngineConfig(load(context), json))
    }

    fun reloadRunningService(context: Context, forceRestart: Boolean = false) {
        if (!P2PTapVpnService.isRunning()) return
        val intent = android.content.Intent(context, P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_RELOAD
            putExtra(P2PTapVpnService.EXTRA_FORCE_RESTART, forceRestart)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }
}
