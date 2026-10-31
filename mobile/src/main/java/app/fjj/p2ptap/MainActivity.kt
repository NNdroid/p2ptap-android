package app.fjj.p2ptap

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import app.fjj.p2ptap.crash.CrashReportDialog
import app.fjj.p2ptap.databinding.ActivityMainBinding
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.ui.ConfigActivity
import app.fjj.p2ptap.ui.HomeFragment
import app.fjj.p2ptap.ui.PeersFragment
import app.fjj.p2ptap.ui.TrafficFragment
import app.fjj.p2ptap.viewmodel.MainViewModel

/**
 * The app shell: bottom navigation plus the three tab Fragments.
 *
 * What is left here is exactly what a Fragment cannot own — the bar, the
 * FragmentManager that swaps tab bodies, and the foreground / background
 * service notifications bound to the host's lifecycle. Everything the home
 * screen renders moved to [HomeFragment]; the peers and traffic lists live in
 * their own Fragments. All three read one [MainViewModel] through
 * `activityViewModels()`, so the engine is still polled once rather than three
 * times, and no tab has to talk to another.
 *
 * Tab bodies are added once and shown/hidden, not replaced: returning to a tab
 * restores its scroll position and its disclosure state. That also means their
 * view lifecycles stay RESUMED while hidden, so each Fragment gates its own
 * re-render through `onHiddenChanged`.
 *
 * Settings is the fourth destination but lives in [ConfigActivity], which
 * inflates the same bar and selects its own item. Tapping it here starts that
 * Activity and leaves [currentTab] alone — on return, onResume re-selects
 * whatever tab this screen is actually showing, so the bar can never claim
 * "settings" while the home hero is on screen.
 */
class MainActivity : AppCompatActivity(), HomeFragment.Host {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var currentTab: Int = TAB_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReportDialog.showIfNeeded(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs(
            savedInstanceState?.getInt(STATE_TAB, TAB_HOME)
                ?: intent?.getIntExtra(EXTRA_TAB, TAB_HOME)
                ?: TAB_HOME,
        )
    }

    private fun setupTabs(initialTab: Int) {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tabHome -> {
                    selectTab(TAB_HOME)
                    true
                }

                R.id.tabPeers -> {
                    selectTab(TAB_PEERS)
                    true
                }

                R.id.tabTraffic -> {
                    selectTab(TAB_TRAFFIC)
                    true
                }

                R.id.tabSettings -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
                    true
                }

                else -> false
            }
        }

        // Re-selecting the current tab scrolls it back to the top, which is
        // what every Material app does and the only way back to the hero
        // without dragging.
        binding.bottomNav.setOnItemReselectedListener { item ->
            val tag = when (item.itemId) {
                R.id.tabHome -> TAG_HOME
                R.id.tabPeers -> TAG_PEERS
                R.id.tabTraffic -> TAG_TRAFFIC
                else -> null
            }
            when (val f = tag?.let { supportFragmentManager.findFragmentByTag(it) }) {
                is HomeFragment -> f.scrollToTop()
                is PeersFragment, is TrafficFragment ->
                    (f.view as? androidx.core.widget.NestedScrollView)?.smoothScrollTo(0, 0)
            }
        }

        selectTab(initialTab)
        binding.bottomNav.selectedItemId = navItemFor(currentTab)
    }

    /**
     * Swaps the visible tab body and syncs the bar without re-entering the
     * listener.
     *
     * commitNow, not commit: the bar's selected item is set on the next line and
     * a Fragment that is still pending cannot be found by tag, which made
     * re-selection and tab restoration flaky.
     */
    private fun selectTab(tab: Int) {
        currentTab = tab
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (t in ALL_TABS) {
            val tag = tagFor(t)
            val existing = fm.findFragmentByTag(tag)
            if (t == tab) {
                if (existing == null) tx.add(R.id.tabContainer, newFragmentFor(t), tag) else tx.show(existing)
            } else if (existing != null) {
                tx.hide(existing)
            }
        }
        tx.commitNow()

        val item = navItemFor(tab)
        if (binding.bottomNav.selectedItemId != item) binding.bottomNav.selectedItemId = item
    }

    private fun newFragmentFor(tab: Int): Fragment = when (tab) {
        TAB_PEERS -> PeersFragment()
        TAB_TRAFFIC -> TrafficFragment()
        else -> HomeFragment()
    }

    private fun tagFor(tab: Int): String = when (tab) {
        TAB_PEERS -> TAG_PEERS
        TAB_TRAFFIC -> TAG_TRAFFIC
        else -> TAG_HOME
    }

    private fun navItemFor(tab: Int): Int = when (tab) {
        TAB_PEERS -> R.id.tabPeers
        TAB_TRAFFIC -> R.id.tabTraffic
        else -> R.id.tabHome
    }

    // ── HomeFragment.Host ──────────────────────────────────────────────────

    override fun navigateToTab(tab: Int) {
        if (tab != currentTab) selectTab(tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Arriving from ConfigActivity's bar: it starts this Activity with
        // CLEAR_TOP|SINGLE_TOP, so the tab lives in the new Intent rather than
        // in a fresh onCreate.
        val tab = intent.getIntExtra(EXTRA_TAB, currentTab)
        if (tab != currentTab) selectTab(tab)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPeers()

        // Re-assert the bar against the tab that is actually visible. Tapping
        // 设置 starts ConfigActivity and the bar briefly marks it as selected
        // before this Activity pauses; without this line it would come back
        // still highlighting Settings while the home hero is on screen.
        binding.bottomNav.selectedItemId = navItemFor(currentTab)

        // Resume engine callbacks when the app returns to foreground.
        if (P2PTapVpnService.isRunning()) {
            startService(Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_APP_FOREGROUND
            })
        }
    }

    override fun onPause() {
        // Pause engine callbacks while the app is in background to avoid
        // false "engine stopped reporting" alarms from the heartbeat monitor.
        if (P2PTapVpnService.isRunning()) {
            startService(Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_APP_BACKGROUND
            })
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, currentTab)
        super.onSaveInstanceState(outState)
    }

    companion object {
        // Bottom-nav destinations hosted by this Activity. Settings is intent
        // #3 — it lives in ConfigActivity — so it has no index here.
        const val TAB_HOME = 0
        const val TAB_PEERS = 1
        const val TAB_TRAFFIC = 2

        private val ALL_TABS = intArrayOf(TAB_HOME, TAB_PEERS, TAB_TRAFFIC)

        private const val TAG_HOME = "tab:home"
        private const val TAG_PEERS = "tab:peers"
        private const val TAG_TRAFFIC = "tab:traffic"

        private const val STATE_TAB = "app.fjj.p2ptap.state.TAB"

        /**
         * Set on the Intent when ConfigActivity's bar navigates back here.
         *
         * Public because the two screens share one bottom bar: ConfigActivity
         * has to name a destination that only MainActivity knows how to show.
         */
        const val EXTRA_TAB = "app.fjj.p2ptap.extra.TAB"
    }
}
