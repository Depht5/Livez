pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 默认 PREFER_SETTINGS：允许本机 init.d 的 CN 镜像（repo-mirror.gradle）注入的 project 仓库共存。
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kotlin_re"

// 镜像 dart_simple_live/simple_live_core → :core
include(":core")
// 镜像 dart_simple_live/simple_live_app → :app
include(":app")
