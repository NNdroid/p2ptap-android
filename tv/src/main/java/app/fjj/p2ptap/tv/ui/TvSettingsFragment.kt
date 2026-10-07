package app.fjj.p2ptap.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.databinding.FragmentTvSettingsBinding

/**
 * S4 Settings. A vertical grid of feature toggles plus a row of tier
 * selectors (obfuscation mode, transport priority, log level, MTU).
 *
 * The TV cannot show an EditText for free-text fields like TAP IP or DNS,
 * so those are omitted from the settings surface entirely — the user reaches
 * them via Transfer → Import a config. What remains here is switches and
 * enums, both of which are 10-foot-safe: focus + DPAD_LEFT/RIGHT to move
 * between values, DPAD_CENTER to select.
 *
 * Wires to AppConfigManager on save. The actual save flow is stubbed here
 * because the toggle/tier UIs were rewritten from scratch in the redesign
 * and the config schema hasn't been re-mapped yet.
 */
class TvSettingsFragment : Fragment() {

    private var _binding: FragmentTvSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Focus animation on the settings rows and the tier selector.
        // The RecyclerView's OnFocusChangeListener is the correct place for
        // this, not per-view.
        binding.tvSettingsRecycler.setOnFocusChangeListener { _, _ -> }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
