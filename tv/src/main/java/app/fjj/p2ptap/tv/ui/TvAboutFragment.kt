package app.fjj.p2ptap.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.tv.R
import app.fjj.p2ptap.tv.databinding.FragmentTvAboutBinding
import com.p2ptap.P2PTap.P2PTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * S7 About. Engine version, app version, peer id, and links.
 */
class TvAboutFragment : Fragment() {

    private var _binding: FragmentTvAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTvAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadVersions()
    }

    private fun loadVersions() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val engine = runCatching { P2PTap.version() }.getOrNull()?.trim().orEmpty()
            val appVersion = runCatching {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName.orEmpty()
            }.getOrNull().orEmpty()
            launch(Dispatchers.Main) {
                binding.tvAboutEngine.text = engine.ifBlank { "—" }
                binding.tvAboutApp.text = appVersion.ifBlank { "—" }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
