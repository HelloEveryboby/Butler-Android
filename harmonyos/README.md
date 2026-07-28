# Butler AI - HarmonyOS NEXT 原生工程

本工程使用 ArkTS 的 Web 组件加载本地 Web 资源（来自 Vite 构建的 `dist` 目录），将 TypeScript Web 应用打包成鸿蒙 HarmonyOS NEXT 原生 APP。

## 目录结构

```
harmonyos/
├── AppScope/                          # 应用级配置
│   ├── app.json5                      # 应用全局配置（bundleName、版本等）
│   └── resources/
│       └── base/
│           ├── element/
│           │   └── string.json        # 应用级字符串资源
│           └── media/
│               └── app_icon.png       # 应用图标
├── entry/                             # Entry 模块（主模块）
│   ├── build-profile.json5           # 模块构建配置
│   ├── hvigorfile.ts                 # 模块 Hvigor 构建脚本
│   ├── oh-package.json5              # 模块包配置
│   └── src/main/
│       ├── module.json5              # 模块配置（Ability、权限等）
│       ├── ets/                       # ArkTS 源码
│       │   ├── entryability/
│       │   │   └── EntryAbility.ets  # 入口 Ability（UIAbility）
│       │   └── pages/
│       │       └── Index.ets         # 主页面（Web 组件加载本地 HTML）
│       └── resources/
│           ├── base/
│           │   ├── element/
│           │   │   ├── string.json   # 模块字符串资源
│           │   │   └── color.json    # 颜色资源
│           │   ├── media/
│           │   │   └── app_icon.png  # 模块图标
│           │   └── profile/
│           │       └── main_pages.json # 页面路由配置
│           ├── dark/
│           │   └── element/
│           │       └── color.json    # 暗色主题颜色
│           └── rawfile/              # Web 资源目录（dist 内容放这里）
│               └── .gitkeep
├── build-profile.json5               # 工程级构建配置
├── hvigorfile.ts                     # 工程级 Hvigor 构建脚本
├── oh-package.json5                  # 工程级包配置
├── sync-web-resources.sh             # Web 资源同步脚本
├── .gitignore
└── README.md
```

## 前置条件

1. **DevEco Studio 5.0+**（鸿蒙官方 IDE）
   - 下载地址：https://developer.huawei.com/consumer/cn/deveco-studio/
2. **HarmonyOS NEXT SDK**（API 12 / 5.0.0）
   - 通过 DevEco Studio 的 SDK Manager 安装
3. **Node.js 18+**（用于构建 Web 资源）
4. **Vite**（Web 应用构建工具）

## 快速开始

### 第一步：构建 Web 资源

在项目根目录（`butler-android/`）下执行 Vite 构建：

```bash
cd /path/to/butler-android
npm install        # 安装依赖（首次）
npm run build      # 构建到 dist/ 目录
```

构建完成后，`dist/` 目录包含：
```
dist/
├── index.html
└── assets/
    ├── index-xxxx.js
    └── index-xxxx.css
```

### 第二步：同步 Web 资源到 rawfile

使用提供的同步脚本自动完成：

```bash
cd harmonyos/
./sync-web-resources.sh
```

该脚本会：
1. 将 `../dist/` 目录的所有内容复制到 `entry/src/main/resources/rawfile/`
2. 自动将 `index.html` 中的绝对路径（`/assets/...`）修正为相对路径（`./assets/...`）

**手动同步方法**（如果不使用脚本）：

```bash
# 1. 清空旧的 rawfile 内容（保留 .gitkeep）
rm -rf entry/src/main/resources/rawfile/assets
rm -f entry/src/main/resources/rawfile/index.html

# 2. 复制 dist 内容到 rawfile
cp -r ../dist/* entry/src/main/resources/rawfile/

# 3. 修正 index.html 中的资源路径（重要！）
#    将 src="/assets/..." 改为 src="./assets/..."
#    将 href="/assets/..." 改为 href="./assets/..."
sed -i 's|"/assets/|"./assets/|g' entry/src/main/resources/rawfile/index.html
```

> **重要提示**：Vite 默认使用绝对路径（`/assets/...`）引用资源。在鸿蒙 Web 组件中通过 `$rawfile('index.html')` 加载本地资源时，绝对路径无法正确解析。必须将路径改为相对路径（`./assets/...`），否则 JS 和 CSS 文件将加载失败，页面显示空白。

### 第三步：在 DevEco Studio 中打开并构建

1. 打开 DevEco Studio
2. 选择 `File > Open`，定位到 `harmonyos/` 目录并打开
3. 等待工程同步完成（DevEco 会自动下载依赖）
4. 连接鸿蒙设备或启动模拟器
5. 点击 `Run` 按钮或使用快捷键 `Shift+F10` 构建并运行

### 命令行构建（可选）

```bash
# 在 harmonyos/ 目录下执行
# Debug 构建
hvigorw assembleHap --mode module -p product=default -p buildMode=debug

# Release 构建
hvigorw assembleHap --mode module -p product=default -p buildMode=release
```

## 核心实现说明

### Web 组件加载本地资源

核心文件 `entry/src/main/ets/pages/Index.ets` 使用 ArkTS 的 `Web` 组件通过 `$rawfile('index.html')` 加载本地 HTML：

```typescript
Web({ src: $rawfile('index.html'), controller: this.controller })
  .domStorageAccess(true)       // 启用 DOM Storage
  .databaseAccess(true)         // 启用数据库访问
  .fileAccess(true)             // 启用文件访问
  .mixedMode(MixedMode.All)     // 允许 HTTPS 页面加载 HTTP 资源
  .javaScriptAccess(true)       // 启用 JavaScript
  .zoomAccess(false)            // 禁用缩放
```

### 网络权限

`module.json5` 中声明了网络权限，确保 Web 应用可以发起网络请求（如 API 调用）：

```json5
"requestPermissions": [
  { "name": "ohos.permission.INTERNET" },
  { "name": "ohos.permission.GET_NETWORK_INFO" }
]
```

### 应用配置

- **bundleName**: `com.butler.app`
- **版本**: 1.0.0 (versionCode: 1000000)
- **目标 SDK**: HarmonyOS NEXT API 12 (5.0.0)
- **支持设备**: phone, tablet

## Vite 构建配置建议

为确保 Web 资源在鸿蒙 Web 组件中正确加载，建议在项目的 `vite.config.ts` 中设置 `base` 为相对路径：

```typescript
import { defineConfig } from 'vite';

export default defineConfig({
  base: './',  // 使用相对路径，避免绝对路径在 rawfile 中无法解析
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
  },
});
```

设置 `base: './'` 后，Vite 构建输出的 `index.html` 会自动使用相对路径（`./assets/...`），无需同步脚本再做路径修正。

## 常见问题

### Q: 页面显示空白？

**A:** 检查 `rawfile/index.html` 中的资源路径是否为相对路径。如果路径是 `/assets/...`（绝对路径），请运行 `./sync-web-resources.sh` 或手动修正为 `./assets/...`。

### Q: Web 应用无法访问网络 API？

**A:** 确认 `module.json5` 中已声明 `ohos.permission.INTERNET` 权限。如果 API 使用 HTTP（非 HTTPS），需要确保 Web 组件设置了 `.mixedMode(MixedMode.All)`。

### Q: 如何调试 Web 内容？

**A:** 在 DevEco Studio 中运行应用后，可以通过 `hilog` 查看原生侧日志。Web 侧的 `console.log` 输出也可以在 hilog 中查看。也可以在 Web 组件上调用 `this.controller.runJavaScript('...')` 执行调试代码。

### Q: 如何更新 Web 资源？

**A:** 每次修改 Web 代码后，重新执行 `npm run build`，然后运行 `./sync-web-resources.sh` 同步到 rawfile，最后在 DevEco Studio 中重新构建运行。

## 技术栈

- **HarmonyOS NEXT** (API 12 / 5.0.0)
- **ArkTS** / **ArkUI** (声明式 UI 框架)
- **ArkWeb** (Web 组件)
- **Stage 模型** (Ability 框架)
- **Vite** (Web 资源构建)
