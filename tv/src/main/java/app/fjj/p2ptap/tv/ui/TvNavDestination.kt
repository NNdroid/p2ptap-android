package app.fjj.p2ptap.tv.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.fjj.p2ptap.tv.R

/**
 * One entry in the left navigation rail.
 *
 * Each item is a destination in the navigation graph, identified by its ID
 * (which is the same as the fragment's R.id), plus a label and an icon that
 * render inside a MaterialButton-shaped rail item.
 *
 * The rail is ordered top-to-bottom in the order of user importance: Home
 * first (90% of a session is on this screen), Peers next (the interesting
 * thing to look at when connected), then the operational screens. This is
 * not a menu — it is a home surface with a rail.
 */
data class TvNavDestination(
    val id: Int,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    companion object {
        val all: List<TvNavDestination> = listOf(
            TvNavDestination(R.id.tvHomeFragment, R.string.tv_screen_home, R.drawable.ic_tv_power),
            TvNavDestination(R.id.tvPeersFragment, R.string.tv_screen_peers, R.drawable.ic_tv_peers),
            TvNavDestination(R.id.tvTransferFragment, R.string.tv_screen_transfer, R.drawable.ic_tv_transfer),
            TvNavDestination(R.id.tvSettingsFragment, R.string.tv_screen_settings, R.drawable.ic_tv_settings),
            TvNavDestination(R.id.tvDiagnosticsFragment, R.string.tv_screen_diagnostics, R.drawable.ic_tv_diagnostics),
            TvNavDestination(R.id.tvKeepAliveFragment, R.string.tv_screen_keepalive, R.drawable.ic_tv_keepalive),
            TvNavDestination(R.id.tvAboutFragment, R.string.tv_screen_about, R.drawable.ic_tv_about),
        )
    }
}
