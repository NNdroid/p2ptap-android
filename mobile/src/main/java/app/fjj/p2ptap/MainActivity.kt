package app.fjj.p2ptap

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import app.fjj.p2ptap.crash.CrashReportDialog
import app.fjj.p2ptap.databinding.ActivityMainBinding
import app.fjj.p2ptap.service.P2PTapVpnService
import app.fjj.p2ptap.ui.ConfigFragment
import app.fjj.p2ptap.ui.HomeFragment
import app.fjj.p2ptap.ui.PeersFragment
import app.fjj.p2ptap.ui.TrafficFragment
import app.fjj.p2ptap.viewmodel.MainViewModel

/**
 * The app shell: bottom navigation plus the four tab Fragments.
 *
 * What is left here is exactly what a Fragment cannot own — the bar and the
 * FragmentManager that swaps tab bodies. All four tabs (HomeFragment /
 * PeersFragment / TrafficFragment / ConfigFragment) read one [MainViewModel]
 * through `activityViewModels()`, and the bar has exactly one selection
 * listener, so there is nothing to keep in sync between screens.
 *
 * Tab bodies are added once and shown/hidden, not replaced: returning to a tab
 * restores its scroll position and its disclosure state. That also means their
 * view lifecycles stay RESUMED while hidden, so each Fragment gates its own
 * re-render through `onHiddenChanged`.
 *
 * Settings used to be the fourth destination in a separate [ConfigActivity]
 * with its own copy of the bar; hopping between the two screens with Intents
 * caused the bar-sync bug family that ended in the 2026-11-01
 * StackOverflowError, so the settings screen is now [ConfigFragment] here.
 */
class MainActivity : AppCompatActivity(), HomeFragment.Host, ConfigFragment.Host {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var currentTab: Int = TAB_HOME

    // The tab to return to when the settings tab closes (Save committed).
    private var tabBeforeSettings: Int = TAB_HOME

    // Re-entrancy guard for programmatic bar selection. Material commits the
    // checked state AFTER the selection callback returns, so while that
    // callback is on the stack `selectedItemId` still reads the PREVIOUS item.
    // selectTab's "already selected?" check therefore always sees a stale value
    // and re-enters setSelectedItemId without a flag — infinite recursion,
    // observed as a StackOverflowError on launch whenever the initial tab is
    // not Home (crash report 2026-11-01 09:38).
    private var barSelectionSync = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReportDialog.showIfNeeded(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs(savedInstanceState?.getInt(STATE_TAB, TAB_HOME) ?: TAB_HOME)
    }

    private fun setupTabs(initialTab: Int) {
        binding.bottomNav.setOnItemSelectedListener { item ->
            // This bar is being driven programmatically; acknowledge and stop.
            if (barSelectionSync) return@setOnItemSelectedListener true
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
                    selectTab(TAB_SETTINGS)
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
        if (tab == TAB_SETTINGS && currentTab != TAB_SETTINGS) {
            tabBeforeSettings = currentTab
        }
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

        syncBarToCurrentTab()
    }

    /**
     * Points the bar at the tab that is actually visible. The selection
     * listener is suppressed for the duration (see [barSelectionSync]): the
     * stale-read inside Material makes the "is it already selected" short
     * circuit unusable on its own.
     */
    private fun syncBarToCurrentTab() {
        val item = navItemFor(currentTab)
        if (binding.bottomNav.selectedItemId == item) return
        barSelectionSync = true
        try {
            binding.bottomNav.selectedItemId = item
        } finally {
            barSelectionSync = false
        }
    }

    private fun newFragmentFor(tab: Int): Fragment = when (tab) {
        TAB_PEERS -> PeersFragment()
        TAB_TRAFFIC -> TrafficFragment()
        TAB_SETTINGS -> ConfigFragment()
        else -> HomeFragment()
    }

    private fun tagFor(tab: Int): String = when (tab) {
        TAB_PEERS -> TAG_PEERS
        TAB_TRAFFIC -> TAG_TRAFFIC
        TAB_SETTINGS -> TAG_SETTINGS
        else -> TAG_HOME
    }

    private fun navItemFor(tab: Int): Int = when (tab) {
        TAB_PEERS -> R.id.tabPeers
        TAB_TRAFFIC -> R.id.tabTraffic
        TAB_SETTINGS -> R.id.tabSettings
        else -> R.id.tabHome
    }

    // ── Host interfaces ────────────────────────────────────────────────────

    override fun navigateToTab(tab: Int) {
        if (tab != currentTab) selectTab(tab)
    }

    override fun onSettingsSaved() {
        selectTab(tabBeforeSettings)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPeers()

        // Re-assert the bar against the tab that is actually visible, in case
        // a recreated view or an intent left it somewhere stale.
        syncBarToCurrentTab()

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
        // Bottom-nav destinations hosted by this Activity — all four of them
        // now, including Settings (ConfigFragment).
        const val TAB_HOME = 0
        const val TAB_PEERS = 1
        const val TAB_TRAFFIC = 2
        const val TAB_SETTINGS = 3

        private val ALL_TABS = intArrayOf(TAB_HOME, TAB_PEERS, TAB_TRAFFIC, TAB_SETTINGS)

        private const val TAG_HOME = "tab:home"
        private const val TAG_PEERS = "tab:peers"
        private const val TAG_TRAFFIC = "tab:traffic"
        private const val TAG_SETTINGS = "tab:settings"

        private const val STATE_TAB = "app.fjj.p2ptap.state.TAB"
    }
}
