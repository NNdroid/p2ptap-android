package app.fjj.p2ptap.tv

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.tv.databinding.ActivityTvQrBinding
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S3b: the code a phone reads.
 *
 * A set-top box has no camera, so this screen can only point outward. Everything
 * that leaves the device passes through this one bitmap, and the four modes are
 * the four payloads a receiver can meaningfully take: the whole node, the config
 * without the identity, the connection URI, or the identity alone.
 *
 * The payload and the bitmap are rebuilt on every mode change, not cached, and
 * always off the main thread: the identity and the address list can both change
 * while the screen is open, and a code encoding a stale address is worse than
 * none at all.
 */
class TvQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvQrBinding

    private var mode = Mode.BUNDLE

    /** The text behind the bitmap currently on screen, for the copy action. */
    private var paintedPayload: String? = null

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val content = withContext(Dispatchers.Default) {
                runCatching { TvTransfer.fullBackup(this@TvQrActivity) }.getOrNull()
            }
            if (content == null) {
                toast(R.string.tv_qr_failed)
                return@launch
            }
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            }.isSuccess
            toast(if (ok) R.string.tv_qr_saved else R.string.tv_qr_failed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvQrModeBundle.setOnClickListener { select(Mode.BUNDLE) }
        binding.tvQrModeConfig.setOnClickListener { select(Mode.CONFIG) }
        binding.tvQrModeUri.setOnClickListener { select(Mode.URI) }
        binding.tvQrModeKey.setOnClickListener { select(Mode.KEY) }

        binding.tvQrCopy.setOnClickListener {
            val text = paintedPayload
            if (text.isNullOrEmpty()) {
                toast(R.string.tv_qr_failed)
            } else {
                TvTransfer.clipboardSet(this, getString(R.string.tv_screen_qr), text)
                toast(R.string.tv_qr_copied)
            }
        }
        binding.tvQrSave.setOnClickListener { saveLauncher.launch("p2ptap-backup.json") }

        loadIdentity()
        binding.tvQrModeBundle.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus lands on the mode chips, because choosing the payload is the
        // only decision this screen has; the bitmap is not a control.
        if (hasFocus) binding.tvQrModeBundle.requestFocus()
    }

    private fun loadIdentity() {
        lifecycleScope.launch(Dispatchers.Default) {
            val ctx = this@TvQrActivity
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
        lifecycleScope.launch(Dispatchers.Default) {
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

    /** The four payloads. Each one is what a receiver can act on by itself. */
    private fun payloadFor(next: Mode): String? = when (next) {
        // Whole node: config plus identity. A phone scanning this becomes this
        // node, which is the point of a backup.
        Mode.BUNDLE -> TvTransfer.fullBackup(this)
        // Config only, no identity: the receiver keeps its own key.
        Mode.CONFIG -> TvTransfer.configOnly(this)
        // Connection URI, the same string the phone build shows.
        Mode.URI -> TvTransfer.connectionUri(
            AppConfigManager.load(this),
            TvTransfer.peerId(this),
            multiaddrs()
        )
        // Identity only: recreate this node elsewhere.
        Mode.KEY -> TvTransfer.identityKey(this)
    }

    private fun multiaddrs(): String =
        if (P2PTapVpnService.isRunning()) {
            runCatching { P2PTap.getMultiaddrs() }.getOrNull()?.trim().orEmpty()
        } else {
            ""
        }

    private fun toast(res: Int) =
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private enum class Mode { BUNDLE, CONFIG, URI, KEY }
}
