plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.muntashirakon.bcl"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.muntashirakon.bcl"
        minSdk = 21
        targetSdk = 34
        versionCode = 27
        versionName = "1.1.0"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            resValue("string", "app_name", "BCL Debug")
            resValue("string", "app_short_name", "BCL Debug")
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Battery Charge Limiter")
            resValue("string", "app_short_name", "BCL")
        }
        create("fdroid") {
            applicationIdSuffix = ".fdroid"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Battery Charge Limiter")
            resValue("string", "app_short_name", "BCL")
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("dev_keystore.jks")
            storePassword = "kJCp!Bda#PBdN2RLK%yMK@hatq&69E"
            keyPassword = "kJCp!Bda#PBdN2RLK%yMK@hatq&69E"
            keyAlias = "key0"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.google.material)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.preference)
    implementation(libs.libsu.core)
    implementation(libs.google.gson)
    implementation(libs.kotlin.stdlib)
}
