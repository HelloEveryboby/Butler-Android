#!/usr/bin/env bash
#
# pybridge-build-android.sh
# ============================================================================
# 交叉编译 Android 版 CPython（基于 Android NDK r26b 的 llvm/clang 工具链）。
#
# 功能：
#   - 下载并编译指定版本的 CPython（默认 3.12.3）
#   - 支持 arm64-v8a 与 armeabi-v7a 两个 ABI
#   - 生成交叉编译所需的 _sysconfigdata 文件（覆盖 CC/CXX/LDSHARED/AR/RANLIB 等）
#   - 安装到 build-android/install-{abi}/ 目录，供 pybridge-build-packages.sh 使用
#   - 顺带写出 build-android/env-{abi}.sh 环境文件，供包编译脚本 source 复用
#
# 用法：
#   ./pybridge-build-android.sh --abi arm64-v8a --python-version 3.12.3
#   ./pybridge-build-android.sh --help
# ============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# 颜色与日志输出
# ----------------------------------------------------------------------------
if [[ -t 1 ]]; then
    C_RESET="\033[0m"; C_RED="\033[31m"; C_GREEN="\033[32m"
    C_YELLOW="\033[33m"; C_BLUE="\033[34m"; C_CYAN="\033[36m"; C_BOLD="\033[1m"
else
    C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""; C_BOLD=""
fi

log_info()    { echo -e "${C_BLUE}[INFO]${C_RESET} $*"; }
log_success() { echo -e "${C_GREEN}[ OK ]${C_RESET} $*"; }
log_warn()    { echo -e "${C_YELLOW}[WARN]${C_RESET} $*"; }
log_error()   { echo -e "${C_RED}[ERROR]${C_RESET} $*" >&2; }
log_step()    { echo -e "\n${C_BOLD}${C_CYAN}========== $* ==========${C_RESET}"; }

# ----------------------------------------------------------------------------
# 默认配置（可被环境变量与命令行参数覆盖，命令行优先级最高）
# ----------------------------------------------------------------------------
PYTHON_VERSION="${PYTHON_VERSION:-3.12.3}"
ANDROID_API="${ANDROID_API:-28}"
NDK="${NDK:-$HOME/android/android-ndk-r26b}"
ABI="${ABI:-arm64-v8a}"
SKIP_HOST_PYTHON="${SKIP_HOST_PYTHON:-0}"
FORCE_REBUILD="${FORCE_REBUILD:-0}"

# 项目根目录（脚本位于 <root>/scripts/，根目录为上一级）
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$PROJECT_ROOT/build-android}"

# ----------------------------------------------------------------------------
# 用法说明
# ----------------------------------------------------------------------------
usage() {
    cat <<'EOF'
pybridge-build-android.sh — 交叉编译 Android 版 CPython

用法:
  pybridge-build-android.sh [选项]

选项:
  --python-version VERSION   CPython 版本（默认: 3.12.3）
  --android-api N            目标 Android API 级别（默认: 28）
  --ndk PATH                 Android NDK 根目录（默认: $NDK 或 ~/android/android-ndk-r26b）
  --abi ABI                  目标 ABI: arm64-v8a | armeabi-v7a（默认: arm64-v8a）
  --build-dir PATH           构建根目录（默认: <项目>/build-android）
  --skip-host-python         跳过宿主 Python 构建，直接使用系统 python3（需主次版本匹配）
  --force-rebuild            强制重新编译目标 CPython（即使已安装）
  -h, --help                 显示本帮助

环境变量（同上对应选项，优先级低于命令行参数）:
  PYTHON_VERSION, ANDROID_API, NDK, ABI, BUILD_DIR,
  SKIP_HOST_PYTHON, FORCE_REBUILD

示例:
  # 使用默认参数编译 arm64-v8a 的 CPython 3.12.3
  ./pybridge-build-android.sh

  # 编译 armeabi-v7a
  ./pybridge-build-android.sh --abi armeabi-v7a

  # 指定 NDK 与 Python 版本
  ./pybridge-build-android.sh --ndk /opt/android-ndk-r26b --python-version 3.12.3

产物:
  build-android/install-{abi}/                  交叉编译后的 CPython 安装目录
  build-android/install-{abi}/lib/python{X.Y}/_sysconfigdata__linux_android_{abi}.py
  build-android/env-{abi}.sh                    供包编译脚本复用的环境变量
EOF
}

# ----------------------------------------------------------------------------
# 参数解析
# ----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --python-version)   PYTHON_VERSION="$2"; shift 2 ;;
        --android-api)      ANDROID_API="$2"; shift 2 ;;
        --ndk)              NDK="$2"; shift 2 ;;
        --abi)              ABI="$2"; shift 2 ;;
        --build-dir)        BUILD_DIR="$2"; shift 2 ;;
        --skip-host-python) SKIP_HOST_PYTHON=1; shift ;;
        --force-rebuild)    FORCE_REBUILD=1; shift ;;
        -h|--help)          usage; exit 0 ;;
        *) log_error "未知参数: $1"; echo; usage; exit 1 ;;
    esac
done

# ----------------------------------------------------------------------------
# ABI -> 目标 triple 映射
#   NDK 工具链编译器命名格式: {triple}{api}-clang
# ----------------------------------------------------------------------------
case "$ABI" in
    arm64-v8a)     TARGET_TRIPLE="aarch64-linux-android" ;;
    armeabi-v7a)   TARGET_TRIPLE="armv7a-linux-androideabi" ;;
    x86)           TARGET_TRIPLE="i686-linux-android" ;;
    x86_64)        TARGET_TRIPLE="x86_64-linux-android" ;;
    *) log_error "不支持的 ABI: $ABI（仅支持 arm64-v8a / armeabi-v7a）"; exit 1 ;;
esac

# sysconfigdata 文件名中的 abi 片段（'-' 替换为 '_'）
#   例: arm64-v8a -> arm64_v8a -> _sysconfigdata__linux_android_arm64_v8a
ABI_TAG="${ABI//-/_}"

# Python 主次版本号 (3.12.3 -> 3.12)
PYTHON_MAJOR_MINOR="${PYTHON_VERSION%.*}"

# ----------------------------------------------------------------------------
# 校验 NDK 是否存在
# ----------------------------------------------------------------------------
if [[ ! -d "$NDK" ]]; then
    log_error "未找到 Android NDK: $NDK"
    log_error "请通过 --ndk 指定 NDK 路径，或设置 NDK 环境变量。"
    log_error "下载地址: https://dl.google.com/android/repository/android-ndk-r26b-linux.zip"
    exit 1
fi

NDK_TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -d "$NDK_TOOLCHAIN" ]]; then
    log_error "NDK llvm 工具链目录不存在: $NDK_TOOLCHAIN"
    log_error "请确认 NDK 版本（推荐 r26b）与宿主平台（linux-x86_64）。"
    exit 1
fi

# 校验交叉编译器是否存在
CLANG_BIN="$NDK_TOOLCHAIN/bin/${TARGET_TRIPLE}${ANDROID_API}-clang"
if [[ ! -x "$CLANG_BIN" ]]; then
    log_error "未找到交叉编译器: $CLANG_BIN"
    log_error "请检查 ABI($ABI) / API($ANDROID_API) 是否匹配 NDK 提供的组合。"
    exit 1
fi

log_info "构建配置:"
log_info "  Python 版本   : $PYTHON_VERSION (py$PYTHON_MAJOR_MINOR)"
log_info "  目标 ABI      : $ABI  (tag=$ABI_TAG)"
log_info "  目标 triple   : $TARGET_TRIPLE"
log_info "  Android API   : $ANDROID_API"
log_info "  NDK           : $NDK"
log_info "  构建目录       : $BUILD_DIR"

# ----------------------------------------------------------------------------
# 路径常量
# ----------------------------------------------------------------------------
mkdir -p "$BUILD_DIR"
CPYTHON_SRC="$BUILD_DIR/Python-$PYTHON_VERSION"
CPYTHON_TGZ="$BUILD_DIR/Python-$PYTHON_VERSION.tgz"
HOST_INSTALL="$BUILD_DIR/host-install"
TARGET_INSTALL="$BUILD_DIR/install-$ABI"
TARGET_BUILD="$BUILD_DIR/build-$ABI"
ENV_FILE="$BUILD_DIR/env-$ABI.sh"

# sysconfigdata 模块名（不含 .py）
SYSCONFIG_NAME="_sysconfigdata__linux_android_${ABI_TAG}"

# ----------------------------------------------------------------------------
# 1. 下载并解压 CPython 源码
# ----------------------------------------------------------------------------
log_step "1/6 下载 CPython $PYTHON_VERSION 源码"
if [[ ! -d "$CPYTHON_SRC" ]]; then
    if [[ ! -f "$CPYTHON_TGZ" ]]; then
        local_url="https://www.python.org/ftp/python/$PYTHON_VERSION/Python-$PYTHON_VERSION.tgz"
        log_info "下载: $local_url"
        wget -q --show-progress -O "$CPYTHON_TGZ" "$local_url"
    fi
    log_info "解压源码 -> $CPYTHON_SRC"
    tar xzf "$CPYTHON_TGZ" -C "$BUILD_DIR"
else
    log_info "源码已存在，跳过下载: $CPYTHON_SRC"
fi

# ----------------------------------------------------------------------------
# 2. 构建宿主（build）Python
#    CPython 3.11+ 交叉编译必须提供一个与目标主次版本一致的"build python"，
#    用于在编译期运行 freeze / 解析脚本。此处默认从同一份源码编译一个原生宿主 Python。
# ----------------------------------------------------------------------------
log_step "2/6 准备宿主 Python（build python）"

get_sys_py_ver() { python3 -c 'import sys; print("%d.%d.%d" % sys.version_info[:3])' 2>/dev/null || echo ""; }

HOST_PYTHON_BIN=""
if [[ "$SKIP_HOST_PYTHON" == "1" ]]; then
    SYS_VER="$(get_sys_py_ver)"
    if [[ -n "$SYS_VER" && "${SYS_VER%.*}" == "$PYTHON_MAJOR_MINOR" ]]; then
        HOST_PYTHON_BIN="$(command -v python3)"
        log_info "使用系统 Python 作为 build python: $HOST_PYTHON_BIN ($SYS_VER)"
    else
        log_error "--skip-host-python 已设置，但系统 python3 版本($SYS_VER)与目标($PYTHON_VERSION)主次版本不匹配。"
        log_error "请移除该选项以让脚本自动构建宿主 Python。"
        exit 1
    fi
fi

if [[ -z "$HOST_PYTHON_BIN" ]]; then
    if [[ -x "$HOST_INSTALL/bin/python3" ]]; then
        HOST_PYTHON_BIN="$HOST_INSTALL/bin/python3"
        log_info "宿主 Python 已构建，复用: $HOST_PYTHON_BIN"
    else
        log_info "构建宿主 Python（原生编译，仅供交叉编译辅助使用）..."
        HOST_BUILD="$BUILD_DIR/host-build"
        mkdir -p "$HOST_BUILD"
        (
            cd "$HOST_BUILD"
            "$CPYTHON_SRC/configure" \
                --prefix="$HOST_INSTALL" \
                --without-ensurepip
            make -j"$(nproc)"
            make install
        )
        HOST_PYTHON_BIN="$HOST_INSTALL/bin/python3"
        log_success "宿主 Python 构建完成: $HOST_PYTHON_BIN ($($HOST_PYTHON_BIN --version))"
    fi
fi

# ----------------------------------------------------------------------------
# 3. 设置交叉编译工具链环境
# ----------------------------------------------------------------------------
log_step "3/6 配置交叉编译工具链（NDK llvm/clang）"

export CC="$NDK_TOOLCHAIN/bin/${TARGET_TRIPLE}${ANDROID_API}-clang"
export CXX="$NDK_TOOLCHAIN/bin/${TARGET_TRIPLE}${ANDROID_API}-clang++"
export AR="$NDK_TOOLCHAIN/bin/llvm-ar"
export RANLIB="$NDK_TOOLCHAIN/bin/llvm-ranlib"
export READELF="$NDK_TOOLCHAIN/bin/llvm-readelf"
export STRIP="$NDK_TOOLCHAIN/bin/llvm-strip"
export LD="$NDK_TOOLCHAIN/bin/ld.lld"

# sysroot 由 clang 包装器自动选择，显式提供以增强兼容性
SYSROOT="$NDK_TOOLCHAIN/sysroot"
export CFLAGS="-fPIC -O2 --sysroot=$SYSROOT"
export CXXFLAGS="-fPIC -O2 --sysroot=$SYSROOT"
export LDFLAGS="--sysroot=$SYSROOT -Wl,--enable-new-dtags"

log_info "CC  = $CC"
log_info "CXX = $CXX"
log_info "AR  = $AR"
log_info "SYSROOT = $SYSROOT"

# ----------------------------------------------------------------------------
# 4. 配置并交叉编译 CPython
# ----------------------------------------------------------------------------
log_step "4/6 配置并交叉编译 CPython（$ABI）"

if [[ -d "$TARGET_INSTALL/lib/python$PYTHON_MAJOR_MINOR" && "$FORCE_REBUILD" != "1" ]]; then
    log_info "检测到已安装的目标 CPython: $TARGET_INSTALL"
    log_info "跳过编译（如需重建请加 --force-rebuild 或设置 FORCE_REBUILD=1）"
else
    mkdir -p "$TARGET_BUILD"
    (
        cd "$TARGET_BUILD"
        # Android (bionic) 不存在 /dev/ptmx、/dev/ptc，需告知 configure
        "$CPYTHON_SRC/configure" \
            --host="$TARGET_TRIPLE" \
            --build="$(gcc -dumpmachine 2>/dev/null || echo x86_64-linux-gnu)" \
            --prefix="$TARGET_INSTALL" \
            --with-build-python="$HOST_PYTHON_BIN" \
            --enable-shared \
            --without-ensurepip \
            ac_cv_file__dev_ptmx=no \
            ac_cv_file__dev_ptc=no \
            ac_cv_buggy_getaddrinfo=no
        make -j"$(nproc)"
        make install
    )
    log_success "CPython 交叉编译完成，安装到: $TARGET_INSTALL"
fi

# ----------------------------------------------------------------------------
# 5. 生成 _sysconfigdata 文件
#    构建过程会自动生成一份 _sysconfigdata，但其工具链变量可能不准确，
#    这里以宿主 Python 运行一段脚本，读取自动生成的 build_time_vars 作为基础，
#    覆盖 CC/CXX/LDSHARED/AR/RANLIB 等关键变量，写出目标文件名。
# ----------------------------------------------------------------------------
log_step "5/6 生成 _sysconfigdata 文件"

SYSCONFIG_GEN="$BUILD_DIR/.gen_sysconfigdata_$ABI_TAG.py"
cat > "$SYSCONFIG_GEN" <<'PYEOF'
"""由 pybridge-build-android.sh 生成：写入 Android 目标 _sysconfigdata 文件。"""
import os
import sys
import importlib.util

if len(sys.argv) != 8:
    sys.stderr.write(
        "usage: gen_sysconfigdata.py <prefix> <py_major_minor> <abi_tag> "
        "<triple> <api> <ndk_toolchain> <host_python>\n")
    sys.exit(1)

prefix, py_mm, abi_tag, triple, api, ndk_tc, _host = sys.argv[1:8]
lib_dir = os.path.join(prefix, "lib", "python" + py_mm)
if not os.path.isdir(lib_dir):
    sys.stderr.write("ERROR: lib dir not found: %s\n" % lib_dir)
    sys.exit(1)

# 1) 尝试加载构建过程中自动生成的 _sysconfigdata 作为基础
build_time_vars = {}
for name in sorted(os.listdir(lib_dir)):
    if name.startswith("_sysconfigdata") and name.endswith(".py"):
        path = os.path.join(lib_dir, name)
        spec = importlib.util.spec_from_file_location(name[:-3], path)
        mod = importlib.util.module_from_spec(spec)
        try:
            spec.loader.exec_module(mod)
            if hasattr(mod, "build_time_vars"):
                build_time_vars = dict(mod.build_time_vars)
                sys.stderr.write("loaded base sysconfig: %s\n" % name)
        except Exception as exc:  # noqa: BLE001
            sys.stderr.write("WARN: failed to load %s: %s\n" % (name, exc))
        break

# 2) 覆盖工具链与路径变量，确保后续包编译使用 NDK clang
clang = "%s/bin/%s%s-clang" % (ndk_tc, triple, api)
build_time_vars.update({
    "CC": clang,
    "CXX": clang + "++",
    "LDSHARED": clang + " -shared",
    "LDCXXSHARED": clang + "++ -shared",
    "AR": ndk_tc + "/bin/llvm-ar",
    "ARFLAGS": "rcs",
    "RANLIB": ndk_tc + "/bin/llvm-ranlib",
    "READELF": ndk_tc + "/bin/llvm-readelf",
    "STRIP": ndk_tc + "/bin/llvm-strip",
    "LD": ndk_tc + "/bin/ld.lld",
    "LDLIBRARY": "libpython%s.so" % py_mm,
    "LIBDIR": os.path.join(prefix, "lib"),
    "INCLUDEPY": os.path.join(prefix, "include", "python" + py_mm),
    "CONFINCLUDEPY": os.path.join(prefix, "include", "python" + py_mm),
    "Py_DEBUG": "0",
    "Py_ENABLE_SHARED": "1",
    "EXE": "",
    "MULTIARCH": triple,
    "HOST_GNU_TYPE": triple,
    "BUILD_GNU_TYPE": "x86_64-linux-gnu",
    "prefix": prefix,
    "exec_prefix": prefix,
    "ANDROID_API": str(api),
    "ANDROID_ABI": abi_tag,
})

# 3) 写入目标 _sysconfigdata 文件
target_name = "_sysconfigdata__linux_android_%s" % abi_tag
target_file = os.path.join(lib_dir, target_name + ".py")
with open(target_file, "w") as fh:
    fh.write("# -*- coding: utf-8 -*-\n")
    fh.write("# Auto-generated by pybridge-build-android.sh — do not edit by hand.\n")
    fh.write("# ABI=%s triple=%s api=%s\n" % (abi_tag, triple, api))
    fh.write("build_time_vars = %r\n" % build_time_vars)
print("OK: %s" % target_file)
PYEOF

"$HOST_PYTHON_BIN" "$SYSCONFIG_GEN" \
    "$TARGET_INSTALL" "$PYTHON_MAJOR_MINOR" "$ABI_TAG" \
    "$TARGET_TRIPLE" "$ANDROID_API" "$NDK_TOOLCHAIN" "$HOST_PYTHON_BIN"

log_success "sysconfigdata 生成完毕: $SYSCONFIG_NAME"

# ----------------------------------------------------------------------------
# 6. 写出环境文件 env-{abi}.sh 并打印摘要
# ----------------------------------------------------------------------------
log_step "6/6 写出环境文件并完成"

cat > "$ENV_FILE" <<EOF
# Auto-generated by pybridge-build-android.sh — source 以复用工具链配置
#   source $ENV_FILE
export PYBRIDGE_PYTHON_VERSION="$PYTHON_VERSION"
export PYBRIDGE_PY_MAJOR_MINOR="$PYTHON_MAJOR_MINOR"
export PYBRIDGE_ABI="$ABI"
export PYBRIDGE_ABI_TAG="$ABI_TAG"
export PYBRIDGE_TARGET_TRIPLE="$TARGET_TRIPLE"
export PYBRIDGE_ANDROID_API="$ANDROID_API"
export PYBRIDGE_NDK="$NDK"
export PYBRIDGE_NDK_TOOLCHAIN="$NDK_TOOLCHAIN"
export PYBRIDGE_TARGET_INSTALL="$TARGET_INSTALL"
export PYBRIDGE_SYSCONFIG_NAME="$SYSCONFIG_NAME"
export PYBRIDGE_HOST_PYTHON="$HOST_PYTHON_BIN"
EOF

log_success "Android CPython 编译完成！"
echo
log_info "安装目录       : $TARGET_INSTALL"
log_info "libpython      : $TARGET_INSTALL/lib/libpython$PYTHON_MAJOR_MINOR.so"
log_info "头文件目录      : $TARGET_INSTALL/include/python$PYTHON_MAJOR_MINOR"
log_info "sysconfigdata  : $TARGET_INSTALL/lib/python$PYTHON_MAJOR_MINOR/${SYSCONFIG_NAME}.py"
log_info "环境文件        : $ENV_FILE"
echo
log_info "后续编译 Python 包请运行:"
log_info "  ./pybridge-build-packages.sh --abi $ABI"
echo
log_info "也可手动 source 环境文件复用配置:"
log_info "  source $ENV_FILE"
