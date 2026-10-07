package app.fjj.p2ptap.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.config.P2PConfig
import app.fjj.p2ptap.tv.databinding.ActivityTvAdvancedBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S4a: the settings that change how the tunnel behaves, not whether it is on.
 *
 * These are one hop behind the main settings screen for a reason that is only
 * arithmetic: the main screen holds ten toggles and one rail with slack to
 * spare, and a 540dp picture cannot hold that plus three more rails. The
 * clipped half of a layout is not hidden, it is gone, so the overflow goes a
 * screen deeper instead.
 *
 * Every control here is a value the engine understands verbatim. There is no
 * free text and no unit conversion, because the config file records exactly
 * what the viewer pressed.
 */
class TvAdvancedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvAdvancedBinding

    private var config: P2PConfig? = null
    private var strategyTier: TierModel? = null
    private var logLevelTier: TierModel? = null
    private var mtuTier: TierModel? = null
    private var dirty = false
    private var firstFocusDone = false

    // The activity's own handler rather than the platform's property, because
    // that property is only present on a recent enough appcompat.
    private val applyHandler = Handler(Looper.getMainLooper())

    /** Same coalescing as the settings screen: one restart per pause, not per chip. */
    private val applyConfig = Runnable {
        dirty = false
        val current = config ?: return@Runnable
        try {
            AppConfigManager.save(this, current)
            AppConfigManager.reloadRunningService(this, forceRestart = true)
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.tv_set_save_failed), Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvAdvancedBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    private fun load() {
        lifecycleScope.launch(Dispatchers.Default) {
            val loaded = runCatching { AppConfigManager.load(this@TvAdvancedActivity) }
                .getOrNull() ?: return@launch

            strategyTier = TierModel(
                R.string.tv_tier_transport, TRANSPORT_STRATEGIES,
                loaded.transportStrategy.ifBlank { TRANSPORT_STRATEGIES.first() }
            ) { value ->
                loaded.transportStrategy = value
                applySoon()
            }
            logLevelTier = TierModel(
                R.string.tv_tier_log_level, LOG_LEVELS,
                loaded.logLevel.ifBlank { LOG_LEVELS.first() }
            ) { value ->
                loaded.logLevel = value
                applySoon()
            }
            mtuTier = TierModel(
                R.string.tv_tier_mtu, MTU_OPTIONS.map { it.toString() },
                loaded.mtu.toString()
            ) { value ->
                loaded.mtu = value.toInt()
                applySoon()
            }

            config = loaded
            launch(Dispatchers.Main) { repaint() }
        }
    }

    private fun repaint() {
        val current = config ?: return
        strategyTier?.render(binding.tvTierStrategy, ::repaint)
        logLevelTier?.render(binding.tvTierLogLevel, ::repaint)
        mtuTier?.render(binding.tvTierMtu, ::repaint)

        binding.tvCardBootMesh.bindToggle(
            Toggle(
                R.string.tv_set_mesh, R.drawable.ic_tv_mesh, false,
                { current.discoverBootMesh }, { current.discoverBootMesh = it }
            ),
            locked = false,
            onToggle = {
                repaint()
                applySoon()
            }
        )

        // The chips only exist after the config load, so the first focus lands
        // here rather than in onCreate, where nothing is focusable yet.
        if (!firstFocusDone) {
            firstFocusDone = true
            binding.tvTierStrategy.tvTierChips.getChildAt(0)?.requestFocus()
        }
    }

    private fun applySoon() {
        dirty = true
        applyHandler.removeCallbacks(applyConfig)
        applyHandler.postDelayed(applyConfig, APPLY_DEBOUNCE_MS)
    }

    private companion object {
        const val APPLY_DEBOUNCE_MS = 400L

        // The engine's own strings, unchanged. "best_path" is the default and
        // is what the phone app installs.
        val TRANSPORT_STRATEGIES = listOf("best_path", "redundant", "fallback")
        val LOG_LEVELS = listOf("debug", "info", "warn", "error")
        // Common router MTUs. Anything below 1280 breaks DNS over the tunnel.
        val MTU_OPTIONS = listOf(1280, 1400, 1420, 1500)
    }
}
