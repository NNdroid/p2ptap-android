package app.fjj.p2ptap.ui

import app.fjj.p2ptap.i18n.UiMessages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.InetAddresses
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.splitPeerAddresses
import app.fjj.p2ptap.config.P2PConfig
import app.fjj.p2ptap.databinding.FragmentConfigBinding
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.VpnReloadPlan
import app.fjj.p2ptap.service.planVpnReload
import com.google.android.material.textfield.TextInputLayout
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * The settings tab, hosted by MainActivity like the other three tabs.
 *
 * Promoted from ConfigActivity: a second Activity inflating its own copy of
 * the bottom bar, hopping back and forth through Intents with CLEAR_TOP /
 * SINGLE_TOP and a tab extra, was the root cause of the bar-sync bug family
 * (highlight drift, stale selection on return, and the re-entrancy
 * StackOverflowError of 2026-11-01). As a Fragment it reuses the host's bar,
 * so there is exactly one selection listener and nothing to keep in sync.
 *
 * The form logic is unchanged from the Activity: same debounced validation,
 * same reload-plan preview, same gating. After Save the host is told to
 * return to the tab the user came from, which is what `finish()` used to do.
 */
class ConfigFragment : Fragment() {

    /** Called by the host activity after Save commits, so it can show the previous tab again. */
    interface Host {
        fun onSettingsSaved()
    }

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!

    // Debounce for real-time validation: each field's latest validation replaces
    // any pending one, so rapid typing only triggers one check after 300 ms of
    // quiet. Without this the user sees transient errors for every intermediate
    // keystroke ("10." is not a valid CIDR yet).
    private val validationHandler = Handler(Looper.getMainLooper())
    private val pendingValidations = mutableMapOf<TextInputLayout, Runnable>()
    private val reloadPlanRunnable = Runnable { refreshReloadPlan() }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // The picker result can land after this view was destroyed (user left
        // the tab while the system picker was up); every branch below touches
        // binding. Bail out instead of hitting the _binding!! in the getter.
        if (uri != null && _binding != null) {
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val jsonStr = reader.readText()
                    val (cfg, restoredPid) = AppConfigManager.importBackupOrConfig(requireContext(), jsonStr)
                    AppConfigManager.reloadRunningService(requireContext(), forceRestart = true)
                    displayConfig(cfg)
                    refreshPeerIdDisplay()
                    val msg = if (restoredPid != null) {
                        getString(R.string.msg_identity_restored_fmt, restoredPid.take(12) + "…")
                    } else {
                        getString(R.string.msg_import_success)
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.msg_import_failed) + UiMessages.describe(requireContext(), e), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDropdownAdapters()
        loadConfig()
        loadThemeMode()
        refreshPeerIdDisplay()
        setupListeners()
    }

    override fun onDestroyView() {
        // The debounce runnables capture TextInputLayouts from this view; drop
        // everything so a destroyed settings view is never touched again.
        validationHandler.removeCallbacksAndMessages(null)
        pendingValidations.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun setupDropdownAdapters() {
        val obfAlgos = arrayOf("auto", "chacha20", "aes-gcm", "none")
        val strategies = arrayOf("best_path", "multipath", "redundant", "fallback")
        val logLevels = arrayOf("debug", "info", "warn", "error")
        val themeModes = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system)
        )

        binding.actvObfuscationAlgo.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, obfAlgos))
        binding.actvTransportStrategy.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, strategies))
        binding.actvLogLevel.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, logLevels))
        binding.actvThemeMode.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, themeModes))
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            saveConfig()
        }

        binding.layoutOpenLogs.setOnClickListener {
            startActivity(Intent(requireContext(), LogViewerActivity::class.java))
        }

        binding.btnBackupRestore.setOnClickListener {
            BackupDialog.newInstance {
                loadConfig()
                refreshPeerIdDisplay()
            }.show(parentFragmentManager, BackupDialog.TAG)
        }

        binding.btnResetKey.setOnClickListener {
            showResetKeyDialog()
        }

        binding.ivCopyPeerId.setOnClickListener {
            val pid = AppConfigManager.getPeerId(requireContext())
            if (pid.isNotBlank()) {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_peer_id), pid))
                Toast.makeText(requireContext(), getString(R.string.msg_peer_id_copied), Toast.LENGTH_SHORT).show()
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

        // The switches and dropdowns have no validation of their own, so they
        // are the only fields that could change the reload plan without the
        // user noticing. actvThemeMode is excluded: the theme mode is stored
        // separately and does not reach the VPN.
        listOf(
            binding.switchEnableMdns, binding.switchDiscoverBootMesh,
            binding.switchDisableCircuitRelay, binding.switchDisableHolePunching,
            binding.switchStrictKey, binding.chipQuic, binding.chipWebrtc,
            binding.chipWebtransport, binding.chipTcp, binding.switchAcceptSubnets,
            binding.switchWebUi
        ).forEach { switch ->
            switch.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, _ ->
                scheduleReloadPlanRefresh()
            })
        }
        // These two are umbrellas for the controls greyed out by applySwitchGating,
        // so toggling them must re-run the gating as well.
        binding.switchDisableRelay.setOnCheckedChangeListener { _, _ ->
            applySwitchGating()
            scheduleReloadPlanRefresh()
        }
        binding.switchObfuscation.setOnCheckedChangeListener { _, _ ->
            applySwitchGating()
            scheduleReloadPlanRefresh()
        }
        listOf(
            binding.actvObfuscationAlgo,
            binding.actvTransportStrategy, binding.actvLogLevel
        ).forEach { dropdown ->
            dropdown.doAfterTextChanged { scheduleReloadPlanRefresh() }
        }

        // Every switch is a 56dp setting line now: title, explanation, thumb.
        // The whole line toggles, which is what gives the explanation a hit
        // area — before, tapping the text beside a bare MaterialSwitch did
        // nothing. Switches greyed out by applySwitchGating stay inert here as
        // well, otherwise the row would be a way around the gating.
        listOf(
            binding.rowEnableMdns to binding.switchEnableMdns,
            binding.rowDiscoverBootMesh to binding.switchDiscoverBootMesh,
            binding.rowDisableRelay to binding.switchDisableRelay,
            binding.rowDisableCircuitRelay to binding.switchDisableCircuitRelay,
            binding.rowDisableHolePunching to binding.switchDisableHolePunching,
            binding.rowObfuscation to binding.switchObfuscation,
            binding.rowStrictKey to binding.switchStrictKey,
            binding.rowAcceptSubnets to binding.switchAcceptSubnets,
            binding.rowWebUi to binding.switchWebUi,
        ).forEach { (row, sw) ->
            row.setOnClickListener { if (sw.isEnabled) sw.toggle() }
        }

        setupRealTimeValidation()
    }

    // ── Real-time field validation ──────────────────────────────────────────
    // Fires 300 ms after the last keystroke so the user does not see transient
    // errors for partial input (e.g. "10." while typing "10.0.0.88/24").
    // Existing saveConfig() checks remain as a final gate.

    private fun debounceValidate(
        til: TextInputLayout,
        validator: () -> String?
    ) {
        pendingValidations[til]?.let { validationHandler.removeCallbacks(it) }
        val runnable = Runnable {
            til.error = validator()
            pendingValidations.remove(til)
            refreshReloadPlan()
        }
        pendingValidations[til] = runnable
        validationHandler.postDelayed(runnable, 300)
    }

    // ── Reload plan preview ───────────────────────────────────────────────
    // Tells the user, above the Save button, whether saving the edits on this
    // screen can be applied to a running VPN or forces it to reconnect. Showing
    // it before they tap Save is the point: after the tap the VPN is already
    // dropping, so a Toast there is only a post-mortem.
    //
    // planVpnReload with a null Context skips the JNI round-trip and uses the
    // local classifier instead, which is what makes this cheap enough to run
    // after every debounced keystroke. The local classifier errs safe — it
    // reports RESTART at least as often as the engine does — so it can
    // over-warn but never under-warn. The real reload still asks the engine, so
    // a wrong hint here cannot cause a change to go unapplied.

    private fun scheduleReloadPlanRefresh() {
        validationHandler.removeCallbacks(reloadPlanRunnable)
        validationHandler.postDelayed(reloadPlanRunnable, 300)
    }

    private fun refreshReloadPlan() {
        // Compare against what the running VPN actually uses. AppConfigManager
        // holds the last saved config and the service's cached config is set
        // from it, so the file read is the baseline until this screen saves.
        val plan = if (P2PTapVpnService.isRunning()) {
            planVpnReload(AppConfigManager.load(requireContext()), collectConfigFromUi(), false, null)
        } else {
            null
        }
        binding.tvReloadPlan.text = when (plan) {
            null -> getString(R.string.reload_plan_idle)
            VpnReloadPlan.NONE -> getString(R.string.reload_plan_no_change)
            VpnReloadPlan.HOT -> getString(R.string.reload_plan_hot)
            VpnReloadPlan.RESTART -> getString(R.string.reload_plan_restart)
        }
        binding.tvReloadPlan.setTextColor(ContextCompat.getColor(requireContext(), when (plan) {
            VpnReloadPlan.HOT -> R.color.status_connected
            VpnReloadPlan.RESTART -> R.color.status_connecting
            else -> R.color.md_on_surface_variant
        }))
    }

    private fun setupRealTimeValidation() {
        // Node Name — max 64 characters
        binding.etNodeName.doAfterTextChanged {
            debounceValidate(binding.tilNodeName) {
                val text = binding.etNodeName.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else if (text.length > 64) getString(R.string.err_invalid_node_name)
                else null
            }
        }

        // TAP IPv4 CIDR
        binding.etTapIp.doAfterTextChanged {
            debounceValidate(binding.tilTapIp) {
                val text = binding.etTapIp.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else if (!isValidIpv4Cidr(text)) getString(R.string.err_invalid_ipv4)
                else null
            }
        }

        // TAP IPv6 CIDR (optional)
        binding.etTapIpv6.doAfterTextChanged {
            debounceValidate(binding.tilTapIpv6) {
                val text = binding.etTapIpv6.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else if (!isValidIpv6Cidr(text)) getString(R.string.err_invalid_ipv6)
                else null
            }
        }

        // MTU range 576–9000
        binding.etMtu.doAfterTextChanged {
            debounceValidate(binding.tilMtu) {
                val text = binding.etMtu.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else {
                    val mtu = text.toIntOrNull()
                    if (mtu == null || mtu < 576 || mtu > 9000)
                        getString(R.string.err_invalid_mtu)
                    else null
                }
            }
        }

        // DNS Servers — each line must be a valid IP
        binding.etDnsServers.doAfterTextChanged {
            debounceValidate(binding.tilDnsServers) {
                val lines = binding.etDnsServers.text?.toString()
                    ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val invalid = lines.firstOrNull { !InetAddresses.isNumericAddress(it) }
                if (invalid != null) getString(R.string.err_invalid_dns_fmt, invalid)
                else null
            }
        }

        // Advertised Subnets — each line must be a valid CIDR
        binding.etAdvertisedSubnets.doAfterTextChanged {
            debounceValidate(binding.tilAdvertisedSubnets) {
                val lines = binding.etAdvertisedSubnets.text?.toString()
                    ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val invalid = lines.firstOrNull { !isValidCidr(it) }
                if (invalid != null) getString(R.string.err_invalid_subnet_fmt, invalid)
                else null
            }
        }

        // Hole Punch Timeout — the field shows seconds (see displayConfig and
        // collectConfig, which divide and multiply by 1000). This used to test
        // the typed value against 1 000–120 000 ms, so "30" failed while "1000"
        // passed and was stored as 1000 s; the message said "1 and 120
        // seconds", so the operator had no way to tell the field was broken.
        binding.etHolePunchTimeout.doAfterTextChanged {
            debounceValidate(binding.tilHolePunchTimeout) {
                val text = binding.etHolePunchTimeout.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else {
                    val seconds = text.toLongOrNull()
                    if (seconds == null || seconds < 1 || seconds > 120)
                        getString(R.string.err_invalid_timeout)
                    else null
                }
            }
        }

        // STUN Servers — each line must be a valid STUN address
        binding.etStunServers.doAfterTextChanged {
            debounceValidate(binding.tilStunServers) {
                val lines = binding.etStunServers.text?.toString()
                    ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val invalid = lines.firstOrNull { !isValidStunServer(it) }
                if (invalid != null) getString(R.string.err_invalid_stun_fmt, invalid)
                else null
            }
        }

        // TURN Servers — each line must start with turn: and have a valid port
        binding.etTurnServers.doAfterTextChanged {
            debounceValidate(binding.tilTurnServers) {
                val lines = binding.etTurnServers.text?.toString()
                    ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val invalid = lines.firstOrNull { !isValidTurnServer(it) }
                if (invalid != null) getString(R.string.err_invalid_turn_fmt, invalid)
                else null
            }
        }

        // PSK — warning if shorter than 16 characters (weak key)
        binding.etPsk.doAfterTextChanged {
            debounceValidate(binding.tilPsk) {
                val text = binding.etPsk.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else if (text.length < 16) getString(R.string.err_psk_too_short)
                else null
            }
        }

        // TLS Server Name — basic hostname format
        binding.etTlsServerName.doAfterTextChanged {
            debounceValidate(binding.tilTlsServerName) {
                val text = binding.etTlsServerName.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else if (!isValidHostname(text)) getString(R.string.err_invalid_hostname)
                else null
            }
        }

        // TLS SNI Suffix — basic hostname format
        binding.etTlsSniSuffix.doAfterTextChanged {
            debounceValidate(binding.tilTlsSniSuffix) {
                val text = binding.etTlsSniSuffix.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else if (!isValidHostname(text)) getString(R.string.err_invalid_hostname)
                else null
            }
        }

        // WebUI Port — 1–65535
        binding.etWebUiPort.doAfterTextChanged {
            debounceValidate(binding.tilWebUiPort) {
                val text = binding.etWebUiPort.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else {
                    val port = text.toIntOrNull()
                    if (port == null || port < 1 || port > 65535)
                        getString(R.string.err_invalid_port)
                    else null
                }
            }
        }

        // WebUI Token — warning if shorter than 16 characters (weak token)
        binding.etWebUiToken.doAfterTextChanged {
            debounceValidate(binding.tilWebUiToken) {
                val text = binding.etWebUiToken.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) null
                else if (text.length < 16) getString(R.string.err_token_too_short)
                else null
            }
        }
    }

    // ── Helper validators (reused by real-time and save-time paths) ──────────

    private fun isValidHostname(s: String): Boolean {
        // RFC 952 / 1123 hostname: alphanumeric, hyphen, dot; no leading/trailing
        // hyphen per label; max 253 total. Lenient — the engine enforces strict rules.
        if (s.length > 253) return false
        if (s.isEmpty() || s.endsWith(".") || s.startsWith(".") || s.startsWith("-")) return false
        val labels = s.split(".")
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith("-") || label.endsWith("-")) return false
            for (c in label) {
                if (!c.isLetterOrDigit() && c != '-') return false
            }
        }
        return true
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
        }.show(parentFragmentManager, AddressListManagerDialog.TAG)
    }

    private fun refreshPeerIdDisplay() {
        val pid = AppConfigManager.getPeerId(requireContext())
        binding.tvConfigPeerId.text = getString(R.string.peer_id_fmt, pid.ifBlank { getString(R.string.identity_not_generated) })
    }

    private fun showResetKeyDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_reset_key_title)
            .setMessage(R.string.confirm_reset_key_msg)
            .setPositiveButton(R.string.btn_confirm_reset) { _, _ ->
                try {
                    val newPid = AppConfigManager.generateNewIdentityKey(requireContext())
                    refreshPeerIdDisplay()
                    // The running tunnel keeps the old identity until restarted —
                    // without this the user sees a new peer ID that never appears
                    // on the wire, and every peer entry containing the old ID
                    // goes stale the moment they reset.
                    if (P2PTapVpnService.isRunning()) {
                        AppConfigManager.reloadRunningService(requireContext(), forceRestart = true)
                    }
                    Toast.makeText(requireContext(), getString(R.string.msg_key_loaded_fmt, newPid.take(12) + "..."), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(R.string.err_key_load_fmt, UiMessages.describe(requireContext(), e)), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun loadConfig() {
        val config = AppConfigManager.load(requireContext())
        displayConfig(config)
    }

    private fun loadThemeMode() {
        val prefs = requireContext().getSharedPreferences("p2ptap_ui_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val label = when (mode) {
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }
        binding.actvThemeMode.setText(label, false)
    }

    private fun saveThemeMode() {
        val prefs = requireContext().getSharedPreferences("p2ptap_ui_prefs", Context.MODE_PRIVATE)
        val mode = when (binding.actvThemeMode.text?.toString()?.trim()) {
            getString(R.string.theme_light) -> AppCompatDelegate.MODE_NIGHT_NO
            getString(R.string.theme_dark) -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        prefs.edit().putInt("night_mode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    // Grey out the switches that the engine would ignore anyway, so the screen
    // never offers a toggle that cannot change behaviour:
    //  - disable_relay is an umbrella that forces circuit relay and hole
    //    punching off (config.CircuitRelayEnabled / HolePunchingEnabled), so
    //    the two specific switches are meaningless while it is on;
    //  - obfuscation off means there is no padded stream to negotiate keys for
    //    or to pick a mode and algorithm for.
    // The values still get saved, so un-greying restores what was there.
    private fun applySwitchGating() {
        val relayOff = binding.switchDisableRelay.isChecked
        binding.switchDisableCircuitRelay.isEnabled = !relayOff
        binding.switchDisableHolePunching.isEnabled = !relayOff
        val obfsOn = binding.switchObfuscation.isChecked
        binding.switchStrictKey.isEnabled = obfsOn
        binding.etPaddingRules.isEnabled = obfsOn
        binding.actvObfuscationAlgo.isEnabled = obfsOn
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
        binding.switchDisableCircuitRelay.isChecked = config.disableCircuitRelay
        binding.switchDisableHolePunching.isChecked = config.disableHolePunching
        binding.switchObfuscation.isChecked = config.obfuscationEnable
        binding.switchStrictKey.isChecked = config.strictKeyNegotiation
        binding.etPaddingRules.setText(config.paddingRules)
        binding.actvObfuscationAlgo.setText(config.obfuscationAlgorithm, false)
        applySwitchGating()
        binding.chipQuic.isChecked = config.enableQuic
        binding.chipWebrtc.isChecked = config.enableWebrtc
        binding.chipWebtransport.isChecked = config.enableWebtransport
        binding.chipTcp.isChecked = config.enableTcp
        binding.actvTransportStrategy.setText(config.transportStrategy, false)
        binding.switchAcceptSubnets.isChecked = config.acceptSubnets
        binding.etAdvertisedSubnets.setText(config.advertisedSubnets.joinToString("\n"))
        binding.etAllowedSubnetPeers.setText(config.allowedSubnetPeers.joinToString(", "))
        binding.switchWebUi.isChecked = config.webUiEnable
        binding.etWebUiPort.setText(config.webUiPort.toString())
        binding.etWebUiToken.setText(config.webUiToken)
        binding.actvLogLevel.setText(config.logLevel, false)
        // Stored in milliseconds; the field is labelled seconds, so show seconds.
        binding.etHolePunchTimeout.setText((config.holePunchTimeout / 1000).toString())
        binding.etStunServers.setText(config.stunServers.joinToString("\n"))
        binding.etTurnServers.setText(config.turnServers.joinToString("\n"))
        // displayConfig runs before setupRealTimeValidation installs the field
        // watchers, so the textChanged callbacks that would refresh this do not
        // fire yet.
        refreshReloadPlan()
    }

    private fun collectConfigFromUi(): P2PConfig {
        val savedConfig = AppConfigManager.load(requireContext())
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
        val disableCircuitRelay = binding.switchDisableCircuitRelay.isChecked
        val disableHolePunching = binding.switchDisableHolePunching.isChecked
        val obfuscation = binding.switchObfuscation.isChecked
        val strictKey = binding.switchStrictKey.isChecked
        val paddingRules = binding.etPaddingRules.text?.toString()?.trim() ?: "0 -> 400-600"
        val obfAlgo = binding.actvObfuscationAlgo.text?.toString()?.trim() ?: "auto"
        val enableQuic = binding.chipQuic.isChecked
        val enableWebrtc = binding.chipWebrtc.isChecked
        val enableWebtransport = binding.chipWebtransport.isChecked
        val enableTcp = binding.chipTcp.isChecked
        val strategy = binding.actvTransportStrategy.text?.toString()?.trim() ?: "best_path"
        val acceptSubnets = binding.switchAcceptSubnets.isChecked
        val advString = binding.etAdvertisedSubnets.text?.toString() ?: ""
        val advList = advString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val aspString = binding.etAllowedSubnetPeers.text?.toString() ?: "*"
        val aspList = aspString.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        val dnsString = binding.etDnsServers.text?.toString() ?: ""
        val dnsList = dnsString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // The field is labelled seconds; the config stores milliseconds.
        val holePunchTimeoutSeconds = binding.etHolePunchTimeout.text?.toString()?.toLongOrNull() ?: 15
        val holePunchTimeout = holePunchTimeoutSeconds * 1000
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
            paddingRules = paddingRules,
            obfuscationAlgorithm = obfAlgo,
            strictKeyNegotiation = strictKey,
            enableQuic = enableQuic,
            enableWebrtc = enableWebrtc,
            enableWebtransport = enableWebtransport,
            enableTcp = enableTcp,
            disableRelay = disableRelay,
            disableCircuitRelay = disableCircuitRelay,
            disableHolePunching = disableHolePunching,
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
            // Engine-only fields with no native editor: carry forward from saved config
            // to prevent silent data loss on every save.
            obfuscationMaxFragSize = savedConfig.obfuscationMaxFragSize,
            forcePrivateReachability = savedConfig.forcePrivateReachability,
            listenAddrs = savedConfig.listenAddrs,
            tapName = savedConfig.tapName,
            tapMac = savedConfig.tapMac,
            driverType = savedConfig.driverType,
            webUiListenIpv6 = savedConfig.webUiListenIpv6,
            relayUpgradeInterval = savedConfig.relayUpgradeInterval,
            relayUpgradeMaxBackoff = savedConfig.relayUpgradeMaxBackoff,
            relayUpgradeFlapWindow = savedConfig.relayUpgradeFlapWindow,
            aclEnable = savedConfig.aclEnable,
            aclDefaultAction = savedConfig.aclDefaultAction,
            aclRulesJson = savedConfig.aclRulesJson,
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
            Toast.makeText(requireContext(), getString(R.string.err_invalid_ipv4), Toast.LENGTH_SHORT).show()
            return
        }
        // TAP IPv6 CIDR (if non-empty)
        if (config.tapIpv6.isNotEmpty() && !isValidIpv6Cidr(config.tapIpv6)) {
            Toast.makeText(requireContext(), getString(R.string.err_invalid_ipv6), Toast.LENGTH_SHORT).show()
            return
        }
        // MTU range
        if (config.mtu < 576 || config.mtu > 9000) {
            Toast.makeText(requireContext(), getString(R.string.err_invalid_mtu), Toast.LENGTH_SHORT).show()
            return
        }
        // WebUI port
        if (config.webUiPort < 1 || config.webUiPort > 65535) {
            Toast.makeText(requireContext(), getString(R.string.err_invalid_port), Toast.LENGTH_SHORT).show()
            return
        }
        // Hole punch timeout (1s–120s). Stored in ms; mirrors the engine's own
        // Validate, which resets anything outside 100ms..120s to the 15s default.
        if (config.holePunchTimeout < 1000 || config.holePunchTimeout > 120000) {
            Toast.makeText(requireContext(), getString(R.string.err_invalid_timeout), Toast.LENGTH_SHORT).show()
            return
        }
        // Node name
        if (config.nodeName.length > 64) {
            Toast.makeText(requireContext(), getString(R.string.err_invalid_node_name), Toast.LENGTH_SHORT).show()
            return
        }
        // Advertised subnets (each line must be a valid CIDR)
        for (sub in config.advertisedSubnets) {
            if (sub.isNotEmpty() && !isValidCidr(sub)) {
                Toast.makeText(requireContext(), getString(R.string.err_invalid_subnet_fmt, sub), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // DNS servers (each must be a valid IP address)
        for (dns in config.dnsServers) {
            if (dns.isNotEmpty() && !InetAddresses.isNumericAddress(dns)) {
                Toast.makeText(requireContext(), getString(R.string.err_invalid_dns_fmt, dns), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // STUN servers (basic format check)
        for (srv in config.stunServers) {
            if (srv.isNotEmpty() && !isValidStunServer(srv)) {
                Toast.makeText(requireContext(), getString(R.string.err_invalid_stun_fmt, srv), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // TURN servers (must start with turn: and have valid port)
        for (srv in config.turnServers) {
            if (srv.isNotEmpty() && !isValidTurnServer(srv)) {
                Toast.makeText(requireContext(), getString(R.string.err_invalid_turn_fmt, srv), Toast.LENGTH_SHORT).show()
                return
            }
        }
        // Obfuscation params
        if (config.obfuscationMaxFragSize > 0 && config.obfuscationMaxFragSize < 256) {
            Toast.makeText(requireContext(), getString(R.string.err_invalid_max_frag_size), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            AppConfigManager.save(requireContext(), config)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.config_invalid_fmt, UiMessages.describe(requireContext(), e)), Toast.LENGTH_LONG).show()
            return
        }
        AppConfigManager.reloadRunningService(requireContext())

        // Explicit success feedback before handing control back to the tab the
        // user came from. Previously the save silently hopped to Home, so the
        // user could not tell the config was actually committed.
        Toast.makeText(requireContext(), getString(R.string.msg_config_saved), Toast.LENGTH_SHORT).show()

        // What finish() used to do: hand control back to the tab the user came
        // from. The host decides where that is.
        (activity as? Host)?.onSettingsSaved()
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
        if (!android.net.InetAddresses.isNumericAddress(parts[0])) return false
        val prefix = parts[1].toIntOrNull() ?: return false
        return prefix in 0..128
    }

    private fun isValidCidr(s: String): Boolean {
        return isValidIpv4Cidr(s) || isValidIpv6Cidr(s)
    }

    private fun isValidStunServer(s: String): Boolean {
        val trimmed = s.trim()
        if (trimmed.startsWith("/udp/") || trimmed.startsWith("/tcp/")) return true
        var withoutPrefix = trimmed.removePrefix("stun:").removePrefix("turn:").removePrefix("tcp:")
        val colonIdx = withoutPrefix.lastIndexOf(':')
        if (colonIdx < 0) {
            // Bare hostname — Go defaults to port 3478.
            val host = withoutPrefix
            return host.isNotEmpty() && !host.contains(" ")
        }
        val host = withoutPrefix.substring(0, colonIdx)
        val port = withoutPrefix.substring(colonIdx + 1)
        if (host.isEmpty()) return false
        val p = port.toIntOrNull() ?: return false
        return p in 1..65535
    }

    private fun isValidTurnServer(s: String): Boolean {
        if (!s.startsWith("turn:")) return false
        val withoutPrefix = s.removePrefix("turn:")
        val queryIdx = withoutPrefix.indexOf('?')
        val hostPart = if (queryIdx >= 0) withoutPrefix.substring(0, queryIdx) else withoutPrefix
        val colonIdx = hostPart.lastIndexOf(':')
        if (colonIdx < 0) {
            // Bare hostname — Go defaults to port 3478.
            return hostPart.isNotEmpty() && !hostPart.contains(" ")
        }
        val port = hostPart.substring(colonIdx + 1)
        val p = port.toIntOrNull() ?: return false
        return p in 1..65535
    }
}
