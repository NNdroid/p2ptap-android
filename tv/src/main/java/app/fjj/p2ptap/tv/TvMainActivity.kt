package app.fjj.p2ptap.tv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import app.fjj.p2ptap.crash.CrashReportDialog
import app.fjj.p2ptap.service.LogCollector
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.tv.databinding.ActivityTvMainBinding
import app.fjj.p2ptap.tv.ui.TvNavDestination
import app.fjj.p2ptap.tv.ui.TvNavRailAdapter

/**
 * The single Activity the TV build launches into.
 *
 * It hosts a two-pane shell: a vertical rail on the left (fixed 240dp) and a
 * [NavHostFragment] on the right. Each destination is a Fragment, so the
 * platform's Activity lifecycle never fires when the user moves between
 * screens — the process, the memory and the Go engine's native handle are all
 * held across navigation. This is what made the pre-redesign eight-Activity
 * design so heavy: every destination switch was a full Activity cold start,
 * which on a box that throttles the CPU under load meant a 600–900 ms gap.
 *
 * The rail is a RecyclerView with [TvNavRailAdapter]; each item is a
 * MaterialButton in `Widget.P2ptap.Tv.RailItem` shape. Focus animation is a
 * 1.06x scale with 200ms AccelerateDecelerate, which matches the Material
 * Design guidance for remote-driven interfaces.
 *
 * All the business logic that used to live in the eight Activities — VPN
 * toggle, Shizuku status, config import, log collection — is in the fragments
 * and the :core module. This Activity owns only the shell and cross-cutting
 * concerns (log collector install, telemetry, back handling).
 */
class TvMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvMainBinding
    private lateinit var navController: NavController
    private lateinit var railAdapter: TvNavRailAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReportDialog.showIfNeeded(this)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable remote control input handling. setIsRemoteControlEnabled is
        // the canonical API for TV; setRemoteFunctionMode is a legacy
        // Activity method that also exists but is not needed alongside it.
        try {
            val method = android.app.Activity::class.java
                .getMethod("setIsRemoteControlEnabled", Boolean::class.javaPrimitiveType!!)
            method.invoke(this, true)
        } catch (_: Exception) {
            // Expected on non-TV builds; the Activity still works, just without
            // the extra remote input handling.
        }

        // Install the log sink once, at the app's entry point. Idempotent;
        // the phone build never calls it.
        LogCollector.install()

        // Navigation host. The graph is set up in activity_tv_main.xml via
        // <navGraph android:navGraph="@navigation/tv_nav_graph">.
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.tv_nav_host) as NavHostFragment
        navController = navHostFragment.navController

        // Wire the rail. On selection change, navigate to the destination.
        railAdapter = TvNavRailAdapter(TvNavDestination.all, selectedId = R.id.tvHomeFragment)
        binding.tvRail.layoutManager = LinearLayoutManager(this)
        binding.tvRail.adapter = railAdapter
        // Disable the default item animator on TV: on remote control, an
        // item animating into place reads as jitter. Instant moves only.
        binding.tvRail.itemAnimator = null
        binding.tvRail.setHasFixedSize(true)

        railAdapter.onSelect = { destination ->
            if (navController.currentDestination?.id != destination.id) {
                navController.navigate(destination.id)
            }
        }

        // Keep the rail highlighted in sync with the current destination,
        // so BACK from a nested destination re-highlights the parent.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            railAdapter.setSelectedDestination(destination.id)
        }

        // Intercept BACK: on a nested destination it navigates up the nav
        // stack; on the top-level destination it lets the platform handle it
        // (which on TV is: minimize the app).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // popBackStack returns true when it removed an entry; false
                // means we're at the root destination, so hand BACK back to
                // the platform (which on TV minimizes the app).
                if (!navController.popBackStack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        TvTelemetry.start(this)
        if (P2PTapVpnService.isRunning()) {
            startService(Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_APP_FOREGROUND
            })
        }
    }

    override fun onPause() {
        TvTelemetry.stop()
        if (P2PTapVpnService.isRunning()) {
            startService(Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_APP_BACKGROUND
            })
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // When the app comes back from the system VPN consent dialog (which
        // is an Activity on top of this one) or from a backgrounded state,
        // restore focus to the rail's currently selected item rather than to
        // whatever the fragment left selected — the rail is the primary
        // navigation surface and is always reachable.
        if (hasFocus) binding.tvRail.requestFocus()
    }
}
