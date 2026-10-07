package app.fjj.p2ptap.i18n

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import app.fjj.p2ptap.core.R
import org.json.JSONException
import java.io.IOException

/** Carry a resource key across context-free configuration and JNI boundaries. */
class LocalizedException(
    @param:StringRes @get:StringRes val messageRes: Int,
    val formatArgs: List<Any> = emptyList(),
    cause: Throwable? = null
) : IllegalArgumentException(cause?.message, cause)

object UiMessages {
    private val configField = Regex("invalid config: ([a-z_]+(?:\\[\\d+])?):", RegexOption.IGNORE_CASE)
    private val activeObfuscation = Regex("Active \\(([^,]+) mode, (\\d+)B\\)")

    fun describe(context: Context, error: Throwable): String {
        // Preserve the diagnostic cause, but do not leak untranslated native errors into UI text.
        Log.w("P2PTapUi", "Operation failed", error)
        return render(context, error)
    }

    internal fun render(context: Context, error: Throwable): String {
        if (error is LocalizedException) return context.getString(error.messageRes, *error.formatArgs.toTypedArray())
        return when (error) {
            is JSONException -> context.getString(R.string.error_invalid_json)
            is IOException -> context.getString(R.string.error_io)
            else -> nativeError(context, error.message.orEmpty())
        }
    }

    fun nativeError(context: Context, message: String): String {
        val field = configField.find(message)?.groupValues?.get(1)
        if (field != null) return context.getString(R.string.error_config_field_fmt, field)
        val key = when {
            message.contains("timeout", true) || message.contains("deadline", true) -> R.string.error_timeout
            message.contains("invalid config", true) -> R.string.error_invalid_configuration
            message.contains("key", true) || message.contains("base64", true) -> R.string.error_identity_key
            else -> R.string.error_unknown
        }
        return context.getString(key)
    }

    fun rttSource(context: Context, source: String): String = context.getString(when (source) {
        "tap-icmp" -> R.string.rtt_tap_icmp
        "libp2p-ping" -> R.string.rtt_libp2p_ping
        "p2p-echo" -> R.string.rtt_p2p_echo
        else -> R.string.telemetry_unmeasured
    })

    fun priority(context: Context, score: Int): String = context.getString(when (score) {
        0 -> R.string.priority_loopback
        10 -> R.string.priority_lan
        20 -> R.string.priority_wan
        100 -> R.string.protocol_relay
        else -> R.string.priority_overlay
    }) + " ($score)"

    fun obfuscation(context: Context, raw: String): String {
        activeObfuscation.find(raw)?.let {
            return context.getString(R.string.security_active_fmt, it.groupValues[1], it.groupValues[2])
        }
        return when (raw.lowercase()) {
            "disabled" -> context.getString(R.string.stat_obf_disabled)
            "auto", "none", "tls", "random", "quic" -> context.getString(R.string.hint_obfuscation_mode) + ": $raw"
            else -> context.getString(R.string.telemetry_unknown)
        }
    }

    fun psk(context: Context, raw: String): String = context.getString(when {
        raw.contains("PSK-bound", true) -> R.string.security_psk_bound
        raw.contains("Public", true) || raw.equals("disabled", true) -> R.string.security_public
        else -> R.string.telemetry_unknown
    })

    fun connectionDetail(context: Context, raw: String): String {
        val resource = when {
            raw.contains("control path failed") -> R.string.detail_relay_control_failed
            raw.contains("no live transport") || raw == "no direct or relay connection" -> R.string.telemetry_unreachable
            raw.contains("awaiting verified") -> R.string.detail_wait_ready
            raw == "relay hop (echo/seqsync)" -> R.string.detail_relay_hop
            raw.contains("encryption handshake pending") -> R.string.detail_wait_encryption
            raw.contains("awaiting peer ready") -> R.string.detail_wait_ready
            raw.contains("standby") -> R.string.detail_wait_data
            raw.contains("decryption failing") -> R.string.telemetry_crypto_failed
            raw.contains("plaintext") -> R.string.stat_obf_disabled
            raw.endsWith("end-to-end OK") || raw.endsWith("OK via relay") -> R.string.telemetry_enabled
            raw.contains("protocol") -> R.string.detail_wait_protocol
            else -> R.string.telemetry_unknown
        }
        // The negotiated algorithm is a protocol identifier, not a translatable label.
        val algorithm = Regex("^(AES[^ ]*|ChaCha[^ ]*|XChaCha[^ ]*)", RegexOption.IGNORE_CASE).find(raw)?.value
        return context.getString(resource) + (algorithm?.let { " · $it" } ?: "")
    }
}
