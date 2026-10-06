package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PConfig
import app.fjj.p2ptap.databinding.DialogQrEnhancedBinding
import app.fjj.p2ptap.service.P2PTapVpnService
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder
import java.util.EnumMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "QrDialog"
        fun newInstance(): QrDialog = QrDialog()
    }

    private var _binding: DialogQrEnhancedBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogQrEnhancedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val cfg = AppConfigManager.load(ctx)
        val peerId = AppConfigManager.getPeerId(ctx)

        binding.tvQrNodeName.text = if (cfg.nodeName.isNotBlank()) cfg.nodeName else getString(R.string.scan_node_unknown)

        if (peerId.isNotBlank()) {
            binding.tvQrPeerId.text = peerId
        } else {
            binding.tvQrPeerId.text = getString(R.string.status_connecting)
        }

        binding.tvQrIpv4.text = if (cfg.tapIp.isNotBlank()) cfg.tapIp else "10.0.0.88/24"

        if (cfg.tapIpv6.isNotBlank()) {
            binding.layoutQrIpv6.visibility = View.VISIBLE
            binding.tvQrIpv6.text = cfg.tapIpv6
        } else {
            binding.layoutQrIpv6.visibility = View.GONE
        }

        if (cfg.advertisedSubnets.isNotEmpty()) {
            binding.layoutQrSubnets.visibility = View.VISIBLE
            binding.tvQrSubnets.text = cfg.advertisedSubnets.joinToString(", ")
        } else {
            binding.layoutQrSubnets.visibility = View.GONE
        }

        // getMultiaddrs() takes the engine's global lock, which an in-progress
        // stop holds for several seconds — calling it here froze the sheet, and
        // the 512x512 setPixel loop below adds tens of milliseconds on top.
        // Both run off the main thread; the node info above is already on screen.
        viewLifecycleOwner.lifecycleScope.launch {
            val multiaddrsStr = withContext(Dispatchers.IO) {
                if (!P2PTapVpnService.isRunning()) return@withContext ""
                try {
                    com.p2ptap.P2PTap.P2PTap.getMultiaddrs().orEmpty()
                } catch (_: Exception) {
                    ""
                }
            }
            val qrUri = buildQrUri(cfg, peerId, multiaddrsStr)
            try {
                binding.ivQrCode.setImageBitmap(withContext(Dispatchers.Default) { generateQrBitmap(qrUri, 512) })
            } catch (_: Exception) {}
            wireQrActions(ctx, cfg, peerId, multiaddrsStr, qrUri)
        }
    }

    private fun buildQrUri(cfg: P2PConfig, peerId: String, multiaddrsStr: String): String = buildString {
        append("p2ptap://")
        append(URLEncoder.encode(cfg.nodeName, "UTF-8"))
        append("?peerid=").append(peerId)
        append("&ip=").append(URLEncoder.encode(cfg.tapIp, "UTF-8"))
        if (cfg.tapIpv6.isNotBlank()) {
            append("&ipv6=").append(URLEncoder.encode(cfg.tapIpv6, "UTF-8"))
        }
        if (multiaddrsStr.isNotBlank()) {
            val list = multiaddrsStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isNotEmpty()) {
                append("&addrs=").append(URLEncoder.encode(list.joinToString(","), "UTF-8"))
            }
        }
        if (cfg.advertisedSubnets.isNotEmpty()) {
            append("&subnets=").append(URLEncoder.encode(cfg.advertisedSubnets.joinToString(","), "UTF-8"))
        }
    }

    private fun wireQrActions(
        ctx: Context,
        cfg: P2PConfig,
        peerId: String,
        multiaddrsStr: String,
        qrUri: String
    ) {
        binding.ivCopyPeerId.setOnClickListener {
            copyToClipboard(getString(R.string.label_peer_id), peerId)
        }

        binding.ivCopyIpv4.setOnClickListener {
            copyToClipboard(getString(R.string.label_tap_ip), cfg.tapIp)
        }

        binding.ivCopyIpv6.setOnClickListener {
            copyToClipboard(getString(R.string.label_tap_ipv6), cfg.tapIpv6)
        }

        binding.btnCopyQrUri.setOnClickListener {
            copyToClipboard(getString(R.string.label_connection_uri), qrUri)
            Toast.makeText(ctx, getString(R.string.msg_qr_copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnShareQrText.setOnClickListener {
            val shareSummary = buildString {
                append(getString(R.string.qr_connection_card_title)).append("\n")
                append(getString(R.string.label_node_name)).append(": ").append(cfg.nodeName).append("\n")
                append(getString(R.string.label_tap_ip)).append(": ").append(cfg.tapIp).append("\n")
                if (cfg.tapIpv6.isNotBlank()) {
                    append(getString(R.string.label_tap_ipv6)).append(": ").append(cfg.tapIpv6).append("\n")
                }
                append(getString(R.string.label_peer_id)).append(": ").append(peerId).append("\n")
                if (multiaddrsStr.isNotBlank()) {
                    append(getString(R.string.label_multiaddrs)).append(":\n").append(multiaddrsStr).append("\n")
                }
                append(getString(R.string.label_connection_uri)).append(":\n").append(qrUri)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.qr_share_subject_fmt, cfg.nodeName))
                putExtra(Intent.EXTRA_TEXT, shareSummary)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.opt_share_file)))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun copyToClipboard(label: String, content: String) {
        if (content.isNotBlank()) {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, content))
            Toast.makeText(requireContext(), getString(R.string.msg_copied_label_fmt, label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
        }
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        val primaryColor = Color.parseColor("#0F172A")
        val accentColor = Color.parseColor("#0891B2")

        for (x in 0 until size) {
            for (y in 0 until size) {
                if (bitMatrix.get(x, y)) {
                    val isCorner = (x < size / 4 && y < size / 4) ||
                            (x > size * 3 / 4 && y < size / 4) ||
                            (x < size / 4 && y > size * 3 / 4)
                    bitmap.setPixel(x, y, if (isCorner) accentColor else primaryColor)
                } else {
                    bitmap.setPixel(x, y, Color.WHITE)
                }
            }
        }
        return bitmap
    }
}
