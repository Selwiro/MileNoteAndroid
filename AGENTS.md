# AGENTS.md · MileNoteAndroid

本文件给在本仓库工作的 AI 编码工具与贡献者阅读。**本机专有环境说明见未入库的
`AGENTS.local.md`**（若存在，其内容优先于本文件）。仓库外的项目现状、决策记录、更早版本历史
不在本仓库，只有作者本机工作区有。

## 现状

- **0.34.1（versionCode 80，beta）**；第一个正式版定为 1.0.0。包名 `com.milenote.android`，
  minSdk 26 / targetSdk 37。界面中英双语（设置 → 外观 → 语言）。
- Kotlin 2.4.21 + Jetpack Compose（BOM 2026.09.00、material3 1.5.0-beta02）+ Material 3 Expressive；
  AGP 9.5.0 / Gradle 9.6.0。**版本矩阵是实测跑通的，不要随手升级。**
- `domain/` = 纯 Kotlin 领域层（与 Web 版同源的算法、备份、导入、OCR 解析，JVM 单测）；
  `app/` = Compose 界面、数据层（单文件 JSON 账本快照）、平台能力（OCR/SAF/WebDAV）。
- **i18n**：用户可见文案用 `tr("中文", "English")`（带占位符用 `tr(zh, en, args)`）；domain 用
  `com.milenote.android.domain.i18n.tr`。分类名/项目名/皮肤 id/presetKey 等**数据值不翻译**，
  显示走 `i18n/DataLabels`。约定与术语表在仓库外 [`../docs/i18n-en-guide.md`](../docs/i18n-en-guide.md)。

## 硬规则

- 账本必须保持 **`version:2` 单文件 JSON**（图片 base64 dataURL 内嵌），**与 Web 版备份双向可导入**。
  改结构先谈兼容；破坏性结构升级归 2.0.0。
- **不新增权限**（现有：相机、网络、粗略定位，共 3 项）；不加回系统通知、应用锁、WorkManager
  后台任务、动态取色。
- WebDAV **只在前台同步**（打开时一次 + 本地修改后防抖），退出应用即结束；不要引入后台周期同步。
- 非代码产物（APK、截图、构建日志）**不进仓库**；构建日志写 `/tmp/*.log` 再 grep 关键行。
- **官网只放在 `gh-pages` 分支**（GitHub Pages Source = gh-pages /(root)），`main` 保持纯代码，
  不要在 main 放网站资源（HTML/CSS/JS/图片/字体）；站点维护说明在 [`docs/website.md`](docs/website.md)，
  该文件不随站点发布。
- 样例数据一律合成：车牌、加油站、订单号、行程等，**不得**使用真实小票/真实账本内容。

## 构建与测试

仓库自带 Gradle wrapper（9.6.0），**无需全局 Gradle**；需要 JDK 17+ 与 Android SDK Platform 37
（`ANDROID_HOME` 或 `local.properties`）。任何人 clone 后 `./gradlew assembleDebug` 即可出 APK。

```bash
./gradlew :domain:test :app:testDebugUnitTest  # 领域 + app 单测（195 + 38），必须全绿
./gradlew :app:assembleDebug                   # 编译检查，产物在 app/build/outputs/apk/debug/
./build.sh debug                               # 编译 + 归档到 dist/
./build.sh release                             # R8 +（有密钥时）签名，归档到 dist/ 并生成 .sha256
```

首次构建会下载 Gradle 9.6.0 与依赖；中国大陆网络可 `export MILENOTE_ALIYUN=1` 走阿里云镜像，
wrapper 发行包下载慢时改 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl`。

`dev-gradle.sh` 只是 `gradlew` 的薄封装：本机环境直接可用；设 `TOOLCHAIN=<目录>` 时从
`<目录>/{jdk-*,sdk,gradle-home}` 取环境；仓库根的不入库 `local.env` 会被自动 source 用来固化本机路径。
归档目录默认仓库内 `dist/`，`MILENOTE_OUT_DIR` 可覆盖。

**签名（可选）**：`keystore/milenote-release.jks` 与口令**都不入库**。口令放 `keystore/keystore.properties`
（该目录已被 `.gitignore` 排除）或环境变量 `MILENOTE_STORE_PASSWORD` / `MILENOTE_KEY_PASSWORD`，
密钥路径可用 `storeFile` 覆盖。未提供时 release 构建产出 `app-release-unsigned.apk`，不影响任何人 clone 后构建。
**绝不**把口令写进代码、文档或提交信息。换签名会导致老用户无法覆盖安装，密钥需单独备份。

## 版本管理

- 分支 `main`；**仓库历史自 0.27.0 起**（更早的 0.26.x / 1.26.x 不在本仓库）。
- **提交时机**：一小步改完、测试通过就提交，不要攒。
- **提交信息**：`type(范围): 中文摘要`，type 用 `feat` / `fix` / `ui` / `docs` / `chore` / `release`；
  正文 1–3 行写「为什么」。例：`ui(保养): 计划页改为平铺表格`。
- **发版**：`versionCode +1`、`versionName` 更新后单独提交 `release: x.y.z (vc n)`，并打 annotated tag
  `git tag -a vX.Y.Z -m "..."`。beta 期版本号用 `0.x`；第一个正式版 `1.0.0`，之后 semver
  （`1.0.x` 修 bug / `1.1.0` 加功能 / `2.0.0` 破坏性变更）。
- **回滚**：优先 `git revert <hash>`（保留历史）；只撤未提交改动用 `git restore <file>`。
  **不要**用 `git reset --hard` 抹掉已提交的历史。
- **APK 命名**：release 为 `milenote-<versionName>-android.apk`（同名 `.sha256` 一起产出，可直接做
  GitHub Release 资产）；debug 为 `milenote-<versionName>-android-debug-<时间戳>.apk`，只留本机。
  全小写 ASCII、连字符分隔、不带空格/中文/括号/时间戳（debug 除外）。

## 隐私自查（推送前）

```bash
git grep -n -i -E "storePassword|keyPassword|口令|token|secret"
git log -p | grep -n -i -E "storePassword|keyPassword|milenote"   # 确认历史里没有明文
```

`.gitignore` 覆盖 `.gradle/`、`build/`、`.kotlin/`、`local.properties`、`keystore/`、`*.log`、`*.apk`、
IDE 文件。`git clean -xdf` 会连 `keystore/` 一起删，跑之前先确认。
