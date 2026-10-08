# ====================================================================
# P2PTap Release ProGuard / R8 Obfuscation & Keep Rules
# ====================================================================
#
# Principle: only keep what an *external* party addresses by name — the Go
# engine (gomobile/JNI), the Android framework (manifest), or third-party
# libraries that reflect. Everything under app.fjj.p2ptap is plain Kotlin with
# no reflection, no Gson/Moshi/kotlinx.serialization, no Parcelable, no
# addJavascriptInterface and no reflection-driven JSONObject(bean), so R8 may
# rename it freely. Keeping more than that only hands an attacker a readable
# map of the VPN's internals.

# 0. Obfuscation — flatten all package names and overload methods
# -repackageclasses '' implies -flattenpackagehierarchy ''
-repackageclasses ''
-overloadaggressively

# 0b. Aggressively optimize: suppress verbose log output in release builds.
# R8 eliminates calls to these Log methods, reducing both APK size and
# per-frame overhead in the VPN data path.  debug() and verbose() are
# stripped; info()/warn()/error() remain (they are cheap and useful).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

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

# 2. Keep Android VpnService, Services, Receivers, and Quick Settings Tiles
-keep public class * extends android.net.VpnService { *; }
-keep public class * extends android.app.Service { *; }
-keep public class * extends android.content.BroadcastReceiver { *; }
-keep public class * extends android.service.quicksettings.TileService { *; }

# 3. Keep ZXing Barcode & QR Code Scanner
-keep class com.google.zxing.Result { *; }
-keep class com.google.zxing.ResultPoint { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.BinaryBitmap { *; }
-keep class com.google.zxing.LuminanceSource { *; }
-keep class com.google.zxing.MultiFormatReader { *; }
-keep class com.google.zxing.RGBLuminanceSource { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# 4. Preserve Line Numbers & Attributes for Crash Stack Traces
# Only useful together with the mapping.txt release.yml now publishes; on its
# own this does not make a release stack trace readable.
-keepattributes Signature, InnerClasses, EnclosingMethod, Annotation, *Annotation*
-keepattributes SourceFile, LineNumberTable
