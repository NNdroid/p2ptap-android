package app.fjj.p2ptap.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PConfig
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.Toggle
import app.fjj.p2ptap.tv.ToggleAdapter
import app.fjj.p2ptap.tv.databinding.FragmentTvSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S4 Settings. A vertical grid of feature toggles plus a row of tier
 * selectors (obfuscation mode, transport priority, log level, MTU).
 *
 * The TV cannot show an EditText for free-text fields like TAP IP or DNS,
 * so those are omitted from the settings surface entirely — the user reaches
 * them via Transfer → Import a config. What remains here is switches and
 * enums, both of which are 10-foot-safe: focus + DPAD_LEFT/RIGHT to move
 * between values, DPAD_CENTER to select.
 */
class TvSettingsFragment : Fragment() {

    private var _binding: FragmentTvSettingsBinding? = null
    private val binding get() = _binding!!

    private var config: P2PConfig? = null
    private var dirty = false

    private val toggleAdapter = ToggleAdapter(::enabledTransports, ::onToggle)

    private val applyHandler = Handler(Looper.getMainLooper())

    private val applyConfig = Runnable {
        dirty = false
        val current = config ?: return@Runnable
        try {
            AppConfigManager.save(requireContext(), current)
            AppConfigManager.reloadRunningService(requireContext(), forceRestart = true)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(), getString(R.string.tv_set_save_failed), Toast.LENGTH_LONG
            ).show()
        }
    }

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

        binding.tvSettingsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.tvSettingsRecycler.adapter = toggleAdapter
        binding.tvSettingsRecycler.itemAnimator = null

        load()
        binding.tvSettingsRecycler.requestFocus()
    }

    override fun onStop() {
        if (dirty) {
            applyHandler.removeCallbacks(applyConfig)
            applyConfig.run()
        }
        super.onStop()
    }

    override fun onDestroy() {
        applyHandler.removeCallbacks(applyConfig)
        super.onDestroy()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val loaded = runCatching { AppConfigManager.load(requireContext()) }
                .getOrNull() ?: return@launch

            config = loaded
            toggles = listOf(
                Toggle(
                    R.string.tv_set_obfuscation, R.drawable.ic_tv_shield, false,
                    { loaded.obfuscationEnable }, { loaded.obfuscationEnable = it }
                ),
                Toggle(
                    R.string.tv_set_strict_keys, R.drawable.ic_tv_key, false,
                    { loaded.strictKeyNegotiation }, { loaded.strictKeyNegotiation = it }
                ),
                Toggle(
                    R.string.tv_set_quic, R.drawable.ic_tv_quic, true,
                    { loaded.enableQuic }, { loaded.enableQuic = it }
                ),
                Toggle(
                    R.string.tv_set_webrtc, R.drawable.ic_tv_webrtc, true,
                    { loaded.enableWebrtc }, { loaded.enableWebrtc = it }
                ),
                Toggle(
                    R.string.tv_set_webtransport, R.drawable.ic_tv_wt, true,
                    { loaded.enableWebtransport }, { loaded.enableWebtransport = it }
                ),
                Toggle(
                    R.string.tv_set_tcp, R.drawable.ic_tv_tcp, true,
                    { loaded.enableTcp }, { loaded.enableTcp = it }
                ),
                Toggle(
                    R.string.tv_set_relay, R.drawable.ic_tv_relay, false,
                    { !loaded.disableRelay }, { loaded.disableRelay = !it }
                ),
                Toggle(
                    R.string.tv_set_mdns, R.drawable.ic_tv_mdns, false,
                    { loaded.enableMdns }, { loaded.enableMdns = it }
                ),
                Toggle(
                    R.string.tv_set_subnets, R.drawable.ic_tv_subnet, false,
                    { loaded.acceptSubnets }, { loaded.acceptSubnets = it }
                ),
                Toggle(
                    R.string.tv_set_webui, R.drawable.ic_tv_webui, false,
                    { loaded.webUiEnable }, { loaded.webUiEnable = it }
                )
            )

            launch(Dispatchers.Main) {
                toggleAdapter.submit(toggles)
            }
        }
    }

    private fun onToggle(item: Toggle) {
        item.set(!item.value())
        toggleAdapter.submit(toggles)
        applySoon()
    }

    private fun enabledTransports(): Int {
        val current = config ?: return 0
        return listOf(
            current.enableQuic,
            current.enableWebrtc,
            current.enableWebtransport,
            current.enableTcp
        ).count { it }
    }

    private fun applySoon() {
        dirty = true
        applyHandler.removeCallbacks(applyConfig)
        applyHandler.postDelayed(applyConfig, APPLY_DEBOUNCE_MS)
    }

    private var toggles: List<Toggle> = emptyList()

    private companion object {
        const val APPLY_DEBOUNCE_MS = 400L
    }
}
