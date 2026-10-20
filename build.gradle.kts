// 根构建脚本：仅声明插件版本，供子模块按需应用。
// 对应 Dart 仓的 dart_simple_live/（core + app 两个包）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Kotlin 官方 Compose 编译器插件：与 Kotlin 同版本，供 app 模块应用。
    alias(libs.plugins.kotlin.compose) apply false
}
