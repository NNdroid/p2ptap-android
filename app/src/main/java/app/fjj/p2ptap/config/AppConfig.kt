package app.fjj.p2ptap.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class P2PConfig(
    var nodeName: String = "Android-" + Build.MODEL.replace(" ", "-").take(14),
    var tapIp: String = "10.0.0.88/24",
    var tapIpv6: String = "fd00::88/64",
    var mtu: Int = 1500,
    var bootstrapPeers: List<String> = listOf(
        "/dnsaddr/bootstrap.libp2p.io/p2p/QmNnooDu7bfjPFoTmoXMY5PeBKyy1EicV2g7HQ1b18423b",
        "/dnsaddr/bootstrap.libp2p.io/p2p/QmQCUEtxvUDMfWiStEwcBsQ55nsnH7tKNiLB5ns3RNr41J",
        "/dnsaddr/bootstrap.libp2p.io/p2p/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt",
        "/dnsaddr/bootstrap.libp2p.io/p2p/QmQCU2EcMqAqQPR2i9bChDtGNJchTbq5TbXJJ16u19uLTa"
    ),
    var staticPeers: List<String> = listOf(),
    var psk: String = "",
    var enableMdns: Boolean = true,
    var obfuscationEnable: Boolean = true,
    var obfuscationMode: String = "auto",
    var obfuscationAlgorithm: String = "auto",
    var strictKeyNegotiation: Boolean = false,
    var enableQuic: Boolean = true,
    var enableWebrtc: Boolean = true,
    var enableWebtransport: Boolean = true,
    var enableTcp: Boolean = true,
    var disableRelay: Boolean = false,
    var acceptSubnets: Boolean = true,
    var advertisedSubnets: List<String> = listOf(),
    var allowedSubnetPeers: List<String> = listOf("*"),
    var transportStrategy: String = "best_path",
    var discoverBootMesh: Boolean = true,
    var exitNode: String = "",
    var webUiEnable: Boolean = true,
    var webUiPort: Int = 15858,
    var webUiToken: String = "p2ptap-admin",
    var logLevel: String = "info",
    var dnsServers: List<String> = listOf()
) {
    /**
     * Converts to JSON string matching p2ptap domain config schema for Go engine
     */
    fun toJsonString(context: Context): String {
        val root = JSONObject()
        root.put("node_name", nodeName)
        root.put("tap_ip", tapIp)
        if (tapIpv6.isNotBlank()) {
            root.put("tap_ipv6", tapIpv6.trim())
        }
        root.put("mtu", mtu)
        root.put("psk", psk)
        root.put("log_level", logLevel)
        root.put("enable_mdns", enableMdns)
        root.put("accept_advertised_subnets", acceptSubnets)
        root.put("transport_strategy", transportStrategy)
        root.put("discover_boot_mesh", discoverBootMesh)

        val targetNode = exitNode.trim()
        if (targetNode.isNotBlank()) {
            root.put("exit_node_peer", targetNode)
            root.put("exit_gateway", targetNode)
        } else {
            root.put("exit_node_peer", "")
            root.put("exit_gateway", "")
        }

        val exitNodeObj = JSONObject()
        exitNodeObj.put("enable", false)
        exitNodeObj.put("target", targetNode)
        root.put("exit_node", exitNodeObj)

        // Transports
        val tr = JSONObject()
        tr.put("enable_quic_reuse", enableQuic)
        tr.put("enable_webrtc", enableWebrtc)
        tr.put("enable_webtransport", enableWebtransport)
        tr.put("enable_tcp_reuse", enableTcp)
        tr.put("disable_relay", disableRelay)
        root.put("transports", tr)

        // Store private node key inside app internal storage
        val keyFile = context.getFileStreamPath("node.key").absolutePath
        root.put("node_key_file", keyFile)

        // Bootstrap peers
        val bsArray = JSONArray()
        for (peer in bootstrapPeers) {
            val trimmed = peer.trim()
            if (trimmed.isNotEmpty()) {
                bsArray.put(trimmed)
            }
        }
        root.put("bootstrap_peers", bsArray)

        // Static peers
        val stArray = JSONArray()
        for (peer in staticPeers) {
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

        // DNS Servers
        val dnsArray = JSONArray()
        for (dns in dnsServers) {
            val trimmed = dns.trim()
            if (trimmed.isNotEmpty()) {
                dnsArray.put(trimmed)
            }
        }
        root.put("dns_servers", dnsArray)

        // Obfuscation
        val obf = JSONObject()
        obf.put("enable", obfuscationEnable)
        obf.put("mode", obfuscationMode)
        obf.put("algorithm", obfuscationAlgorithm)
        obf.put("strict_key_negotiation", strictKeyNegotiation)
        root.put("obfuscation", obf)

        // WebUI config (native HTTP server)
        val webUi = JSONObject()
        webUi.put("enable", webUiEnable)
        webUi.put("listen_ip", "127.0.0.1")
        webUi.put("port", webUiPort)
        if (webUiToken.isNotBlank()) {
            webUi.put("auth_token", webUiToken.trim())
        }
        root.put("web_ui", webUi)

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
        root.put("mtu", mtu)
        root.put("psk", psk)
        root.put("log_level", logLevel)
        root.put("enable_mdns", enableMdns)
        root.put("accept_advertised_subnets", acceptSubnets)
        root.put("transport_strategy", transportStrategy)
        root.put("discover_boot_mesh", discoverBootMesh)
        root.put("exit_node", exitNode)
        root.put("obfuscation_enable", obfuscationEnable)
        root.put("obfuscation_mode", obfuscationMode)
        root.put("obfuscation_algorithm", obfuscationAlgorithm)
        root.put("strict_key_negotiation", strictKeyNegotiation)
        root.put("enable_quic", enableQuic)
        root.put("enable_webrtc", enableWebrtc)
        root.put("enable_webtransport", enableWebtransport)
        root.put("enable_tcp", enableTcp)
        root.put("disable_relay", disableRelay)
        root.put("webui_enable", webUiEnable)
        root.put("webui_port", webUiPort)
        root.put("webui_token", webUiToken)

        val bsArray = JSONArray()
        bootstrapPeers.forEach { bsArray.put(it) }
        root.put("bootstrap_peers", bsArray)

        val stArray = JSONArray()
        staticPeers.forEach { stArray.put(it) }
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

        return root.toString(2)
    }

    companion object {
        /**
         * Parses from export JSON or standard p2ptap config.json
         */
        fun fromJson(jsonStr: String): P2PConfig {
            val root = JSONObject(jsonStr)
            val cfg = P2PConfig()

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
            if (root.has("exit_node")) {
                val exitVal = root.get("exit_node")
                if (exitVal is JSONObject) {
                    cfg.exitNode = exitVal.optString("target", exitVal.optString("peer_id", ""))
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
            } else {
                if (root.has("enable_quic")) cfg.enableQuic = root.getBoolean("enable_quic")
                if (root.has("enable_webrtc")) cfg.enableWebrtc = root.getBoolean("enable_webrtc")
                if (root.has("enable_webtransport")) cfg.enableWebtransport = root.getBoolean("enable_webtransport")
                if (root.has("enable_tcp")) cfg.enableTcp = root.getBoolean("enable_tcp")
                if (root.has("disable_relay")) cfg.disableRelay = root.getBoolean("disable_relay")
            }

            if (root.has("obfuscation")) {
                val obf = root.getJSONObject("obfuscation")
                if (obf.has("enable")) cfg.obfuscationEnable = obf.getBoolean("enable")
                if (obf.has("mode")) cfg.obfuscationMode = obf.getString("mode")
                if (obf.has("algorithm")) cfg.obfuscationAlgorithm = obf.getString("algorithm")
                if (obf.has("strict_key_negotiation")) cfg.strictKeyNegotiation = obf.getBoolean("strict_key_negotiation")
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
            } else {
                if (root.has("webui_enable")) cfg.webUiEnable = root.getBoolean("webui_enable")
                if (root.has("webui_port")) cfg.webUiPort = root.getInt("webui_port")
                if (root.has("webui_token")) cfg.webUiToken = root.getString("webui_token")
                if (root.has("auth_token")) cfg.webUiToken = root.getString("auth_token")
            }

            if (root.has("bootstrap_peers")) {
                val arr = root.getJSONArray("bootstrap_peers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.bootstrapPeers = list
            }

            if (root.has("static_peers")) {
                val arr = root.getJSONArray("static_peers")
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cfg.staticPeers = list
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

            return cfg
        }
    }
}

object AppConfigManager {
    private const val PREFS_NAME = "p2ptap_settings"
    private const val KEY_CONFIG_JSON = "full_config_json"

    @Volatile
    private var cachedConfig: P2PConfig? = null

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
        } catch (_: Exception) {}

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
            val keyB64 = root.optString("node_key_base64")
            if (keyB64.isNotBlank()) {
                restoredPeerId = importIdentityKeyBase64(context, keyB64)
            }
            val configObj = root.optJSONObject("config")
            val cfg = if (configObj != null) {
                P2PConfig.fromJson(configObj.toString())
            } else {
                P2PConfig.fromJson(trimmed)
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

    fun load(context: Context): P2PConfig {
        cachedConfig?.let { return it }
        ensureIdentityKey(context)
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONFIG_JSON, null)
        val cfg = if (!jsonStr.isNullOrEmpty()) {
            try {
                val parsed = P2PConfig.fromJson(jsonStr)
                var changed = false
                if (parsed.tapIpv6.isBlank()) {
                    val lastOctet = parsed.tapIp.substringBefore("/").substringAfterLast(".", "88")
                    parsed.tapIpv6 = "fd00::$lastOctet/64"
                    changed = true
                }
                if (parsed.bootstrapPeers.isEmpty()) {
                    parsed.bootstrapPeers = P2PConfig().bootstrapPeers
                    changed = true
                }
                if (parsed.webUiToken.isBlank()) {
                    parsed.webUiToken = "p2ptap-admin"
                    changed = true
                }
                if (parsed.webUiPort <= 0 || parsed.webUiPort == 8080) {
                    parsed.webUiPort = 15858
                    changed = true
                }
                if (changed) {
                    save(context, parsed)
                }
                parsed
            } catch (_: Exception) {
                P2PConfig()
            }
        } else {
            val defaultCfg = P2PConfig()
            save(context, defaultCfg)
            defaultCfg
        }
        cachedConfig = cfg
        return cfg
    }

    fun save(context: Context, config: P2PConfig) {
        cachedConfig = config
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_CONFIG_JSON, config.toExportJson())
            apply()
        }
    }
}
