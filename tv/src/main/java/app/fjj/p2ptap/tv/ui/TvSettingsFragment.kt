package app.fjj.p2ptap.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PConfig
import app.fjj.p2ptap.tv.Chip
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.Toggle
import app.fjj.p2ptap.tv.ToggleGroup
import app.fjj.p2ptap.tv.bindGroup
import app.fjj.p2ptap.tv.databinding.FragmentTvSettingsBinding
import app.fjj.p2ptap.tv.databinding.ItemTvGroupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S4 Settings. One wide group on a full-width row, four more across two
 * columns, one chip per setting.
 *
 * The TV cannot show an EditText for free-text fields like TAP IP or DNS, so
 * those are omitted from the settings surface entirely - the user reaches them
 * via Transfer to import a config. What remains here is switches, which are
 * 10-foot-safe: focus and DPAD_CENTER to flip one, DPAD_LEFT/RIGHT and UP/DOWN
 * to move between them.
 *
 * Changes are debounced and saved on the way out rather than on every press,
 * because a relay or transport change forces the engine to rebuild and there is
 * no reason to rebuild nine times for a viewer sweeping a group.
 */
class TvSettingsFragment : Fragment() {

    private var _binding: FragmentTvSettingsBinding? = null
    private val binding get() = _binding!!

    private var config: P2PConfig? = null
    private var dirty = false

    private var groups: List<ToggleGroup> = emptyList()
    private var chips: List<Chip> = emptyList()

    private val applyHandler = Handler(Looper.getMainLooper())

    private val applyConfig = Runnable {
        val current = config ?: return@Runnable
        try {
            AppConfigManager.save(requireContext(), current)
            AppConfigManager.reloadRunningService(requireContext(), forceRestart = true)
            // Only clear dirty after success: if save() throws, the change must
            // stay marked so onStop() retries it.
            dirty = false
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
        load()
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
        chips = emptyList()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val loaded = runCatching { AppConfigManager.load(requireContext()) }
                .getOrNull() ?: return@launch

            config = loaded
            groups = listOf(
                ToggleGroup(
                    R.string.tv_grp_transports,
                    listOf(
                        Toggle(
                            R.string.tv_set_quic, transport = true,
                            getter = { loaded.enableQuic }, setter = { loaded.enableQuic = it }
                        ),
                        Toggle(
                            R.string.tv_set_webrtc, transport = true,
                            getter = { loaded.enableWebrtc }, setter = { loaded.enableWebrtc = it }
                        ),
                        Toggle(
                            R.string.tv_set_webtransport, transport = true,
                            getter = { loaded.enableWebtransport },
                            setter = { loaded.enableWebtransport = it }
                        ),
                        Toggle(
                            R.string.tv_set_tcp, transport = true,
                            getter = { loaded.enableTcp }, setter = { loaded.enableTcp = it }
                        ),
                    ),
                ),
                ToggleGroup(
                    titleRes = R.string.tv_grp_relay,
                    toggles = listOf(
                        Toggle(
                            R.string.tv_set_relay,
                            getter = { !loaded.disableRelay },
                            setter = { loaded.disableRelay = !it }
                        ),
                        Toggle(
                            R.string.tv_set_circuit_relay,
                            getter = { !loaded.disableCircuitRelay },
                            setter = { loaded.disableCircuitRelay = !it },
                            enabled = { !loaded.disableRelay }
                        ),
                        Toggle(
                            R.string.tv_set_hole_punching,
                            getter = { !loaded.disableHolePunching },
                            setter = { loaded.disableHolePunching = !it },
                            enabled = { !loaded.disableRelay }
                        ),
                    ),
                    wide = true,
                ),
                ToggleGroup(
                    R.string.tv_grp_security,
                    listOf(
                        Toggle(
                            R.string.tv_set_obfuscation,
                            getter = { loaded.obfuscationEnable },
                            setter = { loaded.obfuscationEnable = it }
                        ),
                        Toggle(
                            R.string.tv_set_strict_keys,
                            getter = { loaded.strictKeyNegotiation },
                            setter = { loaded.strictKeyNegotiation = it }
                        ),
                    ),
                ),
                ToggleGroup(
                    R.string.tv_grp_discovery,
                    listOf(
                        Toggle(
                            R.string.tv_set_mdns,
                            getter = { loaded.enableMdns }, setter = { loaded.enableMdns = it }
                        ),
                        Toggle(
                            R.string.tv_set_subnets,
                            getter = { loaded.acceptSubnets },
                            setter = { loaded.acceptSubnets = it }
                        ),
                    ),
                ),
                ToggleGroup(
                    R.string.tv_grp_webui,
                    listOf(
                        Toggle(
                            R.string.tv_set_webui,
                            getter = { loaded.webUiEnable }, setter = { loaded.webUiEnable = it }
                        ),
                    ),
                ),
            )

            launch(Dispatchers.Main) { render() }
        }
    }

    /**
     * Put the wide group in the full-width lead slot, then tile the rest down
     * the two columns in list order. The wide group is always rendered first
     * so the focus path starts at the top left of the screen; that makes the
     * on-screen order relay, transports, security, discovery, web console, with
     * the single-chip card last.
     */
    private fun render() {
        val columns = listOf(
            binding.tvSettingsColumnLeft,
            binding.tvSettingsColumnRight,
        )
        binding.tvSettingsLead.removeAllViews()
        columns.forEach { it.removeAllViews() }

        val gap = resources.getDimensionPixelSize(R.dimen.tv_group_gap).toInt()
        val painted = mutableListOf<Chip>()
        var nextColumn = 0
        for (group in groups) {
            val slot = if (group.wide) binding.tvSettingsLead else columns[nextColumn++ % columns.size]
            val card = ItemTvGroupBinding.inflate(layoutInflater, slot, false)
            if (slot.childCount > 0) {
                (card.root.layoutParams as LinearLayout.LayoutParams).topMargin = gap
            }
            slot.addView(card.root)
            painted.addAll(card.bindGroup(group, ::onToggle))
        }
        chips = painted
        refresh()

        binding.tvSettingsGroups.post {
            chips.firstOrNull()?.root?.requestFocus()
        }
    }

    private fun refresh() {
        val transportCount = enabledTransports()
        chips.forEach { it.paint(it.toggle.transport && it.toggle.value() && transportCount <= 1) }
    }

    private fun onToggle(toggle: Toggle) {
        toggle.set(!toggle.value())
        refresh()
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

    private companion object {
        const val APPLY_DEBOUNCE_MS = 400L
    }
}
