# Stremini AI — ProGuard Rules (release)

# ── Flutter ──────────────────────────────────────────────────────
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }

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

# ── ML Kit (OCR in MainActivity) ──────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── Native methods ───────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── General keep ─────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# ── Remove LogCat in release builds ──────────────────────────────
# Strips all Log.* calls except Log.w and Log.e (keep warnings/errors for crash analysis).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ── Security: prevent debug information leakage ──────────────────
-removeauxiliaryclassattributes