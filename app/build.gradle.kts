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




        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }







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



            if (hasKeystore) signingConfig = signingConfigs.getByName("stable")
            buildConfigField("boolean", "ALLOW_SHOTS", "false")
        }
        release {



            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("stable")
            buildConfigField("boolean", "ALLOW_SHOTS", "false")
        }




        create("debugShots") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "ALLOW_SHOTS", "true")
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

        buildConfig = true
    }
}




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


    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.19.1@aar")


    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")



    implementation("info.guardianproject:tor-android:0.4.9.5")
    implementation("info.guardianproject:jtorctl:0.4.5.7")




    implementation("com.netzarchitekten:IPtProxy:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")


    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")



    testImplementation("junit:junit:4.13.2")
    testImplementation("com.goterl:lazysodium-java:5.2.0")
    testImplementation("net.java.dev.jna:jna:5.19.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
