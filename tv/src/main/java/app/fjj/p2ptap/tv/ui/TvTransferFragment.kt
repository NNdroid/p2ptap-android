package app.fjj.p2ptap.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.databinding.FragmentTvTransferBinding
import com.google.android.material.card.MaterialCardView

/**
 * S3 Transfer. Four source cards in a horizontal row, each one representing
 * one of the four ways to get a config or identity onto this TV:
 *
 *   1. From a file (SAF document picker)
 *   2. From the clipboard (paste a JSON blob)
 *   3. From a QR code (TV shows QR, phone scans — or vice versa)
 *   4. Generate a new identity (blank node)
 *
 * The TV cannot scan a QR itself — no camera — so the QR source card
 * launches the "display QR" flow where the phone is the scanner.
 */
class TvTransferFragment : Fragment() {

    private var _binding: FragmentTvTransferBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Focus animation on each source card, matching the Home nav cards.
        TvFocusAnimation.attachTo(binding.tvTransferFile, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvTransferClipboard, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvTransferQr, TvFocusAnimation.SCALE_CARD)
        TvFocusAnimation.attachTo(binding.tvTransferNew, TvFocusAnimation.SCALE_CARD)

        // Wire the click listeners. The actual import/export flows are in
        // TvTransfer, which the pre-redesign Import + QR activities used;
        // the fragment delegates to it. Wire up when the transfer logic
        // is wired up next.
        binding.tvTransferFile.setOnClickListener { /* open SAF picker */ }
        binding.tvTransferClipboard.setOnClickListener { /* read clipboard */ }
        binding.tvTransferQr.setOnClickListener { /* display QR */ }
        binding.tvTransferNew.setOnClickListener { /* new identity */ }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
