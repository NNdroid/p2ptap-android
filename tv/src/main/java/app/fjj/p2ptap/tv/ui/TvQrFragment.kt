package app.fjj.p2ptap.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.TvTransfer
import app.fjj.p2ptap.tv.databinding.FragmentTvQrBinding
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QR display screen: the code a phone reads.
 *
 * A set-top box has no camera, so this screen can only point outward. Everything
 * that leaves the device passes through this one bitmap, and the four modes are
 * the four payloads a receiver can meaningfully take: the whole node, the config
 * without the identity, the connection URI, or the identity alone.
 */
class TvQrFragment : Fragment() {

    private var _binding: FragmentTvQrBinding? = null
    private val binding get() = _binding!!

    private var mode = Mode.BUNDLE
    private var paintedPayload: String? = null

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val content = withContext(Dispatchers.Default) {
                runCatching { TvTransfer.fullBackup(requireContext()) }.getOrNull()
            }
            if (content == null) {
                toast(R.string.tv_qr_failed)
                return@launch
            }
            val ok = runCatching {
                requireContext().contentResolver.openOutputStream(uri)
                    ?.use { it.write(content.toByteArray()) }
            }.isSuccess
            toast(if (ok) R.string.tv_qr_saved else R.string.tv_qr_failed)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvQrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvQrModeBundle.setOnClickListener { select(Mode.BUNDLE) }
        binding.tvQrModeConfig.setOnClickListener { select(Mode.CONFIG) }
        binding.tvQrModeUri.setOnClickListener { select(Mode.URI) }
        binding.tvQrModeKey.setOnClickListener { select(Mode.KEY) }

        binding.tvQrCopy.setOnClickListener {
            val text = paintedPayload
            if (text.isNullOrEmpty()) {
                toast(R.string.tv_qr_failed)
            } else {
                TvTransfer.clipboardSet(requireContext(), getString(R.string.tv_screen_qr), text)
                toast(R.string.tv_qr_copied)
            }
        }
        binding.tvQrSave.setOnClickListener { saveLauncher.launch("p2ptap-backup.json") }

        loadIdentity()
        binding.tvQrModeBundle.requestFocus()
    }

    private fun loadIdentity() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val ctx = requireContext()
            val cfg = runCatching { AppConfigManager.load(ctx) }.getOrNull()
            val id = runCatching { AppConfigManager.getPeerId(ctx) }.getOrNull().orEmpty()
            val addrs = if (P2PTapVpnService.isRunning()) {
                runCatching { P2PTap.getMultiaddrs() }.getOrNull()?.trim().orEmpty()
            } else {
                ""
            }
            val nodeName = cfg?.nodeName?.trim().orEmpty()

            launch(Dispatchers.Main) {
                binding.tvQrNodeName.text =
                    if (nodeName.isEmpty()) getString(R.string.tv_qr_node_unknown) else nodeName
                binding.tvQrPeerId.text =
                    if (id.isEmpty()) getString(R.string.tv_qr_peer_unknown)
                    else getString(R.string.tv_qr_peer_fmt, id.take(18) + "…")
                binding.tvQrTap.text =
                    if (cfg == null) ""
                    else getString(
                        R.string.tv_qr_tap_fmt,
                        cfg.tapIp.ifBlank { getString(R.string.tv_unknown) }
                    )
                repaint()
            }
        }
    }

    private fun select(next: Mode) {
        if (next == mode) return
        mode = next
        repaint()
    }

    private fun repaint() {
        binding.tvQrModeBundle.isSelected = mode == Mode.BUNDLE
        binding.tvQrModeConfig.isSelected = mode == Mode.CONFIG
        binding.tvQrModeUri.isSelected = mode == Mode.URI
        binding.tvQrModeKey.isSelected = mode == Mode.KEY

        binding.tvQrBitmap.visibility = View.VISIBLE
        binding.tvQrBitmap.alpha = 0.35f
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val payload = runCatching { payloadFor(mode) }.getOrNull()
            val bitmap = payload?.let { runCatching { TvTransfer.qrBitmap(it) }.getOrNull() }
            launch(Dispatchers.Main) {
                paintedPayload = payload
                binding.tvQrBitmap.alpha = 1f
                if (bitmap == null) {
                    binding.tvQrBitmap.scaleType = android.widget.ImageView.ScaleType.CENTER
                    binding.tvQrBitmap.setImageResource(R.drawable.ic_tv_qr)
                } else {
                    binding.tvQrBitmap.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    binding.tvQrBitmap.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun payloadFor(next: Mode): String? = when (next) {
        Mode.BUNDLE -> TvTransfer.fullBackup(requireContext())
        Mode.CONFIG -> TvTransfer.configOnly(requireContext())
        Mode.URI -> TvTransfer.connectionUri(
            AppConfigManager.load(requireContext()),
            TvTransfer.peerId(requireContext()),
            multiaddrs()
        )
        Mode.KEY -> TvTransfer.identityKey(requireContext())
    }

    private fun multiaddrs(): String =
        if (P2PTapVpnService.isRunning()) {
            runCatching { P2PTap.getMultiaddrs() }.getOrNull()?.trim().orEmpty()
        } else {
            ""
        }

    private fun toast(res: Int) =
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private enum class Mode { BUNDLE, CONFIG, URI, KEY }
}
