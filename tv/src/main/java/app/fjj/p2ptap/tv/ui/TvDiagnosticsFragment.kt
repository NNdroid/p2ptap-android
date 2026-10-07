package app.fjj.p2ptap.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.service.LogCollector
import app.fjj.p2ptap.service.NodeMetrics
import app.fjj.p2ptap.service.P2PStateRepository
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.TvFormat
import app.fjj.p2ptap.tv.databinding.FragmentTvDiagnosticsBinding
import kotlinx.coroutines.launch

/**
 * S5 Diagnostics. A grid of engine counters and a scrolling log pane.
 *
 * The log pane reads from [LogCollector]'s ring buffer, not from `logcat`.
 * The phone build used `Runtime.exec("logcat ...")`, which fails on a locked
 * TV set because the app doesn't have a shell. [LogCollector] installs a
 * [android.util.Log] sink when the VPN service starts, so this fragment just
 * renders the buffer contents.
 */
class TvDiagnosticsFragment : Fragment() {

    private var _binding: FragmentTvDiagnosticsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            P2PStateRepository.metrics.collect { metrics ->
                if (!isAdded) return@collect
                renderMetrics(metrics)
            }
        }
        // Render the log snapshot once on entry. The log pane updates when
        // the user presses the "refresh" button.
        refreshLog()
        binding.tvDiagRefresh.setOnClickListener { refreshLog() }
    }

    private fun renderMetrics(metrics: NodeMetrics) {
        binding.tvDiagTxSpeed.text = TvFormat.speed(metrics.txSpeed)
        binding.tvDiagRxSpeed.text = TvFormat.speed(metrics.rxSpeed)
        binding.tvDiagTotalTx.text = TvFormat.bytes(metrics.totalTx)
        binding.tvDiagTotalRx.text = TvFormat.bytes(metrics.totalRx)
        binding.tvDiagDirectPeers.text = metrics.directPeers.toString()
        binding.tvDiagRelayPeers.text = metrics.relayPeers.toString()
    }

    private fun refreshLog() {
        val text = LogCollector.text()
        binding.tvDiagLog.text = if (text.isBlank()) {
            getString(R.string.tv_no_telemetry)
        } else {
            text
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
