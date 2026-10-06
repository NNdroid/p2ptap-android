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
        when (P2PTapVpnService.currentState) {
            P2PTapVpnService.STATE_STARTING,
            P2PTapVpnService.STATE_STOPPING -> {
                // A lifecycle transition is already in flight. Falling through
                // here used to call VpnService.prepare()/ACTION_START and re-arm
                // the VPN in the middle of a stop, so a user clicking "turn it
                // off" could never actually get it off.
            }
            P2PTapVpnService.STATE_RUNNING -> {
                val stopIntent = Intent(this, P2PTapVpnService::class.java).apply {
                    action = P2PTapVpnService.ACTION_STOP
                }
                startService(stopIntent)
            }
            else -> {
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
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val state = P2PTapVpnService.currentState
        val running = P2PTapVpnService.isRunning()
        // Keep the tile "on" through STARTING/STOPPING: the VPN is either about
        // to be or still is established, and showing it as off invites a restart
        // click while teardown is still releasing the tunnel.
        val active = running || state == P2PTapVpnService.STATE_STARTING || state == P2PTapVpnService.STATE_STOPPING
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(app.fjj.p2ptap.R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val subtitleRes = when (state) {
                P2PTapVpnService.STATE_STARTING -> app.fjj.p2ptap.R.string.status_connecting
                P2PTapVpnService.STATE_STOPPING -> app.fjj.p2ptap.R.string.status_stopping
                else -> if (running) app.fjj.p2ptap.R.string.status_connected else app.fjj.p2ptap.R.string.status_disconnected
            }
            tile.subtitle = getString(subtitleRes)
        }
        tile.updateTile()
    }
}
