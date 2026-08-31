package app.fjj.p2ptap.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import app.fjj.p2ptap.config.P2PConfig
import app.fjj.p2ptap.databinding.DialogScanResultBinding
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.net.URLDecoder

data class ScannedNodeInfo(
    val nodeName: String = "",
    val peerId: String = "",
    val tapIp: String = "",
    val tapIpv6: String = "",
    val addrs: List<String> = emptyList(),
    val subnets: List<String> = emptyList(),
    val psk: String = "",
    val rawPayload: String = "",
    val isFullConfig: Boolean = false,
    val fullConfig: P2PConfig? = null
)

object QrImportHelper {

    fun parse(raw: String): ScannedNodeInfo? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 1. Try JSON format
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val obj = JSONObject(trimmed)
                if (obj.has("config")) {
                    val cfgObj = obj.getJSONObject("config")
                    val cfg = P2PConfig.fromJson(cfgObj.toString())
                    val pid = obj.optString("peer_id", "")
                    return ScannedNodeInfo(
                        nodeName = cfg.nodeName,
                        peerId = pid,
                        tapIp = cfg.tapIp,
                        tapIpv6 = cfg.tapIpv6,
                        addrs = cfg.staticPeers,
                        subnets = cfg.advertisedSubnets,
                        psk = cfg.psk,
                        rawPayload = trimmed,
                        isFullConfig = true,
                        fullConfig = cfg
                    )
                }
                if (obj.has("tap_ip") || obj.has("peer_id") || obj.has("node_name")) {
                    val addrsList = mutableListOf<String>()
                    obj.optJSONArray("addrs")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            addrsList.add(arr.getString(i))
                        }
                    }
                    val subnetsList = mutableListOf<String>()
                    obj.optJSONArray("subnets")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            subnetsList.add(arr.getString(i))
                        }
                    }
                    return ScannedNodeInfo(
                        nodeName = obj.optString("node_name", "RemoteNode"),
                        peerId = obj.optString("peer_id", ""),
                        tapIp = obj.optString("tap_ip", ""),
                        tapIpv6 = obj.optString("tap_ipv6", ""),
                        addrs = addrsList,
                        subnets = subnetsList,
                        psk = obj.optString("psk", ""),
                        rawPayload = trimmed
                    )
                }
            } catch (_: Exception) {}
        }

        // 2. Try URI format: p2ptap://NodeName?peerid=...&ip=...&ipv6=...&addrs=...
        if (trimmed.startsWith("p2ptap://", ignoreCase = true)) {
            try {
                val withoutScheme = trimmed.substring(9)
                val parts = withoutScheme.split("?", limit = 2)
                val nodeName = URLDecoder.decode(parts[0], "UTF-8")
                var peerId = ""
                var tapIp = ""
                var tapIpv6 = ""
                val addrsList = mutableListOf<String>()
                val subnetsList = mutableListOf<String>()
                var psk = ""

                if (parts.size > 1) {
                    val query = parts[1]
                    val params = query.split("&")
                    for (p in params) {
                        val kv = p.split("=", limit = 2)
                        if (kv.size == 2) {
                            val key = kv[0].lowercase()
                            val value = URLDecoder.decode(kv[1], "UTF-8")
                            when (key) {
                                "peerid", "peer_id", "pid" -> peerId = value
                                "ip", "tap_ip", "ipv4" -> tapIp = value
                                "ipv6", "tap_ipv6", "v6" -> tapIpv6 = value
                                "addrs", "addr", "multiaddr" -> {
                                    value.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                                        addrsList.add(it)
                                    }
                                }
                                "subnets", "subnet" -> {
                                    value.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                                        subnetsList.add(it)
                                    }
                                }
                                "psk" -> psk = value
                            }
                        }
                    }
                }
                return ScannedNodeInfo(
                    nodeName = if (nodeName.isNotBlank()) nodeName else "P2PNode",
                    peerId = peerId,
                    tapIp = tapIp,
                    tapIpv6 = tapIpv6,
                    addrs = addrsList,
                    subnets = subnetsList,
                    psk = psk,
                    rawPayload = trimmed
                )
            } catch (_: Exception) {}
        }

        // 3. Raw Multiaddr: /ip4/.../p2p/... or /ip6/...
        if (trimmed.startsWith("/")) {
            return ScannedNodeInfo(
                nodeName = "P2P Address",
                addrs = listOf(trimmed),
                rawPayload = trimmed
            )
        }

        // 4. Raw IP / Subnet
        if (trimmed.contains(".") || trimmed.contains(":")) {
            return ScannedNodeInfo(
                nodeName = "IP Endpoint",
                tapIp = trimmed,
                rawPayload = trimmed
            )
        }

        return null
    }

    fun extractAddresses(raw: String): List<String> {
        val info = parse(raw) ?: return emptyList()
        val result = mutableListOf<String>()
        if (info.addrs.isNotEmpty()) {
            result.addAll(info.addrs)
        } else if (info.peerId.isNotBlank()) {
            result.add("/p2p/" + info.peerId)
        } else if (info.tapIp.isNotBlank()) {
            result.add(info.tapIp)
        }
        return result
    }

    fun decodeQrFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val source = RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val reader = MultiFormatReader()
                val result = reader.decode(binaryBitmap)
                result.text
            }
        } catch (_: Exception) {
            null
        }
    }

    fun showScanResultDialog(
        context: Context,
        info: ScannedNodeInfo,
        onAddStatic: (String) -> Unit,
        onAddBootstrap: (String) -> Unit,
        onImportFull: ((P2PConfig) -> Unit)? = null
    ) {
        val binding = DialogScanResultBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()

        binding.tvScanNodeName.text = if (info.nodeName.isNotBlank()) info.nodeName else "发现节点"
        val ipText = buildString {
            if (info.tapIp.isNotBlank()) append("IPv4: " + info.tapIp)
            if (info.tapIpv6.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append("IPv6: " + info.tapIpv6)
            }
        }
        binding.tvScanIps.text = if (ipText.isNotBlank()) ipText else "未指定虚拟 IP"
        binding.tvScanPeerId.text = if (info.peerId.isNotBlank()) "Peer ID: " + info.peerId else ""
        binding.tvScanPeerId.visibility = if (info.peerId.isNotBlank()) View.VISIBLE else View.GONE

        if (info.addrs.isNotEmpty()) {
            binding.tvScanMultiaddrs.visibility = View.VISIBLE
            binding.tvScanMultiaddrs.text = info.addrs.joinToString("\n")
        } else {
            binding.tvScanMultiaddrs.visibility = View.GONE
        }

        val targetMultiaddr = when {
            info.addrs.isNotEmpty() -> info.addrs.first()
            info.peerId.isNotBlank() -> "/p2p/" + info.peerId
            info.tapIp.isNotBlank() -> info.tapIp
            else -> info.rawPayload
        }

        binding.btnAddStaticPeer.setOnClickListener {
            onAddStatic(targetMultiaddr)
            dialog.dismiss()
        }

        binding.btnAddBootstrapPeer.setOnClickListener {
            onAddBootstrap(targetMultiaddr)
            dialog.dismiss()
        }

        if (info.isFullConfig && info.fullConfig != null && onImportFull != null) {
            binding.btnImportFullConfig.visibility = View.VISIBLE
            binding.btnImportFullConfig.setOnClickListener {
                onImportFull(info.fullConfig)
                dialog.dismiss()
            }
        } else {
            binding.btnImportFullConfig.visibility = View.GONE
        }

        binding.btnCancelScan.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
