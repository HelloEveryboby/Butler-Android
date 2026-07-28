# Butler-Android: 桌面 Skill 迁移至 Android 的完整实现

将 Butler 桌面端的 Python Skills 自动转换为 Android 可运行格式，包含智能分类打包、C 扩展交叉编译、Gradle 集成、PyBridge 运行时和 Skill 市场。

## 项目结构

```
butler-android/
├── smart_skill_builder.py          # 智能打包流水线（核心入口）
├── scripts/
│   ├── pybridge-build-android.sh   # 交叉编译 Android CPython
│   └── pybridge-build-packages.sh  # 交叉编译 C 扩展包
├── pybridge-gradle-plugin/         # Gradle 插件（自动集成预编译包）
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/pybridge/
│       ├── PyBridgeExtension.kt    # DSL 扩展
│       └── PyBridgePlugin.kt       # 插件主类
├── pybridge-runtime/               # Android Python 运行时
│   └── src/main/
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   └── pybridge_jni.cpp    # JNI 桥接层
│       └── python/pybridge/
│           ├── __init__.py         # 运行时入口
│           └── skill_loader.py     # Skill 加载器
├── skill-market/                   # Skill 分发市场
│   ├── backend/                    # FastAPI 后端
│   │   ├── app.py
│   │   ├── storage.py
│   │   ├── models.py
│   │   ├── config.py
│   │   └── requirements.txt
│   └── client/                     # Android Kotlin 客户端
│       ├── SkillMarketManager.kt
│       ├── SkillModels.kt
│       ├── PyBridgeInterface.kt
│       └── SkillMarketViewModel.kt
└── tests/
    ├── test_skill_builder.py       # 单元测试（14 项）
    └── test_e2e.sh                 # 端到端测试（31 项）
```

## 快速开始

### 1. 智能打包 Skill

```bash
# 批量打包所有 skill（自动区分纯 Python / C 扩展）
python smart_skill_builder.py butler/skills/ -o android_skills/

# 配置 NDK 后打包（C 扩展自动交叉编译）
python smart_skill_builder.py butler/skills/ --ndk ~/android-ndk-r26b -o android_skills/
```

### 2. 交叉编译 C 扩展（可选，需 NDK）

```bash
# 编译 Android CPython
bash scripts/pybridge-build-android.sh --ndk ~/android-ndk-r26b --abi arm64-v8a

# 编译 C 扩展包
bash scripts/pybridge-build-packages.sh --ndk ~/android-ndk-r26b --abi arm64-v8a
```

### 3. 启动 Skill 市场

```bash
cd skill-market/backend
pip install -r requirements.txt
python app.py
```

### 4. 运行测试

```bash
bash tests/test_e2e.sh
```

## 智能分类逻辑

| 检测维度 | 方法 | 示例 |
|---------|------|------|
| 依赖名匹配 | C 扩展数据库（18+ 包） | `Pillow` → 需编译 |
| C 源文件扫描 | 扫描 `.c/.cpp/.h` | skill 自带 C 代码 → 需编译 |
| import 分析 | AST 解析 | `import fitz` → PyMuPDF |
| 纯 Python 白名单 | 20+ 已知包 | `pdfplumber` → 直接打包 |

## .bsk 包格式

```
skill_name.bsk (ZIP)
├── manifest.json      # 元数据（skill_type, dependencies, arch）
├── __init__.py        # Android 入口包装器（run() 函数）
├── deps_map.json      # C 扩展编译结果（仅 c_extension 类型）
└── skill/             # 原始 skill 代码
    ├── main.py
    └── SKILL.md
```

## 技术栈

- **交叉编译**: Android NDK r26b + Clang + sysconfigdata
- **Android 集成**: Gradle Plugin 8.x + CMake + JNI
- **Python 运行时**: CPython 3.12 + importlib 动态加载
- **市场后端**: FastAPI + Pydantic v2
- **市场客户端**: Kotlin + OkHttp + Coroutines + MVVM
