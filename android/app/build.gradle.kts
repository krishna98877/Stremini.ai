import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.android.stremini_ai"
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

        // ── Secret injection ───────────────────────────────────────────
        // All API keys, auth_config_ids, and account IDs are injected at build
        // time from `local.properties` (or env vars). The repo itself NEVER
        // contains real secrets — it's open source.
        //
        // To build a working APK, copy `android/local.properties.example` to
        // `android/local.properties` and fill in your own keys. See
        // SECURITY.md for details.
        val localProps = rootProject.file("local.properties")
        val props = Properties()
        if (localProps.exists()) props.load(localProps.inputStream())

        // Helper: read from local.properties -> env var -> empty string.
        fun secret(propKey: String, envKey: String): String =
            props.getProperty(propKey)?.takeIf { it.isNotBlank() }
                ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
                ?: ""

        // Composio developer API key (required for any automation)
        val composioKey = secret("composio.consumer.key", "COMPOSIO_CONSUMER_KEY")
        buildConfigField("String", "COMPOSIO_CONSUMER_KEY", "\"$composioKey\"")

        // Groq API key (required for chat + keyboard AI)
        val groqKey = secret("groq.api.key", "GROQ_API_KEY")
        buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")

        // Composio auth_config_id per service. Each is tied to the developer's
        // Composio project — required for the OAuth connect flow to work.
        // Find them in the Composio dashboard: https://dashboard.composio.dev
        val authConfigGithub       = secret("auth.config.github",       "AUTH_CONFIG_GITHUB")
        val authConfigGmail        = secret("auth.config.gmail",        "AUTH_CONFIG_GMAIL")
        val authConfigInstagram    = secret("auth.config.instagram",    "AUTH_CONFIG_INSTAGRAM")
        val authConfigFacebook     = secret("auth.config.facebook",     "AUTH_CONFIG_FACEBOOK")
        val authConfigWhatsapp     = secret("auth.config.whatsapp",     "AUTH_CONFIG_WHATSAPP")
        val authConfigGoogledrive  = secret("auth.config.googledrive",  "AUTH_CONFIG_GOOGLEDRIVE")
        val authConfigDiscord      = secret("auth.config.discord",      "AUTH_CONFIG_DISCORD")
        val authConfigLinkedin     = secret("auth.config.linkedin",     "AUTH_CONFIG_LINKEDIN")
        val authConfigReddit       = secret("auth.config.reddit",       "AUTH_CONFIG_REDDIT")
        val authConfigGooglesheets = secret("auth.config.googlesheets", "AUTH_CONFIG_GOOGLESHEETS")
        val authConfigYoutube      = secret("auth.config.youtube",      "AUTH_CONFIG_YOUTUBE")
        buildConfigField("String", "AUTH_CONFIG_GITHUB",       "\"$authConfigGithub\"")
        buildConfigField("String", "AUTH_CONFIG_GMAIL",        "\"$authConfigGmail\"")
        buildConfigField("String", "AUTH_CONFIG_INSTAGRAM",    "\"$authConfigInstagram\"")
        buildConfigField("String", "AUTH_CONFIG_FACEBOOK",     "\"$authConfigFacebook\"")
        buildConfigField("String", "AUTH_CONFIG_WHATSAPP",     "\"$authConfigWhatsapp\"")
        buildConfigField("String", "AUTH_CONFIG_GOOGLEDRIVE",  "\"$authConfigGoogledrive\"")
        buildConfigField("String", "AUTH_CONFIG_DISCORD",      "\"$authConfigDiscord\"")
        buildConfigField("String", "AUTH_CONFIG_LINKEDIN",     "\"$authConfigLinkedin\"")
        buildConfigField("String", "AUTH_CONFIG_REDDIT",       "\"$authConfigReddit\"")
        buildConfigField("String", "AUTH_CONFIG_GOOGLESHEETS", "\"$authConfigGooglesheets\"")
        buildConfigField("String", "AUTH_CONFIG_YOUTUBE",      "\"$authConfigYoutube\"")

        // Connected account identifiers — only required if you've already
        // connected the developer's own WhatsApp Business number / Instagram
        // page and want the automation system to use them as defaults.
        val whatsappPhoneNumberId = secret("whatsapp.phone.number.id", "WHATSAPP_PHONE_NUMBER_ID")
        val instagramDefaultPsid  = secret("instagram.default.psid",   "INSTAGRAM_DEFAULT_PSID")
        buildConfigField("String", "WHATSAPP_PHONE_NUMBER_ID", "\"$whatsappPhoneNumberId\"")
        buildConfigField("String", "INSTAGRAM_DEFAULT_PSID",   "\"$instagramDefaultPsid\"")
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
            pickFirsts += "META-INF/proguard/androidx-*.pro"
            pickFirsts += "META-INF/*.kotlin_module"
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
}