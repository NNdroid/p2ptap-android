package app.fjj.p2ptap.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.keeper.TvShellKeeper
import app.fjj.p2ptap.tv.databinding.FragmentTvKeepaliveBinding

/**
 * S6 Keep-Alive. The three layers the TV build ships with:
 *
 *   L1  Boot restore     — always on by default (P2PBootFlagStore)
 *   L2  In-process watchdog  — a second foreground service
 *   L3  Shizuku keeper   — shell uid can bring the app back after a kill
 *
 * L1 and L2 are always active and cost nothing. L3 requires Shizuku to be
 * installed and running. Every call site below is gated on
 * [TvShellKeeper.shizukuRunning]; an app without Shizuku sees the L3
 * section as disabled, not as missing.
 *
 * The honest answer for the TV: rebooting a set-top box drops every
 * foreground service and Shizuku itself goes away. L1 restores the "I want
 * the tunnel back" intent; L2 watches within the app's own process; L3 is
 * the only layer that survives the set killing every P2PTap process — but
 * the user has to re-activate Shizuku after the reboot.
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
        renderL1()
        renderL2()
        renderL3()

        binding.tvKaActivate.setOnClickListener {
            // Launch the Shizuku launcher activity. It does not exist on
            // every box; the fallback message is the honest answer.
            val launch = Intent().apply {
                setPackage("moe.shizuku.launcher")
                action = "android.intent.action.MAIN"
                addCategory("android.intent.category.LAUNCHER")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launch.resolveActivity(requireContext().packageManager) == null) {
                binding.tvKaStatus.text = getString(R.string.tv_ka_missing)
            } else {
                startActivity(launch)
            }
        }
        binding.tvKaRecover.setOnClickListener {
            if (TvShellKeeper.restoreTunnel()) {
                Toast.makeText(requireContext(), R.string.tv_ka_recover_done,
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.tv_ka_missing,
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderL1() {
        // L1 is always on (the P2PBootFlagStore default); no switch here.
        binding.tvKaL1State.text = getString(R.string.tv_ka_l1_state_on)
    }

    private fun renderL2() {
        // L2 is passive; it activates with the VPN service.
        binding.tvKaL2State.text = getString(R.string.tv_ka_l2_state_active)
    }

    private fun renderL3() {
        val up = TvShellKeeper.shizukuRunning()
        if (up) {
            binding.tvKaState.text = getString(R.string.tv_ka_up)
            binding.tvKaActivate.isEnabled = false
            binding.tvKaRecover.isEnabled = true
            binding.tvKaStatus.text = getString(
                R.string.tv_ka_ver_fmt,
                TvShellKeeper.serviceVersion().toString(),
                TvShellKeeper.uid()
            )
        } else {
            binding.tvKaState.text = getString(R.string.tv_ka_down)
            binding.tvKaActivate.isEnabled = true
            binding.tvKaRecover.isEnabled = false
            binding.tvKaStatus.text = getString(R.string.tv_ka_missing)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

