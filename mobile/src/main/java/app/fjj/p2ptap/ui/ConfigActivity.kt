package app.fjj.p2ptap.ui

import app.fjj.p2ptap.i18n.UiMessages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.InetAddresses
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.splitPeerAddresses
import app.fjj.p2ptap.config.P2PConfig
import app.fjj.p2ptap.databinding.ActivityConfigBinding
import app.fjj.p2ptap.service.P2PTapVpnService
import java.io.BufferedReader
import java.io.InputStreamReader

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val jsonStr = reader.readText()
                    val (cfg, restoredPid) = AppConfigManager.importBackupOrConfig(this, jsonStr)
                    AppConfigManager.reloadRunningService(this, forceRestart = true)
                    displayConfig(cfg)
                    refreshPeerIdDisplay()
                    val msg = if (restoredPid != null) {
                        getString(R.string.msg_identity_restored_fmt, restoredPid.take(12) + "…")
                    } else {
                        getString(R.string.msg_import_success)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.msg_import_failed) + UiMessages.describe(this, e), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncStatusBarColor()
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        setupDropdownAdapters()
        loadConfig()
        loadThemeMode()
        refreshPeerIdDisplay()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        syncStatusBarColor()
    }

    private fun syncStatusBarColor() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = ContextCompat.getColor(this, R.color.window_bg)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNight
    }

    private fun setupDropdownAdapters() {
        val obfModes = arrayOf("auto", "fixed", "block", "random", "dynamic")
        val obfAlgos = arrayOf("auto", "chacha20", "aes-gcm", "none")
        val strategies = arrayOf("best_path", "redundant", "fallback")
        val logLevels = arrayOf("debug", "info", "warn", "error")
        val themeModes = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system)
        )

        binding.actvObfuscationMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, obfModes))
        binding.actvObfuscationAlgo.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, obfAlgos))
        binding.actvTransportStrategy.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, strategies))
        binding.actvLogLevel.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, logLevels))
        binding.actvThemeMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, themeModes))
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            saveConfig()
        }

        binding.btnBackupRestore.setOnClickListener {
            BackupDialog.newInstance {
                loadConfig()
                refreshPeerIdDisplay()
            }.show(supportFragmentManager, BackupDialog.TAG)
        }

        binding.btnResetKey.setOnClickListener {
            showResetKeyDialog()
        }

        binding.ivCopyPeerId.setOnClickListener {
            val pid = AppConfigManager.getPeerId(this)
            if (pid.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_peer_id), pid))
                Toast.makeText(this, getString(R.string.msg_peer_id_copied), Toast.LENGTH_SHORT).show()
            }
        }

        binding.layoutConfigPeerId.setOnClickListener {
            binding.ivCopyPeerId.performClick()
        }

        // Bootstrap Peers List Manager
        binding.btnManageBootstrap.setOnClickListener {
            openAddressManager(AddressListType.BOOTSTRAP_PEERS)
        }
        binding.tilBootstrapPeers.setEndIconOnClickListener {
            openAddressManager(AddressListType.BOOTSTRAP_PEERS)
        }

        // Static Peers List Manager
        binding.btnManageStatic.setOnClickListener {
            openAddressManager(AddressListType.STATIC_PEERS)
        }
        binding.tilStaticPeers.setEndIconOnClickListener {
            openAddressManager(AddressListType.STATIC_PEERS)
        }

        // Advertised Subnets List Manager
        binding.btnManageSubnets.setOnClickListener {
            openAddressManager(AddressListType.ADVERTISED_SUBNETS)
        }
        binding.tilAdvertisedSubnets.setEndIconOnClickListener {
            openAddressManager(AddressListType.ADVERTISED_SUBNETS)
        }

        // Allowed Subnet Peer IDs List Manager
        binding.btnManageAllowedPeers.setOnClickListener {
            openAddressManager(AddressListType.ALLOWED_SUBNET_PEERS)
        }
        binding.tilAllowedSubnetPeers.setEndIconOnClickListener {
            openAddressManager(AddressListType.ALLOWED_SUBNET_PEERS)
        }

        // DNS Servers List Manager
        binding.btnManageDns.setOnClickListener {
            openAddressManager(AddressListType.DNS_SERVERS)
        }
        binding.tilDnsServers.setEndIconOnClickListener {
            openAddressManager(AddressListType.DNS_SERVERS)
        }

        // STUN Servers List Manager
        binding.btnManageStun.setOnClickListener {
            openAddressManager(AddressListType.STUN_SERVERS)
        }
        binding.tilStunServers.setEndIconOnClickListener {
            openAddressManager(AddressListType.STUN_SERVERS)
        }

        // TURN Servers List Manager
        binding.btnManageTurn.setOnClickListener {
            openAddressManager(AddressListType.TURN_SERVERS)
        }
        binding.tilTurnServers.setEndIconOnClickListener {
            openAddressManager(AddressListType.TURN_SERVERS)
        }
    }

    private fun openAddressManager(type: AddressListType) {
        val currentList = when (type) {
            AddressListType.BOOTSTRAP_PEERS -> {
                splitPeerAddresses(binding.etBootstrapPeers.text?.toString().orEmpty())
            }
            AddressListType.STATIC_PEERS -> {
                splitPeerAddresses(binding.etStaticPeers.text?.toString().orEmpty())
            }
            AddressListType.ADVERTISED_SUBNETS -> {
                binding.etAdvertisedSubnets.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            }
            AddressListType.ALLOWED_SUBNET_PEERS -> {
                val raw = binding.etAllowedSubnetPeers.text?.toString() ?: "*"
                raw.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
            }
            AddressListType.DNS_SERVERS -> {
                binding.etDnsServers.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            }
            AddressListType.STUN_SERVERS -> {
                binding.etStunServers.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            }
            AddressListType.TURN_SERVERS -> {
                binding.etTurnServers.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            }
        }

        AddressListManagerDialog.newInstance(type, currentList) { updatedList ->
            when (type) {
                AddressListType.BOOTSTRAP_PEERS -> {
                    binding.etBootstrapPeers.setText(updatedList.joinToString("\n"))
                }
                AddressListType.STATIC_PEERS -> {
                    binding.etStaticPeers.setText(updatedList.joinToString("\n"))
                }
                AddressListType.ADVERTISED_SUBNETS -> {
                    binding.etAdvertisedSubnets.setText(updatedList.joinToString("\n"))
                }
                AddressListType.ALLOWED_SUBNET_PEERS -> {
                    val joined = if (updatedList.isEmpty()) "*" else updatedList.joinToString(", ")
                    binding.etAllowedSubnetPeers.setText(joined)
                }
                AddressListType.DNS_SERVERS -> {
                    binding.etDnsServers.setText(updatedList.joinToString("\n"))
                }
                AddressListType.STUN_SERVERS -> {
                    binding.etStunServers.setText(updatedList.joinToString("\n"))
                }
                AddressListType.TURN_SERVERS -> {
                    binding.etTurnServers.setText(updatedList.joinToString("\n"))
                }
            }
        }.show(supportFragmentManager, AddressListManagerDialog.TAG)
    }

    private fun refreshPeerIdDisplay() {
        val pid = AppConfigManager.getPeerId(this)
        binding.tvConfigPeerId.text = getString(R.string.peer_id_fmt, pid.ifBlank { getString(R.string.identity_not_generated) })
    }

    private fun showResetKeyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_reset_key_title)
            .setMessage(R.string.confirm_reset_key_msg)
            .setPositiveButton(R.string.btn_confirm_reset) { _, _ ->
                try {
                    val newPid = AppConfigManager.generateNewIdentityKey(this)
                    refreshPeerIdDisplay()
                    Toast.makeText(this, getString(R.string.msg_key_loaded_fmt, newPid.take(12) + "..."), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.err_key_load_fmt, UiMessages.describe(this, e)), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun loadConfig() {
        val config = AppConfigManager.load(this)
        displayConfig(config)
    }

    private fun loadThemeMode() {
        val prefs = getSharedPreferences("p2ptap_ui_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val label = when (mode) {
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }
        binding.actvThemeMode.setText(label, false)
    }

    private fun saveThemeMode() {
        val prefs = getSharedPreferences("p2ptap_ui_prefs", Context.MODE_PRIVATE)
        val mode = when (binding.actvThemeMode.text?.toString()?.trim()) {
            getString(R.string.theme_light) -> AppCompatDelegate.MODE_NIGHT_NO
            getString(R.string.theme_dark) -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        prefs.edit().putInt("night_mode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun displayConfig(config: P2PConfig) {
        binding.etNodeName.setText(config.nodeName)
        binding.etTapIp.setText(config.tapIp)
        binding.etTapIpv6.setText(config.tapIpv6)
        binding.etMtu.setText(config.mtu.toString())
        binding.etDnsServers.setText(config.dnsServers.joinToString("\n"))
        binding.etPsk.setText(config.psk)
        binding.etTlsServerName.setText(config.tlsServerName)
        binding.etTlsSniSuffix.setText(config.tlsSniSuffix)
        binding.etBootstrapPeers.setText(config.bootstrapPeers.joinToString("\n"))
        binding.etStaticPeers.setText(config.staticPeers.joinToString("\n"))
        binding.switchEnableMdns.isChecked = config.enableMdns
        binding.switchDiscoverBootMesh.isChecked = config.discoverBootMesh
        binding.switchDisableRelay.isChecked = config.disableRelay
        binding.switchObfuscation.isChecked = config.obfuscationEnable
        binding.switchStrictKey.isChecked = config.strictKeyNegotiation
        binding.actvObfuscationMode.setText(config.obfuscationMode, false)
        binding.actvObfuscationAlgo.setText(config.obfuscationAlgorithm, false)
        binding.switchQuic.isChecked = config.enableQuic
        binding.switchWebrtc.isChecked = config.enableWebrtc
        binding.switchWebtransport.isChecked = config.enableWebtransport
        binding.switchTcp.isChecked = config.enableTcp
        binding.actvTransportStrategy.setText(config.transportStrategy, false)
        binding.switchAcceptSubnets.isChecked = config.acceptSubnets
        binding.etAdvertisedSubnets.setText(config.advertisedSubnets.joinToString("\n"))
        binding.etAllowedSubnetPeers.setText(config.allowedSubnetPeers.joinToString(", "))
        binding.switchWebUi.isChecked = config.webUiEnable
        binding.etWebUiPort.setText(config.webUiPort.toString())
        binding.etWebUiToken.setText(config.webUiToken)
        binding.actvLogLevel.setText(config.logLevel, false)
        binding.etHolePunchTimeout.setText(config.holePunchTimeout.toString())
        binding.etStunServers.setText(config.stunServers.joinToString("\n"))
        binding.etTurnServers.setText(config.turnServers.joinToString("\n"))
    }

    private fun collectConfigFromUi(): P2PConfig {
        val savedConfig = AppConfigManager.load(this)
        val nodeName = binding.etNodeName.text?.toString()?.trim() ?: ""
        val tapIp = binding.etTapIp.text?.toString()?.trim() ?: "10.0.0.88/24"
        val tapIpv6 = binding.etTapIpv6.text?.toString()?.trim() ?: ""
        val mtu = binding.etMtu.text?.toString()?.toIntOrNull() ?: 1500
        val psk = binding.etPsk.text?.toString()?.trim() ?: ""
        val tlsServerName = binding.etTlsServerName.text?.toString()?.trim() ?: ""
        val tlsSniSuffix = binding.etTlsSniSuffix.text?.toString()?.trim() ?: ""
        val bsString = binding.etBootstrapPeers.text?.toString() ?: ""
        val bsList = splitPeerAddresses(bsString)
        val stString = binding.etStaticPeers.text?.toString() ?: ""
        val stList = splitPeerAddresses(stString)
        val enableMdns = binding.switchEnableMdns.isChecked
        val discoverBootMesh = binding.switchDiscoverBootMesh.isChecked
        val disableRelay = binding.switchDisableRelay.isChecked
        val obfuscation = binding.switchObfuscation.isChecked
        val strictKey = binding.switchStrictKey.isChecked
        val obfMode = binding.actvObfuscationMode.text?.toString()?.trim() ?: "auto"
        val obfAlgo = binding.actvObfuscationAlgo.text?.toString()?.trim() ?: "auto"
        val enableQuic = binding.switchQuic.isChecked
        val enableWebrtc = binding.switchWebrtc.isChecked
        val enableWebtransport = binding.switchWebtransport.isChecked
        val enableTcp = binding.switchTcp.isChecked
        val strategy = binding.actvTransportStrategy.text?.toString()?.trim() ?: "best_path"
        val acceptSubnets = binding.switchAcceptSubnets.isChecked
        val advString = binding.etAdvertisedSubnets.text?.toString() ?: ""
        val advList = advString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val aspString = binding.etAllowedSubnetPeers.text?.toString() ?: "*"
        val aspList = aspString.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        val dnsString = binding.etDnsServers.text?.toString() ?: ""
        val dnsList = dnsString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val holePunchTimeout = binding.etHolePunchTimeout.text?.toString()?.toLongOrNull() ?: 15000
        val stunString = binding.etStunServers.text?.toString() ?: ""
        val stunList = stunString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val turnString = binding.etTurnServers.text?.toString() ?: ""
        val turnList = turnString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val webUiEnable = binding.switchWebUi.isChecked
        val webUiPort = binding.etWebUiPort.text?.toString()?.toIntOrNull() ?: 15858
        val webUiToken = binding.etWebUiToken.text?.toString()?.trim() ?: ""
        val logLevel = binding.actvLogLevel.text?.toString()?.trim() ?: "info"

        return P2PConfig(
            nodeName = nodeName,
            tapIp = tapIp,
            tapIpv6 = tapIpv6,
            mtu = mtu,
            bootstrapPeers = bsList,
            staticPeers = stList,
            psk = psk,
            tlsServerName = tlsServerName,
            tlsSniSuffix = tlsSniSuffix,
            enableMdns = enableMdns,
            obfuscationEnable = obfuscation,
            obfuscationMode = obfMode,
            obfuscationAlgorithm = obfAlgo,
            strictKeyNegotiation = strictKey,
            enableQuic = enableQuic,
            enableWebrtc = enableWebrtc,
            enableWebtransport = enableWebtransport,
            enableTcp = enableTcp,
            disableRelay = disableRelay,
            acceptSubnets = acceptSubnets,
            advertisedSubnets = advList,
            allowedSubnetPeers = aspList,
            dnsServers = dnsList,
            holePunchTimeout = holePunchTimeout,
            stunServers = stunList,
            turnServers = turnList,
            transportStrategy = strategy,
            discoverBootMesh = discoverBootMesh,
            // Exit-node selection is managed by ExitNodeSelectorDialog. Saving
            // unrelated settings must not silently reset that independent choice.
            exitNode = savedConfig.exitNode,
            engineConfig = savedConfig.engineConfig,
            webUiEnable = webUiEnable,
            webUiPort = webUiPort,
            webUiToken = webUiToken,
            logLevel = logLevel
        )
    }

    private fun saveConfig() {
        saveThemeMode()
        val config = collectConfigFromUi()

        // --- UI-level format validation for immediate feedback ---
        // TAP IPv4 CIDR
        if (!isValidIpv4Cidr(config.tapIp)) {
            Toast.makeText(this, getString(R.string.err_invalid_ipv4), Toast.LENGTH_SHORT).show()
            return
        }
        // TAP IPv6 CIDR (if non-empty)
        if (config.tapIpv6.isNotEmpty() && !isValidIpv6Cidr(config.tapIpv6)) {
            Toast.makeText(this, getString(R.string.err_invalid_ipv6), Toast.LENGTH_SHORT).show()
            return
        }
        // MTU range
        if (config.mtu < 576 || config.mtu > 9000) {
            Toast.makeText(this, getString(R.string.err_invalid_mtu), Toast.LENGTH_SHORT).show()
            return
        }
        // WebUI port
        if (config.webUiPort < 1 || config.webUiPort > 65535) {
            Toast.makeText(this, getString(R.string.err_invalid_port), Toast.LENGTH_SHORT).show()
            return
        }
        // Hole punch timeout (1s–120s)
        if (config.holePunchTimeout < 1000 || config.holePunchTimeout > 120000) {
            Toast.makeText(this, getString(R.string.err_invalid_timeout), Toast.LENGTH_SHORT).show()
            return
        }
        // Node name
        if (config.nodeName.length > 64) {
            Toast.makeText(this, getString(R.string.err_invalid_node_name), Toast.LENGTH_SHORT).show()
            return
        }
        // Advertised subnets (each line must be a valid CIDR)
        for (sub in config.advertisedSubnets) {
            if (sub.isNotEmpty() && !isValidCidr(sub)) {
                Toast.makeText(this, getString(R.string.err_invalid_subnet_fmt, sub), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // DNS servers (each must be a valid IP address)
        for (dns in config.dnsServers) {
            if (dns.isNotEmpty() && !InetAddresses.isNumericAddress(dns)) {
                Toast.makeText(this, getString(R.string.err_invalid_dns_fmt, dns), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // STUN servers (basic format check)
        for (srv in config.stunServers) {
            if (srv.isNotEmpty() && !isValidStunServer(srv)) {
                Toast.makeText(this, getString(R.string.err_invalid_stun_fmt, srv), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // TURN servers (must start with turn:)
        for (srv in config.turnServers) {
            if (srv.isNotEmpty() && !srv.startsWith("turn:")) {
                Toast.makeText(this, getString(R.string.err_invalid_turn_fmt, srv), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // Obfuscation params
        if (config.obfuscationMode == "fixed" && config.obfuscationFixedSize <= 0) {
            Toast.makeText(this, getString(R.string.err_invalid_fixed_size), Toast.LENGTH_SHORT).show()
            return
        }
        if (config.obfuscationMode == "block" && config.obfuscationBlockSize <= 0) {
            Toast.makeText(this, getString(R.string.err_invalid_block_size), Toast.LENGTH_SHORT).show()
            return
        }
        if (config.obfuscationJitterRange < 0) {
            Toast.makeText(this, getString(R.string.err_invalid_jitter), Toast.LENGTH_SHORT).show()
            return
        }
        if (config.obfuscationMaxFragSize > 0 && config.obfuscationMaxFragSize < 256) {
            Toast.makeText(this, getString(R.string.err_invalid_max_frag_size), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            AppConfigManager.save(this, config)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.config_invalid_fmt, UiMessages.describe(this, e)), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.msg_config_saved), Toast.LENGTH_SHORT).show()

        AppConfigManager.reloadRunningService(this)

        finish()
    }

    // --- Format validators (UI-level, fast checks before JNI round-trip) ---

    private fun isValidIpv4Cidr(s: String): Boolean {
        if (!s.contains("/")) return false
        val parts = s.split("/")
        if (parts.size != 2) return false
        val ip = parts[0].split(".")
        if (ip.size != 4) return false
        for (seg in ip) {
            val v = seg.toIntOrNull() ?: return false
            if (v < 0 || v > 255) return false
        }
        val prefix = parts[1].toIntOrNull() ?: return false
        return prefix in 0..32
    }

    private fun isValidIpv6Cidr(s: String): Boolean {
        if (!s.contains("/")) return false
        val parts = s.split("/")
        if (parts.size != 2) return false
        val ip = parts[0]
        if (android.net.InetAddresses.isNumericAddress(ip)) return true // numeric IP
        // Simple check: must contain at least one colon for IPv6
        if (!ip.contains(":")) return false
        val prefix = parts[1].toIntOrNull() ?: return false
        return prefix in 0..128
    }

    private fun isValidCidr(s: String): Boolean {
        return isValidIpv4Cidr(s) || isValidIpv6Cidr(s)
    }

    private fun isValidStunServer(s: String): Boolean {
        val trimmed = s.trim()
        if (trimmed.startsWith("/udp/") || trimmed.startsWith("/tcp/")) return true
        val withoutPrefix = trimmed.removePrefix("stun:")
        val colonIdx = withoutPrefix.lastIndexOf(':')
        if (colonIdx < 0) return false
        val host = withoutPrefix.substring(0, colonIdx)
        val port = withoutPrefix.substring(colonIdx + 1)
        if (host.isEmpty()) return false
        val p = port.toIntOrNull() ?: return false
        return p in 1..65535
    }
}
