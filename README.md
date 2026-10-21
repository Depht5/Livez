# Livez

Livez 是一款基于 Kotlin 与 Jetpack Compose 开发的 Android 原生多平台直播聚合客户端。基于 [Simple Live](https://github.com/xiaoyaocz/dart_simple_live) 重写，目前支持哔哩哔哩、斗鱼、虎牙和抖音四大直播平台。

## 功能特性

- **多平台聚合**：浏览哔哩哔哩、斗鱼、虎牙、抖音的推荐直播、分类分区与搜索结果。
- **原生播放内核**：基于 mpv（libmpv）实现，支持硬件解码（MediaCodec）、多画质选择与多播放线路切换。
- **自定义弹幕**：通过 Canvas 渲染弹幕层，支持调节弹幕字号、滚动速度、不透明度与同屏密度，支持屏蔽词与规则过滤。
- **界面与主题**：遵循 Material 3 设计规范，支持浅色、深色与纯黑（AMOLED）主题及系统动态取色；适配平板与折叠屏设备的横屏分栏布局。
- **关注与历史**：本地管理关注主播列表、开播状态更新与观看历史记录。
- **账号与数据同步**：支持哔哩哔哩扫码登录与斗鱼、抖音 Cookie 导入；支持本地 JSON 数据导入导出及 WebDAV 云端备份。
- **应用内更新**：支持 Gitee 和 GitHub 双渠道检查更新，通过系统通知栏显示下载进度并在校验后调起安装。

## 技术栈与架构

- **语言与系统要求**：Kotlin 2.0.21，最低支持 Android 8.0（API 26，受 libmpv 限制），目标版本 Android 15（API 35）。
- **用户界面**：单 Activity 架构，纯 Jetpack Compose（Material 3）与 Navigation Compose 实现。
- **模块划分**：
  - `core/`：纯 Kotlin/JVM 模块，负责各直播平台的接口请求、数据解析、WebSocket 弹幕通信，以及基于 Rhino 引擎的动态 JS 签名执行（抖音 a_bogus / webcast 及斗鱼 crptext）。
  - `app/`：Android 应用模块，包含 Compose 界面、Room 数据库、DataStore 配置存储、mpv 播放器封装及更新服务。
- **主要依赖**：OkHttp 4、kotlinx.serialization、Room、DataStore、libmpv、Rhino。

## 构建说明

本项目仅打包 `arm64-v8a` 架构（不支持 32 位 armv7a 或 x86 模拟器直接运行）。

### 环境要求

- JDK 17+
- Android SDK（API 35，Build-Tools 35.0.0）

### 常用命令

```powershell
# Windows (PowerShell)
$env:JAVA_HOME = "D:\Programming\jdk17"  # 根据本机实际 JDK 路径指定

# 运行核心模块单元测试
.\gradlew.bat :core:test

# 编译 Debug APK
.\gradlew.bat :app:assembleDebug

# 编译 Release APK
.\gradlew.bat :app:assembleRelease
```

```bash
# Linux / macOS
./gradlew :core:test
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

编译生成的 APK 位于 `app/build/outputs/apk/debug/` 或 `app/build/outputs/apk/release/` 目录下。

## 下载安装

可在以下地址下载最新版本的安装包：

- [GitHub Releases](https://github.com/Depht5/Livez/releases)
- [Gitee Releases](https://gitee.com/Depht5/livez/releases)

## 开源协议与声明

- 本项目采用 [GNU General Public License v3.0](LICENSE)（GPL-3.0-only）开源。
- 本项目基于 [Simple Live](https://github.com/xiaoyaocz/dart_simple_live)（Copyright (C) 2023-2025 xiaoyaocz, GPL-3.0）重写并沿用其协议。第三方依赖的许可说明见 [NOTICE](NOTICE)。
- 免责声明：本项目仅供编程学习与个人交流使用。应用内展示的所有直播音视频、弹幕及元数据均通过各平台公开接口获取，相关权利归属原平台所有。
