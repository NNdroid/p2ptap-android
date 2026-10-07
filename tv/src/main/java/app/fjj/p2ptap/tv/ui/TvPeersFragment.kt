package app.fjj.p2ptap.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.fjj.p2ptap.service.NodeMetrics
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.PeerItemData
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.TvFormat
import app.fjj.p2ptap.tv.databinding.FragmentTvPeersBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/**
 * S2 Peers. A vertical list of peer cards, one per mesh peer. Each card
 * shows the peer's name, ID, TAP IP, transport badge (QUIC / WebRTC / WT /
 * TCP / relay), RTT, direct/relay path, and per-peer up/down bytes.
 *
 * The list is driven by [P2PStateRepository.peers], which is populated by
 * the VPN service's refresh loop. When the VPN is off the peers flow is
 * empty, and the fragment renders a single "connect to see peers" card
 * instead — because a TV user on an offline node wants to see the reason,
 * not an empty list.
 */
class TvPeersFragment : Fragment() {

    private var _binding: FragmentTvPeersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PeersAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvPeersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        TvFocusAnimation.attachTo(binding.tvPeersRecycler, TvFocusAnimation.SCALE_CARD)

        adapter = PeersAdapter()
        binding.tvPeersRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.tvPeersRecycler.adapter = adapter
        binding.tvPeersRecycler.itemAnimator = null

        viewLifecycleOwner.lifecycleScope.launch {
            P2PStateRepository.peers.collect { peers ->
                if (!isAdded) return@collect
                adapter.submitList(peers)
                binding.tvPeersEmpty.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            P2PStateRepository.metrics.collect { metrics ->
                if (!isAdded) return@collect
                renderSummary(metrics)
            }
        }
    }

    private fun renderSummary(metrics: NodeMetrics) {
        val count = resources.getQuantityString(R.plurals.tv_peer_count, metrics.peerCount, metrics.peerCount)
        val direct = metrics.directPeers
        val relay = metrics.relayPeers
        binding.tvPeersSummary.text = "$count · $direct direct · $relay relay"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** RecyclerView adapter for a single peer card. */
    private class PeersAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PeersAdapter.Holder>() {
        private var peers: List<PeerItemData> = emptyList()

        class Holder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.tv_peer_card)
            val name: android.widget.TextView = view.findViewById(R.id.tv_peer_name)
            val peerId: android.widget.TextView = view.findViewById(R.id.tv_peer_id)
            val tap: android.widget.TextView = view.findViewById(R.id.tv_peer_tap)
            val transport: android.widget.TextView = view.findViewById(R.id.tv_peer_transport)
            val rtt: android.widget.TextView = view.findViewById(R.id.tv_peer_rtt)
            val tx: android.widget.TextView = view.findViewById(R.id.tv_peer_tx)
            val rx: android.widget.TextView = view.findViewById(R.id.tv_peer_rx)
            val path: android.widget.TextView = view.findViewById(R.id.tv_peer_path)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inflater = LayoutInflater.from(parent.context)
            val view = inflater.inflate(R.layout.item_tv_peer_card, parent, false)
            TvFocusAnimation.attachTo(view.findViewById<MaterialCardView>(R.id.tv_peer_card),
                TvFocusAnimation.SCALE_CARD)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val peer = peers[position]
            val ctx = holder.itemView.context
            holder.name.text = peer.nodeName.ifBlank { peer.peerId.take(12) }
            holder.peerId.text = TvFormat.shorten(peer.peerId, 12, 8)
            holder.tap.text = peer.tapIp.ifBlank { peer.tapIpv6.ifBlank { "—" } }
            val protocol = TvFormat.transport(ctx, peer.transport, peer.isRelayed)
            holder.transport.text = protocol.label
            holder.transport.backgroundTintList =
                androidx.core.content.ContextCompat.getColorStateList(ctx, protocol.colorRes)
            holder.rtt.text = TvFormat.rtt(ctx, peer.rtt, peer.rttMeasured)
            holder.tx.text = TvFormat.bytes(peer.txBytes)
            holder.rx.text = TvFormat.bytes(peer.rxBytes)
            holder.path.text = if (peer.isRelayed) ctx.getString(R.string.tv_path_relay)
                else if (peer.isDirect) ctx.getString(R.string.tv_path_direct)
                else ctx.getString(R.string.tv_path_unknown)
        }

        override fun getItemCount(): Int = peers.size

        fun submitList(peers: List<PeerItemData>) {
            this.peers = peers
            notifyDataSetChanged()
        }
    }
}
