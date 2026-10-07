package app.fjj.p2ptap.tv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.EnumMap

/**
 * S3: how a configuration actually reaches a set-top box.
 *
 * A TV has no keyboard and no camera, so every free-text field in the config is
 * unreachable by typing and every QR code is unreachable by scanning. What
 * remains is exactly four directions:
 *
 *   file  -> TV   SAF open document, the system file picker is remote-navigable
 *   TV    -> file SAF create document, same picker
 *   TV    -> phone   a code drawn on screen for a phone camera to read
 *   phone -> TV   clipboard, or the code above read back into a phone
 *
 * This object holds the two halves that both screens need: writing a code to a
 * bitmap, and shaping the text that goes inside it. Keeping them here rather
 * than in each Activity means the TV and the phone cannot drift apart over what
 * a "backup bundle" or a "connection URI" means.
 */
object TvTransfer {

    /** Document types the system file picker should offer. Deliberately wide. */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/json",
        "text/*",
        "application/octet-stream"
    )

    /** The three things a piece of pasted text can be. */
    enum class Kind { CONFIG, IDENTITY, UNKNOWN }

    private const val BITMAP = 768
    private val DARK = Color.parseColor("#0F172A")
    private val CORNER = Color.parseColor("#0891B2")
    private const val WHITE = Color.WHITE

    // ── Reading ─────────────────────────────────────────────────────────────

    fun readUri(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) throw IllegalStateException("no stream")
            BufferedReader(InputStreamReader(stream)).readText()
        }

    fun clipboardText(context: Context): String? =
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = cm.primaryClip?.getItemAt(0) ?: return null
            val text = item.coerceToText(context).toString().trim()
            text.takeIf { it.isNotEmpty() }
        }.getOrNull()

    fun clipboardSet(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * Decide what a chunk of free text is before doing anything with it.
     *
     * Order matters: a full backup bundle is JSON that also contains a base64
     * key, so it must be recognised as a config before the key branch can see
     * it.
     */
    fun classify(raw: String): Kind {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Kind.UNKNOWN
        if (trimmed.startsWith("{")) {
            return runCatching {
                org.json.JSONObject(trimmed)
            }.getOrNull()?.let { _ -> Kind.CONFIG } ?: Kind.UNKNOWN
        }
        // A node key is base64. Restrict to plausible key lengths so a pasted
        // sentence is not mistaken for a credential.
        if (trimmed.matches(Regex("^[A-Za-z0-9+/=_\\-]+$")) && trimmed.length in 32..4096) {
            return Kind.IDENTITY
        }
        return Kind.UNKNOWN
    }

    /**
     * Import whatever the caller has, returning the peer id when an identity
     * was restored and null otherwise.
     */
    fun importText(context: Context, raw: String): Pair<Boolean, String?> {
        return when (classify(raw)) {
            Kind.CONFIG -> {
                val (_, restored) = AppConfigManager.importBackupOrConfig(context, raw)
                AppConfigManager.reloadRunningService(context, forceRestart = true)
                Pair(true, restored)
            }
            Kind.IDENTITY -> {
                val restored = AppConfigManager.importIdentityKeyBase64(context, raw.trim())
                AppConfigManager.reloadRunningService(context, forceRestart = true)
                Pair(true, restored)
            }
            Kind.UNKNOWN -> Pair(false, null)
        }
    }

    // ── Export ──────────────────────────────────────────────────────────────

    /** Full bundle: config plus identity. A phone scanning it becomes this node. */
    fun fullBackup(context: Context): String = AppConfigManager.exportFullBackupBundle(context)

    /** Config only: no identity, so the receiving side keeps its own. */
    fun configOnly(context: Context): String =
        AppConfigManager.load(context).toExportJson()

    /** Identity only: this node's key, so it can be re-created elsewhere. */
    fun identityKey(context: Context): String =
        AppConfigManager.exportIdentityKeyBase64(context)

    fun peerId(context: Context): String = AppConfigManager.getPeerId(context)

    /**
     * The URI the phone build shows in its QR dialog, reproduced here so the two
     * form factors present the same node the same way.
     */
    fun connectionUri(cfg: P2PConfig, peerId: String, multiaddrs: String): String = buildString {
        append("p2ptap://")
        append(URLEncoder.encode(cfg.nodeName.ifBlank { "p2ptap" }, "UTF-8"))
        append("?peerid=").append(peerId)
        append("&ip=").append(URLEncoder.encode(cfg.tapIp.ifBlank { "10.0.0.88" }, "UTF-8"))
        if (cfg.tapIpv6.isNotBlank()) {
            append("&ipv6=").append(URLEncoder.encode(cfg.tapIpv6, "UTF-8"))
        }
        if (multiaddrs.isNotBlank()) {
            val list = multiaddrs.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isNotEmpty()) {
                append("&addrs=").append(URLEncoder.encode(list.joinToString(","), "UTF-8"))
            }
        }
        if (cfg.advertisedSubnets.isNotEmpty()) {
            append("&subnets=").append(
                URLEncoder.encode(cfg.advertisedSubnets.joinToString(","), "UTF-8")
            )
        }
    }

    // ── Encoding ────────────────────────────────────────────────────────────

    /**
     * Draw a QR code.
     *
     * Rendered at [BITMAP] square rather than the displayed size so a 4K set
     * stays crisp; the ImageView scales it down. Level M is the middle ground
     * for phone cameras reading a screen: H would halve the data capacity for
     * a benefit a few feet away cannot use, L would fail on a glance.
     */
    fun qrBitmap(content: String): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
        }
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, BITMAP, BITMAP, hints)
        val bitmap = Bitmap.createBitmap(BITMAP, BITMAP, Bitmap.Config.ARGB_8888)

        val quarter = BITMAP / 4
        for (x in 0 until BITMAP) {
            for (y in 0 until BITMAP) {
                if (!matrix.get(x, y)) {
                    bitmap.setPixel(x, y, WHITE)
                    continue
                }
                // The three finder patterns are the ones a camera locks onto.
                // Giving them the accent colour is what makes the code read as
                // this app's at a glance from the sofa.
                val isFinder = (x < quarter && y < quarter) ||
                    (x > BITMAP - quarter && y < quarter) ||
                    (x < quarter && y > BITMAP - quarter)
                bitmap.setPixel(x, y, if (isFinder) CORNER else DARK)
            }
        }
        return bitmap
    }
}
