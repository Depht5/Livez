// 对应 dart_simple_live/simple_live_core。
// 纯 Kotlin/JVM 模块，不依赖 Android —— 与 Dart core 的“无 Flutter 依赖”约束一致。
// 随 Gradle JVM（JDK17）编译；Java 与 Kotlin 目标统一为 17，避免 JVM-target 不一致报错。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // JSON 序列化（kotlinx）/ 网络（OkHttp）/ 协程
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    // B站弹幕 brotli 解压（对应 Dart 的 brotli 包）
    implementation(libs.brotli.dec)
    // 抖音 a_bogus/webcast 签名 JS 执行引擎（对应 Dart 的 dart_quickjs；纯 JVM 用 Rhino）
    implementation(libs.rhino)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    // 提供本地回环测试服务器（可选）
    testImplementation(libs.mockwebserver)
}
