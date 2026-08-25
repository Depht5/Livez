// 根构建脚本：仅声明插件版本，供子模块按需应用。
// 对应 Dart 仓的 dart_simple_live/（core + app 两个包）。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Kotlin 官方 Compose 编译器插件：与 Kotlin 同版本（2.0.21），供 app 模块应用。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
