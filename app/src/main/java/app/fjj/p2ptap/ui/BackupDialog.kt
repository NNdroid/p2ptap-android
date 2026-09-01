package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.databinding.DialogBackupBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.BufferedReader
import java.io.InputStreamReader

class BackupDialog(private val onImportSuccess: (() -> Unit)? = null) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "BackupDialog"
        fun newInstance(onImportSuccess: (() -> Unit)? = null): BackupDialog = BackupDialog(onImportSuccess)
    }

    private var _binding: DialogBackupBinding? = null
    private val binding get() = _binding!!

    // Local JSON file picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val jsonStr = reader.readText()
                    val (cfg, restoredPid) = AppConfigManager.importBackupOrConfig(requireContext(), jsonStr)
                    val msg = if (restoredPid != null) {
                        "导入成功！已恢复配置及 Peer ID: " + restoredPid.take(12) + "..."
                    } else {
                        getString(R.string.msg_import_success)
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    onImportSuccess?.invoke()
                    dismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.msg_import_failed) + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Camera QR Scanner launcher
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleImportContent(result.contents)
        }
    }

    // Gallery Image QR picker launcher
    private val galleryQrLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val content = QrImportHelper.decodeQrFromUri(requireContext(), uri)
            if (content != null) {
                handleImportContent(content)
            } else {
                Toast.makeText(requireContext(), getString(R.string.msg_scan_no_result), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        // 1. Export Full Bundle
        binding.btnExportBundle.setOnClickListener {
            try {
                val bundleJson = AppConfigManager.exportFullBackupBundle(ctx)
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("P2PTap Full Backup", bundleJson))
                Toast.makeText(ctx, getString(R.string.msg_backup_copied), Toast.LENGTH_SHORT).show()

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, "p2ptap-backup.json")
                    putExtra(Intent.EXTRA_TEXT, bundleJson)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.opt_share_file)))
                dismiss()
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "", Toast.LENGTH_LONG).show()
            }
        }

        // 2. Export Config Only
        binding.btnExportConfig.setOnClickListener {
            val cfg = AppConfigManager.load(ctx)
            val jsonStr = cfg.toExportJson()
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("P2PTap Config", jsonStr))
            Toast.makeText(ctx, getString(R.string.msg_config_copied), Toast.LENGTH_SHORT).show()
            dismiss()
        }

        // 3. Export Private Key (Base64)
        binding.btnExportKey.setOnClickListener {
            try {
                val keyB64 = AppConfigManager.exportIdentityKeyBase64(ctx)
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Peer ID Private Key", keyB64))
                Toast.makeText(ctx, getString(R.string.msg_key_copied), Toast.LENGTH_SHORT).show()
                dismiss()
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "", Toast.LENGTH_LONG).show()
            }
        }

        // 4. Scan QR to Import
        binding.btnScanQrImport.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt(getString(R.string.title_scan_qr))
                setBeepEnabled(true)
                setBarcodeImageEnabled(false)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        }

        // 5. Choose QR from Gallery
        binding.btnGalleryQrImport.setOnClickListener {
            galleryQrLauncher.launch("image/*")
        }

        // 6. Paste from Clipboard to Import
        binding.btnPasteImport.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                Toast.makeText(ctx, getString(R.string.msg_clipboard_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            handleImportContent(text)
        }

        // 7. Choose File
        binding.btnFileImport.setOnClickListener {
            filePickerLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }

        binding.btnCancelBackup.setOnClickListener {
            dismiss()
        }
    }

    private fun handleImportContent(raw: String) {
        val ctx = requireContext()
        val info = QrImportHelper.parse(raw)
        if (info == null) {
            Toast.makeText(ctx, getString(R.string.msg_scan_no_result), Toast.LENGTH_SHORT).show()
            return
        }

        if (info.isFullConfig && info.fullConfig != null) {
            try {
                val (cfg, restoredPid) = AppConfigManager.importBackupOrConfig(ctx, raw)
                val msg = if (restoredPid != null) {
                    getString(R.string.msg_key_loaded_fmt, restoredPid.take(12) + "...")
                } else {
                    getString(R.string.msg_import_success)
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                onImportSuccess?.invoke()
                dismiss()
                return
            } catch (_: Exception) {}
        }

        // Otherwise show interactive action dialog for scanned peer info
        QrImportHelper.showScanResultDialog(
            context = ctx,
            info = info,
            onAddStatic = { multiaddr ->
                val cfg = AppConfigManager.load(ctx)
                val current = cfg.staticPeers.toMutableList()
                if (!current.contains(multiaddr)) {
                    current.add(multiaddr)
                    cfg.staticPeers = current
                    AppConfigManager.save(ctx, cfg)
                    Toast.makeText(ctx, getString(R.string.msg_address_added), Toast.LENGTH_SHORT).show()
                    onImportSuccess?.invoke()
                    dismiss()
                } else {
                    Toast.makeText(ctx, getString(R.string.msg_peer_already_exists), Toast.LENGTH_SHORT).show()
                }
            },
            onAddBootstrap = { multiaddr ->
                val cfg = AppConfigManager.load(ctx)
                val current = cfg.bootstrapPeers.toMutableList()
                if (!current.contains(multiaddr)) {
                    current.add(multiaddr)
                    cfg.bootstrapPeers = current
                    AppConfigManager.save(ctx, cfg)
                    Toast.makeText(ctx, getString(R.string.msg_address_added), Toast.LENGTH_SHORT).show()
                    onImportSuccess?.invoke()
                    dismiss()
                } else {
                    Toast.makeText(ctx, getString(R.string.msg_peer_already_exists), Toast.LENGTH_SHORT).show()
                }
            },
            onImportFull = { fullConfig ->
                AppConfigManager.save(ctx, fullConfig)
                Toast.makeText(ctx, getString(R.string.msg_imported_saved), Toast.LENGTH_LONG).show()
                onImportSuccess?.invoke()
                dismiss()
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
