import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// local.properties 用来注入「后端地址」这类**非机密**的构建期参数。
//
// 这里刻意不再注入任何云服务密钥：BuildConfig 的字符串常量是明文躺在 classes.dex 里的，
// 反编译就能读出来（旧包实测能直接搜到腾讯云主账号的 SecretId / SecretKey）。
// 需要凭证的能力一律由后端代理，App 侧不持有。
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

        // ---- 后端接口 ----
        // 默认指向 10.0.2.2:8000，也就是「模拟器里的宿主机」。真机调试时二选一：
        //   1) adb reverse tcp:8000 tcp:8000，然后把这里的地址改成 http://localhost:8000/
        //   2) 直接填局域网地址 http://192.168.x.x:8000/（需在 network_security_config 放行明文）
        // 两者都是**开发期**地址；生产环境填 https://<域名>/ 即可，无需改代码。
        //
        // API_ENABLED 是总闸：关掉它就彻底退回「完全离线」，一条网络请求都不发，
        // 用来快速判断某个问题是出在网络链路还是本地逻辑。
        buildConfigField("String", "API_BASE_URL",
            "\"" + (localProperties.getProperty("API_BASE_URL", "http://10.0.2.2:8000/") ?: "") + "\"")
        buildConfigField("boolean", "API_ENABLED",
            (localProperties.getProperty("API_ENABLED", "true") ?: "true"))

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

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
