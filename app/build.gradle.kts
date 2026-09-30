import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 腾讯云语音合成的密钥从 local.properties 读，不进版本库（见 .gitignore）。
// 没填时 BuildConfig 里是空串，引擎按「未配置」处理，离线朗读照常。
val localProperties = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.poetry"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.poetry"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 腾讯云密钥：空串 = 未配置，QCloudTts 自动降级为不可用
        buildConfigField("String", "QCLOUD_APP_ID",
            "\"" + (localProperties.getProperty("QCLOUD_APP_ID", "") ?: "") + "\"")
        buildConfigField("String", "QCLOUD_SECRET_ID",
            "\"" + (localProperties.getProperty("QCLOUD_SECRET_ID", "") ?: "") + "\"")
        buildConfigField("String", "QCLOUD_SECRET_KEY",
            "\"" + (localProperties.getProperty("QCLOUD_SECRET_KEY", "") ?: "") + "\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx ships four ABIs (~90 MB). Only the two ARM ABIs are
        // packaged: arm64 for current devices, armeabi-v7a for 32-bit ones.
        // The x86/x86_64 emulator libs are dropped to keep the APK smaller.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            // Android 10+ 不许 dlopen 应用数据目录里的 .so，所以 native 库只能随 APK
            // 分发，不能像模型那样按需下载。压缩存放（useLegacyPackaging）让下载小
            // 约 19 MB，代价是装机时解压、占用大一些。
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Offline neural TTS（sherpa-onnx + VITS，见 media/SherpaTts.java）。
    // aar 是 Kotlin 编译的，所以即使本模块是 Java 也要带上 Kotlin 运行时。
    // 两个都按 .gitignore 不入库，用 tools/fetch-tts-deps.sh 取。
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation(files("libs/libqcloudtts-release.aar"))

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
