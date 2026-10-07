package app.fjj.p2ptap.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.tv.databinding.ActivityTvImportBinding
import app.fjj.p2ptap.tv.databinding.ItemTvActionCardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S3a: how a configuration arrives at a set-top box.
 *
 * Four presses and nothing else, because a TV accepts no typed text. Three of
 * them are pure input, and the fourth destroys something, which is why it sits
 * last and asks twice. The status line at the bottom is the only feedback this
 * screen has: a result has to survive the viewer's attention wandering off the
 * card that caused it.
 */
class TvImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvImportBinding
    private val statusHandler = Handler(Looper.getMainLooper())

    /**
     * Whether the next press on "new identity" will really erase the current
     * one. First press arms it, second press does it, and the arm expires so a
     * press made out of reflex an hour later is not the same as a press made
     * after reading the card.
     */
    private var pendingNewIdentity = false

    private val configLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> readAndImport(uri) }

    private val identityLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> readAndImportIdentity(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvImportConfig.tvActionIcon.setImageResource(R.drawable.ic_tv_import)
        binding.tvImportConfig.tvActionTitle.setText(R.string.tv_import_config)
        binding.tvImportConfig.tvActionDesc.setText(R.string.tv_import_config_desc)
        binding.tvImportConfig.root.setOnClickListener {
            configLauncher.launch(TvTransfer.IMPORT_MIME_TYPES)
        }

        binding.tvImportIdentity.tvActionIcon.setImageResource(R.drawable.ic_tv_key)
        binding.tvImportIdentity.tvActionTitle.setText(R.string.tv_import_identity)
        binding.tvImportIdentity.tvActionDesc.setText(R.string.tv_import_identity_desc)
        binding.tvImportIdentity.root.setOnClickListener {
            identityLauncher.launch(TvTransfer.IMPORT_MIME_TYPES)
        }

        binding.tvImportClipboard.tvActionIcon.setImageResource(R.drawable.ic_tv_clipboard)
        binding.tvImportClipboard.tvActionTitle.setText(R.string.tv_import_clipboard)
        binding.tvImportClipboard.tvActionDesc.setText(R.string.tv_import_clipboard_desc)
        binding.tvImportClipboard.root.setOnClickListener { importClipboard() }

        binding.tvImportNew.tvActionIcon.setImageResource(R.drawable.ic_tv_key_new)
        binding.tvImportNew.tvActionTitle.setText(R.string.tv_import_new)
        binding.tvImportNew.tvActionDesc.setText(R.string.tv_import_new_desc)
        binding.tvImportNew.root.setOnClickListener {
            if (pendingNewIdentity) {
                statusHandler.removeCallbacks(resetArm)
                pendingNewIdentity = false
                repaintNewCard()
                generateNewIdentity()
            } else {
                pendingNewIdentity = true
                repaintNewCard()
                statusHandler.postDelayed(resetArm, ARM_TIMEOUT_MS)
            }
        }

        binding.tvImportQr.setOnClickListener {
            startActivity(android.content.Intent(this, TvQrActivity::class.java))
        }

        binding.tvImportConfig.root.requestFocus()
    }

    override fun onDestroy() {
        statusHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── The three import paths ──────────────────────────────────────────────

    private fun readAndImport(uri: Uri?) {
        if (uri == null) return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { TvTransfer.readUri(this@TvImportActivity, uri) }.getOrNull() }
            if (text == null) {
                report(R.string.tv_import_read_failed)
                return@launch
            }
            finishImport(text)
        }
    }

    private fun readAndImportIdentity(uri: Uri?) {
        if (uri == null) return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { TvTransfer.readUri(this@TvImportActivity, uri) }.getOrNull() }
            if (text == null) {
                report(R.string.tv_import_read_failed)
                return@launch
            }
            // The identity card promises a key, but the picker can hand back
            // anything. Classify anyway so a bundle is not silently dropped.
            when (TvTransfer.classify(text)) {
                TvTransfer.Kind.IDENTITY -> finishImport(text)
                TvTransfer.Kind.CONFIG -> finishImport(text)
                TvTransfer.Kind.UNKNOWN -> report(R.string.tv_import_unrecognized)
            }
        }
    }

    private fun importClipboard() {
        val text = TvTransfer.clipboardText(this)
        when {
            text == null -> report(R.string.tv_import_empty_clip)
            TvTransfer.classify(text) == TvTransfer.Kind.UNKNOWN -> report(R.string.tv_import_unrecognized)
            else -> finishImport(text)
        }
    }

    private fun finishImport(raw: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            val result = runCatching { TvTransfer.importText(this@TvImportActivity, raw) }
            launch(Dispatchers.Main) {
                when {
                    result.isFailure -> report(R.string.tv_import_failed)
                    result.getOrNull() == null || result.getOrNull()!!.first.not() ->
                        report(R.string.tv_import_unrecognized)
                    result.getOrNull()!!.second != null ->
                        report(R.string.tv_import_done_identity, shortId(result.getOrNull()!!.second!!))
                    else -> report(R.string.tv_import_done_cfg)
                }
            }
        }
    }

    // ── The one destructive action ─────────────────────────────────────────

    private fun generateNewIdentity() {
        lifecycleScope.launch(Dispatchers.Default) {
            val result = runCatching {
                // Stop first: the engine holds the key file open while the
                // tunnel is up, and replacing it underneath a live node is how
                // a node ends up with two identities at once.
                val wasRunning = app.fjj.p2ptap.service.P2PTapVpnService.isRunning()
                if (wasRunning) {
                    AppConfigManager.reloadRunningService(this@TvImportActivity, forceRestart = false)
                }
                val id = AppConfigManager.generateNewIdentityKey(this@TvImportActivity)
                if (wasRunning) {
                    AppConfigManager.reloadRunningService(this@TvImportActivity, forceRestart = true)
                }
                id
            }
            launch(Dispatchers.Main) {
                if (result.isSuccess) report(R.string.tv_import_done_new, shortId(result.getOrNull().orEmpty()))
                else report(R.string.tv_import_failed)
            }
        }
    }

    private fun repaintNewCard() {
        val card = binding.tvImportNew
        if (pendingNewIdentity) {
            card.tvActionTitle.setText(R.string.tv_import_new_confirm)
            card.root.isSelected = true
        } else {
            card.tvActionTitle.setText(R.string.tv_import_new)
            card.root.isSelected = false
        }
    }

    private val resetArm = Runnable {
        pendingNewIdentity = false
        repaintNewCard()
    }

    // ── Feedback ────────────────────────────────────────────────────────────

    private fun report(res: Int, arg: String? = null) {
        val text = if (arg == null) getString(res) else getString(res, arg)
        binding.tvImportStatus.text = text
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun shortId(id: String): String =
        if (id.length > 14) id.take(12) + "…" else id

    private companion object {
        const val ARM_TIMEOUT_MS = 5000L
    }
}
