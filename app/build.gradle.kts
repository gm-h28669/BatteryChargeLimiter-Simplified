plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.muntashirakon.bcl"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.muntashirakon.bcl"
        minSdk = 21
        targetSdk = 34
        versionCode = 34
        versionName = "1.4.2"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("dev_keystore.jks")
            storePassword = "kJCp!Bda#PBdN2RLK%yMK@hatq&69E"
            keyPassword = "kJCp!Bda#PBdN2RLK%yMK@hatq&69E"
            keyAlias = "key0"
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            resValue("string", "app_name", "Battery Charge Limiter")
            resValue("string", "app_short_name", "BCL")
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Battery Charge Limiter")
            resValue("string", "app_short_name", "BCL")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("fdroid") {
            applicationIdSuffix = ".fdroid"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Battery Charge Limiter")
            resValue("string", "app_short_name", "BCL")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    // rename apk files to follow the convention: battery-charge-limiter-$variantName.$versionName.apk
    onVariants { variant ->
        val variantName = variant.name
        val versionName = android.defaultConfig.versionName
        val assembleTaskName = "assemble${variantName.replaceFirstChar { it.uppercaseChar() }}"

        val renameTask =
            project.tasks.register("copyAndRenameApk${variantName.replaceFirstChar { it.uppercaseChar() }}") {
                dependsOn(assembleTaskName)
                doLast {
                    val apkDir = layout.buildDirectory.get().asFile.resolve("outputs/apk")
                    project.fileTree(apkDir).matching {
                        include("**/$variantName/*.apk")
                        exclude("**/battery-charge-limiter-*.apk")
                    }.files.forEach { apk ->
                        val dest = apk.parentFile.resolve("battery-charge-limiter-$variantName.$versionName.apk")
                        apk.copyTo(dest, overwrite = true)
                        println("Created APK file: $dest")
                    }
                }
            }

        // Hook into the assemble task so this runs automatically
        project.tasks.matching { it.name == assembleTaskName }.configureEach {
            finalizedBy(renameTask)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.google.material)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.ktx)
    implementation(libs.libsu.core)
    implementation(libs.google.gson)
    implementation(libs.kotlin.stdlib)
}
