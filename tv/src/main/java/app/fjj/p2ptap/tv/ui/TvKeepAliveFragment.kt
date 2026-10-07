package app.fjj.p2ptap.tv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.fjj.p2ptap.config.P2PBootFlagStore
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.TvKeepAliveState
import app.fjj.p2ptap.tv.TvWatchdogService
import app.fjj.p2ptap.tv.databinding.FragmentTvKeepaliveBinding
import app.fjj.p2ptap.tv.keeper.TvShellKeeper

/**
 * S6 Keep-Alive. The three layers the TV build ships with:
 *
 *   L1  Boot restore     — reads P2PBootFlagStore
 *   L2  In-process watchdog  — a second foreground service, toggleable
 *   L3  Shizuku keeper   — shell uid can bring the app back after a kill
 *
 * L1 and L2 are always active and cost nothing. L3 requires Shizuku to be
 * installed and running. Every call site below is gated on
 * [TvShellKeeper.shizukuRunning]; an app without Shizuku sees the L3
 * section as disabled, not as missing.
 */
class TvKeepAliveFragment : Fragment() {

    private var _binding: FragmentTvKeepaliveBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvKeepaliveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvKaActivate.setOnClickListener {
            openShizuku()
        }
        binding.tvKaRecover.setOnClickListener {
            if (TvShellKeeper.restoreTunnel()) {
                Toast.makeText(requireContext(), R.string.tv_ka_recover_done,
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.tv_ka_activate_failed,
                    Toast.LENGTH_SHORT).show()
            }
        }
        binding.tvKaL2Card.setOnClickListener { toggleWatchdog() }
        binding.tvKaL2State.setOnClickListener { toggleWatchdog() }
    }

    override fun onResume() {
        super.onResume()
        bindKeeper()
        render()
    }

    override fun onPause() {
        TvShellKeeper.unbind()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── L1: boot restore ────────────────────────────────────────────────────

    private fun renderL1() {
        binding.tvKaL1State.text = getString(
            if (P2PBootFlagStore.isAutostart(requireContext()))
                R.string.tv_ka_l1_state_on
            else
                R.string.tv_ka_l1_state_off
        )
    }

    // ── L2: in-process watchdog ─────────────────────────────────────────────

    private fun renderL2() {
        val state = TvKeepAliveState.resolve(requireContext())
        val label = when (state) {
            TvKeepAliveState.WatchdogState.RUNNING -> R.string.tv_ka_l2_state_active
            TvKeepAliveState.WatchdogState.PROCESS_GONE -> R.string.tv_ka_l2_state_gone
            TvKeepAliveState.WatchdogState.DEGRADED -> R.string.tv_ka_l2_state_degraded
            TvKeepAliveState.WatchdogState.STOPPED -> R.string.tv_ka_l2_state_stopped
        }
        binding.tvKaL2State.text = getString(label)
    }

    private fun toggleWatchdog() {
        val state = TvKeepAliveState.resolve(requireContext())
        if (state == TvKeepAliveState.WatchdogState.STOPPED ||
            state == TvKeepAliveState.WatchdogState.DEGRADED ||
            state == TvKeepAliveState.WatchdogState.PROCESS_GONE
        ) {
            TvWatchdogService.startWatchdog(requireContext())
        } else {
            TvWatchdogService.stopWatchdog(requireContext())
        }
        renderL2()
    }

    // ── L3: Shizuku keeper ──────────────────────────────────────────────────

    private fun bindKeeper() {
        if (!TvShellKeeper.shizukuRunning()) {
            TvShellKeeper.unbind()
            return
        }
        val problem = TvShellKeeper.bind(requireContext())
        if (problem.isNotEmpty()) {
            Log.w(TAG, "Keeper bind reported $problem")
        }
    }

    private fun renderL3() {
        val installed = isShizukuLauncherInstalled()
        val up = TvShellKeeper.shizukuRunning()

        when {
            !installed -> {
                binding.tvKaState.text = getString(R.string.tv_ka_down)
                binding.tvKaActivate.isEnabled = false
                binding.tvKaRecover.isEnabled = false
                binding.tvKaStatus.text = getString(R.string.tv_ka_missing)
            }

            !up -> {
                binding.tvKaState.text = getString(R.string.tv_ka_down)
                binding.tvKaActivate.isEnabled = true
                binding.tvKaRecover.isEnabled = false
                binding.tvKaStatus.text = getString(R.string.tv_ka_needs_activation)
            }

            else -> {
                binding.tvKaState.text = getString(R.string.tv_ka_up)
                binding.tvKaActivate.isEnabled = false
                binding.tvKaRecover.isEnabled = TvShellKeeper.keeperBinder() != null
                binding.tvKaStatus.text = getString(
                    R.string.tv_ka_ver_fmt,
                    TvShellKeeper.serviceVersion().toString(),
                    TvShellKeeper.uid()
                )
            }
        }
    }

    private fun openShizuku() {
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(SHIZUKU_LAUNCHER_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch.resolveActivity(requireContext().packageManager) == null) {
            Toast.makeText(requireContext(), getString(R.string.tv_ka_missing), Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(launch)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(requireContext(), getString(R.string.tv_ka_activate_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun isShizukuLauncherInstalled(): Boolean = runCatching {
        requireContext().packageManager.getPackageInfo(SHIZUKU_LAUNCHER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun render() {
        renderL1()
        renderL2()
        renderL3()
    }

    private companion object {
        const val TAG = "TvKeepAlive"
        const val SHIZUKU_LAUNCHER_PACKAGE = "moe.shizuku.launcher"
    }
}
