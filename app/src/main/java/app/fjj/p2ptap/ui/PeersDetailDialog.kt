package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.DialogPeersDetailBinding
import app.fjj.p2ptap.databinding.ItemPeerDetailBinding
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.PeerItemData
import app.fjj.p2ptap.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PeersDetailDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "PeersDetailDialog"
        fun newInstance(): PeersDetailDialog = PeersDetailDialog()
    }

    private var _binding: DialogPeersDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPeersDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRefresh.setOnClickListener {
            viewModel.refreshPeers()
            if (_binding != null && context != null) {
                Toast.makeText(requireContext(), getString(R.string.msg_peers_refreshed), Toast.LENGTH_SHORT).show()
            }
        }

        observeViewModel()
        startPeriodicPeersRefresh()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.peers.collect { peerList ->
                    renderPeerList(peerList)
                }
            }
        }
    }

    private fun startPeriodicPeersRefresh() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    viewModel.refreshPeers()
                    delay(2000)
                }
            }
        }
    }

    private fun renderPeerList(peerDataList: List<PeerItemData>) {
        val b = _binding ?: return
        val ctx = context ?: return

        if (!P2PTapVpnService.isRunning()) {
            b.tvSummary.text = getString(R.string.peers_offline_hint)
            b.peersContainer.removeAllViews()
            return
        }

        val totalPeers = peerDataList.size
        var directCount = 0
        var relayCount = 0
        for (p in peerDataList) {
            if (p.isDirect) directCount++ else relayCount++
        }

        if (totalPeers == 0) {
            b.peersContainer.removeAllViews()
            b.tvSummary.text = getString(R.string.peers_summary_fmt, 0, 0, 0)
            return
        }

        val currentChildCount = b.peersContainer.childCount
        val targetCount = peerDataList.size

        if (currentChildCount > targetCount) {
            b.peersContainer.removeViews(targetCount, currentChildCount - targetCount)
        } else if (currentChildCount < targetCount) {
            for (c in currentChildCount until targetCount) {
                ItemPeerDetailBinding.inflate(LayoutInflater.from(ctx), b.peersContainer, true)
            }
        }

        for (i in 0 until targetCount) {
            val childView = b.peersContainer.getChildAt(i)
            val itemBinding = ItemPeerDetailBinding.bind(childView)
            val item = peerDataList[i]

            itemBinding.tvNodeName.text = item.nodeName
            itemBinding.tvBadge.apply {
                when (item.connState) {
                    "ok" -> {
                        text = getString(R.string.badge_direct)
                        setTextColor(Color.parseColor("#059669"))
                    }
                    "relay_ok" -> {
                        text = getString(R.string.badge_relay)
                        setTextColor(Color.parseColor("#0284C7"))
                    }
                    "connecting" -> {
                        text = getString(R.string.badge_connecting)
                        setTextColor(Color.parseColor("#D97706"))
                    }
                    "obf_failed", "proto_mismatch" -> {
                        text = getString(R.string.badge_error)
                        setTextColor(Color.parseColor("#DC2626"))
                    }
                    else -> {
                        text = if (item.isDirect) getString(R.string.badge_direct) else getString(R.string.badge_relay)
                        setTextColor(if (item.isDirect) Color.parseColor("#059669") else Color.parseColor("#D97706"))
                    }
                }
            }

            itemBinding.tvRtt.text = if (item.rtt > 0) "📶 ${item.rtt.toInt()}ms" else ""

            val ipText = buildString {
                if (item.tapIp.isNotBlank()) append("IPv4: ${item.tapIp}  ")
                if (item.tapIpv6.isNotBlank()) append("IPv6: ${item.tapIpv6}")
            }
            itemBinding.tvIpAddresses.text = ipText
            itemBinding.tvIpAddresses.visibility = if (ipText.isBlank()) View.GONE else View.VISIBLE
            itemBinding.tvIpAddresses.setOnClickListener {
                val ip = if (item.tapIp.isNotBlank()) item.tapIp else item.tapIpv6
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Peer IP", ip))
                Toast.makeText(ctx, getString(R.string.msg_copied_clipboard) + ": $ip", Toast.LENGTH_SHORT).show()
            }

            itemBinding.tvPeerId.text = "ID: ${item.peerId}"
            itemBinding.layoutPeerId.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Peer ID", item.peerId))
                Toast.makeText(ctx, getString(R.string.msg_peer_id_copied_fmt, item.peerId), Toast.LENGTH_SHORT).show()
            }

            val endpointText = buildString {
                if (item.multiaddr.isNotBlank()) {
                    append(getString(R.string.label_endpoint_prefix))
                    append(item.multiaddr)
                } else if (item.transport.isNotBlank()) {
                    append(getString(R.string.label_endpoint_prefix))
                    append("(${item.transport})")
                }
                if (item.transportPriority.isNotBlank()) {
                    append(" [${item.transportPriority}]")
                }
            }
            itemBinding.tvEndpoint.text = endpointText
            itemBinding.tvEndpoint.visibility = if (endpointText.isBlank()) View.GONE else View.VISIBLE

            val tx = P2PStateRepository.formatBytes(item.txBytes)
            val rx = P2PStateRepository.formatBytes(item.rxBytes)
            val sys = if (item.os.isNotBlank()) " • ${item.os} ${item.version}".trim() else ""
            val exitBadge = if (item.isExitNode) " • 🚀 Exit Gateway" else ""
            itemBinding.tvTraffic.text = getString(R.string.label_traffic_prefix) + "↑ $tx  ↓ $rx$sys$exitBadge"
        }

        binding.tvSummary.text = getString(R.string.peers_summary_fmt, totalPeers, directCount, relayCount)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
