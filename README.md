# kotlin_re —— Kotlin 原生 Android 重写

本目录用 **Kotlin + Android 原生栈**重写 `simple_live_core`（纯 Dart 直播聚合核心）与
`simple_live_app`（Android 端），并**镜像现有 Dart 的文件/模块组织方式**。

> 规范见 `.trellis/spec/kotlin_re/index.md`。现有 `dart_simple_live/` 保持只读，作为行为参考。

## 目录镜像关系

| Dart 包 | Kotlin 模块 | 内部布局 |
|---|---|---|
| `dart_simple_live/simple_live_core` | `core/` | `interfaces/`（Dart `interface/`；Kotlin 保留字，用复数）、`site/`、`danmaku/`、`model/`、`common/` |
| `dart_simple_live/simple_live_app` | `app/` | `app/`、`di/`、`models/`、`modules/`、`services/`、`widgets/`、`routes/` |

包名：`com.kotlin_livez.core` / `com.kotlin_livez.app`。

## 技术栈

- Kotlin 2.0.21 · AGP 8.13.2 · Gradle 8.14.5
- kotlinx.serialization（JSON）· OkHttp 4.12.0（HTTP + WebSocket）· kotlinx-coroutines
- Room + DataStore（持久化，对应 Dart 的 Hive）
- 手写轻量 DI（`ServiceContainer`，对应 GetX 服务）
- Rhino 1.7.15（纯 JVM JS 引擎，对应 Dart 的 dart_quickjs）：执行抖音 a_bogus/webcast 签名
  与斗鱼服务器动态签名的 JS 脚本（脚本资源见下）

### JS 脚本资源（抖音/斗鱼签名）

- `core/src/main/resources/douyin/abogus.js`、`webms_sdk.js`：从上游 Dart
  `douyin_sign.dart` 提取并已提交到仓库，`DouyinSign` 以 Rhino 解释模式执行。
  提取脚本依赖同级的 Dart 仓库，仅作为维护者本地工具，不随本仓库分发。
- 斗鱼 `crptext` 为服务器动态下发（内含 `ub98484234`），`DouyuSign` 内置 CryptoJS 并以
  Rhino 执行（`$` 以 `\u0024` 转义规避字符串模板）。

## 构建与验证

需要在 **JDK 17+**（Android Gradle Plugin 8.x 要求）下构建。本机 JDK 路径示例：

```bash
# Windows (PowerShell)
$env:JAVA_HOME = "D:\Programming\jdk17"
cd kotlin_re

# 核心编译 + 单元测试（纯 JVM，不依赖 Android）
.\gradlew.bat :core:compileKotlin :core:test

# App 编译（需 Android SDK，见 local.properties）
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

注意：仅打包 arm64-v8a（镜像 app 构建策略）；x86_64 模拟器无法安装 arm64-only APK，请用
物理 arm64 设备或 arm64 系统镜像验证。

## 当前状态

- [x] Gradle 多模块工程骨架（core / app + wrapper）
- [x] core 契约：`LiveSite` / `LiveDanmaku`
- [x] core 模型：全部 `Live*` 模型 + `FollowRefresh*` / `SyncProgress`
- [x] core 公共设施：`CoreError` / `CoreLog` / `HttpClient` / `HttpLogSanitizer` / `ConvertHelper` / `WebSocketUtil`
  + `DouyinSign`（Rhino，含脚本加载时 Rhino 兼容预处理）/ `DouyinCookieHelper` / `AutoExitSession` /
  `DouyinFollowRefreshLimiter` / `PlayerErrorClassifier` / `PlayerVolumePolicy` / `BinaryWriter`
- [x] core 站点：bilibili / douyu / huya / douyin（全部注册于 `LiveSiteRegistry`；快手平台已按需求移除）
- [x] core 弹幕：BiliBili / Douyu（689 协议）/ Huya（Tars）/ Douyin（手写 protobuf）
- [x] core 单元测试：6 个测试类、32 个用例（ConvertHelper / DouyinCookieHelper /
  AutoExitSession / PlayerVolumePolicy / DouyinFollowRefreshLimiter / DouyinSign（含真实执行
  abogus.js 产出 a_bogus 的回归用例））
- [x] 验证：`:core:compileKotlin` + `:core:test` BUILD SUCCESSFUL（JDK 17）
- [ ] app 层：模块 / 服务 / 存储 / 界面逐步移植

## 冻结标识约定（镜像 Dart 规范）

站点 key（`bilibili` 等）作为数据/存储键保持不变；错误文案（core）与 UI 文案为冻结中文契约。
方法职责与 Dart 一一对应（Dart 拼写错误 `getCategores`/`getPlayQualites` 在 Kotlin 中修正，
但职责一致）。新增公共类在 `LiveSiteRegistry` 聚合入口统一暴露。

## 许可证

本项目以 **GNU General Public License v3.0（GPL-3.0-only）** 发布，完整条款见
[LICENSE](LICENSE)。Copyright (C) 2026 Depht5。

本项目是 [Simple Live](https://github.com/xiaoyaocz/dart_simple_live)
（Copyright (C) 2023-2025 xiaoyaocz，GPL-3.0）的 Kotlin / Android 重写：
协议解析、签名逻辑与内置的抖音 JS 脚本源自上游，上游版权与 GPL 义务同样适用。
第三方依赖的许可说明见 [NOTICE](NOTICE)。

分发 APK 时须同时提供本仓库源码，且不得附加 GPL 之外的额外限制；修改后的版本
同样以 GPL-3.0 发布。
