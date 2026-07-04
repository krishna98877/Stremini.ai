import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.android.stremini_ai"
    // UPDATED: 34 -> 36 (Required by image_picker)
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    defaultConfig {
        applicationId = "com.android.stremini_ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
        multiDexEnabled = true

        // Inject Composio consumer key from local.properties or env var
        val localProps = rootProject.file("local.properties")
        val props = Properties()
        if (localProps.exists()) props.load(localProps.inputStream())
        val composioKey = props.getProperty("composio.consumer.key")
            ?: System.getenv("COMPOSIO_CONSUMER_KEY")
            ?: ""
        buildConfigField("String", "COMPOSIO_CONSUMER_KEY", "\"$composioKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.json:json:20231013")
    implementation("androidx.core:core-ktx:1.13.1")
    // Play Core `com.google.android.play:core` is deprecated and blocked by Play Console.
    // Use API-specific modern artifacts to stay Android 14+ compatible.
    // feature-delivery is required by Flutter deferred components (SplitCompat/SplitInstall classes).
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}