package app.fjj.p2ptap.tv

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.fjj.p2ptap.config.P2PBootFlagStore
import app.fjj.p2ptap.tv.databinding.ActivityTvKeepaliveBinding
import app.fjj.p2ptap.tv.databinding.ItemTvKaLayerBinding
import app.fjj.p2ptap.tv.keeper.TvShellKeeper

/**
 * S6: what keeps the tunnel alive, and whether each layer is really doing it.
 *
 * The screen is a read of three independent facts plus one action per row.
 * Nothing here is a switch: the layers are not user-tunable, they are
 * properties of the box. Showing a toggle for something decided by whether a
 * Linux uid exists would be a promise this app cannot keep.
 *
 * The Shizuku row is the only one whose action can fail in a way the viewer
 * can fix, so it gets the activate button. The other two report and act
 * without ceremony, because a keep-alive that asks permission before restoring
 * a tunnel is a keep-alive that missed the window.
 */
class TvKeepAliveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvKeepaliveBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvKeepaliveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureBootRow()
        configureWatchdogRow()
        configureShizukuRow()

        // The watchdog row is the first action most viewers will take; the
        // Shizuku row needs the most explanation. Focus starts where the screen
        // is cheapest to use.
        binding.tvKaWatchdog.root.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // The keeper runs in another process and this activity is where the
        // viewer decides it should exist. Binding here rather than at app
        // startup means a box without Shizuku never pays for the process, and a
        // box with Shizuku gets it back whenever the screen is opened — which
        // is exactly when it matters.
        bindKeeper()
        render()
    }

    override fun onPause() {
        // The daemon flag on the args is what keeps the keeper alive across app
        // restarts, not something we hold. Unbinding here releases the handle
        // this activity owns so the next open is a fresh bind.
        TvShellKeeper.unbind()
        super.onPause()
    }

    // ── Row setup ───────────────────────────────────────────────────────────

    private fun configureBootRow() {
        val row = binding.tvKaBoot
        row.tvKaLayerTitle.setText(R.string.tv_ka_boot)
        row.tvKaLayerDesc.setText(R.string.tv_ka_boot_desc)
        row.tvKaLayerIcon.setImageResource(R.drawable.ic_tv_power)
        // L1 is read-only: it reports what will happen, and the viewer cannot
        // change it without changing the tunnel state itself.
        row.tvKaLayerAction.visibility = View.GONE
        row.tvKaLayerAction.isFocusable = false
    }

    private fun configureWatchdogRow() {
        val row = binding.tvKaWatchdog
        row.tvKaLayerTitle.setText(R.string.tv_ka_watchdog)
        row.tvKaLayerDesc.setText(R.string.tv_ka_watchdog_desc)
        row.tvKaLayerIcon.setImageResource(R.drawable.ic_tv_keepalive)
        row.tvKaLayerAction.setOnClickListener { toggleWatchdog() }
        row.root.setOnClickListener { toggleWatchdog() }
    }

    private fun configureShizukuRow() {
        val row = binding.tvKaShizuku
        row.tvKaLayerTitle.setText(R.string.tv_ka_shizuku)
        row.tvKaLayerDesc.setText(R.string.tv_ka_shizuku_desc)
        row.tvKaLayerIcon.setImageResource(R.drawable.ic_tv_shield)
        row.tvKaLayerAction.setOnClickListener { onShizukuAction() }
        row.root.setOnClickListener { onShizukuAction() }
    }

    // ── L1: boot restore ────────────────────────────────────────────────────

    private fun renderBoot() {
        binding.tvKaBoot.tvKaLayerStatus.setText(
            if (P2PBootFlagStore.isAutostart(this)) R.string.tv_autostart_on
            else R.string.tv_autostart_off
        )
    }

    // ── L2: watchdog ────────────────────────────────────────────────────────

    private fun renderWatchdog() {
        val row = binding.tvKaWatchdog
        val state = TvKeepAliveState.resolve(this)
        row.tvKaLayerStatus.setText(TvKeepAliveState.watchdogLabel(state))

        if (state == TvKeepAliveState.WatchdogState.DEGRADED &&
            TvKeepAliveState.watchdogDetail.isNotEmpty()
        ) {
            // DEGRADED means the foreground declaration was refused. Showing the
            // exception name here is the difference between "it says disabled"
            // and "the box refused the second foreground service".
            row.tvKaLayerStatus.text =
                getString(R.string.tv_ka_disabled) + " (" + TvKeepAliveState.watchdogDetail + ")"
        }

        // PROCESS_GONE counts as "should be running" for the button label: the
        // viewer pressed Start before, and the right next press is Start again.
        val shouldRun = state == TvKeepAliveState.WatchdogState.STOPPED ||
            state == TvKeepAliveState.WatchdogState.DEGRADED
        row.tvKaLayerAction.setText(if (shouldRun) R.string.tv_ka_start else R.string.tv_ka_stop)
    }

    private fun toggleWatchdog() {
        val state = TvKeepAliveState.resolve(this)
        if (state == TvKeepAliveState.WatchdogState.STOPPED ||
            state == TvKeepAliveState.WatchdogState.DEGRADED ||
            state == TvKeepAliveState.WatchdogState.PROCESS_GONE
        ) {
            TvWatchdogService.startWatchdog(this)
        } else {
            TvWatchdogService.stopWatchdog(this)
        }
        render()
    }

    // ── L3: Shizuku ─────────────────────────────────────────────────────────

    /**
     * Bind the keeper when Shizuku is actually live. Every failure is swallowed:
     * this screen must report that Shizuku is unusable, never crash to say so.
     */
    private fun bindKeeper() {
        if (!TvShellKeeper.shizukuRunning()) {
            TvShellKeeper.unbind()
            return
        }
        val problem = TvShellKeeper.bind(this)
        if (problem.isNotEmpty()) {
            Log.w(TAG, "Keeper bind reported $problem")
        }
    }

    private fun renderShizuku() {
        val row = binding.tvKaShizuku
        val action = row.tvKaLayerAction

        when {
            !isShizukuLauncherInstalled() -> {
                row.tvKaLayerStatus.setText(R.string.tv_ka_missing)
                hideAction(action)
            }

            !TvShellKeeper.shizukuRunning() -> {
                row.tvKaLayerStatus.setText(R.string.tv_ka_needs_activation)
                showAction(action, R.string.tv_ka_activate)
            }

            else -> {
                row.tvKaLayerStatus.text = shizukuStatusText()
                action.visibility = View.VISIBLE
                action.isFocusable = true
                action.setText(
                    if (TvShellKeeper.keeperBinder() != null) R.string.tv_ka_recover
                    else R.string.tv_ka_activate
                )
            }
        }
    }

    private fun shizukuStatusText(): String {
        val version = TvShellKeeper.serviceVersion().toString()
        return getString(R.string.tv_ka_ver_fmt, version, TvShellKeeper.uid())
    }

    private fun onShizukuAction() {
        val action = binding.tvKaShizuku.tvKaLayerAction
        if (action.text.toString() == getString(R.string.tv_ka_activate)) {
            openShizuku()
            return
        }
        // The other branch is the payoff: ask shell uid to bring the tunnel
        // back. One broadcast, and it cannot hang the UI.
        val delivered = TvShellKeeper.restoreTunnel()
        Toast.makeText(
            this,
            if (delivered) getString(R.string.tv_ka_recover_done)
            else getString(R.string.tv_ka_activate_failed),
            Toast.LENGTH_SHORT
        ).show()
        render()
    }

    /**
     * Resolve the launcher by package, not by activity class: the class name has
     * moved between Shizuku versions and a package filter survives that.
     * FLAG_ACTIVITY_NEW_TASK because a TV activity is not always the current
     * task owner when this is pressed.
     */
    private fun openShizuku() {
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(SHIZUKU_LAUNCHER_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch.resolveActivity(packageManager) == null) {
            Toast.makeText(this, getString(R.string.tv_ka_missing), Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(launch)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.tv_ka_activate_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun isShizukuLauncherInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(SHIZUKU_LAUNCHER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun hideAction(action: android.widget.TextView) {
        action.visibility = View.GONE
        action.isFocusable = false
    }

    private fun showAction(action: android.widget.TextView, labelRes: Int) {
        action.visibility = View.VISIBLE
        action.isFocusable = true
        action.setText(labelRes)
    }

    private fun render() {
        renderBoot()
        renderWatchdog()
        renderShizuku()
    }

    private companion object {
        const val TAG = "TvKeepAlive"
        const val SHIZUKU_LAUNCHER_PACKAGE = "moe.shizuku.launcher"
    }
}
