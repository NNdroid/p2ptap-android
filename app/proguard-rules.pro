# ====================================================================
# P2PTap Release ProGuard / R8 Obfuscation & Keep Rules
# ====================================================================

# 1. Keep Go Native Engine (P2PTap JNI & Gomobile Bindings)
# Critical: Go C-shared library calls JNI methods by exact class/method signature.
-keep class com.p2ptap.P2PTap.** { *; }
-keep interface com.p2ptap.P2PTap.** { *; }

# Keep all classes implementing P2PTap JNI interfaces
-keep class * implements com.p2ptap.P2PTap.Protector { *; }
-keep class * implements com.p2ptap.P2PTap.StateListener { *; }
-keep class * implements com.p2ptap.P2PTap.InterfaceProvider { *; }

# Keep native methods in all classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. Keep Android VpnService, Services, Receivers, and Quick Settings Tiles
-keep public class * extends android.net.VpnService { *; }
-keep public class * extends android.app.Service { *; }
-keep public class * extends android.content.BroadcastReceiver { *; }
-keep public class * extends android.service.quicksettings.TileService { *; }

# 3. Keep P2PTap Configuration & State Models (Serialized via JSONObject)
-keep class app.fjj.p2ptap.config.P2PConfig { *; }
-keep class app.fjj.p2ptap.service.NodeMetrics { *; }
-keep class app.fjj.p2ptap.service.P2PStateRepository { *; }

# 4. Keep View Binding Classes
-keep class app.fjj.p2ptap.databinding.** { *; }

# 5. Keep ZXing Barcode & QR Code Scanner
-keep class com.google.zxing.Result { *; }
-keep class com.google.zxing.ResultPoint { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.BinaryBitmap { *; }
-keep class com.google.zxing.LuminanceSource { *; }
-keep class com.google.zxing.MultiFormatReader { *; }
-keep class com.google.zxing.RGBLuminanceSource { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# 6. Preserve Line Numbers & Attributes for Crash Stack Traces
-keepattributes Signature, InnerClasses, EnclosingMethod, Annotation, *Annotation*
-keepattributes SourceFile, LineNumberTable
