package app.fjj.p2ptap.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.fjj.p2ptap.MainActivity

class P2PTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (P2PTapVpnService.isRunning()) {
            val stopIntent = Intent(this, P2PTapVpnService::class.java).apply {
                action = P2PTapVpnService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        activityIntent,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                    startActivityAndCollapse(activityIntent)
                }
            } else {
                val startIntent = Intent(this, P2PTapVpnService::class.java).apply {
                    action = P2PTapVpnService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val running = P2PTapVpnService.isRunning()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(app.fjj.p2ptap.R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) getString(app.fjj.p2ptap.R.string.status_connected) else getString(app.fjj.p2ptap.R.string.status_disconnected)
        }
        tile.updateTile()
    }
}
