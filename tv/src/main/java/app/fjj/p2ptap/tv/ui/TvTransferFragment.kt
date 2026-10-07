package app.fjj.p2ptap.tv.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.TvTransfer
import app.fjj.p2ptap.tv.databinding.FragmentTvTransferBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S3 Transfer. Four source cards in a horizontal row, each one representing
 * one of the four ways to get a config or identity onto this TV:
 *
 *   1. From a file (SAF document picker)
 *   2. From the clipboard (paste a JSON blob)
 *   3. From a QR code (TV shows QR, phone scans)
 *   4. Generate a new identity (blank node)
 *
 * The TV cannot scan a QR itself — no camera — so the QR source card
 * launches the "display QR" flow where the phone is the scanner.
 */
class TvTransferFragment : Fragment() {

    private var _binding: FragmentTvTransferBinding? = null
    private val binding get() = _binding!!

    private var pendingNewIdentity = false
    private val statusHandler = Handler(Looper.getMainLooper())

    private val configLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> readAndImport(uri) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        TvFocusAnimation.attachTo(binding.tvTransferFile, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvTransferClipboard, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvTransferQr, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvTransferNew, TvFocusAnimation.SCALE_CARD)

        binding.tvTransferFile.setOnClickListener {
            configLauncher.launch(TvTransfer.IMPORT_MIME_TYPES)
        }
        binding.tvTransferClipboard.setOnClickListener { importClipboard() }
        binding.tvTransferQr.setOnClickListener {
            val navController = findNavController()
            navController.navigate(R.id.tvQrFragment)
        }
        binding.tvTransferNew.setOnClickListener {
            if (pendingNewIdentity) {
                statusHandler.removeCallbacks(resetArmRunnable)
                pendingNewIdentity = false
                generateNewIdentity()
            } else {
                pendingNewIdentity = true
                binding.tvTransferNew.isSelected = true
                statusHandler.postDelayed(resetArmRunnable, ARM_TIMEOUT_MS)
            }
        }

        binding.tvTransferFile.requestFocus()
    }

    override fun onDestroy() {
        statusHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Import paths ────────────────────────────────────────────────────────

    private fun readAndImport(uri: Uri?) {
        if (uri == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { TvTransfer.readUri(requireContext(), uri) }.getOrNull()
            }
            if (text == null) {
                toast(R.string.tv_import_read_failed)
                return@launch
            }
            finishImport(text)
        }
    }

    private fun importClipboard() {
        val text = TvTransfer.clipboardText(requireContext())
        when {
            text == null -> toast(R.string.tv_import_empty_clip)
            TvTransfer.classify(text) == TvTransfer.Kind.UNKNOWN ->
                toast(R.string.tv_import_unrecognized)
            else -> finishImport(text)
        }
    }

    private fun finishImport(raw: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val result = runCatching { TvTransfer.importText(requireContext(), raw) }
            launch(Dispatchers.Main) {
                when {
                    result.isFailure -> toast(R.string.tv_import_failed)
                    result.getOrNull() == null || !result.getOrNull()!!.first ->
                        toast(R.string.tv_import_unrecognized)
                    result.getOrNull()!!.second != null ->
                        toast(R.string.tv_import_done_identity, shortId(result.getOrNull()!!.second!!))
                    else -> toast(R.string.tv_import_done_cfg)
                }
            }
        }
    }

    // ── The one destructive action ─────────────────────────────────────────

    private fun generateNewIdentity() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val result = runCatching {
                val wasRunning = P2PTapVpnService.isRunning()
                if (wasRunning) {
                    AppConfigManager.reloadRunningService(requireContext(), forceRestart = false)
                }
                val id = AppConfigManager.generateNewIdentityKey(requireContext())
                if (wasRunning) {
                    AppConfigManager.reloadRunningService(requireContext(), forceRestart = true)
                }
                id
            }
            launch(Dispatchers.Main) {
                if (result.isSuccess) toast(R.string.tv_import_done_new, shortId(result.getOrNull().orEmpty()))
                else toast(R.string.tv_import_failed)
            }
        }
    }

    private val resetArmRunnable = Runnable {
        pendingNewIdentity = false
        if (_binding != null) _binding!!.tvTransferNew.isSelected = false
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun toast(res: Int, arg: String? = null) {
        val text = if (arg == null) getString(res) else getString(res, arg)
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }

    private fun shortId(id: String): String =
        if (id.length > 14) id.take(12) + "…" else id

    private companion object {
        const val ARM_TIMEOUT_MS = 5000L
    }
}
