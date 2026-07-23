# Butler AI - 多平台打包指南

> 将 TypeScript/Vite Web 应用打包为 iOS、Android、鸿蒙 HarmonyOS 原生 APP

## 目录结构

```
butler-android/
├── src/                    # TypeScript 源码
├── dist/                   # Vite 构建产物 (所有平台共用)
├── android/                # Capacitor Android 原生工程
├── ios/                    # Capacitor iOS 原生工程
├── harmonyos/              # 鸿蒙 HarmonyOS NEXT 原生工程
├── capacitor.config.ts     # Capacitor 配置
├── vite.config.ts          # Vite 构建配置 (base: './' 相对路径)
├── package.json            # 项目依赖与脚本
└── index.html              # Web 入口
```

## 架构说明

| 平台 | 方案 | 原理 |
|------|------|------|
| Android | Capacitor v8 | WebView 包装 + 原生桥接 |
| iOS | Capacitor v8 | WKWebView 包装 + 原生桥接 |
| 鸿蒙 | HarmonyOS NEXT ArkTS | Web 组件加载 rawfile 本地资源 |

三个平台共享同一份 Web 构建产物 (`dist/`)，无需修改业务代码。

---

## 快速开始

### 1. 构建 Web 资源

```bash
npm install
npm run build
```

构建产物在 `dist/` 目录。

### 2. 同步到所有平台

```bash
npm run sync:all
```

此命令会：
- 构建 Web 资源到 `dist/`
- 同步到 `android/app/src/main/assets/public/`
- 同步到 `ios/App/App/public/`
- 同步到 `harmonyos/entry/src/main/resources/rawfile/`

---

## Android 打包

### 环境要求

- Android Studio (Hedgehog 或更新)
- JDK 17
- Android SDK 34
- Gradle 8.5+

### 构建 APK

```bash
# 同步 Web 资源
npm run sync:android

# 构建 Debug APK
npm run build:android-debug
# 输出: android/app/build/outputs/apk/debug/app-debug.apk

# 构建 Release APK
npm run build:android
# 输出: android/app/build/outputs/apk/release/app-release.apk
```

### 在 Android Studio 中打开

```bash
npm run open:android
```

### 生成签名密钥

```bash
keytool -genkey -v -keystore butler-release.keystore -alias butler -keyalg RSA -keysize 2048 -validity 10000
```

在 `android/app/build.gradle` 中配置签名：

```groovy
android {
    signingConfigs {
        release {
            storeFile file('../../butler-release.keystore')
            storePassword 'your-password'
            keyAlias 'butler'
            keyPassword 'your-password'
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

### 构建 AAB (Google Play 上架)

```bash
cd android
./gradlew bundleRelease
# 输出: android/app/build/outputs/bundle/release/app-release.aab
```

---

## iOS 打包

### 环境要求

- macOS (必须)
- Xcode 15+
- CocoaPods 1.15+
- Apple Developer 账号

### 构建

```bash
# 1. 同步 Web 资源
npm run sync:ios

# 2. 安装 Pods
cd ios/App
pod install

# 3. 打开 Xcode
cd ../..
npx cap open ios
```

### Xcode 配置

1. 在 Xcode 中选择 App target
2. 设置 Signing & Capabilities → Team (Apple Developer)
3. 修改 Bundle Identifier (如需)
4. 选择目标设备/模拟器
5. `Cmd + R` 运行

### 打包 IPA

1. Xcode → Product → Archive
2. 在 Organizer 中选择 Archive → Distribute App
3. 选择 App Store Connect (上架) 或 Ad Hoc (测试) 或 Enterprise

### 命令行构建

```bash
cd ios/App
xcodebuild -workspace App.xcworkspace -scheme App -configuration Release -archivePath build/Butler.xcarchive archive
xcodebuild -exportArchive -archivePath build/Butler.xcarchive -exportPath build/ipa -exportOptionsPlist exportOptions.plist
```

---

## 鸿蒙 HarmonyOS 打包

### 环境要求

- DevEco Studio 5.0+ (鸿蒙官方 IDE)
- HarmonyOS SDK 5.0.0 (API 12)
- Windows 10/11 或 macOS

### 同步 Web 资源

```bash
npm run sync:harmonyos
```

此命令将 `dist/` 内容复制到 `harmonyos/entry/src/main/resources/rawfile/`，并自动修正资源路径为相对路径。

### 在 DevEco Studio 中构建

1. 打开 DevEco Studio
2. File → Open → 选择 `harmonyos/` 目录
3. 等待工程同步完成
4. 配置签名：File → Project Structure → Signing Configs → 自动签名
5. 选择目标设备/模拟器
6. 点击 Run 或 `Ctrl+R` 运行

### 命令行构建

```bash
cd harmonyos

# Debug 构建
hvigorw assembleHap --mode module -p product=default -p buildMode=debug

# Release 构建
hvigorw assembleHap --mode module -p product=default -p buildMode=release

# 输出: entry/build/default/outputs/default/entry-default-signed.hap
```

### 生成 APP (上架华为应用市场)

```bash
# 需要先在 AGC (AppGallery Connect) 创建项目和签名证书
hvigorw assembleApp --mode project -p product=default -p buildMode=release
# 输出: build/outputs/default/butler-default-signed.app
```

---

## NPM 脚本速查

| 命令 | 说明 |
|------|------|
| `npm run dev` | 启动 Vite 开发服务器 |
| `npm run build` | 构建 Web 资源到 dist/ |
| `npm run sync:all` | 同步到所有平台 |
| `npm run sync:android` | 仅同步到 Android |
| `npm run sync:ios` | 仅同步到 iOS |
| `npm run sync:harmonyos` | 仅同步到鸿蒙 |
| `npm run open:android` | 在 Android Studio 中打开 |
| `npm run open:ios` | 在 Xcode 中打开 |
| `npm run build:android` | 构建 Android Release APK |
| `npm run build:android-debug` | 构建 Android Debug APK |
| `npm run build:ios` | 同步并准备 iOS 构建 |
| `npm run build:harmonyos` | 同步并准备鸿蒙构建 |

---

## 开发工作流

```
┌─────────────┐     ┌──────────┐     ┌─────────────────────────┐
│  开发 Web UI │────▶│ Vite 构建 │────▶│      dist/ 目录          │
│  (src/*.ts)  │     │ tsc+vite │     │  index.html + assets/   │
└─────────────┘     └──────────┘     └───────────┬─────────────┘
                                                  │
                    ┌─────────────────────────────┼──────────────────┐
                    │                             │                  │
                    ▼                             ▼                  ▼
           ┌──────────────┐            ┌──────────────┐    ┌──────────────┐
           │   Android    │            │     iOS      │    │   鸿蒙       │
           │  Capacitor   │            │  Capacitor   │    │  ArkTS Web   │
           │  WebView     │            │  WKWebView   │    │  Component   │
           └──────┬───────┘            └──────┬───────┘    └──────┬───────┘
                  │                           │                   │
                  ▼                           ▼                   ▼
           app-release.apk            Butler.ipa            entry-default.hap
```

### 日常开发流程

1. 修改 `src/` 中的 TypeScript 代码
2. `npm run dev` 实时预览
3. 满意后 `npm run build` 构建
4. `npm run sync:all` 同步到所有平台
5. 在各平台 IDE 中运行/打包

---

## Capacitor 插件 (原生能力)

已集成的 Capacitor 插件：

| 插件 | 功能 | 平台 |
|------|------|------|
| `@capacitor/app` | 应用生命周期管理 | Android, iOS |
| `@capacitor/haptics` | 触觉反馈 (振动) | Android, iOS |
| `@capacitor/status-bar` | 状态栏控制 | Android, iOS |
| `@capacitor/splash-screen` | 启动画面 | Android, iOS |

在 TypeScript 中使用：

```typescript
import { App } from '@capacitor/app';
import { Haptics, ImpactStyle } from '@capacitor/haptics';
import { StatusBar, Style } from '@capacitor/status-bar';

// 监听应用状态
App.addListener('appStateChange', ({ isActive }) => {
  console.log('App state:', isActive);
});

// 触觉反馈
await Haptics.impact({ style: ImpactStyle.Medium });

// 设置状态栏
await StatusBar.setStyle({ style: Style.Dark });
await StatusBar.setBackgroundColor({ color: '#0a0a0f' });
```

---

## 常见问题

### Q: Web 资源加载 404？
A: 确保 `vite.config.ts` 中 `base: './'` (相对路径)。鸿蒙需要运行 `sync:harmonyos` 脚本自动修正路径。

### Q: Android 网络请求失败？
A: `AndroidManifest.xml` 已配置 `usesCleartextTraffic="true"`，允许 HTTP 请求。

### Q: iOS 构建报错找不到 Pods？
A: 运行 `cd ios/App && pod install`，然后用 `.xcworkspace` 打开 (不是 `.xcodeproj`)。

### Q: 鸿蒙 Web 组件白屏？
A: 检查 `rawfile/` 目录是否有 `index.html`，运行 `npm run sync:harmonyos` 重新同步。

### Q: 如何添加更多 Capacitor 插件？
A: `npm install @capacitor/camera`，然后 `npx cap sync` 同步到原生工程。
