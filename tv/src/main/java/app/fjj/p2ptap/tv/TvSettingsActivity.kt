package app.fjj.p2ptap.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PConfig
import app.fjj.p2ptap.tv.databinding.ActivityTvSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S4: the settings a viewer actually changes.
 *
 * Ten toggles and one tier, all reachable by pressing, none of them by typing.
 * A set-top box has no keyboard, so any setting that needs free text — bootstrap
 * peers, DNS, PSK, SNI — is not here. It arrives with a config file through the
 * import screen, which is also how the TV gets a configuration at all.
 *
 * The tier for obfuscation mode is not here: it only means something when
 * obfuscation is on, so it sits with the toggle that controls it, on this
 * screen, and dims itself when that toggle is off.
 */
class TvSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvSettingsBinding
    private val toggleAdapter = ToggleAdapter(::enabledTransports, ::onToggle)

    private var config: P2PConfig? = null
    private var toggles: List<Toggle> = emptyList()
    private var tier: TierModel? = null
    private var dirty = false

    // The activity's own handler rather than the platform's property, because
    // that property is only present on a recent enough appcompat.
    private val applyHandler = Handler(Looper.getMainLooper())

    /**
     * Save and restart, coalesced.
     *
     * Every change is written into the same config object, so the only thing a
     * delay buys is fewer restarts. Without it, a viewer sweeping across a rail
     * of chips would restart the tunnel once per chip, which is the difference
     * between a settings screen and a settings screen that fights the user.
     */
    private val applyConfig = Runnable {
        dirty = false
        val current = config ?: return@Runnable
        try {
            AppConfigManager.save(this, current)
            // A hard restart, not a soft reload: transport, MTU and log level
            // are engine settings and a live tunnel cannot take them without
            // being replaced.
            AppConfigManager.reloadRunningService(this, forceRestart = true)
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.tv_set_save_failed), Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvToggleGrid.apply {
            adapter = toggleAdapter
            setNumColumns(2)
        }
        binding.tvSettingsAdvanced.setOnClickListener {
            startActivity(Intent(this, TvAdvancedActivity::class.java))
        }
        binding.tvSettingsKeepAlive.setOnClickListener {
            startActivity(Intent(this, TvKeepAliveActivity::class.java))
        }
        binding.tvSettingsAbout.setOnClickListener {
            startActivity(Intent(this, TvAboutActivity::class.java))
        }

        load()
        binding.tvToggleGrid.requestFocus()
    }

    override fun onStop() {
        // Home key, BACK to home, and the app switcher all land here rather
        // than in onDestroy, so a pending change is written before it is lost.
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

    private fun load() {
        lifecycleScope.launch(Dispatchers.Default) {
            val loaded = runCatching { AppConfigManager.load(this@TvSettingsActivity) }
                .getOrNull() ?: return@launch

            val obfuscationTier = TierModel(
                labelRes = R.string.tv_tier_obfuscation,
                options = OBFUSCATION_MODES,
                current = loaded.obfuscationMode.ifBlank { OBFUSCATION_MODES.first() }
            ) { value ->
                loaded.obfuscationMode = value
                // A viewer who reaches for a different obfuscation mode wants it
                // on; picking the mode turns the switch on with it.
                loaded.obfuscationEnable = true
                applySoon()
            }

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
                // Stored as disableRelay and shown as "relay allowed". A viewer
                // thinks in positives, and a setting that reads "disable" while
                // it is the one that is on is what gets toggled back by accident.
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
            tier = obfuscationTier

            launch(Dispatchers.Main) { repaint() }
        }
    }

    private fun repaint() {
        val current = config ?: return
        tier?.enabled = current.obfuscationEnable
        toggleAdapter.submit(toggles)
        tier?.render(binding.tvTierObfuscation, ::repaint)
    }

    private fun onToggle(item: Toggle) {
        val current = config ?: return
        item.set(!item.value())
        repaint()
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

        // The engine's own mode names, in the order the phone offers them.
        val OBFUSCATION_MODES = listOf("auto", "fixed", "block", "random", "dynamic")
    }
}
