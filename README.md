# 里程志 MileNote

![release](https://img.shields.io/github/v/release/Selwiro/MileNoteAndroid?include_prereleases&label=release&color=55758b)
![platform](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)
![compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-4285F4?logo=jetpackcompose&logoColor=white)

> **一辆车，一本账。** 把油费、保养和每一公里，记成一本看得懂的账。

里程志（MileNote）是一款 **Android 原生、本地优先的多车用车账本**：记录九类用车支出，用满箱法计算真实油耗（纯电车辆按满电法计算电耗），按「先到为准」管理保养。**无账号、无后端**——账本默认只存在你的手机里；需要跨设备时，可以同步到你自己提供的 WebDAV。

[官网与下载](https://selwiro.github.io/MileNoteAndroid/) · [English site](https://selwiro.github.io/MileNoteAndroid/en/) · [Releases](https://github.com/Selwiro/MileNoteAndroid/releases) · [问题反馈](https://github.com/Selwiro/MileNoteAndroid/issues)

<details>
<summary><b>English</b></summary>

MileNote is a local-first, multi-vehicle car expense tracker for Android, built with Kotlin and Jetpack Compose. It records nine expense categories, computes real fuel economy with the full-tank method (kWh/100km for EVs), and manages maintenance by whichever comes first — mileage or date. There is no account and no backend: your ledger stays on your phone, with optional sync to your own WebDAV server. See the [English website](https://selwiro.github.io/MileNoteAndroid/en/) and [Releases](https://github.com/Selwiro/MileNoteAndroid/releases).

</details>

## 界面

> 截图来自 App 内置演示账本，数据为合成示例。更多界面见[官网](https://selwiro.github.io/MileNoteAndroid/#screenshots)。

| 用车总览 | 记一笔 | 费用统计 | 维修保养 |
|:---:|:---:|:---:|:---:|
| <img src="https://raw.githubusercontent.com/Selwiro/MileNoteAndroid/gh-pages/assets/screens/dashboard.webp" width="180" alt="用车总览"> | <img src="https://raw.githubusercontent.com/Selwiro/MileNoteAndroid/gh-pages/assets/screens/editor.webp" width="180" alt="记一笔"> | <img src="https://raw.githubusercontent.com/Selwiro/MileNoteAndroid/gh-pages/assets/screens/analytics.webp" width="180" alt="费用统计"> | <img src="https://raw.githubusercontent.com/Selwiro/MileNoteAndroid/gh-pages/assets/screens/maintenance.webp" width="180" alt="维修保养"> |

## 功能

- **多车账本**：可添加任意数量车辆，费用、油耗、保养、导入与导出都按当前车辆隔离；完整备份包含全部车辆。支持车辆照片（相册上传自动压缩，或填写图片 URL）。
- **记一笔**：九类内置费用——油费、保险、保养、维修、汽车美容、过路费、停车费、罚单、汽车用品，可新增自定义分类；分类图标网格置顶，金额、日期、里程与能源字段一屏填完，支持搜索、筛选、按月分组、编辑与删除。
- **油耗 / 电耗**：满箱法只统计两次「加满」之间，第一次加满只建立基准，未加满的加油量累计到下次结算，标记漏记即重设基准；输出综合平均油耗、每公里油费、油耗走势与加油明细。纯电车辆切换为能耗分析（kWh/100km，满电法，范围内没有满电区间时用累计充电量估算并标注口径）。
- **保养计划**：燃油车 12 项、纯电车 6 项默认方案，公里数或日期任一到期就进入待办；计划表展示上次已做、下次日期/里程与状态；完成并记录后自动安排下一轮。
- **费用统计**：累计支出、记录里程、每公里成本、综合平均油耗；按月堆叠柱状图看支出趋势，环图看费用构成；统计 / 明细双页签，支持自定义时间区间。
- **导入与导出**：小熊油耗（XLS/XLSX）、钱迹（TXT/JSON/CSV/XLSX）与完整 JSON 备份；字段映射、逐行预览、重复记录跳过，也可从系统分享直接进入导入。费用可导出当前车辆 CSV（17 列），完整账本可导出 JSON。
- **小票识别**：CameraX 拍照或相册选图，ML Kit 中文端侧识别实付金额、加油量与日期；只回填空字段，识别结果需核对后保存，图片不落盘、不上传。
- **WebDAV 同步（可选）**：可连接坚果云、Nextcloud、群晖或自建 WebDAV；前台自动同步（打开时一次 + 本地修改后防抖）加手动触发，退出应用即结束。连接密码用 Android Keystore + AES-256-GCM 加密保存。
- **今日油价**：按车辆燃油标号显示定位城市的当日油价与下次调价方向，数据仅供参考（纯电车辆与英文界面不显示）。
- **外观与语言**：Material 3 Expressive；五套皮肤（里程蓝、森林绿、丁香紫、暖日橙、石墨灰）× 浅色 / 深色；中英双语界面；货币 / 里程 / 油量仅切换显示单位，不换算数值。
- **原生集成**：桌面长按快捷方式（记一笔 / 扫小票）、系统分享接收、SAF 文件选择、检查更新；界面锁竖屏。

## 数据与隐私

- **无需账号**：不需要手机号、邮箱或第三方登录，没有订阅、广告与埋点统计。
- **账本在本地**：保存在应用私有目录下的单文件 `version:2` JSON（车辆图片以 dataURL 内嵌），原子写入，不经过开发者服务器。
- **本地计算**：文件解析与 OCR 都在设备上完成，原始文件与识别用图不上传、不写入账本。
- **权限只有 3 项**：相机、网络、粗略定位（定位仅用于今日油价卡）。
- **WebDAV 是你自己的服务器**：地址与密码由你填写；Android 上传的是明文 JSON，release 包只允许 HTTPS，同步只在前台进行。
- **备份请自己保管**：卸载应用、更换设备或清理数据前，请先导出完整 JSON 备份；备份文件包含车辆图片与完整账本。

## 下载与安装

当前版本 **0.35.3（beta，versionCode 92）**，首个正式版定为 1.0.0。最新安装包、版本说明与 SHA-256 见 [Releases](https://github.com/Selwiro/MileNoteAndroid/releases)；官网下载页会自动读取最新 Release 的版本、包大小与校验值。

- 系统要求：Android 8.0（API 26）及以上
- 架构：arm64-v8a / armeabi-v7a，安装包约 23 MB
- 自签名 APK，首次安装需要在系统设置里允许「安装未知应用」

安装后可以先打开内置演示账本试记（两辆车、完整样例数据，退出后自动还原，不写入真实账本），再添加车辆、记第一笔，并导出一次完整 JSON 备份。不同签名的版本不能覆盖安装；卸载或换机前请先备份。

## 从源码构建

需要 **JDK 17+** 与 **Android SDK Platform 37 / Build-Tools 37.0.0**；Gradle 由仓库内 wrapper 提供（9.6.0，首次构建自动下载），无需全局安装 Gradle。SDK 位置用 `ANDROID_HOME` 指定，或让 Android Studio 打开仓库后自动生成 `local.properties`。

```bash
git clone git@github.com:Selwiro/MileNoteAndroid.git
cd MileNoteAndroid

./gradlew :app:assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :domain:test :app:testDebugUnitTest    # 244 项领域 + 116 项 app 单测
./build.sh release                               # 可选：R8 + 签名（提供密钥时），归档到 dist/ 并生成 .sha256
```

也可以直接用 Android Studio 打开仓库根目录，选 `app` 运行。

- **中国大陆网络**：设置 `export MILENOTE_ALIYUN=1` 优先走阿里云镜像；wrapper 发行包下载慢时，可把 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 换成镜像地址（如腾讯云 `https://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip`）。
- **发布签名是可选的**：`keystore/` 与口令文件都不入库。把密钥与 `keystore/keystore.properties`（`storePassword` / `keyAlias` / `keyPassword`）放到位，或设置环境变量 `MILENOTE_STORE_PASSWORD` / `MILENOTE_KEY_PASSWORD`，release 才会签名；否则产出 `app-release-unsigned.apk`，不影响任何人 clone 后构建。
- `build.sh` 默认归档到仓库内 `dist/`，可用 `MILENOTE_OUT_DIR=<目录>` 覆盖。

## 技术栈与结构

Kotlin + Jetpack Compose + Material 3 Expressive；单 Activity + Compose Navigation；账本为单文件 JSON 快照（DataStore 存设置）；ML Kit 端侧 OCR；OkHttp 访问 WebDAV。

| 模块 | 内容 |
|---|---|
| `app/` | Compose 界面与 ViewModel、数据层（账本读写）、OCR、导入导出、WebDAV、平台能力（SAF / 分享 / 定位） |
| `domain/` | 纯 Kotlin 领域层：数据模型、满箱法 / 满电法、保养方案、统计、备份合并、导入解析（JVM 单测） |
| `app/src/test`、`domain/src/test` | app 单测（116 项）与领域单测（244 项） |
| `build.sh`、`dev-gradle.sh` | 构建、签名与归档脚本 |

版本矩阵（实测跑通，不建议随意升级）：Gradle 9.6.0 · AGP 9.5.0 · Kotlin 2.4.21 · Compose BOM 2026.09.00 · material3 1.5.0-beta02 · compileSdk / targetSdk 37 · minSdk 26。

## 版本与发布

`main` 分支持续迭代，`vX.Y.Z` tag 对应 GitHub Release 资产（APK + `.sha256`）。beta 期版本号用 `0.x`，首个正式版为 `1.0.0`，之后按 semver 递增；本仓库历史自 `v0.27.0`（2026-11-06）起。更新日志见 [Releases](https://github.com/Selwiro/MileNoteAndroid/releases)。

## 已知限制

- 没有账号体系与多端实时同步；WebDAV 只在前台同步，退出应用即停止，远端账本为明文 JSON。
- 保养到期只在 App 内「待办」提示，不做系统通知；可以把单条提醒导出为 `.ics` 日历文件。
- 界面锁竖屏，未做平板 / 横屏双栏。
- APK 为自签名，首次安装需允许未知来源；换签名会导致老用户无法覆盖安装。
- 纯电车辆不提供充电小票 OCR、电池健康与续航估算；今日油价的调价方向仅供参考，以发改委公布为准。
- 每公里成本 = 已录入支出 ÷ 记录里程，不包含折旧、贷款利息等未录入成本。

## 反馈与贡献

- **Bug 与建议**：请到 [Issues](https://github.com/Selwiro/MileNoteAndroid/issues) 提交，附上版本号（设置 → 关于）、机型、复现步骤与必要截图。
- **Pull Request**：欢迎先开 Issue 讨论再动手；提交前请跑通 `./gradlew :domain:test :app:testDebugUnitTest` 与 `./gradlew :app:assembleDebug`。
- **代码约定**：仓库内的 [AGENTS.md](AGENTS.md) 记录了账本格式、权限、测试与发布等硬规则，提交信息使用 `type(范围): 摘要`。
- **隐私**：样例数据一律使用合成数据，不得提交真实小票、真实账本、密钥或构建产物。

## 链接

- 官网 / 下载页：<https://selwiro.github.io/MileNoteAndroid/>（英文版：<https://selwiro.github.io/MileNoteAndroid/en/>）
- 版本发布：<https://github.com/Selwiro/MileNoteAndroid/releases>
- 问题反馈：<https://github.com/Selwiro/MileNoteAndroid/issues>
- 本站截图与页面源码在 `gh-pages` 分支；`main` 只保留 Android 代码。
