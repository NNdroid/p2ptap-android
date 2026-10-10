package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.i18n.UiMessages
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.DialogPeersDetailBinding
import app.fjj.p2ptap.databinding.ItemPeerDetailBinding
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.PeerItemData
import app.fjj.p2ptap.viewmodel.MainViewModel



import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import java.util.Locale

class PeersDetailDialog : LiveDataSheet() {

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
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = viewModel.refreshStats()
                if (_binding != null) Toast.makeText(requireContext(), getString(
                    if (ok) R.string.msg_peers_refreshed else R.string.telemetry_refresh_failed), Toast.LENGTH_SHORT).show()
            }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.telemetry, viewModel.state) { data, state -> data to state }.collect { (data, state) ->
                    renderPeerList(data.snapshot?.peers.orEmpty())
                    val b = _binding ?: return@collect
                    b.btnRefresh.isEnabled = state == "RUNNING" && !data.refreshing
                    if (state == "RUNNING" && data.snapshot == null) b.tvSummary.text = getString(
                        if (data.failed) R.string.telemetry_refresh_failed else R.string.telemetry_loading)
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

        var directCount = 0
        var relayCount = 0
        for (p in peerDataList) {
            when (p.connState) {
                "ok" -> if (p.isDirect) directCount++ else if (p.isRelayed) relayCount++
                "relay_ok" -> relayCount++
            }
        }
        val totalPeers = directCount + relayCount

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
                        if (item.isDirect) {
                            text = getString(R.string.badge_direct)
                            setTextColor(ContextCompat.getColor(ctx, R.color.status_connected))
                        } else if (item.isRelayed) {
                            text = getString(R.string.badge_relay)
                            setTextColor(ContextCompat.getColor(ctx, R.color.brand_primary_dark))
                        } else {
                            text = getString(R.string.badge_error)
                            setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
                        }
                    }
                    "relay_ok" -> {
                        text = getString(R.string.badge_relay)
                        setTextColor(ContextCompat.getColor(ctx, R.color.brand_primary_dark))
                    }
                    "connecting" -> {
                        text = getString(R.string.badge_connecting)
                        setTextColor(ContextCompat.getColor(ctx, R.color.status_connecting))
                    }
                    "obf_failed", "proto_mismatch" -> {
                        text = getString(if (item.connState == "obf_failed") R.string.telemetry_crypto_failed else R.string.telemetry_proto_mismatch)
                        setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
                    }
                    else -> {
                        text = getString(if (item.connState == "unreachable") R.string.telemetry_unreachable else R.string.telemetry_unknown)
                        setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
                    }
                }
            }

            itemBinding.tvRtt.text = if (item.rttMeasured) String.format(Locale.getDefault(), "%.1f ms", item.rtt) else getString(R.string.telemetry_unmeasured)
            itemBinding.tvRtt.contentDescription = "${itemBinding.tvRtt.text} ${UiMessages.rttSource(ctx, item.rttSource)}"

            val ipText = buildString {
                if (item.tapIp.isNotBlank()) append("IPv4: ${item.tapIp}  ")
                if (item.tapIpv6.isNotBlank()) append("IPv6: ${item.tapIpv6}")
            }
            itemBinding.tvIpAddresses.text = ipText.ifBlank { getString(R.string.telemetry_unknown) }
            itemBinding.tvIpAddresses.visibility = View.VISIBLE
            itemBinding.tvIpAddresses.setOnClickListener {
                val ip = listOf(item.tapIp, item.tapIpv6).filter { it.isNotBlank() }.joinToString("\n")
                if (ip.isBlank()) return@setOnClickListener
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_tap_ip), ip))
                Toast.makeText(ctx, getString(R.string.msg_copied_clipboard) + ": $ip", Toast.LENGTH_SHORT).show()
            }

            itemBinding.tvPeerId.text = getString(R.string.peer_id_fmt, item.peerId)
            itemBinding.layoutPeerId.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_peer_id), item.peerId))
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
                    append(" [${UiMessages.priority(ctx, item.transportScore)}]")
                }
            }
            itemBinding.tvEndpoint.text = endpointText
            itemBinding.tvEndpoint.visibility = if (endpointText.isBlank()) View.GONE else View.VISIBLE

            val unknown = getString(R.string.telemetry_unknown)
            val tx = if (item.linkTrafficAvailable) P2PStateRepository.formatBytes(item.txBytes) else unknown
            val rx = if (item.linkTrafficAvailable) P2PStateRepository.formatBytes(item.rxBytes) else unknown
            itemBinding.tvTraffic.text = buildString {
                append(getString(R.string.telemetry_link_traffic, tx, rx))
                append("\n↑ ${item.txSpeed?.let(P2PStateRepository::formatSpeed) ?: unknown}  ↓ ${item.rxSpeed?.let(P2PStateRepository::formatSpeed) ?: unknown}")
                append("\n${item.os.ifBlank { unknown }} · ${item.version.ifBlank { unknown }}")
                append("\n${UiMessages.rttSource(ctx, item.rttSource)}")
                if (item.isExitNode) append("\n${getString(R.string.telemetry_exit_gateway)}")
                if (item.connDetail.isNotBlank()) append("\n${UiMessages.connectionDetail(ctx, item.connDetail)}")
            }
        }

        binding.tvSummary.text = getString(R.string.telemetry_peer_summary, totalPeers, directCount, relayCount, peerDataList.size)
        if (peerDataList.isEmpty()) binding.tvSummary.text = getString(R.string.peers_waiting_hint)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
