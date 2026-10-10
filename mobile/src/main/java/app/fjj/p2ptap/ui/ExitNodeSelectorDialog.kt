package app.fjj.p2ptap.ui

import app.fjj.p2ptap.i18n.UiMessages

import android.net.InetAddresses
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.databinding.DialogExitNodeSelectorBinding
import app.fjj.p2ptap.databinding.ItemExitNodePeerBinding
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.service.PeerItemData
import app.fjj.p2ptap.service.TelemetryState
import app.fjj.p2ptap.service.matchesVirtualAddress
import app.fjj.p2ptap.viewmodel.MainViewModel

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class ExitNodeSelectorDialog : LiveDataSheet() {
    companion object {
        const val TAG = "ExitNodeSelectorDialog"
        fun newInstance(onChanged: ((String) -> Unit)? = null) = ExitNodeSelectorDialog().apply {
            onExitNodeChangedListener = onChanged
        }
    }
    private var _binding: DialogExitNodeSelectorBinding? = null
    private val viewModel: MainViewModel by activityViewModels()
    var onExitNodeChangedListener: ((String) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return DialogExitNodeSelectorBinding.inflate(inflater, container, false).also { _binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding?.btnRefreshPeers?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = viewModel.refreshStats()
                if (_binding != null) Toast.makeText(requireContext(), getString(
                    if (ok) R.string.msg_peers_refreshed else R.string.telemetry_refresh_failed), Toast.LENGTH_SHORT).show()
            }
        }
        _binding?.cardAutoMode?.setOnClickListener { selectExitNode("") }
        _binding?.layoutCustomInput?.setOnClickListener { showCustomInputDialog() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.telemetry, viewModel.state) { data, state -> data to state }
                    .collect { (data, state) -> renderPeers(data, state) }
            }
        }
    }

    private fun PeerItemData.matches(target: String) = target.isNotBlank() &&
        (target == peerId || matchesVirtualAddress(target, tapIp) || matchesVirtualAddress(target, tapIpv6))

    private fun PeerItemData.healthy() = connState == "ok" || connState == "relay_ok"

    private fun renderPeers(data: TelemetryState, state: String) {
        val b = _binding ?: return
        val ctx = context ?: return
        val target = AppConfigManager.load(ctx).exitNode.trim()
        val primary = ContextCompat.getColor(ctx, R.color.brand_primary)
        val stroke = ContextCompat.getColor(ctx, R.color.card_stroke)
        b.cardAutoMode.strokeColor = if (target.isBlank()) primary else stroke
        b.ivAutoChecked.visibility = if (target.isBlank()) View.VISIBLE else View.GONE
        b.btnRefreshPeers.isEnabled = state == "RUNNING" && !data.refreshing
        b.tvSelection.text = when {
            target.isBlank() -> getString(R.string.exit_node_auto_display)
            state != "RUNNING" -> getString(R.string.exit_node_idle_fmt, target)
            data.snapshot?.matchesExit(target) == true ->
                getString(R.string.exit_node_active_fmt, target, getString(R.string.telemetry_applied))
            else ->
                getString(R.string.exit_node_active_fmt, target, getString(R.string.telemetry_pending))
        }
        // Kept in a local: TelemetryState lives in :core, and a public
        // cross-module property is not smart-castable after a null check.
        val snap = data.snapshot
        b.tvDataStatus.text = getString(when {
            state != "RUNNING" -> R.string.exit_node_vpn_stopped_hint
            data.failed -> R.string.telemetry_refresh_failed
            snap == null -> R.string.telemetry_loading
            snap.peers.isEmpty() -> R.string.exit_node_empty_hint
            else -> R.string.telemetry_exit_select_hint
        })
        val peers = (if (state == "RUNNING") data.snapshot?.peers.orEmpty() else emptyList()).sortedWith(
            compareByDescending<PeerItemData> { it.matches(target) }.thenByDescending { it.isExitNode }
                .thenBy { it.peerId })
        val count = b.containerPeers.childCount
        if (count > peers.size) b.containerPeers.removeViews(peers.size, count - peers.size)
        for (i in count until peers.size) ItemExitNodePeerBinding.inflate(layoutInflater, b.containerPeers, true)
        peers.forEachIndexed { i, peer ->
            val item = ItemExitNodePeerBinding.bind(b.containerPeers.getChildAt(i))
            val selected = peer.matches(target)
            item.tvNodeName.text = peer.nodeName
            val connection = getString(when {
                peer.isDirect -> R.string.badge_direct
                peer.healthy() && peer.isRelayed -> R.string.badge_relay
                peer.connState == "connecting" -> R.string.badge_connecting
                peer.connState == "unreachable" -> R.string.telemetry_unreachable
                else -> R.string.badge_error
            })
            item.tvBadge.text = if (peer.isExitNode) "${getString(R.string.telemetry_exit_gateway)} · $connection"
                else getString(R.string.telemetry_not_gateway)
            item.tvBadge.setTextColor(ContextCompat.getColor(ctx, if (peer.isExitNode) R.color.brand_primary else R.color.text_muted))
            item.tvRtt.text = if (peer.rttMeasured) String.format(Locale.getDefault(), "%.1f ms", peer.rtt)
                else getString(R.string.telemetry_unmeasured)
            item.tvIpAndPid.text = buildString {
                append("IPv4: ${peer.tapIp.ifBlank { getString(R.string.telemetry_unknown) }}")
                append("\nIPv6: ${peer.tapIpv6.ifBlank { getString(R.string.telemetry_unknown) }}")
                append("\nID: ${peer.peerId}")
                append("\n${peer.os.ifBlank { getString(R.string.telemetry_unknown) }} · ${peer.version.ifBlank { getString(R.string.telemetry_unknown) }}")
                if (peer.rttSource.isNotBlank()) append("\n${peer.rttSource}")
            }
            item.cardPeer.strokeColor = if (selected) primary else stroke
            item.ivSelectedCheck.visibility = if (selected) View.VISIBLE else View.GONE
            item.cardPeer.isEnabled = peer.isExitNode && peer.healthy() && state == "RUNNING"
            item.cardPeer.alpha = if (item.cardPeer.isEnabled) 1f else 0.65f
            item.cardPeer.setOnClickListener {
                val latest = viewModel.telemetry.value.snapshot?.peers?.find { it.peerId == peer.peerId }
                if (latest?.isExitNode == true && latest.healthy() && viewModel.state.value == "RUNNING") selectExitNode(peer.peerId)
            }
        }
    }

    private fun selectExitNode(target: String) {
        val ctx = context ?: return
        val config = AppConfigManager.load(ctx)
        val value = target.trim()
        if (value == config.exitNode.trim()) { dismiss(); return }
        config.exitNode = value
        try {
            AppConfigManager.save(ctx, config)
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.config_invalid_fmt, UiMessages.describe(ctx, e)), Toast.LENGTH_LONG).show()
            return
        }
        AppConfigManager.reloadRunningService(ctx)
        Toast.makeText(ctx, getString(R.string.telemetry_exit_saved), Toast.LENGTH_SHORT).show()
        onExitNodeChangedListener?.invoke(value)
        dismiss()
    }

    private fun showCustomInputDialog() {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            hint = getString(R.string.hint_custom_exit_node)
            setText(AppConfigManager.load(ctx).exitNode)
            setSelection(text.length)
            maxLines = 3
        }
        val dialog = AlertDialog.Builder(ctx).setTitle(R.string.dialog_custom_exit_node_title).setView(input)
            .setPositiveButton(R.string.btn_save, null).setNegativeButton(R.string.btn_cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target = input.text.toString().trim()
                val peerId = target.length in 32..128 && target.matches(Regex("[1-9A-HJ-NP-Za-km-z]+"))
                if (target.isNotBlank() && !InetAddresses.isNumericAddress(target) && !peerId) {
                    input.error = getString(R.string.telemetry_invalid_exit)
                } else { selectExitNode(target); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
