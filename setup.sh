#!/bin/bash
#
# Butler AI - 一键构建 & 打包脚本
# 用法: bash setup.sh [android|ios|harmonyos|all]
# 默认: all
#
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 向上查找 Butler-main 中的后端源码
BACKEND_SRC=""
if [ -d "../butler_android/app/src/main/python" ]; then
    BACKEND_SRC="$(cd "../butler_android/app/src/main/python" && pwd)"
elif [ -d "../../butler_android/app/src/main/python" ]; then
    BACKEND_SRC="$(cd "../../butler_android/app/src/main/python" && pwd)"
fi

TARGET="${1:-all}"

echo -e "${CYAN}"
echo "╔══════════════════════════════════════════╗"
echo "║     Butler AI - 一键构建 & 打包          ║"
echo "╚══════════════════════════════════════════╝"
echo -e "${NC}"

# ── 1. 检查环境 ──
echo -e "${GREEN}[1/6] 检查环境...${NC}"

if ! command -v node &>/dev/null; then
    echo -e "${RED}错误: 未找到 Node.js，请先安装 (>=18)${NC}"
    exit 1
fi
echo -e "  Node.js: $(node -v)"

if ! command -v npm &>/dev/null; then
    echo -e "${RED}错误: 未找到 npm${NC}"
    exit 1
fi
echo -e "  npm: $(npm -v)"

# ── 2. 同步后端代码到 Android (Chaquopy Python) ──
echo -e "${GREEN}[2/6] 同步后端代码...${NC}"

if [ -n "$BACKEND_SRC" ] && [ -d "$BACKEND_SRC" ]; then
    ANDROID_PYTHON="android/app/src/main/python"
    mkdir -p "$ANDROID_PYTHON"

    # 复制入口文件
    if [ -f "$BACKEND_SRC/butler_android.py" ]; then
        cp "$BACKEND_SRC/butler_android.py" "$ANDROID_PYTHON/"
        echo -e "  ${GREEN}✓ butler_android.py${NC}"
    fi

    # 复制核心包
    for dir in butler package skills config programs; do
        if [ -d "$BACKEND_SRC/$dir" ]; then
            cp -r "$BACKEND_SRC/$dir" "$ANDROID_PYTHON/"
            count=$(find "$ANDROID_PYTHON/$dir" -type f 2>/dev/null | wc -l)
            echo -e "  ${GREEN}✓ $dir/ ($count 个文件)${NC}"
        fi
    done

    echo -e "  ${GREEN}后端代码同步完成 ($(find "$ANDROID_PYTHON" -type f | wc -l) 个文件)${NC}"
else
    echo -e "  ${YELLOW}⚠ 未找到后端源码目录 (butler_android/app/src/main/python)，跳过${NC}"
    echo -e "  ${YELLOW}  Android APK 将仅包含前端 UI，无后端引擎${NC}"
fi

# ── 3. 安装前端依赖 ──
echo -e "${GREEN}[3/6] 安装前端依赖...${NC}"
if [ ! -d "node_modules" ] || [ ! -f "node_modules/.package-lock.json" ]; then
    npm install
    echo -e "  ${GREEN}依赖安装完成${NC}"
else
    echo -e "  已安装，跳过"
fi

# ── 4. 构建 Web ──
echo -e "${GREEN}[4/6] 构建 Web 资源 (tsc + vite)...${NC}"
npm run build
echo -e "  ${GREEN}构建产物: dist/${NC}"

# ── 5. 同步到各平台 ──
echo -e "${GREEN}[5/6] 同步到目标平台: ${TARGET}${NC}"

sync_android() {
    echo -e "${CYAN}  → Android (Capacitor)...${NC}"
    npx cap sync android 2>&1 | tail -3
    echo -e "  ${GREEN}  Android 同步完成${NC}"
}

sync_ios() {
    echo -e "${CYAN}  → iOS (Capacitor)...${NC}"
    npx cap sync ios 2>&1 | tail -3
    echo -e "  ${GREEN}  iOS 同步完成${NC}"
}

sync_harmonyos() {
    echo -e "${CYAN}  → 鸿蒙 HarmonyOS (rawfile)...${NC}"
    bash harmonyos/sync-web-resources.sh
}

case "$TARGET" in
    android)  sync_android ;;
    ios)      sync_ios ;;
    harmonyos) sync_harmonyos ;;
    all)
        sync_android
        sync_ios
        sync_harmonyos
        ;;
    *)
        echo -e "${RED}未知目标: ${TARGET}${NC}"
        echo "用法: bash setup.sh [android|ios|harmonyos|all]"
        exit 1
        ;;
esac

# ── 6. 输出构建提示 ──
echo -e "${GREEN}[6/6] 构建原生包提示${NC}"
echo ""

if [ "$TARGET" = "all" ] || [ "$TARGET" = "android" ]; then
    echo -e "  ${CYAN}Android APK (含 Python 后端):${NC}"
    echo -e "    Debug:   cd android && ./gradlew assembleDebug"
    echo -e "    Release: cd android && ./gradlew assembleRelease"
    echo -e "    AAB:     cd android && ./gradlew bundleRelease"
    echo -e ""
    echo -e "  ${YELLOW}注意: Python pip 包将在首次 Gradle 构建时自动下载${NC}"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "ios" ]; then
    echo -e "  ${CYAN}iOS IPA (仅前端):${NC}"
    echo -e "    打开: npx cap open ios"
    echo -e "    ${YELLOW}iOS 不支持 Chaquopy Python，后端需通过远程服务器提供${NC}"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "harmonyos" ]; then
    echo -e "  ${CYAN}鸿蒙 HAP (仅前端):${NC}"
    echo -e "    在 DevEco Studio 中打开 harmonyos/ 目录构建"
    echo -e "    ${YELLOW}鸿蒙不支持 Python 内嵌，后端需通过远程服务器提供${NC}"
fi

echo ""
echo -e "${GREEN}╔══════════════════════════════════════════╗"
echo -e "║              全部完成!                       ║"
echo -e "╚══════════════════════════════════════════╝${NC}"
