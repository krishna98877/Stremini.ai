# Stremini AI — ProGuard Rules (release)

# ── Flutter framework ────────────────────────────────────────────
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }

# ── Flutter embedder (required for all Flutter apps) ─────────────
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.view.FlutterView { *; }
-keep class io.flutter.embedding.android.FlutterActivity { *; }
-keep class io.flutter.embedding.android.FlutterFragment { *; }

# ── Stremini components (referenced via XML / reflection) ────────
-keep class com.android.stremini_ai.** { *; }

# ── OkHttp ───────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── JSON ─────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── Android components ───────────────────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ── ML Kit (OCR) ────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── Plugin: url_launcher ─────────────────────────────────────────
-keep class io.flutter.plugins.urllauncher.** { *; }
-dontwarn io.flutter.plugins.urllauncher.**

# ── Plugin: file_picker ─────────────────────────────────────────
-keep class com.mr.flutter.plugin.filepicker.** { *; }
-dontwarn com.mr.flutter.plugin.filepicker.**

# ── Plugin: image_picker ────────────────────────────────────────
-keep class io.flutter.plugins.imagepicker.** { *; }
-dontwarn io.flutter.plugins.imagepicker.**

# ── Syncfusion PDF Viewer ──────────────────────────────────────
-keep class com.syncfusion.flutter.pdfviewer.** { *; }
-dontwarn com.syncfusion.flutter.pdfviewer.**
-keep class com.syncfusion.** { *; }
-dontwarn com.syncfusion.**

# ── Shared Preferences ─────────────────────────────────────────
-keep class io.flutter.plugins.sharedpreferences.** { *; }
-dontwarn io.flutter.plugins.sharedpreferences.**

# ── Native methods ───────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Preserve all Android framework callbacks ─────────────────────
-keepclasseswithmembers class * {
    public <init>(android.content.Context);
    public void on*(android.view.View);
}

# ── General keep ─────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ── Remove LogCat in release builds ──────────────────────────────
# Strips all Log.* calls except Log.w and Log.e (keep warnings/errors for crash analysis).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ── Security: prevent debug information leakage ──────────────────
# NOTE: -removeauxiliaryclassattributes was removed because R8 (the default
# shrinker in AGP 8.x) doesn't recognize this ProGuard option and fails with
# "R8: Unknown option '-removeauxiliaryclassattributes'". The equivalent
# behavior (stripping Auxiliary Class Attributes) is achieved via
# -keepattributes SourceFile,LineNumberTable above (which keeps only the
# minimum debug info needed for crash stack traces).

# ── Flutter Play Core (deferred components) ──────────────────────
# Flutter's PlayStoreDeferredComponentManager references com.google.android.play.core.*
# classes that aren't on the classpath when the app doesn't ship Play Core
# deferred components. Without these -dontwarn rules R8 fails with
# "Missing class" errors during :app:minifyReleaseWithR8.
-dontwarn com.google.android.play.core.splitcompat.**
-dontwarn com.google.android.play.core.splitinstall.**
-dontwarn com.google.android.play.core.tasks.**
-dontwarn com.google.android.play.core.listener.**
-keep class com.google.android.play.core.** { *; }