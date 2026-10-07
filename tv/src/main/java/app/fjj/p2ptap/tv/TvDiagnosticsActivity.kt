package app.fjj.p2ptap.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.fjj.p2ptap.tv.databinding.ActivityTvDiagnosticsBinding
import app.fjj.p2ptap.tv.databinding.ItemTvMetricBinding
import app.fjj.p2ptap.service.EncryptionData
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.StatsSnapshot
import app.fjj.p2ptap.service.TelemetryState
import kotlinx.coroutines.launch

/**
 * S5: the engine's own counters, laid out for a viewer.
 *
 * The phone shows these as a 21-row BottomSheet, which is a scrollable dump
 * designed for a technical reader. A TV shows two columns of the same facts
 * in the order a viewer asks about them: traffic, then reliability, then
 * protocol mix, then security. No logs — logcat is not readable from a couch
 * and the shell path it uses does not work on locked-down sets anyway.
 */
class TvDiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvDiagnosticsBinding

    private val leftAdapter = MetricAdapter()
    private val rightAdapter = MetricAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDiagLeft.apply {
            adapter = leftAdapter
            setNumColumns(1)
        }
        binding.tvDiagRight.apply {
            adapter = rightAdapter
            setNumColumns(1)
        }

        binding.tvDiagLeft.requestFocus()
        observeRepository()
    }

    override fun onResume() {
        super.onResume()
        TvTelemetry.start(this)
    }

    override fun onPause() {
        TvTelemetry.stop()
        super.onPause()
    }

    private fun observeRepository() {
        lifecycleScope.launch {
            P2PStateRepository.telemetry.collect { telemetry ->
                val state = P2PStateRepository.state.value
                render(state, telemetry)
            }
        }
    }

    private fun render(state: String, telemetry: TelemetryState) {
        val running = state == P2PTapVpnService.STATE_RUNNING
        val snapshot = telemetry.snapshot

        val offline = !running
        val noData = running && snapshot == null
        binding.tvDiagColumns.visibility = if (offline || noData) View.GONE else View.VISIBLE
        binding.tvDiagEmpty.visibility = if (noData) View.VISIBLE else View.GONE

        binding.tvDiagSummary.text = when {
            offline -> getString(R.string.tv_diag_offline)
            noData && telemetry.failed -> getString(R.string.tv_no_telemetry)
            noData -> getString(R.string.tv_diag_empty)
            else -> {
                val sent = snapshot?.counters?.get("bytes_sent")
                val recv = snapshot?.counters?.get("bytes_recv")
                if (sent != null && recv != null) {
                    getString(R.string.tv_total_tx_fmt, TvFormat.bytes(sent)) + " · " +
                        getString(R.string.tv_total_rx_fmt, TvFormat.bytes(recv))
                } else {
                    getString(R.string.tv_diag_empty)
                }
            }
        }

        if (offline || snapshot == null) {
            leftAdapter.submit(emptyList())
            rightAdapter.submit(emptyList())
            return
        }

        val s = snapshot
        val c = s.counters

        // Left: traffic and reliability. Ordered by how a viewer reads it —
        // volume first, then the counters that indicate something is wrong.
        leftAdapter.submit(
            listOf(
                metric(R.string.tv_diag_bytes_sent, c["bytes_sent"], { TvFormat.bytes(it) }),
                metric(R.string.tv_diag_bytes_recv, c["bytes_recv"], { TvFormat.bytes(it) }),
                metric(R.string.tv_diag_packets_sent, c["packets_sent"], { it.toString() }),
                metric(R.string.tv_diag_packets_recv, c["packets_recv"], { it.toString() }),
                metric(R.string.tv_diag_dispatch_drops, c["dispatch_drops"], { it.toString() }),
                metric(R.string.tv_diag_dedup, c["dedup_count"], { it.toString() }),
                metric(R.string.tv_diag_replay_drops, c["replay_drops"], { it.toString() }),
                metric(R.string.tv_diag_synced_peers, c["synced_peers"], { it.toString() })
            )
        )

        // Right: protocol mix, then what is protecting the link, then where
        // traffic actually leaves. Encryption is summarised rather than
        // enumerated: the viewer wants "everything is encrypted", not a table.
        rightAdapter.submit(
            buildList {
                add(metric(R.string.tv_diag_ipv4, c["ipv4"], { it.toString() }))
                add(metric(R.string.tv_diag_ipv6, c["ipv6"], { it.toString() }))
                add(metric(R.string.tv_diag_arp, c["arp"], { it.toString() }))
                add(metric(R.string.tv_diag_ndp, c["ndp"], { it.toString() }))
                add(metric(R.string.tv_diag_tcp, c["tcp"], { it.toString() }))
                add(metric(R.string.tv_diag_udp, c["udp"], { it.toString() }))
                add(metric(R.string.tv_diag_icmp, c["icmp"], { it.toString() }))
                add(metric(R.string.tv_diag_other, c["other"], { it.toString() }))
                add(
                    Metric(
                        R.string.tv_diag_obfuscation,
                        TvFormat.orUnknown(this@TvDiagnosticsActivity, s.obfuscation)
                    )
                )
                add(
                    Metric(
                        R.string.tv_diag_psk,
                        TvFormat.orUnknown(this@TvDiagnosticsActivity, s.pskStatus)
                    )
                )
                add(Metric(R.string.tv_diag_section_security, encryptionSummary(s.encryption)))
                val exit = s.activeExitIpv4.ifBlank { s.activeExitPeerId }
                add(Metric(R.string.tv_diag_exit_node, TvFormat.shorten(exit, 28, 12)))
            }
        )
    }

    /**
     * Collapse the per-peer encryption table into one honest sentence.
     *
     * Individual peer encryption is a per-connection detail, and there is no
     * meaningful way to show a table from ten feet. The viewer's question is
     * "is my traffic protected", so this answers that and shows the count.
     */
    private fun encryptionSummary(encryption: List<EncryptionData>): String {
        if (encryption.isEmpty()) return getString(R.string.tv_unknown)
        val negotiated = encryption.count { it.negotiated }
        val encrypted = encryption.count { it.encrypted }
        val algorithms = encryption.map { it.algorithm.trim() }.filter { it.isNotEmpty() }
            .distinct().joinToString("/").ifBlank { getString(R.string.tv_unknown) }
        return "$encrypted/$negotiated · $algorithms"
    }

    private fun metric(labelRes: Int, value: Long?, format: (Long) -> String): Metric {
        if (value == null) return Metric(labelRes, getString(R.string.tv_unknown))
        return Metric(labelRes, format(value))
    }
}

/** One labelled value. The value is pre-formatted so binding stays trivial. */
private data class Metric(val labelRes: Int, val value: String)

private class MetricAdapter : RecyclerView.Adapter<MetricAdapter.Row>() {

    private var rows: List<Metric> = emptyList()

    fun submit(newRows: List<Metric>) {
        if (newRows == rows) return
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val inflater = LayoutInflater.from(parent.context)
        return Row(ItemTvMetricBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(rows[position])

    override fun getItemCount(): Int = rows.size

    class Row(private val binding: ItemTvMetricBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(metric: Metric) {
            binding.tvMetricLabel.text = binding.root.context.getString(metric.labelRes)
            binding.tvMetricValue.text = metric.value
        }
    }
}
