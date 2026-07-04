# Kemini APK Build Failures — Complete Fixture Reference

This document catalogs **all known build failure scenarios** and their root causes in the Kemini codebase, with reproduction steps and fixes.

---

## 🔴 **CRITICAL: Missing `local.properties`**

### Issue
```
ERROR: flutter.sdk not set in local.properties
```

### Root Cause
`android/settings.gradle.kts` (line 2-6) **requires** `local.properties` to exist:

```kotlin
pluginManagement {
    val flutterSdkPath = run {
        val properties = java.util.Properties()
        file("local.properties").inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk")
            ?: throw GradleException("flutter.sdk not set in local.properties")  // ← FAILS HERE
    }
    // ...
}
```

### Reproduction
```bash
cd krishna98877/Kemini
flutter clean
flutter build apk --release
# ERROR: flutter.sdk not set in local.properties
```

### Fix
```bash
# Create local.properties
cat > android/local.properties << 'EOF'
flutter.sdk=/path/to/flutter/sdk
sdk.dir=/path/to/android/sdk
composio.consumer.key=ck__YOUR_KEY_HERE  # Optional: required for Composio features
EOF

# Then rebuild
flutter pub get
flutter build apk --release
```

---

## 🔴 **CRITICAL: BuildConfig.COMPOSIO_CONSUMER_KEY Not Set**

### Issue
```
BuildConfig.COMPOSIO_CONSUMER_KEY is empty string ""
Runtime: Composio auth fails silently or returns 401
```

### Root Cause
`android/app/build.gradle.kts` (line 33-40) injects the key at build time:

```kotlin
defaultConfig {
    // ...
    val localProps = rootProject.file("local.properties")
    val props = Properties()
    if (localProps.exists()) props.load(localProps.inputStream())
    val composioKey = props.getProperty("composio.consumer.key")
        ?: System.getenv("COMPOSIO_CONSUMER_KEY")
        ?: ""  // ← FALLS BACK TO EMPTY STRING IF NOT PROVIDED
    buildConfigField("String", "COMPOSIO_CONSUMER_KEY", "\"$composioKey\"")
}
```

### Reproduction
```bash
# Build without setting the key
flutter build apk --release

# Then in app, Composio service fails:
# ComposioAuthActivity crash or 401 Unauthorized responses
```

### Fix
**Option A: Via `local.properties`**
```properties
composio.consumer.key=ck__your_actual_key_here
```

**Option B: Via environment variable**
```bash
export COMPOSIO_CONSUMER_KEY=ck__your_actual_key_here
flutter build apk --release
```

---

## 🔴 **ProGuard Minification: Missing Rules for Core Dependencies**

### Issue
```
Release APK crashes at runtime:
- NullPointerException in OkHttp interceptor
- JSON parsing fails (JSONObject not found)
- ML Kit OCR component stripped
- Riverpod state management broken
```

### Root Cause
`android/app/build.gradle.kts` enables minification (line 49-50):

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true      // ← Aggressive code stripping
        isShrinkResources = true    // ← Also strips unused resources
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

But `android/app/proguard-rules.pro` is **incomplete**. Missing rules for:
- Riverpod/StateNotifier reflection
- Flutter plugin internals
- URL Launcher plugin
- File Picker/Image Picker callbacks

### Reproduction
```bash
flutter build apk --release

# APK runs on emulator but crashes on real device with:
# E: java.lang.ClassNotFoundException: riverpod.StateNotifier
# E: java.lang.NoSuchMethodException: url_launcher callback
```

### Fix
**Update `android/app/proguard-rules.pro`:**

```proguard
# ── Riverpod (State Management) ──────────────────────────────────
-keep class riverpod.** { *; }
-keep class package:flutter_riverpod.** { *; }
-dontwarn riverpod.**
-keepclasseswithmembernames class * {
    public <methods> riverpod.AsyncValue;
}

# ── Plugin: url_launcher ─────────────────────────────────────────
-keep class io.flutter.plugins.urllauncher.** { *; }
-dontwarn io.flutter.plugins.urllauncher.**

# ── Plugin: file_picker ─────────────────────────────────────────
-keep class com.mr.flutter.plugin.filepicker.** { *; }
-dontwarn com.mr.flutter.plugin.filepicker.**

# ── Plugin: image_picker ────────────────────────────────────────
-keep class io.flutter.plugins.imagepicker.** { *; }
-dontwarn io.flutter.plugins.imagepicker.**

# ── Syncfusion PDF ──────────────────────────────────────────────
-keep class com.syncfusion.flutter.pdfviewer.** { *; }
-dontwarn com.syncfusion.flutter.pdfviewer.**

# ── Preserve Flutter embedder ────────────────────────────────────
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.view.FlutterView { *; }

# ── Keep all native methods ──────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Preserve all Android framework callbacks ─────────────────────
-keepclasseswithmembernames class * {
    public <init>(android.content.Context);
    public void on*(android.view.View);
}
```

---

## 🔴 **Dependency Conflict: image_picker^1.0.7 + compileSdk 36**

### Issue
```
FAILURE: Build failed with an exception.

* What went wrong:
  Execution failed for task ':app:mergeReleaseResources'.
  > Android resource linking failed
  > ERROR: resource integer (attr/max_aspect_ratio_portrait) not found in SDK
```

### Root Cause
`pubspec.yaml` (line 19) pins:
```yaml
image_picker: ^1.0.7
```

`android/app/build.gradle.kts` (line 26) targets SDK 36:
```kotlin
targetSdk = 36
```

**image_picker ^1.0.7 is incompatible with SDK 36.** It was designed for SDK 34-35 and doesn't include resources for newer SDK versions.

### Reproduction
```bash
flutter pub get
flutter build apk --release

# Build fails: resource attributes missing for Android 16 (API 36)
```

### Fix
**Update `pubspec.yaml` to latest image_picker:**

```yaml
dependencies:
  # was: image_picker: ^1.0.7
  image_picker: ^1.1.1  # Latest version with SDK 36 support
  file_picker: ^8.0.0
  # ... rest
```

Then:
```bash
flutter pub get
flutter clean
flutter build apk --release
```

---

## 🔴 **AGP Version Mismatch: 8.9.1 with Old Gradle Wrapper**

### Issue
```
FAILURE: Build failed with an exception.

* What went wrong:
  Plugin with id 'com.android.application' not found.
```

### Root Cause
`android/build.gradle.kts` (line 3) and `android/settings.gradle.kts` (line 21) specify:
```kotlin
id("com.android.application") version "8.9.1" apply false
```

But `android/gradle/wrapper/gradle-wrapper.properties` may reference **Gradle 7.x or 8.0**, which doesn't support AGP 8.9.1.

### Reproduction
```bash
# Check gradle version
cat android/gradle/wrapper/gradle-wrapper.properties

# If gradle version is < 8.5, build will fail
```

### Fix
**Update Gradle wrapper to 8.11.1 (minimum for AGP 8.9.1):**

```bash
cd android
./gradlew wrapper --gradle-version 8.11.1 --distribution-type all
cd ..
flutter clean
flutter build apk --release
```

---

## 🔴 **Resource Exclusion Conflict: META-INF Files**

### Issue
```
Duplicate files copied in APK META-INF/LICENSE.txt
Duplicate files copied in APK META-INF/NOTICE.txt
```

### Root Cause
`android/app/build.gradle.kts` (line 62-70) excludes files:
```kotlin
packaging {
    resources {
        excludes += "META-INF/DEPENDENCIES"
        excludes += "META-INF/LICENSE"
        excludes += "META-INF/LICENSE.txt"
        excludes += "META-INF/NOTICE"
        excludes += "META-INF/NOTICE.txt"
    }
}
```

But **OkHttp 4.11.0** and other dependencies still have conflicting metadata files. Gradle sometimes fails to cleanly exclude them during merge.

### Reproduction
```bash
flutter build apk --release

# May see warning or fail:
# [AGP] Duplicate files copied in APK META-INF/
```

### Fix
**Add pickFirst() to resolve conflicts:**

```gradle
// In android/app/build.gradle.kts, update packaging block:
packaging {
    resources {
        excludes += "META-INF/DEPENDENCIES"
        excludes += "META-INF/LICENSE"
        excludes += "META-INF/LICENSE.txt"
        excludes += "META-INF/NOTICE"
        excludes += "META-INF/NOTICE.txt"
        
        // For remaining conflicts, pick first
        pickFirsts += "META-INF/proguard/androidx-*.pro"
    }
}
```

---

## 🔴 **Kotlin Compiler: Version Mismatch (2.1.0 + Old Dependencies)**

### Issue
```
ERROR: Kotlin compiler version 2.1.0 is not supported.
The supported versions are 1.8.x, 1.9.x, 2.0.x

Caused by: org.jetbrains.kotlin.utils.KotlinCompilerVersion
```

### Root Cause
`android/build.gradle.kts` (line 4) specifies:
```kotlin
id("org.jetbrains.kotlin.android") version "2.1.0" apply false
```

But some transitive dependencies (e.g., older versions of ML Kit, AndroidX) were compiled against Kotlin 2.0.x and fail with 2.1.0.

### Reproduction
```bash
flutter build apk --release

# Gradle fails: Kotlin 2.1.0 not supported by dependency X
```

### Fix
**Option A: Downgrade to Kotlin 2.0.x**
```gradle
// In android/build.gradle.kts
id("org.jetbrains.kotlin.android") version "2.0.20" apply false
```

**Option B: Update all dependencies to support Kotlin 2.1.0**
```gradle
// In android/app/build.gradle.kts
dependencies {
    implementation("com.google.mlkit:text-recognition:16.1.0")  // Check latest
    implementation("androidx.appcompat:appcompat:1.7.0")         // Update
}
```

---

## 🔴 **Java Version Mismatch: compileSdk 36 + Java 8**

### Issue
```
ERROR: Incompatible Java version. You are using Java 8, but compileSdk 36 requires Java 11+
Cause: UnsupportedClassVersionError
```

### Root Cause
`android/app/build.gradle.kts` (line 15-16) declares:
```kotlin
compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
```

But system `java` command is Java 8 or earlier:
```bash
$ java -version
openjdk version "1.8.0_292"  # ← PROBLEM
```

### Reproduction
```bash
which java
java -version  # If shows "1.8", "1.7", or "1.6", build will fail
flutter build apk --release
```

### Fix
**Install Java 11+:**

```bash
# macOS (homebrew)
brew install java11
# or use OpenJDK via sdkman
sdk install java 11.0.19-librca

# Linux (Ubuntu/Debian)
sudo apt-get install openjdk-11-jdk

# Windows
# Download from: https://adoptopenjdk.net/ (select Java 11 LTS)

# Verify
java -version  # Should show "11.x.x" or later
```

---

## 🔴 **Flutter SDK Not Installed or Misconfigured**

### Issue
```
ERROR: No Flutter SDK found at /path/to/flutter/sdk
Cause: GradleException in settings.gradle.kts
```

### Root Cause
`local.properties` specifies an incorrect or non-existent Flutter SDK path:
```properties
flutter.sdk=/invalid/flutter/path
```

### Reproduction
```bash
echo "flutter.sdk=/nonexistent/path" > android/local.properties
flutter build apk --release
# ERROR: No Flutter SDK found
```

### Fix
**Find correct Flutter SDK path:**
```bash
which flutter
# or
flutter doctor -v | grep "Flutter"

# Get the full path
echo $FLUTTER_HOME
# or on non-bash shells
which flutter | xargs dirname | xargs dirname

# Update local.properties
echo "flutter.sdk=$(which flutter | xargs dirname | xargs dirname)" >> android/local.properties
```

---

## 🟡 **WARNING: Compose Restrictions on Android 14+**

### Issue
```
WARNING: App uses deprecated WindowManager methods
Target SDK 36 (Android 16) enforces new API restrictions
ComposioAuthActivity WebView may not display correctly
```

### Root Cause
`android/app/src/main/kotlin/com/android/stremini_ai/ComposioAuthActivity.kt` uses deprecated WebView APIs for Android 16.

### Fix (Non-Critical)
Update WebView usage in ComposioAuthActivity to use modern APIs:
```kotlin
// Before:
webView.loadUrl(url)

// After (SDK 36+ safe):
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    webView.loadUrl(url, null)
} else {
    webView.loadUrl(url)
}
```

---

## 🟡 **Network Security Config: cleartext Traffic Disabled**

### Issue
```
ERROR: java.net.UnknownHostException when connecting to http:// (non-https) backend
Cause: Network security policy blocks cleartext
```

### Root Cause
`android/app/src/main/AndroidManifest.xml` (line 43-44):
```xml
android:usesCleartextTraffic="false"
android:networkSecurityConfig="@xml/network_security_config"
```

If `res/xml/network_security_config.xml` doesn't whitelist your backend or uses `http://`, connection fails.

### Fix
**Verify `res/xml/network_security_config.xml` exists and whitelists backends:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
        <!-- Only for development; use HTTPS in production -->
    </domain-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">api.groq.com</domain>
        <domain includeSubdomains="true">backend.composio.dev</domain>
    </domain-config>
</network-security-config>
```

---

## 📋 **Build Checklist Before Calling `flutter build apk`**

```bash
# 1. Verify environment
java -version              # Must be Java 11+
flutter --version
which flutter

# 2. Set up local.properties
cat > android/local.properties << 'EOF'
flutter.sdk=$(which flutter | xargs dirname | xargs dirname)
sdk.dir=$ANDROID_SDK_ROOT  # or /path/to/Android/sdk
composio.consumer.key=ck__YOUR_KEY_HERE  # Optional but recommended
EOF

# 3. Update dependencies
flutter pub get
flutter pub upgrade

# 4. Verify Gradle
cd android
./gradlew --version  # Should be 8.11.1+
cd ..

# 5. Clean build
flutter clean
rm -rf android/build

# 6. Run debug build first
flutter build apk --debug -v

# 7. If debug passes, try release
flutter build apk --release -v
```

---

## 🔍 **Debugging Build Failures**

### See full error output:
```bash
flutter build apk --release -v
```

### Clean gradle cache (nuclear option):
```bash
flutter clean
rm -rf android/build android/.gradle ~/.gradle
flutter pub get
cd android && ./gradlew clean && cd ..
flutter build apk --release
```

### Check for Kotlin/AGP compatibility:
```bash
cat android/build.gradle.kts | grep -E "version|id\("
cat android/gradle/wrapper/gradle-wrapper.properties
java -version
```

### Test compilation without minification:
```bash
# Edit android/app/build.gradle.kts:
# release { isMinifyEnabled = false }  # Temporary for debugging

flutter build apk --release

# If this works, ProGuard is the issue
```

---

## 📝 **Summary**

| Issue | Severity | Fix Time | Fixture |
|-------|----------|----------|---------|
| Missing `local.properties` | 🔴 CRITICAL | < 1 min | Create file with Flutter SDK path |
| Missing `COMPOSIO_CONSUMER_KEY` | 🔴 CRITICAL | < 1 min | Set env var or update `local.properties` |
| ProGuard incomplete rules | 🔴 CRITICAL | ~10 min | Add Riverpod + plugin rules to proguard-rules.pro |
| image_picker^1.0.7 + SDK 36 | 🔴 CRITICAL | ~5 min | Update to image_picker: ^1.1.1 |
| AGP 8.9.1 + old Gradle | 🔴 CRITICAL | ~3 min | Update Gradle wrapper to 8.11.1 |
| Kotlin 2.1.0 incompatibility | 🔴 CRITICAL | ~5 min | Downgrade to 2.0.20 or update deps |
| Java 8 installed | 🔴 CRITICAL | ~15 min | Install Java 11+ |
| Invalid Flutter SDK path | 🔴 CRITICAL | < 1 min | Fix `local.properties` path |
| META-INF conflicts | 🟡 WARNING | ~2 min | Add `pickFirsts` to packaging block |
| Deprecated WebView API | 🟡 WARNING | ~10 min | Update ComposioAuthActivity |
| Network cleartext disabled | 🟡 WARNING | ~5 min | Verify network_security_config.xml |

