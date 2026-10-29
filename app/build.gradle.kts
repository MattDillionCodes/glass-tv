import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.mattdillioncodes.glass"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mattdillioncodes.glass"
        minSdk = 28 // Android 9 (Android TV / Google TV boxes and sets from ~2018 on)
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    // The release key lives outside the repo (~/.android-keys). Every public APK must be signed
    // with it, or it won't install over the previous version. Without the file (anyone else
    // building from source), releases fall back to the debug key.
    val releaseKey = file("${System.getProperty("user.home")}/.android-keys/glass-release.properties")
    signingConfigs {
        if (releaseKey.exists()) create("release") {
            val p = Properties().apply { releaseKey.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        // What goes on the TV: R8-shrunk and optimised.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
