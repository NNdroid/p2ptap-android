package app.fjj.p2ptap.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.fjj.p2ptap.tv.databinding.ActivityTvPeersBinding
import app.fjj.p2ptap.tv.databinding.ItemTvPeerBinding
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.PeerItemData
import app.fjj.p2ptap.service.TelemetryState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * S2: who is in the mesh.
 *
 * Every row answers one question a viewer cares about — which node is this,
 * how does it reach me, and how much is it carrying. There is no editing here:
 * a TV has no keyboard, so peer management stays on the phone and the TV is a
 * read-only observer of the mesh it belongs to.
 */
class TvPeersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvPeersBinding
    private val adapter = PeerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvPeersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvPeersGrid.apply {
            adapter = this@TvPeersActivity.adapter
            // One column, no stagger: keeps the focus path a straight vertical
            // line, which is what DPAD_UP and DPAD_DOWN expect.
            setNumColumns(1)
        }

        binding.tvPeersGrid.requestFocus()
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
        // Peers, state and telemetry are three flows that describe one moment,
        // so they are combined rather than observed separately: rendering each
        // on its own would briefly show a mix of a new list and an old state.
        lifecycleScope.launch {
            combine(
                P2PStateRepository.peers,
                P2PStateRepository.state,
                P2PStateRepository.telemetry
            ) { peers, state, telemetry -> Triple(peers, state, telemetry) }
                .collect { (peers, state, telemetry) -> render(state, peers, telemetry) }
        }
    }

    private fun render(state: String, peers: List<PeerItemData>, telemetry: TelemetryState) {
        val running = state == P2PTapVpnService.STATE_RUNNING

        val offline = !running
        val empty = !offline && peers.isEmpty()
        binding.tvPeersGrid.visibility = if (offline) View.GONE else View.VISIBLE
        binding.tvPeersEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.tvPeersEmptyHint.visibility = if (empty) View.VISIBLE else View.GONE

        binding.tvPeersSummary.text = when {
            offline -> getString(R.string.tv_peers_offline)
            empty -> getString(R.string.tv_peers_empty)
            telemetry.failed -> getString(R.string.tv_no_telemetry)
            else -> {
                val direct = peers.count { it.isDirect }
                val relay = peers.count { it.isRelayed }
                getString(
                    R.string.tv_peers_summary_fmt,
                    resources.getQuantityString(R.plurals.tv_peer_count, peers.size, peers.size),
                    getString(R.string.tv_path_direct), direct,
                    getString(R.string.tv_path_relay), relay
                )
            }
        }

        adapter.submit(peers)
    }
}

/**
 * Rows for one mesh peer.
 *
 * notifyDataSetChanged on submit is deliberate: a mesh rarely has more than a
 * handful of peers, and the list only changes on a telemetry tick. The row
 * content is keyed entirely by peer id, so a recycled holder is always bound to
 * the peer it is showing.
 */
private class PeerAdapter : RecyclerView.Adapter<PeerAdapter.Row>() {

    private var rows: List<PeerItemData> = emptyList()

    fun submit(newRows: List<PeerItemData>) {
        if (newRows == rows) return
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val inflater = LayoutInflater.from(parent.context)
        return Row(ItemTvPeerBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(rows[position])

    override fun getItemCount(): Int = rows.size

    class Row(private val binding: ItemTvPeerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(peer: PeerItemData) {
            val ctx = binding.root.context

            binding.tvPeerName.text = peer.nodeName

            val tap = listOf(peer.tapIp.trim(), peer.tapIpv6.trim()).filter { it.isNotEmpty() }
            binding.tvPeerTap.text = if (tap.isEmpty()) ctx.getString(R.string.tv_unknown)
            else tap.joinToString("  ")

            val protocol = TvFormat.transport(ctx, peer.transport, peer.isRelayed)
            binding.tvPeerProtocol.text = protocol.label
            binding.tvPeerProtocol.backgroundTintList =
                ContextCompat.getColorStateList(ctx, protocol.colorRes)

            binding.tvPeerRtt.text = TvFormat.rtt(ctx, peer.rtt, peer.rttMeasured)

            val pathRes = when {
                peer.isRelayed -> R.string.tv_path_relay
                peer.isDirect -> R.string.tv_path_direct
                else -> R.string.tv_path_unknown
            }
            binding.tvPeerPath.text = ctx.getString(pathRes)
            binding.tvPeerPath.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        peer.isRelayed -> R.color.tv_prot_relay
                        peer.isDirect -> R.color.tv_status_connected
                        else -> R.color.tv_text_muted
                    }
                )
            )

            binding.tvPeerExitBadge.visibility = if (peer.isExitNode) View.VISIBLE else View.GONE
            binding.tvPeerExitBadge.backgroundTintList =
                ContextCompat.getColorStateList(ctx, R.color.tv_brand_secondary)

            // Link counters are measured locally; remote counters are the peer's
            // own report. Link traffic is the honest number for "how much is
            // this node moving", so it wins whenever the engine provides it.
            val tx = if (peer.linkTrafficAvailable) peer.txBytes else peer.remoteTxBytes ?: -1L
            val rx = if (peer.linkTrafficAvailable) peer.rxBytes else peer.remoteRxBytes ?: -1L
            binding.tvPeerTx.text = if (tx >= 0) {
                ctx.getString(R.string.tv_peer_tx) + " " + TvFormat.bytes(tx)
            } else {
                ctx.getString(R.string.tv_unknown)
            }
            binding.tvPeerRx.text = if (rx >= 0) {
                ctx.getString(R.string.tv_peer_rx) + " " + TvFormat.bytes(rx)
            } else {
                ctx.getString(R.string.tv_unknown)
            }

            binding.tvPeerOs.text = TvFormat.orUnknown(ctx, peer.os)
            val addr = TvFormat.shorten(peer.multiaddr, 60, 24)
            binding.tvPeerAddr.text = if (addr.isBlank()) ctx.getString(R.string.tv_unknown)
            else addr
        }
    }
}
