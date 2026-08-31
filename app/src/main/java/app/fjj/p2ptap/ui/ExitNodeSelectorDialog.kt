package app.fjj.p2ptap.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.databinding.DialogExitNodeSelectorBinding
import app.fjj.p2ptap.databinding.ItemExitNodePeerBinding
import app.fjj.p2ptap.service.P2PTapVpnService
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ExitNodeSelectorDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ExitNodeSelectorDialog"
        fun newInstance(onChanged: ((String) -> Unit)? = null): ExitNodeSelectorDialog {
            val dialog = ExitNodeSelectorDialog()
            dialog.onExitNodeChangedListener = onChanged
            return dialog
        }
    }

    private var _binding: DialogExitNodeSelectorBinding? = null
    private val binding get() = _binding!!
    var onExitNodeChangedListener: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogExitNodeSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRefreshPeers.setOnClickListener {
            renderPeers()
        }

        binding.cardAutoMode.setOnClickListener {
            selectExitNode("")
        }

        binding.layoutCustomInput.setOnClickListener {
            showCustomInputDialog()
        }

        renderPeers()
    }

    private fun isExitGatewayPeer(peerObj: JSONObject): Boolean {
        if (peerObj.optBoolean("is_exit_node", false) ||
            peerObj.optBoolean("can_exit", false) ||
            peerObj.optBoolean("is_gateway", false) ||
            peerObj.optBoolean("allow_exit", false) ||
            peerObj.optBoolean("exit_node", false)) {
            return true
        }

        val subnets = peerObj.optJSONArray("advertised_subnets")
        if (subnets != null) {
            for (j in 0 until subnets.length()) {
                val sub = subnets.optString(j, "").trim()
                if (sub == "0.0.0.0/0" || sub == "::/0" || sub.equals("exit", ignoreCase = true) || sub.equals("gateway", ignoreCase = true)) {
                    return true
                }
            }
        }

        val subnetsStr = peerObj.optString("advertised_subnets", "")
        return subnetsStr.contains("0.0.0.0/0") || subnetsStr.contains("::/0") || subnetsStr.contains("exit")
    }

    private fun renderPeers() {
        val ctx = requireContext()
        val config = AppConfigManager.load(ctx)
        val currentExitNode = config.exitNode.trim()

        // Highlight Auto Mode Card if exitNode is blank
        val isAuto = currentExitNode.isBlank()
        val brandPrimary = ContextCompat.getColor(ctx, R.color.brand_primary)
        val cardStroke = ContextCompat.getColor(ctx, R.color.card_stroke)

        binding.cardAutoMode.strokeColor = if (isAuto) brandPrimary else cardStroke
        binding.cardAutoMode.strokeWidth = if (isAuto) 4 else 2
        binding.ivAutoChecked.visibility = if (isAuto) View.VISIBLE else View.GONE

        binding.containerPeers.removeAllViews()

        if (!P2PTapVpnService.isRunning()) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val statsJsonStr = P2PTap.getStatsJSON()
                val statsJson = JSONObject(statsJsonStr)
                val peersArray = statsJson.optJSONArray("active_peers")
                val totalPeers = peersArray?.length() ?: 0

                val peerList = mutableListOf<ExitNodePeerData>()

                if (totalPeers > 0) {
                    for (i in 0 until totalPeers) {
                        val peer = peersArray!!.getJSONObject(i)
                        val peerId = peer.optString("peer_id", "-")
                        val nodeName = peer.optString("node_name", "Peer-$i")
                        val tapIp = peer.optString("tap_ip", "")
                        val connState = peer.optString("conn_state", "ok")
                        val rtt = peer.optDouble("rtt_ms", 0.0)
                        val isDirect = (connState == "ok")

                        val isExitGateway = isExitGatewayPeer(peer)
                        val isSelected = currentExitNode.isNotBlank() &&
                                (currentExitNode.equals(peerId, ignoreCase = true) || currentExitNode.equals(tapIp, ignoreCase = true))

                        // Only include peers that advertise exit gateway capability or are currently selected
                        if (isExitGateway || isSelected) {
                            peerList.add(
                                ExitNodePeerData(
                                    peerId = peerId,
                                    nodeName = nodeName,
                                    tapIp = tapIp,
                                    isDirect = isDirect,
                                    rtt = rtt
                                )
                            )
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.containerPeers.removeAllViews()

                    if (peerList.isEmpty()) {
                        val emptyTv = android.widget.TextView(ctx).apply {
                            text = getString(R.string.exit_node_empty_hint)
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                            gravity = android.view.Gravity.CENTER
                            setPadding(24, 48, 24, 48)
                        }
                        binding.containerPeers.addView(emptyTv)
                        return@withContext
                    }

                    for (peer in peerList) {
                        val itemBinding = ItemExitNodePeerBindingHolder.inflate(ctx, binding.containerPeers)

                        itemBinding.tvNodeName.text = peer.nodeName
                        itemBinding.tvBadge.apply {
                            text = if (peer.isDirect) getString(R.string.badge_direct) else getString(R.string.badge_relay)
                            setTextColor(if (peer.isDirect) Color.parseColor("#059669") else Color.parseColor("#D97706"))
                        }

                        itemBinding.tvRtt.text = if (peer.rtt > 0) "📶 ${peer.rtt.toInt()}ms" else ""

                        val ipAndPid = buildString {
                            if (peer.tapIp.isNotBlank()) append("IPv4: ${peer.tapIp}  ")
                            append("ID: ${peer.peerId}")
                        }
                        itemBinding.tvIpAndPid.text = ipAndPid

                        // Check if selected
                        val isSelected = (currentExitNode.isNotBlank() &&
                                (currentExitNode.equals(peer.peerId, ignoreCase = true) ||
                                        currentExitNode.equals(peer.tapIp, ignoreCase = true)))

                        itemBinding.cardPeer.strokeColor = if (isSelected) brandPrimary else cardStroke
                        itemBinding.cardPeer.strokeWidth = if (isSelected) 4 else 2
                        itemBinding.ivSelectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

                        // Use peerId or tapIp as exit node target
                        val targetNode = if (peer.peerId.isNotBlank() && peer.peerId != "-") peer.peerId else peer.tapIp

                        itemBinding.cardPeer.setOnClickListener {
                            selectExitNode(targetNode)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private object ItemExitNodePeerBindingHolder {
        fun inflate(ctx: android.content.Context, parent: ViewGroup): ItemExitNodePeerBinding {
            return ItemExitNodePeerBinding.inflate(LayoutInflater.from(ctx), parent, true)
        }
    }

    private fun selectExitNode(target: String) {
        val ctx = context ?: return
        val config = AppConfigManager.load(ctx)
        config.exitNode = target.trim()
        AppConfigManager.save(ctx, config)

        if (target.isBlank()) {
            Toast.makeText(ctx, getString(R.string.msg_exit_node_cleared), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, getString(R.string.msg_exit_node_updated, target), Toast.LENGTH_SHORT).show()
        }

        if (P2PTapVpnService.isRunning()) {
            Toast.makeText(ctx, getString(R.string.msg_exit_node_hot_reloaded), Toast.LENGTH_LONG).show()
            reloadVpnService(ctx)
        }

        onExitNodeChangedListener?.invoke(target.trim())
        dismiss()
    }

    private fun reloadVpnService(ctx: android.content.Context) {
        val intent = android.content.Intent(ctx, P2PTapVpnService::class.java).apply {
            action = P2PTapVpnService.ACTION_RELOAD
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun showCustomInputDialog() {
        val ctx = context ?: return
        val config = AppConfigManager.load(ctx)

        val editText = EditText(ctx).apply {
            hint = getString(R.string.hint_custom_exit_node)
            setText(config.exitNode)
            setSelection(text.length)
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.dialog_custom_exit_node_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val input = editText.text.toString().trim()
                selectExitNode(input)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private data class ExitNodePeerData(
        val peerId: String,
        val nodeName: String,
        val tapIp: String,
        val isDirect: Boolean,
        val rtt: Double
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
