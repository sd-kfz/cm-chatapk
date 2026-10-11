plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}




android {
    namespace = "org.pocketcalc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.pocketcalc.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
