# Butler AI 技术文档

## 项目概述

Butler AI 是一款跨平台智能助手应用，采用 TypeScript/Vite 构建 Web 前端界面，通过 Capacitor v8 打包为 Android/iOS 原生 APP，同时支持鸿蒙 HarmonyOS NEXT 的 ArkTS Web 组件方案。三平台共享同一份 Web 构建产物，核心业务代码无需针对不同平台做任何修改。

### 技术栈

| 层级 | 技术选型 | 版本 |
|------|---------|------|
| 前端语言 | TypeScript | 5.3+ |
| 构建工具 | Vite | 5.0+ |
| UI 样式 | 原生 CSS (CSS Variables + Glass Morphism) | - |
| 图标库 | Font Awesome | 6.4 |
| 字体 | Inter (Google Fonts CDN) | - |
| Android 打包 | Capacitor + Gradle | Capacitor 8.4 |
| iOS 打包 | Capacitor + Xcode + CocoaPods | Capacitor 8.4 |
| 鸿蒙打包 | ArkTS Web 组件 + DevEco Studio | API 12 |
| 后端通信 | WebSocket + REST API | - |
| 原生桥接 | @capacitor/app, haptics, status-bar, splash-screen | 8.0 |

---

## 系统架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                    用户设备 (手机/平板)                      │
│  ┌─────────────────────────────────────────────────────┐  │
│  │              原生 APP (Android/iOS/鸿蒙)               │  │
│  │  ┌───────────────────────────────────────────────┐ │  │
│  │  │              WebView (Blink/WebKit)             │ │  │
│  │  │  ┌─────────────────────────────────────────┐   │ │  │
│  │  │  │       TypeScript 前端 (dist/)             │   │ │  │
│  │  │  │  ┌──────────┐ ┌──────────┐ ┌─────────┐  │   │ │  │
│  │  │  │  │ 聊天模块   │ │ 技能模块  │ │ 终端模块 │  │   │ │  │
│  │  │  │  └──────────┘ └──────────┘ └─────────┘  │   │ │  │
│  │  │  │  ┌──────────┐ ┌──────────┐ ┌─────────┐  │   │ │  │
│  │  │  │  │ 时光监控  │ │ DAG画布   │ │ 设置模块 │  │   │ │  │
│  │  │  │  └──────────┘ └──────────┘ └─────────┘  │   │ │  │
│  │  │  │  ┌──────────┐ ┌──────────┐              │   │ │  │
│  │  │  │  │ 备忘录    │ │ 热力图   │              │   │ │  │
│  │  │  │  └──────────┘ └──────────┘              │   │ │  │
│  │  │  └─────────────────────────────────────────┘   │ │  │
│  │  └───────────────────────┬───────────────────────┘ │  │
│  │                          │                         │  │
│  │              ┌───────────┴───────────┐           │  │
│  │              │    通信服务层          │           │  │
│  │              │  ┌───────────────┐   │           │  │
│  │              │  │ WebSocket     │   │           │  │
│  │              │  │ (实时聊天/终端) │   │           │  │
│  │              │  ├───────────────┤   │           │  │
│  │              │  │ REST API      │   │           │  │
│  │              │  │ (配置/列表CRUD) │   │           │  │
│  │              │  └───────────────┘   │           │  │
│  │              └───────────┬───────────┘           │  │
│  └──────────────────────────┼────────────────────────┘  │
└─────────────────────────────┼───────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    │   Python 后端     │
                    │   (Butler Core)   │
                    │   WebSocket:8080  │
                    │   REST:8080/api   │
                    └───────────────────┘
```

### 前后端通信协议

| 通道 | 协议 | 地址 | 用途 |
|------|------|------|------|
| WebSocket | JSON over WS | `ws://localhost:8080/ws` | 实时聊天流式输出、技能执行、终端命令 |
| REST GET | HTTP | `http://localhost:8080/api/skills` | 获取技能列表 |
| REST GET | HTTP | `http://localhost:8080/api/settings` | 读取配置 |
| REST PUT | HTTP | `http://localhost:8080/api/settings` | 保存配置 |
| REST GET | HTTP | `http://localhost:8080/api/memos` | 获取备忘录列表 |
| REST POST | HTTP | `http://localhost:8080/api/memos` | 创建备忘录 |

### WebSocket 消息格式

**客户端发送:**

```json
{ "type": "chat", "message": "你好", "stream": true }
{ "type": "skill:run", "skillId": "sys-audit", "params": {} }
{ "type": "terminal", "command": "ls -la" }
```

**服务端响应:**

```json
{ "type": "chat_chunk", "request_id": "xxx", "chunk": "你" }
{ "type": "chat_end", "request_id": "xxx" }
{ "type": "chat_error", "request_id": "xxx", "message": "..." }
```

---

## 目录结构

```
butler-android/
├── index.html                  # 主入口 HTML (所有面板结构)
├── package.json                # 项目配置与 npm 脚本
├── tsconfig.json               # TypeScript 编译配置
├── vite.config.ts              # Vite 构建配置
├── capacitor.config.ts         # Capacitor 跨平台配置
├── setup.sh                    # 一键构建打包脚本
├── BUILD.md                    # 多平台打包指南
├── TECHNICAL.md                # 本技术文档
│
├── src/                        # TypeScript 源码
│   ├── main.ts                 # 主入口，初始化所有模块
│   ├── style.css               # 完整样式 (~1666 行)
│   │
│   ├── modules/                # 业务模块
│   │   ├── chat.ts            # 聊天管理 (消息发送/快捷操作)
│   │   ├── matrix.ts          # 状态矩阵 (Tab切换/Dynamic Island)
│   │   ├── settings.ts        # 设置管理 (主题/字体/模糊度/API配置)
│   │   ├── skills.ts          # 技能卡片网格渲染与运行
│   │   ├── terminal.ts        # 终端覆盖层 (命令输入/输出)
│   │   ├── memos.ts           # 备忘录覆盖层 (搜索/CRUD)
│   │   ├── timemachine.ts     # 时光监控 (指标卡片/历史日志)
│   │   ├── dag.ts             # DAG 画布引擎 (启动/暂停/清空)
│   │   └── heatmap.ts         # 背景热力图 Canvas 动画
│   │
│   └── services/              # 服务层
│       ├── websocket.ts       # WebSocket 连接管理 (自动重连)
│       ├── api.ts             # REST + WebSocket API 客户端
│       └── notification.ts    # Toast 通知系统 (4种类型)
│
├── android/                    # Capacitor Android 原生工程
│   ├── build.gradle            # Gradle 根配置
│   ├── settings.gradle         # 项目设置
│   ├── gradle.properties       # Gradle 属性
│   ├── capacitor.settings.gradle  # Capacitor 插件管理
│   ├── variables.gradle        # 版本变量
│   ├── gradlew / gradlew.bat   # Gradle Wrapper
│   └── app/
│       ├── build.gradle        # App 模块构建配置
│       ├── src/main/
│       │   ├── AndroidManifest.xml  # 权限声明
│       │   ├── java/.../MainActivity.java  # 入口 Activity
│       │   ├── res/            # 图标/启动图/布局资源
│       │   └── assets/         # Web 资源 (Capacitor 同步)
│       └── proguard-rules.pro   # 混淆规则
│
├── ios/                        # Capacitor iOS 原生工程
│   ├── .gitignore
│   └── App/
│       ├── Podfile             # CocoaPods 依赖
│       ├── App/
│       │   ├── AppDelegate.swift       # 应用委托
│       │   ├── SceneDelegate.swift       # 场景委托 (CAPBridgeViewController)
│       │   ├── Butler-Bridging-Header.h  # ObjC-Swift 桥接
│       │   ├── Info.plist               # 应用配置 (暗色/权限)
│       │   ├── capacitor.config.json    # Capacitor JSON 配置
│       │   ├── Base.lproj/              # Storyboard (启动画面/主画面)
│       │   ├── Assets.xcassets/         # App图标/启动图
│       │   └── Configuration/          # 扩展配置
│       └── App.xcodeproj/       # Xcode 工程 (含 Scheme)
│
└── harmonyos/                  # 鸿蒙 HarmonyOS NEXT 原生工程
    ├── build-profile.json5      # 工程构建配置
    ├── hvigorfile.ts            # Hvigor 构建脚本
    ├── oh-package.json5         # 包配置
    ├── sync-web-resources.sh    # Web 资源同步脚本
    ├── AppScope/
    │   ├── app.json5            # 应用全局配置
    │   └── resources/           # 应用级资源
    └── entry/
        ├── build-profile.json5  # 模块构建配置
        ├── module.json5         # 模块配置 (Ability + 权限)
        ├── oh-package.json5     # 模块包配置
        └── src/main/
            ├── ets/
            │   ├── entryability/EntryAbility.ets  # 入口 Ability
            │   └── pages/Index.ets               # 主页面 (Web组件)
            └── resources/
                ├── base/        # 基础资源 (字符串/颜色)
                ├── dark/        # 暗色主题
                └── rawfile/    # Web资源 (dist同步目标)
```

---

## 模块说明

### 前端模块

#### StateMatrix (`matrix.ts`)

全局 UI 状态管理器，负责：
- Dock 导航栏的 Tab 切换逻辑
- 面板的显示/隐藏切换
- 设置页内部 Tab 切换
- Dynamic Island 顶部状态栏文本更新与动画
- 终端和备忘录覆盖层的关闭按钮绑定

#### ChatManager (`chat.ts`)

聊天功能核心模块：
- 快捷操作卡片点击事件绑定
- 输入框自适应高度 (max 120px)
- Shift+Enter 换行，Enter 发送
- 发送消息时隐藏欢迎页、显示用户气泡、显示思考指示器
- 通过 ButlerAPI.chat() 将消息发送到后端
- 后端不可用时显示 demo 回复

#### ButlerAPI (`api.ts`)

统一 API 客户端，双通道通信：
- WebSocket 通道：chat, skill:run, terminal (实时场景)
- REST 通道：getSkills, getSettings, saveSettings, getMemos, saveMemo (CRUD 场景)
- 所有 REST 请求自带 catch 降级，返回空数据不会中断流程

#### WebSocketService (`websocket.ts`)

WebSocket 连接管理：
- 自动连接到 `ws://localhost:8080/ws`
- 断线自动重连 (3秒间隔)
- 事件发布/订阅机制 (on/off/emit)
- JSON 消息自动解析

#### SettingsManager (`settings.ts`)

设置管理：
- 深浅色主题切换 (document.body.className)
- 模型配置保存/恢复 (localStorage 持久化)
- 字体大小动态调整 (CSS 变量 --font-size-base)
- 高斯模糊度动态调整 (CSS 变量 --glass-blur)

#### SkillsManager (`skills.ts`)

技能卡片网格：
- 渲染 9 个内置技能卡片 (图标 + 名称 + 描述)
- 运行按钮绑定 api.runSkill()
- 后端在线时可从 API 动态加载技能列表

#### HeatmapRenderer (`heatmap.ts`)

背景视觉效果：
- Canvas 2D 绘制 20 个径向渐变光斑
- 随机运动 + 碰壁反弹动画循环
- 4 种低透明度颜色 (蓝/紫/绿/橙)
- 自动跟随窗口 resize

### 通信机制

前端与后端采用双通道架构：

1. **WebSocket** — 适合需要实时推送的场景
   - 聊天对话 (逐字流式输出)
   - 技能执行 (实时状态反馈)
   - 终端命令 (实时输出)

2. **REST API** — 适合请求-响应场景
   - 配置读写 (GET/PUT)
   - 列表获取 (skills, memos)
   - 数据创建 (POST memos)

降级策略：当后端不可用时，各模块自动使用内置 demo 数据，界面不会白屏。

---

## 各平台实现细节

### Android (Capacitor v8)

- **WebView**: Android System WebView (Blink 引擎)
- **最小 SDK**: 24 (Android 7.0)
- **目标 SDK**: 34 (Android 14)
- **签名方案**: debug 签名 (可配置 release 密钥)
- **权限**: 网络、存储、麦克风、通知、前台服务、唤醒锁、振动
- **启动画面**: Capacitor SplashScreen 插件 (1.5s)
- **WebView 配置**: 允许混合内容、HTTPS scheme、背景色 #0a0a0f
- **构建命令**: `./gradlew assembleDebug` / `./gradlew assembleRelease`
- **输出**: APK (android/app/build/outputs/apk/)

### iOS (Capacitor v8)

- **WebView**: WKWebView (WebKit 引擎)
- **最低版本**: iOS 15.0
- **状态栏**: 隐藏 (UIStatusBarHidden)
- **界面风格**: 暗色 (UIUserInterfaceStyle = Dark)
- **方向支持**: 竖屏 + 左横屏 + 右横屏
- **网络**: 允许 HTTP 明文 (NSAllowsArbitraryLoads)
- **权限**: 麦克风、相机、相册
- **依赖管理**: CocoaPods
- **构建**: Xcode Archive → Distribute App
- **输出**: IPA

### 鸿蒙 HarmonyOS NEXT

- **WebView**: ArkTS Web 组件 (系统 WebKit 内核)
- **API 版本**: 12 (5.0.0)
- **资源加载**: `$rawfile('index.html')` 从 rawfile 目录加载本地资源
- **配置**: DOM Storage、数据库访问、文件访问、JavaScript 执行全部启用
- **混合模式**: MixedMode.All (允许 HTTP 资源)
- **权限**: ohos.permission.INTERNET, ohos.permission.GET_NETWORK_INFO
- **资源同步**: `sync-web-resources.sh` 脚本自动将 dist/ 复制到 rawfile/ 并修正路径
- **构建**: DevEco Studio 或 hvigorw
- **输出**: HAP (entry/build/default/outputs/) / APP (上架)

---

## 构建与部署

### 一键构建

```bash
cd butler-android
bash setup.sh              # 构建并同步到所有平台
bash setup.sh android      # 只构建 Android
bash setup.sh ios          # 只构建 iOS
bash setup.sh harmonyos    # 只构建鸿蒙
```

脚本自动执行：
1. 检查 Node.js/npm 环境
2. `npm install` 安装依赖
3. `npm run build` 构建 Web 资源 (tsc + vite)
4. 同步到目标平台的 native 工程
5. 输出各平台原生包构建命令

### 手动构建

```bash
# 安装依赖
npm install

# 构建 Web
npm run build

# 同步到各平台
npm run sync:android
npm run sync:ios
npm run sync:harmonyos

# 构建原生包
cd android && ./gradlew assembleRelease    # Android APK
npx cap open ios                              # iOS (Xcode)
# 鸿蒙在 DevEco Studio 中打开 harmonyos/ 目录
```

### Vite 构建配置要点

`vite.config.ts` 中的 `base: './'` 是关键配置：
- 确保所有资源引用使用相对路径 (`./assets/xxx.js`)
- 鸿蒙 Web 组件要求资源路径必须是相对路径
- Capacitor WebView 同样需要相对路径才能正确加载本地资源
- 构建产物输出到 `dist/` 目录

---

## Capacitor 插件

| 插件 | 功能 | 使用场景 |
|------|------|---------|
| @capacitor/app | 应用生命周期管理 | 监听前后台切换 |
| @capacitor/haptics | 触觉反馈 | 按钮点击反馈 |
| @capacitor/status-bar | 状态栏控制 | 深色模式状态栏适配 |
| @capacitor/splash-screen | 启动画面 | 冷启动过渡 |

在 TypeScript 中调用：

```typescript
import { App } from '@capacitor/app';
import { Haptics, ImpactStyle } from '@capacitor/haptics';
import { StatusBar, Style } from '@capacitor/status-bar';

// 监听应用状态
App.addListener('appStateChange', ({ isActive }) => {
  console.log('App active:', isActive);
});

// 触觉反馈
await Haptics.impact({ style: ImpactStyle.Medium });

// 状态栏
await StatusBar.setStyle({ style: Style.Dark });
await StatusBar.setBackgroundColor({ color: '#0a0a0f' });
```

---

## UI 设计规范

### 颜色体系

采用 CSS Variables 定义的深色主题颜色体系，所有颜色通过变量引用：

```css
:root {
  --bg-primary: #0a0a0f;
  --bg-secondary: #12121a;
  --surface: rgba(255, 255, 255, 0.06);
  --surface-hover: rgba(255, 255, 255, 0.1);
  --text-primary: #e6e6f0;
  --text-secondary: #8888a0;
  --accent-blue: #007AFF;
  --accent-orange: #FF9500;
  --accent-purple: #AF52DE;
  --accent-green: #34C759;
  --accent-red: #FF3B30;
  --glass-blur: 30px;
}
```

### 响应式断点

| 断点 | 适配设备 | 布局调整 |
|------|---------|---------|
| ≤ 375px | iPhone SE / 小屏手机 | Dock 图标缩小，面板全屏 |
| ≤ 428px | iPhone 14 Pro / 标准手机 | 主要适配尺寸 |
| ≤ 768px | iPad mini / 大屏手机 | 侧边栏横向滚动 |
| > 768px | iPad / 桌面 | 双栏布局 |

### 核心组件

- **Floating Dock**: 底部固定导航栏，类似 macOS Dock，带活动指示点
- **Dynamic Island**: 顶部状态条，带展开动画
- **Glass Surface**: 毛玻璃拟态面板 (backdrop-filter: blur)
- **Panel**: 全屏面板，带窗口控制按钮 (红黄绿三色圆点)
- **Chat Bubble**: 用户消息右对齐蓝色，机器人消息左对齐玻璃色
- **Quick Action Card**: 2x2 快捷操作网格
- **Skill Card**: 图标 + 名称 + 描述 + 运行按钮
- **Terminal**: 暗色覆盖层，monospace 字体
- **Toast Notification**: 4 种类型 (info/success/warning/error)

---

## 环境要求

### 通用

| 工具 | 最低版本 | 用途 |
|------|---------|------|
| Node.js | 18.0 | 运行构建脚本 |
| npm | 9.0 | 包管理 |

### Android

| 工具 | 版本 | 用途 |
|------|------|------|
| Android Studio | Hedgehog+ | IDE / 构建工具 |
| JDK | 17 | Gradle 编译 |
| Android SDK | 34 | 目标平台 |
| Gradle | 8.5+ | 构建系统 |

### iOS

| 工具 | 版本 | 用途 |
|------|------|------|
| macOS | 13+ | 构建环境 |
| Xcode | 15+ | IDE / 编译器 |
| CocoaPods | 1.15+ | 依赖管理 |
| Apple Developer | - | 签名与分发 |

### 鸿蒙

| 工具 | 版本 | 用途 |
|------|------|------|
| DevEco Studio | 5.0+ | IDE |
| HarmonyOS SDK | 5.0.0 (API 12) | 目标平台 |
| Hvigor | - | 构建系统 |

---

## 常见问题

**Q: Vite 构建后页面空白？**
检查 `vite.config.ts` 中 `base` 是否为 `'./'` (相对路径)，以及 `dist/` 目录是否包含 `index.html` 和 `assets/`。

**Q: Android WebView 白屏？**
确认 `android/app/src/main/assets/public/` 目录下有 `index.html` 和 `assets/` 子目录。运行 `npm run sync:android` 重新同步。

**Q: iOS 构建找不到 Pods？**
在 `ios/App/` 目录执行 `pod install`，然后用 `App.xcworkspace` (不是 .xcodeproj) 打开。

**Q: 鸿蒙 Web 组件白屏？**
运行 `npm run sync:harmonyos`，确认 `harmonyos/entry/src/main/resources/rawfile/index.html` 存在。检查 `index.html` 中资源路径是否为 `./assets/` (相对路径)。

**Q: WebSocket 连接失败？**
确认后端服务运行在 `localhost:8080`。APP 内 WebView 默认可访问 localhost。如果后端在局域网其他设备，需要修改 `src/services/websocket.ts` 中的 URL。

**Q: 如何添加新的 Capacitor 插件？**
`npm install @capacitor/camera` 然后 `npx cap sync`，插件会自动安装到 Android/iOS 原生工程。鸿蒙不支持 Capacitor 插件，需要通过 ArkTS 原生实现。
