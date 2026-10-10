package app.fjj.p2ptap.tv

import android.content.Context
import app.fjj.p2ptap.service.P2PStateRepository
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Presentational glue shared by every TV screen: turn an engine value into
 * something a viewer can read from ten feet away.
 *
 * Everything here is a pure lookup. The numbers themselves come from
 * [app.fjj.p2ptap.service.NodeMetrics] and
 * [app.fjj.p2ptap.service.PeerItemData], which are already unit-tested in
 * :core; this file only decides what a viewer is shown. It must stay free of
 * JNI so it is safe to call on the main thread at focus speed — the formatters
 * in [P2PStateRepository] are pure string math, so reusing them here is fine.
 */
object TvFormat {

    /** Resolution of a peer transport string into a label and its chip colour. */
    data class Protocol(val label: String, val colorRes: Int)

    /**
     * Map an engine transport string to the chip a viewer sees.
     *
     * The engine emits whatever the libp2p transport was called, so the match
     * is substring based and case insensitive: `quic-v1` and `QUIC` are the
     * same link. A relayed hop always wins, because the chip is answering the
     * viewer's actual question — how did this packet leave the house — and
     * "over QUIC via a relay" collapses to the same fact either way. An unknown
     * string still gets a chip rather than a blank.
     */
    fun transport(context: Context, transport: String, relayed: Boolean): Protocol {
        if (relayed) return Protocol(context.getString(R.string.tv_prot_relay), R.color.tv_prot_relay)
        val lower = transport.lowercase(Locale.ROOT).trim()
        return when {
            lower.contains("webtransport") -> Protocol(
                context.getString(R.string.tv_prot_wt), R.color.tv_prot_wt
            )
            lower.contains("quic") -> Protocol(
                context.getString(R.string.tv_prot_quic), R.color.tv_prot_quic
            )
            lower.contains("webrtc") -> Protocol(
                context.getString(R.string.tv_prot_webrtc), R.color.tv_prot_webrtc
            )
            lower.contains("tcp") -> Protocol(
                context.getString(R.string.tv_prot_tcp), R.color.tv_prot_tcp
            )
            lower.contains("relay") -> Protocol(
                context.getString(R.string.tv_prot_relay), R.color.tv_prot_relay
            )
            lower.isBlank() -> Protocol(
                context.getString(R.string.tv_prot_unknown), R.color.tv_prot_unknown
            )
            else -> Protocol(lower.uppercase(Locale.ROOT), R.color.tv_prot_unknown)
        }
    }

    /**
     * Shorten an unbounded engine string so a row fits on one line at 18sp.
     * Peer IDs and multiaddrs are identified by both ends, so keeping head and
     * tail preserves the parts a viewer actually cross-references.
     */
    fun shorten(value: String, head: Int, tail: Int): String {
        val trimmed = value.trim()
        if (trimmed.length <= head + tail + 1) return trimmed
        return trimmed.substring(0, head) + "…" + trimmed.substring(trimmed.length - tail)
    }

    /**
     * Resolve a value that may be blank, falling back to the em-dash
     * placeholder.
     *
     * Engine fields are optional: a peer that never negotiated encryption
     * reports an empty algorithm, and the viewer wants to see "unknown", not an
     * empty slot that implies the row is broken.
     */
    fun orUnknown(context: Context, value: String): String =
        value.trim().ifBlank { context.getString(R.string.tv_unknown) }

    /** Format an RTT in ms, or the honest "not measured" when there is none. */
    fun rtt(context: Context, rttMs: Double, measured: Boolean): String {
        if (!measured || !rttMs.isFinite() || rttMs < 0) {
            return context.getString(R.string.tv_rtt_unmeasured)
        }
        // Round to the first decimal: below 100 ms that is the resolution the
        // engine itself reports, and it stops a number from flickering on a
        // 10 Hz refresh while the link is stable.
        val rounded = (rttMs * 10).roundToInt() / 10.0
        return context.getString(R.string.tv_rtt_fmt, "%.1f".format(Locale.ROOT, rounded))
    }

    /** Re-export the :core formatters so screens have one import to reach. */
    fun bytes(bytes: Long): String = P2PStateRepository.formatBytes(bytes)

    fun speed(bytesPerSec: Long): String = P2PStateRepository.formatSpeed(bytesPerSec)
}
