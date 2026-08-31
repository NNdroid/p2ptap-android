package app.fjj.p2ptap.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.DialogAddressListManagerBinding
import app.fjj.p2ptap.databinding.ItemAddressCardBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

enum class AddressListType {
    BOOTSTRAP_PEERS,
    STATIC_PEERS,
    ADVERTISED_SUBNETS,
    ALLOWED_SUBNET_PEERS
}

class AddressListManagerDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AddressListManagerDialog"
        private const val ARG_TYPE = "list_type"
        private const val ARG_ITEMS = "items"

        fun newInstance(
            type: AddressListType,
            currentItems: List<String>,
            onSave: (List<String>) -> Unit
        ): AddressListManagerDialog {
            val dialog = AddressListManagerDialog()
            dialog.arguments = Bundle().apply {
                putString(ARG_TYPE, type.name)
                putStringArrayList(ARG_ITEMS, ArrayList(currentItems))
            }
            dialog.onSaveCallback = onSave
            return dialog
        }
    }

    private var _binding: DialogAddressListManagerBinding? = null
    private val binding get() = _binding!!

    private var listType: AddressListType = AddressListType.BOOTSTRAP_PEERS
    private val items = mutableListOf<String>()
    private var onSaveCallback: ((List<String>) -> Unit)? = null
    private var isTextMode = false

    private lateinit var adapter: AddressAdapter

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedContent(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val typeStr = arguments?.getString(ARG_TYPE) ?: AddressListType.BOOTSTRAP_PEERS.name
        listType = AddressListType.valueOf(typeStr)
        arguments?.getStringArrayList(ARG_ITEMS)?.let {
            items.addAll(it.filter { s -> s.isNotBlank() })
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            val behavior = BottomSheetBehavior.from(bottomSheet)
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            bottomSheet.layoutParams.height = (screenHeight * 0.75).toInt()
            behavior.peekHeight = (screenHeight * 0.75).toInt()
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAddressListManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTitle()
        setupRecyclerView()
        setupListeners()
        updateEmptyState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupTitle() {
        val titleRes = when (listType) {
            AddressListType.BOOTSTRAP_PEERS -> R.string.dialog_manage_bootstrap
            AddressListType.STATIC_PEERS -> R.string.dialog_manage_static
            AddressListType.ADVERTISED_SUBNETS -> R.string.dialog_manage_subnets
            AddressListType.ALLOWED_SUBNET_PEERS -> R.string.dialog_manage_allowed_peers
        }
        binding.tvDialogTitle.text = getString(titleRes)
    }

    private fun setupRecyclerView() {
        adapter = AddressAdapter(
            items = items,
            onCopy = { addr ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("P2PTap Item", addr))
                Toast.makeText(requireContext(), getString(R.string.msg_copied_clipboard), Toast.LENGTH_SHORT).show()
            },
            onEdit = { index, addr ->
                showEditAddressDialog(index, addr)
            },
            onDelete = { index ->
                if (index in items.indices) {
                    items.removeAt(index)
                    adapter.notifyDataSetChanged()
                    updateEmptyState()
                    Toast.makeText(requireContext(), getString(R.string.msg_address_deleted), Toast.LENGTH_SHORT).show()
                }
            }
        )
        binding.rvAddresses.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAddresses.adapter = adapter
    }

    private fun setupListeners() {
        binding.ivClose.setOnClickListener {
            dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnSaveList.setOnClickListener {
            if (isTextMode) {
                val raw = binding.etRawAddresses.text?.toString() ?: ""
                items.clear()
                items.addAll(raw.lines().map { it.trim() }.filter { it.isNotEmpty() })
            }
            onSaveCallback?.invoke(items.toList())
            dismiss()
        }

        binding.btnToggleMode.setOnClickListener {
            toggleMode()
        }

        binding.btnAddAddress.setOnClickListener {
            showAddAddressDialog()
        }

        binding.btnPasteClipboard.setOnClickListener {
            pasteFromClipboard()
        }

        binding.btnScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt(getString(R.string.title_scan_qr))
                setBeepEnabled(true)
                setBarcodeImageEnabled(false)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        }
    }

    private fun toggleMode() {
        isTextMode = !isTextMode
        if (isTextMode) {
            binding.layoutListMode.visibility = View.GONE
            binding.layoutTextMode.visibility = View.VISIBLE
            binding.etRawAddresses.setText(items.joinToString("\n"))
            binding.btnToggleMode.text = getString(R.string.btn_switch_list_mode)
            binding.btnToggleMode.setIconResource(R.drawable.ic_list)
        } else {
            val raw = binding.etRawAddresses.text?.toString() ?: ""
            items.clear()
            items.addAll(raw.lines().map { it.trim() }.filter { it.isNotEmpty() })
            adapter.notifyDataSetChanged()
            updateEmptyState()

            binding.layoutTextMode.visibility = View.GONE
            binding.layoutListMode.visibility = View.VISIBLE
            binding.btnToggleMode.text = getString(R.string.btn_switch_text_mode)
            binding.btnToggleMode.setIconResource(R.drawable.ic_text)
        }
    }

    private fun updateEmptyState() {
        binding.layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.rvAddresses.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddAddressDialog() {
        val defaultHint = if (listType == AddressListType.ALLOWED_SUBNET_PEERS) {
            "输入 Peer ID (如 12D3...) 或 * (允许所有)"
        } else {
            getString(R.string.hint_input_address)
        }

        val editText = EditText(requireContext()).apply {
            hint = defaultHint
            textSize = 13f
            setPadding(40, 30, 40, 30)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_address_title)
            .setView(editText)
            .setPositiveButton(R.string.btn_add_address) { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotBlank()) {
                    val newLines = text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                    items.addAll(newLines)
                    adapter.notifyDataSetChanged()
                    updateEmptyState()
                    Toast.makeText(requireContext(), getString(R.string.msg_address_added), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showEditAddressDialog(index: Int, currentAddress: String) {
        val editText = EditText(requireContext()).apply {
            setText(currentAddress)
            textSize = 13f
            setPadding(40, 30, 40, 30)
            typeface = android.graphics.Typeface.MONOSPACE
            setSelection(text.length)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_edit_address_title)
            .setView(editText)
            .setPositiveButton(R.string.msg_config_saved) { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotBlank() && index in items.indices) {
                    items[index] = text
                    adapter.notifyItemChanged(index)
                    Toast.makeText(requireContext(), getString(R.string.msg_address_updated), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                val newLines = text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                items.addAll(newLines)
                adapter.notifyDataSetChanged()
                updateEmptyState()
                Toast.makeText(requireContext(), "已追加 " + newLines.size + " 项", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(requireContext(), "剪贴板为空", Toast.LENGTH_SHORT).show()
    }

    private fun handleScannedContent(raw: String) {
        val extractedAddrs = QrImportHelper.extractAddresses(raw)
        if (extractedAddrs.isNotEmpty()) {
            if (listType == AddressListType.ALLOWED_SUBNET_PEERS) {
                // If scanned full multiaddr or payload, extract pure PeerID if possible
                val info = QrImportHelper.parse(raw)
                val pid = if (info != null && info.peerId.isNotBlank()) info.peerId else extractedAddrs.first()
                items.add(pid)
            } else {
                items.addAll(extractedAddrs)
            }
            adapter.notifyDataSetChanged()
            updateEmptyState()
            Toast.makeText(requireContext(), getString(R.string.msg_scan_success), Toast.LENGTH_SHORT).show()
        } else {
            items.add(raw.trim())
            adapter.notifyDataSetChanged()
            updateEmptyState()
            Toast.makeText(requireContext(), getString(R.string.msg_scan_success), Toast.LENGTH_SHORT).show()
        }
    }

    inner class AddressAdapter(
        private val items: List<String>,
        private val onCopy: (String) -> Unit,
        private val onEdit: (Int, String) -> Unit,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<AddressAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAddressCardBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemAddressCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val addr = items[position]
            val b = holder.binding

            b.tvIndex.text = "#" + (position + 1)
            b.tvAddress.text = addr

            val badge = detectProtocolBadge(addr)
            b.tvProtocolBadge.text = badge.first
            b.tvProtocolBadge.setBackgroundColor(Color.parseColor(badge.second))

            b.ivCopy.setOnClickListener { onCopy(addr) }
            b.ivEdit.setOnClickListener { onEdit(position, addr) }
            b.ivDelete.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount(): Int = items.size

        private fun detectProtocolBadge(addr: String): Pair<String, String> {
            val lower = addr.lowercase()
            return when {
                addr.trim() == "*" -> "ALL" to "#10B981"
                addr.startsWith("12D3") || addr.startsWith("Qm") -> "Peer ID" to "#6366F1"
                lower.contains("quic-v1") || lower.contains("quic") -> "QUIC-v1" to "#0891B2"
                lower.contains("webrtc") -> "WebRTC" to "#10B981"
                lower.contains("webtransport") -> "WebTransport" to "#6366F1"
                lower.contains("p2p-circuit") -> "Relay" to "#8B5CF6"
                lower.contains("/tcp/") -> "TCP" to "#F59E0B"
                lower.contains("/ip6/") || lower.contains("::") -> "IPv6" to "#7C3AED"
                lower.contains("/") && lower.contains(".") -> "CIDR" to "#2563EB"
                else -> "Endpoint" to "#06B6D4"
            }
        }
    }
}
