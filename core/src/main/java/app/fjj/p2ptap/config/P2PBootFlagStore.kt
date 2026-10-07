package app.fjj.p2ptap.config

import android.content.Context

/**
 * Whether the VPN should be running when the device comes back up.
 *
 * Without this, a box that loses power during a VPN session comes back with the
 * tunnel dead and nobody to notice: the only recovery the project had was
 * START_STICKY plus a null-action intent re-delivery, which covers the service
 * being killed, not the whole device. That is exactly the case on a TV set,
 * where nobody is sitting in front of the screen to press CONNECT.
 *
 * The flag records the last state the user deliberately chose, so a reboot
 * restores intent rather than unconditionally turning the tunnel on: a set that
 * was disconnected before power loss stays disconnected after it.
 *
 * Lives in :core so both :mobile and :tv persist and read the same key. The
 * receivers that act on it are app-specific (only :tv declares one today).
 */
object P2PBootFlagStore {
    private const val PREFS = "p2ptap_boot"
    private const val KEY_AUTOSTART = "autostart"

    fun isAutostart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, false)

    fun setAutostart(context: Context, autostart: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTOSTART, autostart)
            .apply()
    }
}
