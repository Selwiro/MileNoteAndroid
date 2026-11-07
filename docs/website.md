# 官网（GitHub Pages）维护说明

> 站点**只存在于 `gh-pages` 分支**，本文件在 `main` 里、不会随站点发布。
> 线上地址：<https://selwiro.github.io/MileNoteAndroid/>
> Pages Source：Deploy from a branch → `gh-pages` / `(root)`；推送该分支自动构建（约 1–3 分钟）。

## 站点里有什么

`gh-pages` 分支根目录即站点根目录（纯静态、无构建、无外部依赖）：

```
gh-pages/
├─ index.html            # 单页：Hero / 数据约定 / 为什么 / 界面巡览 / 外观 / 功能清单 / 隐私 / 下载 / FAQ
├─ 404.html              # GitHub Pages 根目录 404 页（同样跟随深浅色）
├─ site.webmanifest      # PWA manifest（图标、主题色）
├─ sitemap.xml / robots.txt
├─ .nojekyll             # 关闭 Jekyll，静态文件原样发布
└─ assets/
   ├─ style.css          # 设计系统 + 布局（深浅色用 data-theme）
   ├─ app.js             # 主题 / 菜单 / 进场 / 巡览切换 / 灯箱 / 复制 SHA-256 / FAQ / 最新版本自动读取
   ├─ og.png             # 社交分享卡（1200×630）
   ├─ favicon-16.png、favicon-32.png、apple-touch-icon.png
   └─ screens/*.webp     # 9 张演示账本截图（720×1600，WebP q82，共约 325 KB）
```

页面结构（2026-11-07 重设计后）：
1. **Hero**：标题 + 下载/看界面两个入口 + 版本信息，右侧手机实拍与三个数据浮卡；
2. **数据约定**（卡片，叠在 Hero 下沿）：本地优先存储 / 无需注册账号 / 满箱法口径透明 / 数据可完整带走，四条并列；
3. **为什么是里程志**：左侧粘性标题 + 右侧三条编号条目（一车一本账 / 满箱法 / 保养先到为准）；
4. **界面巡览**：左侧六个章节 tab，右侧手机舞台切换对应截图，点手机开大图（键盘 ← → 切换、Esc 关闭）；
5. **外观**：两台手机 + 五套皮肤色板（色值取自 `ui/theme/Theme.kt`）；
6. **功能清单**：3×3 细线索引；
7. **数据与隐私**：深色区 + 「数据流向」示意卡（手机 → 私有目录 → 可选 WebDAV，开发者服务器划掉）；
8. **下载**：渐变卡 + 规格表 + SHA-256 复制 + 三步安装；
9. **FAQ**：手风琴 + 右侧反馈卡；页脚。

## 本地预览

```bash
cd MileNoteAndroid
git worktree add /tmp/milenote-pages gh-pages
python3 -m http.server 5190 --bind 127.0.0.1 --directory /tmp/milenote-pages
# 打开 http://127.0.0.1:5190/
```

验收：桌面 1440×900 / 移动 390×844、浅色 + 深色各过一遍；界面巡览六个 tab 能切换舞台截图与说明；
点舞台手机能开大图、← → 能切、Esc 能关；导航能跳区块并高亮；下载区「复制」能拿到完整 SHA-256；
移动端菜单能开合；无横向溢出；浏览器 Network 里 `api.github.com/.../releases` 200，Hero 与下载卡显示的
版本 / versionCode / SHA-256 / 包大小与最新 Release 一致。

## 更新与发布

```bash
cd MileNoteAndroid
git worktree add /tmp/milenote-pages gh-pages     # 首次
# 改 /tmp/milenote-pages/index.html 或 assets/...
cd /tmp/milenote-pages
git add -A && git commit -m "deploy: 更新官网文案" && git push origin gh-pages
```

### 发布新版（页面自动跟随，不再手改版本）

1. `./build.sh release` 生成 `outputs/Android/releases/milenote-<版本>-android.apk` + `.sha256`；
2. 打并推送 tag：`git tag -a v<版本> -m "release: <版本> (vc <versionCode>)" && git push origin v<版本>`；
3. 在 GitHub 建 Release（`prerelease`，beta 期），上传 APK 与 `.sha256` 两个资产；
4. **Release 正文第一行写 `versionCode: <n>`**（页面从正文里读它；没有就隐藏 versionCode 行）；
5. 站点无需任何改动：打开页面会请求 GitHub Releases 列表，自动换成最新 Release 的
   版本号 / 包大小 / SHA-256 / 下载地址 / 更新日志链接。

> 本机没有 `gh` 且 `github.com:443` 不通时，可用 GitHub 设备码流程（`public_repo` 权限）拿临时 token，
> 再走 `api.github.com` / `uploads.github.com` 创建 Release；token 用完即删，不落库。

### 排障：Pages 构建失败

发布历史里多次出现 `Page build failed.`（API 不返回具体原因，内容本身没问题，属偶发构建失败）。
在仓库 **Actions → `pages build and deployment` → Re-run all jobs**，或向 `gh-pages` 推一个空提交
（`git commit --allow-empty -m "deploy: 重跑 Pages 构建" && git push`）后，同一份内容通常即可成功。
遇到报错先**重跑构建**，不要急着改站点内容。

> 不要改用 Actions 工作流发布：发布路径固定为 `gh-pages` 分支，避免两套机制打架。

## 素材与版本号约定

- **截图来源**：Android App 内置演示账本，数据为合成示例。当前 9 张统一来自
  **Android 0.27.0（versionCode 60）** 模拟器实拍；原始 PNG 归档在作者本机
  `outputs/Android/screenshots/v60-site-*.png`，**不进仓库**。写入站点前统一转成宽 720 的
  WebP（q82），放在 `gh-pages` 的 `assets/screens/`。各截图状态栏时间不同属正常。
- 截图对：dashboard / analytics（费用统计）/ records（费用明细）/ fuel / maintenance（保养计划）/
  editor（记一笔）/ settings（设置与外观）/ skin-forest / dark。
- **不要**使用真实账单、真实车牌 / 加油站 / 订单号；截图一律合成数据。
- **版本信息自动读取（2026-11-07 起）**：`assets/app.js` 的 `initRelease()` 请求
  `https://api.github.com/repos/Selwiro/MileNoteAndroid/releases?per_page=20`，取最新非草稿 Release
  （含 prerelease，按 `published_at` 排序），用 APK 资产的 `browser_download_url` / `size` /
  `digest` 更新所有带 `data-release-*` 标记的节点（Hero 版本行、三处下载按钮、下载卡规格表、
  SHA-256、更新日志链接），并同步 JSON-LD 的 `softwareVersion` / `downloadUrl`；
  `sessionStorage` 缓存 10 分钟，API 失败或浏览器禁用 JS 时回退到 `index.html` 里的静态值。
- **发版契约**：Release 正文写 `versionCode: <n>`；资产名保持
  `milenote-<版本>-android.apk`（页面按 `.apk` 后缀找资产，不依赖资产名）。
- **静态回退值**：`index.html` 中的 `v<版本> beta` / versionCode / SHA-256 / 下载 URL 只在 API 不可用时可见，
  允许滞后；正常发版不需要改站点。`sitemap.xml` 的 `lastmod` 仍可在页面结构变化时手动更新。
- **OG 分享图** `assets/og.png`：本机用临时 HTML（1200×630，系统字体 + `brand-tile.png` + `dashboard.webp`）
  在浏览器里渲染后截图生成，临时文件不入库；换文案或主视觉时重画，并确认 `index.html` 的 `og:image` /
  `twitter:image` 仍为绝对 URL、`og:image:width/height` 为 1200×630。

## 设计约束（和 App 保持一致）

- 品牌基色：`#55758B`（里程蓝 seed），主色 `#446378` / 深色 `#2E4A5D` / 渐变 `#20394A → #446378 → #7E9EAF`；
  浅色底 `#F7FAFB`，深色底 `#0E1316`；暖沙色 `#8F6239`（深色 `#C79A72`）只用于编号等细节，正文不用。
  圆角、留白与字阶按 Material 3 Expressive 的观感走；页面走「账本」语汇：细线、编号、规格表、行线纹理。
- 不再使用「六张截图平铺网格」：截图统一由「界面巡览」的舞台 + 章节列表承载，一次只显示一张，
  避免一屏堆六个手机。
- 字体只用系统字体栈（PingFang SC / Hiragino Sans GB / Microsoft YaHei / Noto Sans SC），不加载外部字体。
- 深浅色：`<html data-theme="light|dark">`；首屏前内联脚本读 `localStorage` 与 `prefers-color-scheme`
  以避免深色闪白，切换按钮写入 `milenote-theme`。
- 无 JS 时正文必须可见（`.reveal` 的隐藏样式挂在 `html.js` 下），截图与文字不依赖脚本渲染。
