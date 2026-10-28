plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Releases are versioned by their tag: -Phalone.version=0.2.0. 1.2.3 becomes versionCode 10203.
val releaseVersion = findProperty("halone.version") as String? ?: "0.1.0"
val (major, minor, patch) = releaseVersion.substringBefore("-").split(".").map(String::toInt)

android {
    namespace = "dev.halone.plugin.android"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "dev.halone.plugin.android"
        minSdk = 29
        targetSdk = 36
        versionCode = major * 10000 + minor * 100 + patch
        versionName = releaseVersion
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    val keystore = findProperty("haloneKeystore") as String?
    val releaseSigning = keystore?.let {
        signingConfigs.create("release") {
            storeFile = file(it)
            storePassword = findProperty("haloneKeystorePassword") as String?
            keyAlias = findProperty("haloneKeyAlias") as String?
            keyPassword = findProperty("haloneKeyPassword") as String?
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
}

dependencies {
    implementation(libs.halone.plugin.sdk)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
}
