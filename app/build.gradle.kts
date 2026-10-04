plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "org.cmchat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.cmchat.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // Ship only the ABIs we support. tor-android and IPtProxy both also
        // carry a 32-bit x86 lib we don't need (no real 32-bit x86 phones);
        // dropping it trims the APK and matches our supported-device list.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Stable-key signing: active only when CI (or a local build) sets
    // CMCHAT_KEYSTORE to an existing keystore path. Nothing secret is committed.
    // Used for BOTH debug and release so the published debug APKs carry a STABLE
    // signature across CI runs — they install/upgrade in place (same signer as
    // earlier release builds), fixing "App not installed". Without the key, debug
    // falls back to Android's auto debug key and release is unsigned.
    val ksPath = System.getenv("CMCHAT_KEYSTORE")
    val hasKeystore = ksPath != null && file(ksPath).exists()
    signingConfigs {
        if (hasKeystore) {
            create("stable") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("CMCHAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CMCHAT_KEY_ALIAS")
                keyPassword = System.getenv("CMCHAT_KEY_PASSWORD")
            }
        }
    }

    // Per-ABI APK splits: one lean APK per architecture instead of one fat
    // universal APK. Each split still bundles that ABI's native libs — libtor.so
    // AND libgojni.so (the obfs4/snowflake pluggable transports) — so bridges
    // keep working in every split. A universal APK is also produced as a
    // fallback. x86_64 is built (emulator testing) but not published.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            // Stable signer (when configured) so published debug APKs upgrade in
            // place. FLAG_SECURE is disabled in debug (see MainActivity) so these
            // test builds can be screenshotted.
            if (hasKeystore) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Needed so BuildConfig.DEBUG can gate the debug-only self-attack harness.
        buildConfig = true
    }
}

// A transitive dependency (tor-android) pulls kotlin-stdlib 2.3.0, whose
// metadata our Kotlin 2.1.0 compiler can't read. Pin stdlib to 2.1.0; its
// public API is a subset so tor-android still links and runs.
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.0")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Crypto: libsodium via lazysodium (Argon2id + secretbox). No hand-rolled crypto.
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // Vault serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Tor: Guardian Project tor-android (bundles the tor binary) + jtorctl
    // control-port library. All networking goes through Tor.
    implementation("info.guardianproject:tor-android:0.4.9.5")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    // Pluggable transports (bridges): IPtProxy bundles lyrebird/obfs4proxy +
    // snowflake as in-process Go clients for all ABIs. Used to hide that Tor is
    // in use from a network observer. ~35 MB AAR (native libs per ABI).
    implementation("com.netzarchitekten:IPtProxy:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QR generate + scan for exchanging CM-IDs.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Unit tests run on the host JVM; lazysodium-java bundles a desktop
    // libsodium so the same CryptoManager/Vault code is testable in CI.
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.goterl:lazysodium-java:5.2.0")
    testImplementation("net.java.dev.jna:jna:5.19.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
