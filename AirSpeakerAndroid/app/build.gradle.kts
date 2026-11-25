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
        applicationId = "com.hasan.airspeaker"
        minSdk = 24
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
                val resolvedVersion = System.getenv("AIRSPEAKER_VERSION")
            ?.trim()
            ?.removePrefix("v")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("GITHUB_REF_NAME")
                ?.trim()
                ?.takeIf { it.startsWith("v") && it.length > 1 }
                ?.removePrefix("v")
            ?: "1.0.0"
        versionName = resolvedVersion

        buildConfigField("String", "BUILD_SHA", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "dev"}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
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
                ?: props.getProperty("storeFile")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("AIRSPEAKER_KEYSTORE_PASSWORD")
                    ?: props.getProperty("storePassword")
                keyAlias = System.getenv("AIRSPEAKER_KEY_ALIAS")
                    ?: props.getProperty("keyAlias")
                keyPassword = System.getenv("AIRSPEAKER_KEY_PASSWORD")
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
            } else {
                signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    val abiCodes = mapOf(
        "armeabi-v7a" to 1,
        "arm64-v8a" to 2,
        "x86" to 3,
        "x86_64" to 4
    )

    applicationVariants.all {
        val baseCode = versionCode
        outputs.forEach { output ->
            val apkOutput = output as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (apkOutput != null) {
                val abiFilter = apkOutput.getFilter(com.android.build.VariantOutput.FilterType.ABI)
                val offset = if (abiFilter != null) (abiCodes[abiFilter] ?: 0) else 0
                apkOutput.versionCodeOverride = (baseCode * 10) + offset
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.oboe:oboe:1.9.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

tasks.register("copyDebugApkToRoot") {
    doLast {
        val buildDir = layout.buildDirectory.asFile.get()
        val apkDir = File(buildDir, "outputs/apk/debug")
        val rootDir = rootProject.projectDir.parentFile
        val mapping = mapOf(
            "app-arm64-v8a-debug.apk" to "AirSpeaker-arm64-v8a.apk",
            "app-armeabi-v7a-debug.apk" to "AirSpeaker-armeabi-v7a.apk",
            "app-x86_64-debug.apk" to "AirSpeaker-x86_64.apk",
            "app-x86-debug.apk" to "AirSpeaker-x86.apk",
            "app-universal-debug.apk" to "AirSpeaker-universal.apk"
        )
        mapping.forEach { (srcName, dstName) ->
            val src = File(apkDir, srcName)
            if (src.exists()) {
                src.copyTo(rootDir.resolve(dstName), overwrite = true)
                println("Copied ${src.name} -> ${rootDir.resolve(dstName)}")
            }
        }
        val universal = File(apkDir, "app-universal-debug.apk").takeIf { it.exists() }
            ?: File(apkDir, "app-debug.apk")
        if (universal.exists()) {
            universal.copyTo(rootDir.resolve("AirSpeaker.apk"), overwrite = true)
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("copyDebugApkToRoot")
}

