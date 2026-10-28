plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.halone.plugin.android"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "dev.halone.plugin.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
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
