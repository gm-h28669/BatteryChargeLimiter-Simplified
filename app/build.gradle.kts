plugins {
    id("com.android.application")
    id("kotlin-android")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val kotlin_version: String by rootProject.extra
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlin_version")
}
