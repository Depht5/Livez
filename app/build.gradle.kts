import java.util.Properties

// 对应 dart_simple_live/simple_live_app —— Android 原生应用。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    // Kotlin 官方 Compose 编译器插件（版本由根构建脚本声明，与 Kotlin 2.0.21 同版本）
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 7
        versionName = "0.1.6"
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
    implementation("androidx.core:core-ktx:1.13.1")
    // appcompat / material：无 Kotlin 代码直接引用，但 res/values/styles.xml 的
    // Theme.LiveApp parent 为 Theme.MaterialComponents.DayNight.NoActionBar（Manifest
    // android:theme 在用），主题层级运行时需要这两个库 —— Batch 8 评估后保守保留。
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // constraintlayout：全仓零引用（Batch 8 grep 证据），删除。

    // 生命周期 / ViewModel（替代 GetX 状态）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")

    // 持久化：Room（关系数据 follow/history/tag ↔ Hive）+ DataStore（键值设置）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 协程 / 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 图片加载：Coil（对应 Dart extended_image / NetImage）
    implementation("io.coil-kt:coil:2.7.0")

    // ===== Jetpack Compose（UI 迁移目标栈，对应 Dart Flutter widget 体系）=====
    // BOM 统一管理 Compose 各库版本
    // BOM 2025.06.00 = Compose 1.8.2（task 09-12-predictive-back：预测性手势返回与
    // 共享元素转场的随手势回放需要 1.8；经阿里云 google 镜像分发，直连 google() 不可达）
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    // Material3 组件库（对应 Dart MaterialApp/Material 组件）
    implementation("androidx.compose.material3:material3")
    // foundation 基础能力（LazyVerticalGrid / 手势等，对应 Dart GridView/Listener）
    implementation("androidx.compose.foundation:foundation")
    // Compose UI 核心（对应 Dart widget 渲染层）
    implementation("androidx.compose.ui:ui")
    // Activity 的 Compose 宿主入口 setContent（替代原 Activity + View 树）
    implementation("androidx.activity:activity-compose:1.9.2")
    // 单 Activity 导航（对应 Dart GetX 路由）
    implementation("androidx.navigation:navigation-compose:2.8.1")
    // Coil 的 Compose 封装 AsyncImage（对应 Dart NetImage/extended_image）
    implementation("io.coil-kt:coil-compose:2.7.0")
    // collectAsStateWithLifecycle：StateFlow 生命周期安全订阅（对应 Dart Rx Obx）
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // 下拉刷新：Compose 侧用 material3 PullToRefreshBox，swiperefreshlayout 全仓零引用
    //（仅注释提及），Batch 8 删除。

    // 播放内核：libmpv（Jellyfin 维护的 Android 构建，mvp-android 同源 JNI API）
    // holdallsn 对应 Dart media_kit(mpv) 内核；本 AAR 自带 ffmpeg+mpv so（arm64-v8a）。
    implementation("dev.jdtech.mpv:libmpv:0.4.1")

    // 局域网同步：轻量 HTTP 服务端（对应 Dart 端 shelf + shelf_router，Android 无 com.sun.net.httpserver）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
