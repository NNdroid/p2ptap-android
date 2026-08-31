package app.fjj.p2ptap

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import app.fjj.p2ptap.config.AppConfigManager
import kotlin.concurrent.thread

class P2PTapApp : Application() {

    companion object {
        private const val PREFS_NAME = "p2ptap_ui_prefs"
        private const val KEY_NIGHT_MODE = "night_mode"
    }

    override fun onCreate() {
        super.onCreate()
        initNightMode()
        prewarmConfigCache()
    }

    private fun initNightMode() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun prewarmConfigCache() {
        thread(name = "P2PTap-PrewarmThread") {
            try {
                AppConfigManager.load(this)
            } catch (_: Exception) {}
        }
    }
}
