import java.util.Properties

// 对应 dart_simple_live/simple_live_app —— Android 原生应用。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlin.kapt")
    // Kotlin 官方 Compose 编译器插件（版本由版本目录声明，与 Kotlin 同版本）
    alias(libs.plugins.kotlin.compose)
}

// 发布签名材料读取自 local.properties（已 gitignore，不入库）；缺失时 release 退化为未签名构建。
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile = signingProps.getProperty("storeFile", "").takeIf { it.isNotBlank() }

android {
    namespace = "com.kotlin_livez.app"
    // compileSdk 35（Android 15）：配合 targetSdk 35；AGP 8.13.2 支持
    compileSdk = 35

    buildFeatures {
        buildConfig = true
        // 启用 Jetpack Compose（配合 kotlin.plugin.compose 编译器插件）
        compose = true
    }

    defaultConfig {
        applicationId = "com.kotlin_livez.app"
        // minSdk 26（Android 8.0+）：libmpv（dev.jdtech.mpv:libmpv）AAR 强制要求；
        // mpv 播放内核在旧系统上不可用。
        minSdk = 26
        // targetSdk 35（Android 15）：强制 edge-to-edge（本 app 已全局 e2e，无行为回归预期）；
        // 系统栏 setter（statusBarColor/navigationBarColor）在 35 弃用，已迁移 enableEdgeToEdge()。
        targetSdk = 35
        versionCode = 22
        versionName = "0.2.4.261025"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingProps.getProperty("storePassword", "")
                keyAlias = signingProps.getProperty("keyAlias", "")
                keyPassword = signingProps.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            // 共存：debug 包名加后缀（com.kotlin_livez.app.debug），与 release 并存；
            // Room/DataStore 按包名沙箱隔离，两变体数据互不影响。
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 自定义 APK 输出文件名（仅影响构建产物文件名，不影响包名/版本/签名）：
    //   release → Livez-<version>-arm64-v8a.apk
    //   debug   → Livez_D-<version>-arm64-v8a.apk
    // 未配置签名时 release 追加 -unsigned，避免未签名包与正式包同名。
    val apkVersion = defaultConfig.versionName ?: "dev"
    val hasReleaseSigning = signingConfigs.findByName("release") != null
    applicationVariants.all {
        val isDebug = buildType.name == "debug"
        val unsigned = name == "release" && !hasReleaseSigning
        outputs.all {
            val prefix = if (isDebug) "Livez_D" else "Livez"
            val suffix = if (unsigned) "-unsigned" else ""
            @Suppress("DEPRECATION")
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "$prefix-$apkVersion-arm64-v8a$suffix.apk"
        }
    }

    // 仅打包 arm64-v8a（测试设备为 arm64 真机；减少包体）
    packaging {
        jniLibs {
            excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**")
        }
    }
    ndkVersion = "27.0.12077973"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":core"))

    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    // appcompat / material：无 Kotlin 代码直接引用，但 res/values/styles.xml 的
    // Theme.LiveApp parent 为 Theme.MaterialComponents.DayNight.NoActionBar（Manifest
    // android:theme 在用），主题层级运行时需要这两个库 —— Batch 8 评估后保守保留。
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // constraintlayout：全仓零引用（Batch 8 grep 证据），删除。

    // 生命周期 / ViewModel（替代 GetX 状态）
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // 持久化：Room（关系数据 follow/history/tag ↔ Hive）+ DataStore（键值设置）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // 协程 / 序列化
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 图片加载：Coil（对应 Dart extended_image / NetImage）
    implementation(libs.coil)

    // ===== Jetpack Compose（UI 迁移目标栈，对应 Dart Flutter widget 体系）=====
    // BOM 统一管理 Compose 各库版本
    // BOM 2025.06.00 = Compose 1.8.2（task 09-12-predictive-back：预测性手势返回与
    // 共享元素转场的随手势回放需要 1.8；经阿里云 google 镜像分发，直连 google() 不可达）
    implementation(platform(libs.androidx.compose.bom))
    // Material3 组件库（对应 Dart MaterialApp/Material 组件）
    implementation(libs.androidx.compose.material3)
    // foundation 基础能力（LazyVerticalGrid / 手势等，对应 Dart GridView/Listener）
    implementation(libs.androidx.compose.foundation)
    // Compose UI 核心（对应 Dart widget 渲染层）
    implementation(libs.androidx.compose.ui)
    // Activity 的 Compose 宿主入口 setContent（替代原 Activity + View 树）
    implementation(libs.androidx.activity.compose)
    // 单 Activity 导航（对应 Dart GetX 路由）
    implementation(libs.androidx.navigation.compose)
    // Coil 的 Compose 封装 AsyncImage（对应 Dart NetImage/extended_image）
    implementation(libs.coil.compose)
    // collectAsStateWithLifecycle：StateFlow 生命周期安全订阅（对应 Dart Rx Obx）
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 下拉刷新：Compose 侧用 material3 PullToRefreshBox，swiperefreshlayout 全仓零引用
    //（仅注释提及），Batch 8 删除。

    // 播放内核：libmpv（Jellyfin 维护的 Android 构建，mvp-android 同源 JNI API）
    // holdallsn 对应 Dart media_kit(mpv) 内核；本 AAR 自带 ffmpeg+mpv so（arm64-v8a）。
    implementation(libs.libmpv)

    // 局域网同步：轻量 HTTP 服务端（对应 Dart 端 shelf + shelf_router，Android 无 com.sun.net.httpserver）
    implementation(libs.nanohttpd)

    // 二维码编码：扫码登录页把二维码内容渲染成图（对应 Dart qr_flutter 的 QrImageView）
    implementation(libs.zxing.core)

    // 二维码扫描（task 10-24-lan-sync-ui）：局域网同步「扫一扫」，自带 CaptureActivity +
    // ActivityResult ScanContract（对应 Dart qr_code_scanner_plus）
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
