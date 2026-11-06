# 官网（GitHub Pages）维护说明

> 站点**只存在于 `gh-pages` 分支**，本文件在 `main` 里、不会随站点发布。
> 线上地址：<https://selwiro.github.io/MileNoteAndroid/>
> Pages Source：Deploy from a branch → `gh-pages` / `(root)`；推送该分支自动构建（约 1–3 分钟）。

## 站点里有什么

`gh-pages` 分支根目录即站点根目录（纯静态、无构建、无外部依赖）：

```
gh-pages/
├─ index.html            # 单页：导航 / Hero / 三卖点 / 截图画廊 / 皮肤 / 功能矩阵 / 隐私 / 下载 / FAQ
├─ 404.html              # GitHub Pages 根目录 404 页
├─ site.webmanifest      # PWA manifest（图标、主题色）
├─ sitemap.xml / robots.txt
├─ .nojekyll             # 关闭 Jekyll，静态文件原样发布
└─ assets/
   ├─ style.css          # 设计系统 + 布局（深浅色用 data-theme）
   ├─ app.js             # 主题切换 / 移动菜单 / 滚动进场 / 截图大图 / 复制 SHA-256 / FAQ
   ├─ og.png             # 社交分享卡（1200×630）
   ├─ favicon-16.png、favicon-32.png、apple-touch-icon.png
   └─ screens/*.webp     # 9 张演示账本截图（720×1600，WebP q82，共约 325 KB）
```

## 本地预览

```bash
cd MileNoteAndroid
git worktree add /tmp/milenote-pages gh-pages
python3 -m http.server 5190 --bind 127.0.0.1 --directory /tmp/milenote-pages
# 打开 http://127.0.0.1:5190/
```

验收：桌面 1440×900 / 移动 390×844、浅色 + 深色各过一遍；点截图能开大图；导航能跳区块；
下载区「复制」能拿到完整 SHA-256。

## 更新与发布

```bash
cd MileNoteAndroid
git worktree add /tmp/milenote-pages gh-pages     # 首次
# 改 /tmp/milenote-pages/index.html 或 assets/...
cd /tmp/milenote-pages
git add -A && git commit -m "deploy: 更新官网文案" && git push origin gh-pages
```

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
- **版本信息**三处同步改：
  1. `index.html` Hero 下的 `v0.27.0 beta · versionCode 60`；
  2. 下载卡的版本 / versionCode / SHA-256 / 包大小；
  3. `sitemap.xml` 的 `lastmod`。
- **APK 下载地址**用 GitHub Releases 固定 URL：
  `https://github.com/Selwiro/MileNoteAndroid/releases/download/v<版本>/milenote-<版本>-android.apk`。
  发新版时改 `index.html`（Hero 与下载区各一处）与 Release 资产名即可。
- **OG 分享图** `assets/og.png` 由应用图标 + 首屏截图合成；换主视觉时同步更新
  `index.html` 的 `og:image` 与 `twitter:image`（绝对 URL）。

## 设计约束（和 App 保持一致）

- 品牌基色：`#55758B`（里程蓝 seed），主色 `#446378` / 深色 `#2E4A5D` / Hero 渐变 `#2E4A5D → #7E9EAF`；
  圆角、留白与字阶按 Material 3 Expressive 的观感走。
- 字体只用系统字体栈（PingFang SC / Hiragino Sans GB / Microsoft YaHei / Noto Sans SC），不加载外部字体。
- 深浅色：`<html data-theme="light|dark">`；首屏前内联脚本读 `localStorage` 与 `prefers-color-scheme`
  以避免深色闪白，切换按钮写入 `milenote-theme`。
- 无 JS 时正文必须可见（`.reveal` 的隐藏样式挂在 `html.js` 下），截图与文字不依赖脚本渲染。
