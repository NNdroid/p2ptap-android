package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
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
                    displayConfig(cfg)
                    refreshPeerIdDisplay()
                    val msg = if (restoredPid != null) {
                        "导入成功！已恢复配置及 Peer ID: " + restoredPid.take(12) + "..."
                    } else {
                        getString(R.string.msg_import_success)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.msg_import_failed) + e.message, Toast.LENGTH_LONG).show()
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

        binding.actvObfuscationMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, obfModes))
        binding.actvObfuscationAlgo.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, obfAlgos))
        binding.actvTransportStrategy.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, strategies))
        binding.actvLogLevel.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, logLevels))
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
                cm.setPrimaryClip(ClipData.newPlainText("Peer ID", pid))
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
    }

    private fun openAddressManager(type: AddressListType) {
        val currentList = when (type) {
            AddressListType.BOOTSTRAP_PEERS -> {
                binding.etBootstrapPeers.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            }
            AddressListType.STATIC_PEERS -> {
                binding.etStaticPeers.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
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
            }
        }.show(supportFragmentManager, AddressListManagerDialog.TAG)
    }

    private fun refreshPeerIdDisplay() {
        val pid = AppConfigManager.getPeerId(this)
        binding.tvConfigPeerId.text = if (pid.isNotBlank()) "Peer ID: " + pid else "Peer ID: 未生成"
    }

    private fun showResetKeyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_reset_key_title)
            .setMessage(R.string.confirm_reset_key_msg)
            .setPositiveButton(R.string.btn_confirm_reset) { _, _ ->
                try {
                    val newPid = AppConfigManager.generateNewIdentityKey(this)
                    refreshPeerIdDisplay()
                    Toast.makeText(this, "身份密钥已重置！新 Peer ID: " + newPid.take(12) + "...", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "生成新密钥失败: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun loadConfig() {
        val config = AppConfigManager.load(this)
        displayConfig(config)
    }

    private fun displayConfig(config: P2PConfig) {
        binding.etNodeName.setText(config.nodeName)
        binding.etTapIp.setText(config.tapIp)
        binding.etTapIpv6.setText(config.tapIpv6)
        binding.etMtu.setText(config.mtu.toString())
        binding.etDnsServers.setText(config.dnsServers.joinToString("\n"))
        binding.etPsk.setText(config.psk)
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
    }

    private fun collectConfigFromUi(): P2PConfig {
        val nodeName = binding.etNodeName.text?.toString()?.trim() ?: ""
        val tapIp = binding.etTapIp.text?.toString()?.trim() ?: "10.0.0.88/24"
        val tapIpv6 = binding.etTapIpv6.text?.toString()?.trim() ?: ""
        val mtu = binding.etMtu.text?.toString()?.toIntOrNull() ?: 1500
        val psk = binding.etPsk.text?.toString()?.trim() ?: ""
        val bsString = binding.etBootstrapPeers.text?.toString() ?: ""
        val bsList = bsString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val stString = binding.etStaticPeers.text?.toString() ?: ""
        val stList = stString.lines().map { it.trim() }.filter { it.isNotEmpty() }
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
        val webUiEnable = binding.switchWebUi.isChecked
        val webUiPort = binding.etWebUiPort.text?.toString()?.toIntOrNull() ?: 15858
        val webUiToken = binding.etWebUiToken.text?.toString()?.trim() ?: ""
        val logLevel = binding.actvLogLevel.text?.toString()?.trim() ?: "info"

        return P2PConfig(
            nodeName = if (nodeName.isEmpty()) "Android-Node" else nodeName,
            tapIp = tapIp,
            tapIpv6 = tapIpv6,
            mtu = mtu,
            bootstrapPeers = bsList,
            staticPeers = stList,
            psk = psk,
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
            allowedSubnetPeers = if (aspList.isEmpty()) listOf("*") else aspList,
            dnsServers = dnsList,
            transportStrategy = strategy,
            discoverBootMesh = discoverBootMesh,
            webUiEnable = webUiEnable,
            webUiPort = webUiPort,
            webUiToken = webUiToken,
            logLevel = logLevel
        )
    }

    private fun saveConfig() {
        val config = collectConfigFromUi()
        if (config.tapIp.isEmpty() || !config.tapIp.contains(".")) {
            Toast.makeText(this, "请输入正确的虚拟 IPv4 (如 10.0.0.88/24)", Toast.LENGTH_SHORT).show()
            return
        }

        AppConfigManager.save(this, config)
        Toast.makeText(this, getString(R.string.msg_config_saved), Toast.LENGTH_SHORT).show()

        if (P2PTapVpnService.isRunning()) {
            val intent = android.content.Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_RELOAD
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        finish()
    }
}
