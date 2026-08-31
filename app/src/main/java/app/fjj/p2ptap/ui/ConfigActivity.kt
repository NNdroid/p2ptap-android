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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedResult(result.contents)
        }
    }

    private val galleryQrLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val content = QrImportHelper.decodeQrFromUri(this, uri)
            if (content != null) {
                handleScannedResult(content)
            } else {
                Toast.makeText(this, getString(R.string.msg_scan_no_result), Toast.LENGTH_SHORT).show()
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

        binding.btnScanQrConfig.setOnClickListener {
            showQrSourceChoiceDialog()
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
            }
        }.show(supportFragmentManager, AddressListManagerDialog.TAG)
    }

    private fun showQrSourceChoiceDialog() {
        val options = arrayOf(getString(R.string.title_scan_qr), getString(R.string.btn_choose_gallery_qr))
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_scan_config)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val scanOptions = ScanOptions().apply {
                            setPrompt(getString(R.string.title_scan_qr))
                            setBeepEnabled(true)
                            setBarcodeImageEnabled(false)
                            setOrientationLocked(false)
                        }
                        qrScanLauncher.launch(scanOptions)
                    }
                    1 -> {
                        galleryQrLauncher.launch("image/*")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun handleScannedResult(raw: String) {
        val info = QrImportHelper.parse(raw)
        if (info == null) {
            Toast.makeText(this, getString(R.string.msg_scan_no_result), Toast.LENGTH_SHORT).show()
            return
        }

        QrImportHelper.showScanResultDialog(
            context = this,
            info = info,
            onAddStatic = { multiaddr ->
                val current = binding.etStaticPeers.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
                if (!current.contains(multiaddr)) {
                    current.add(multiaddr)
                    binding.etStaticPeers.setText(current.joinToString("\n"))
                    Toast.makeText(this, "已成功添加至静态节点列表！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "该节点已存在于静态列表中", Toast.LENGTH_SHORT).show()
                }
            },
            onAddBootstrap = { multiaddr ->
                val current = binding.etBootstrapPeers.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
                if (!current.contains(multiaddr)) {
                    current.add(multiaddr)
                    binding.etBootstrapPeers.setText(current.joinToString("\n"))
                    Toast.makeText(this, "已成功添加至引导节点列表！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "该引导节点已存在", Toast.LENGTH_SHORT).show()
                }
            },
            onImportFull = { fullConfig ->
                displayConfig(fullConfig)
                Toast.makeText(this, "配置已导入，请核对并点击保存！", Toast.LENGTH_LONG).show()
            }
        )
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
        finish()
    }
}
