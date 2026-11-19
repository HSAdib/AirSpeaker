import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hasan.airspeaker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hasan.airspeaker.v2"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropFile = rootProject.file("keystore.properties")
            val props = Properties()
            if (keystorePropFile.exists()) {
                FileInputStream(keystorePropFile).use { props.load(it) }
            }

            val storePath = System.getenv("AIRSPEAKER_KEYSTORE_PATH")
                ?: System.getenv("PHONESPEAKER_KEYSTORE_PATH")
                ?: props.getProperty("storeFile")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("AIRSPEAKER_KEYSTORE_PASSWORD")
                    ?: System.getenv("PHONESPEAKER_KEYSTORE_PASSWORD")
                    ?: props.getProperty("storePassword")
                keyAlias = System.getenv("AIRSPEAKER_KEY_ALIAS")
                    ?: System.getenv("PHONESPEAKER_KEY_ALIAS")
                    ?: props.getProperty("keyAlias")
                keyPassword = System.getenv("AIRSPEAKER_KEY_PASSWORD")
                    ?: System.getenv("PHONESPEAKER_KEY_PASSWORD")
                    ?: props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSign = signingConfigs.getByName("release")
            if (releaseSign.storeFile != null && releaseSign.storeFile!!.exists()) {
                signingConfig = releaseSign
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        prefab = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.oboe:oboe:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

tasks.register("copyDebugApkToRoot") {
    doLast {
        val srcApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val destApkV21 = rootProject.projectDir.parentFile.resolve("AirSpeaker.v2.1.apk")
        if (srcApk.exists()) {
            srcApk.copyTo(destApkV21, overwrite = true)
            println("Copied ${srcApk.absolutePath} to ${destApkV21.absolutePath}")
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("copyDebugApkToRoot")
}

