# ====================================================================
# P2PTap :core consumer rules — merged into every app that depends on
# this module.
#
# This holds only what :core addresses by name across a reflection or
# JNI boundary. App-level rules (ZXing, the tile, UI keep rules) live
# in each app's own proguard-rules.pro.
# ====================================================================

# 1. Keep Go Native Engine (P2PTap JNI & Gomobile Bindings)
# Critical: the Go C-shared library calls into Java by exact class/method
# signature, so a renamed member is not a build error — it is a silent runtime
# failure inside the engine's callback path.
-keep class com.p2ptap.P2PTap.** { *; }
-keep interface com.p2ptap.P2PTap.** { *; }
-keep class go.** { *; }

# P2PTapVpnService implements Protector/StateListener/InterfaceProvider itself
# and also hands the engine anonymous StateListener and ConfigStore instances
# (P2PTapVpnService.kt:213 and :229). ConfigStore was missing from this list:
# the anonymous class is named P2PTapVpnService$N, so the "implements" match
# below is the only thing protecting it.
-keep class * implements com.p2ptap.P2PTap.Protector { *; }
-keep class * implements com.p2ptap.P2PTap.StateListener { *; }
-keep class * implements com.p2ptap.P2PTap.InterfaceProvider { *; }
-keep class * implements com.p2ptap.P2PTap.ConfigStore { *; }

# Keep native methods in all classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. Keep the VPN service: the manifest starts it, and the native node holds a
# reference into it for the duration of a session.
-keep public class * extends android.net.VpnService { *; }

# 3. Preserve Line Numbers & Attributes for Crash Stack Traces
# Only useful together with the mapping.txt release.yml now publishes; on its
# own this does not make a release stack trace readable.
-keepattributes Signature, InnerClasses, EnclosingMethod, Annotation, *Annotation*
-keepattributes SourceFile, LineNumberTable
