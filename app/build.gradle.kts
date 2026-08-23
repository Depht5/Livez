// 对应 dart_simple_live/simple_live_app —— Android 原生应用。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "dev.livefork.app"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.livefork.app"
        // minSdk 26（Android 8.0+）：libmpv（dev.jdtech.mpv:libmpv）AAR 强制要求；
        // mpv 播放内核在旧系统上不可用。
        minSdk = 26
        targetSdk = 34
        versionCode = 11208
        versionName = "1.12.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

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

    // 播放内核：libmpv（Jellyfin 维护的 Android 构建，mvp-android 同源 JNI API）
    // holdallsn 对应 Dart media_kit(mpv) 内核；本 AAR 自带 ffmpeg+mpv so（arm64-v8a）。
    implementation("dev.jdtech.mpv:libmpv:0.4.1")

    // 局域网同步：轻量 HTTP 服务端（对应 Dart 端 shelf + shelf_router，Android 无 com.sun.net.httpserver）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
