package app.fjj.p2ptap.tv

import android.app.Application
import app.fjj.p2ptap.crash.CrashReporter

class TvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
