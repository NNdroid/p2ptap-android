# :tv ships only its own UI on top of :core. The Go engine's keep rules
# live in core/consumer-rules.pro and reach this module through the library's
# consumerProguardFiles; nothing here needs to duplicate them.

# 0. Obfuscation — flatten all package names and overload methods
# -repackageclasses '' implies -flattenpackagehierarchy ''
-repackageclasses ''
-overloadaggressively

# 0b. Aggressively optimize: suppress verbose log output in release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Leanback adapters and item holders are instantiated reflectively by the
# framework's own focus/grid machinery.
-keep class androidx.tv.widget.** { *; }

# TV activities subclass Activity, which R8 keeps on its own, but the view
# binding fields they reference must survive.
-keep class app.fjj.p2ptap.tv.databinding.** { *; }

# TvKeeperService is instantiated by the Shizuku server via
# Class.forName + getConstructor(Context.class). R8 has no caller it can see,
# and stripping either the class or that constructor degrades L3 to a silent
# no-op that only shows up after a box reboot. @Keep on the constructor is not
# enough on its own for a class this app never references by value, so the
# whole class is kept verbatim.
-keep class app.fjj.p2ptap.tv.keeper.TvKeeperService { *; }

# The AIDL-generated Stub keeps itself, but the client-side interface must
# survive too: TvShellKeeper holds a reference to ITvKeeperService.
-keep interface app.fjj.p2ptap.tv.keeper.ITvKeeperService { *; }

# TvKeepAliveState.WatchdogState is persisted as its name string and
# read back via valueOf(). If R8 renames an enum constant the persisted
# value stops parsing and silently falls back to STOPPED, which disables
# the process-death detection that L3 depends on.
-keepnames class app.fjj.p2ptap.tv.TvKeepAliveState$WatchdogState { *; }

-keepattributes SourceFile, LineNumberTable
