# 里程志 MileNote · Android 原生版

Kotlin + Jetpack Compose + Material 3 Expressive。账本本地优先、无账号、无后端。
非代码产物（APK、截图、构建日志）不进本仓库。

- 官网 / 下载页：<https://selwiro.github.io/MileNoteAndroid/>
  站点只放在 `gh-pages` 分支，`main` 保持纯 Android 代码（网站文件不提交到 main）；
  维护说明见 [`docs/website.md`](docs/website.md)。
- 最新 APK：[Releases](https://github.com/Selwiro/MileNoteAndroid/releases/latest)。

## 环境

- JDK 17+、Android SDK Platform 37 + Build-Tools 37.0.0（Android Studio 或 `sdkmanager`）
- Gradle 由仓库内 wrapper 提供（9.6.0，首次构建自动下载），**无需全局安装 Gradle**
- SDK 定位：用 Android Studio 打开仓库根目录会自动生成 `local.properties`；命令行构建设置
  `ANDROID_HOME`（或自行写 `local.properties` 的 `sdk.dir`）
- 首次构建会下载 Gradle 9.6.0（约 130 MB）与依赖。中国大陆网络可 `export MILENOTE_ALIYUN=1`
  优先走阿里云镜像；若 `services.gradle.org` 下载慢，可把 `gradle/wrapper/gradle-wrapper.properties`
  里的 `distributionUrl` 换成镜像地址（如腾讯云 `https://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip`）

## 构建

```bash
./gradlew assembleDebug                          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :domain:test :app:testDebugUnitTest    # 单测
./build.sh debug                                 # 编译 + 归档到 dist/
./build.sh release                               # R8 + 签名（提供密钥时），归档到 dist/ 并生成 .sha256
```

也可以直接用 Android Studio 打开仓库根目录，选 `app` 运行。
`build.sh` 的归档目录默认是仓库内 `dist/`，可用 `MILENOTE_OUT_DIR=<目录>` 覆盖。
本仓库的 `dev-gradle.sh` 是 `gradlew` 的薄封装：直接用它即可；若机器上有独立工具链目录，
可设 `TOOLCHAIN=<目录>` 从其中取 JDK/SDK/Gradle 缓存。

**发布签名是可选的**：`keystore/milenote-release.jks` 与口令文件都不入库。把密钥和
`keystore/keystore.properties`（`storePassword` / `keyAlias` / `keyPassword`）放到位，或用环境变量
`MILENOTE_STORE_PASSWORD` / `MILENOTE_KEY_PASSWORD`，才会给 release 签名；否则产出
`app-release-unsigned.apk`（未签名，可 zipalign 后自行签名）。换签名会导致老用户无法覆盖安装，密钥需单独备份。

版本矩阵：Gradle 9.6.0 · AGP 9.5.0 · Kotlin 2.4.21（AGP 内置）· compose-bom 2026.09.00 ·
material3 1.5.0-beta02 · compileSdk/targetSdk 37 · minSdk 26（arm64-v8a + armeabi-v7a）。

## 版本管理

本仓库 `main` 分支，**历史自 0.27.0 起**（2026-11-06 建立，根提交
`chore: 初始化 Android 仓库，基线 0.27.0 (vc 60)` 与 tag `v0.27.0`；更早的 0.26.x / 1.26.x 历史
不在本仓库）。beta 期版本号用 0.x；第一个正式版定为 1.0.0。
远端：<https://github.com/Selwiro/MileNoteAndroid>。

```bash
git log --oneline          # 历史（自 v0.27.0 起）
git tag -n                 # 版本标签（v0.27.0、…）
git revert <hash>          # 回滚某次提交
```

发布流程：改版本号 → `./build.sh release` → 提交 `release: x.y.z (vc n)` → 打 tag `vX.Y.Z`。
release APK 归档为 `milenote-<versionName>-android.apk`（附 `.sha256`，可直接作为 GitHub Release 资产）。

## 模块

- `app/`：Compose 界面、数据层（单文件 JSON 账本快照 + DataStore 设置）、OCR、导入导出
- `domain/`：纯 Kotlin 领域层（数据模型、满箱法/保养/统计/备份/导入解析），JVM 单测

## 测试

```bash
./gradlew :domain:test :app:testDebugUnitTest  # 192 项领域 + 33 项 app 测试
./gradlew :app:assembleDebug                   # 构建 + 编译检查
```

## 目录要点

| 路径 | 内容 |
|---|---|
| `app/src/main/kotlin/com/milenote/android/ui/theme/` | 5 皮肤 × 浅/深 M3 ColorScheme、字阶、形状、动效 |
| `.../ui/components/` | StatusPill / 表单 / 空状态 / 图表容器等 12 个组件 |
| `.../ui/screens/` | 总览 / 记录 / 记一笔 / 油耗 / 保养 / 车辆与设置 / 导入向导 / OCR |
| `.../data/garage/` | 账本 JSON 快照（原子写入）、演示模式、备份恢复 |
| `.../platform/` | OCR 识别、CSV/ICS/备份导出、SAF/分享 |
| `domain/src/main/kotlin/.../domain/` | Web 同源的算法/备份/导入/OCR 解析（192 tests） |
| `keystore/` | 自签发布密钥与口令文件（**不入库**，需单独备份） |

## 说明

- 账本存储采用与 Web 完全同构的 `version:2` 单文件 JSON（图片 base64 dataURL 内嵌），保证与 Web 版备份双向互通；不使用 Room 分表。
- 本仓库只含 Android 端代码；功能范围、验收记录、已知限制与更早版本历史保存在作者本机工作区，不在本仓库。
- WebDAV / 桌面小组件 / 应用锁 / 平板双栏属于第二批，未包含在本版本。
