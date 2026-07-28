#!/usr/bin/env bash
#
# pybridge-build-packages.sh
# ============================================================================
# 交叉编译 Python 包（含 C 扩展）到 Android 平台。
#
# 依赖 pybridge-build-android.sh 的产物：
#   - build-android/install-{abi}/        已交叉编译的 CPython
#   - build-android/env-{abi}.sh          工具链环境变量（优先 source 复用）
#
# 功能：
#   - 读取包列表，自动区分纯 Python 包与 C 扩展包
#   - 纯 Python 包：pip download noarch wheel 并解包
#   - C 扩展包：使用 NDK clang 交叉编译 setup.py build_ext
#   - 对 Pillow / PyMuPDF / lxml / numpy 提供定制编译参数
#   - 收集 .so  -> prebuilt-packages/{abi}/
#   - 收集 .py  -> prebuilt-packages/{abi}/{pkg}/
#   - 生成 deps_manifest.json 记录每个包的编译状态
#
# 用法：
#   ./pybridge-build-packages.sh --abi arm64-v8a
#   ./pybridge-build-packages.sh --packages "Pillow,lxml,numpy"
#   ./pybridge-build-packages.sh --help
# ============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# 颜色与日志输出
# ----------------------------------------------------------------------------
if [[ -t 1 ]]; then
    C_RESET="\033[0m"; C_RED="\033[31m"; C_GREEN="\033[32m"
    C_YELLOW="\033[33m"; C_BLUE="\033[34m"; C_CYAN="\033[36m"
    C_MAGENTA="\033[35m"; C_BOLD="\033[1m"; C_DIM="\033[2m"
else
    C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""
    C_CYAN=""; C_MAGENTA=""; C_BOLD=""; C_DIM=""
fi

log_info()    { echo -e "${C_BLUE}[INFO]${C_RESET} $*"; }
log_success() { echo -e "${C_GREEN}[ OK ]${C_RESET} $*"; }
log_warn()    { echo -e "${C_YELLOW}[WARN]${C_RESET} $*"; }
log_error()   { echo -e "${C_RED}[ERROR]${C_RESET} $*" >&2; }
log_step()    { echo -e "\n${C_BOLD}${C_CYAN}========== $* ==========${C_RESET}"; }
log_pkg()     { echo -e "${C_MAGENTA}>>${C_RESET} ${C_BOLD}$*${C_RESET}"; }

# 进度条：current total label
progress() {
    local cur="$1" total="$2" label="$3"
    local width=24 filled pct
    total="${total:-1}"
    pct=$(( cur * 100 / total ))
    filled=$(( width * cur / total ))
    [ "$filled" -gt "$width" ] && filled="$width"
    local bar=""
    for ((i=0; i<filled; i++)); do bar+="#"; done
    for ((i=filled; i<width; i++)); do bar+="-"; done
    printf "\r${C_DIM}[${C_GREEN}%s${C_DIM}]${C_RESET} %3d%% %s   " "$bar" "$pct" "$label"
}

# ----------------------------------------------------------------------------
# 默认配置
# ----------------------------------------------------------------------------
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$PROJECT_ROOT/build-android}"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJECT_ROOT/prebuilt-packages}"

NDK="${NDK:-$HOME/android/android-ndk-r26b}"
ABI="${ABI:-arm64-v8a}"
ANDROID_API="${ANDROID_API:-28}"
PYTHON_VERSION="${PYTHON_VERSION:-3.12.3}"
KEEP_SOURCES="${KEEP_SOURCES:-0}"   # 1 = 保留下载的源码用于调试

# Python 主次版本号 (3.12.3 -> 3.12)
PYTHON_MAJOR_MINOR="${PYTHON_VERSION%.*}"

# 已知包含 C/C++ 扩展的包（按规范化名称小写匹配）
# 这些包会走 setup.py build_ext 交叉编译路径
KNOWN_C_EXTENSIONS=(
    pillow lxml numpy pymupdf
    cffi cryptography regex bcrypt
    pyyaml markupsafe pycares
    ujson msgpack zstandard
    pysimdjson orjson
)
c_extension_p() {
    # 判断包名是否属于 C 扩展包（大小写、规范化下划线/连字符不敏感）
    local pkg="$1" norm
    norm="$(echo "$pkg" | tr '[:upper:]' '[:lower:]' | tr '-' '_')"
    for k in "${KNOWN_C_EXTENSIONS[@]}"; do
        [[ "$norm" == "$k" ]] && return 0
    done
    return 1
}

# 默认包列表（纯 Python + C 扩展）
DEFAULT_PURE_PYTHON=( pdfminer.six pdfplumber python-docx tabulate openpyxl markitdown chardet )
DEFAULT_C_EXTENSIONS=( Pillow lxml numpy PyMuPDF )

# 最终待处理列表（可被 --packages 覆盖）
PACKAGES=()

# ----------------------------------------------------------------------------
# 用法说明
# ----------------------------------------------------------------------------
usage() {
    cat <<'EOF'
pybridge-build-packages.sh — 交叉编译 Python 包（含 C 扩展）到 Android

用法:
  pybridge-build-packages.sh [选项]

选项:
  --packages "a,b,c"         覆盖待处理的包列表（逗号分隔），自动判别纯 Python / C 扩展
  --abi ABI                  目标 ABI: arm64-v8a | armeabi-v7a（默认: arm64-v8a）
  --ndk PATH                 Android NDK 根目录（默认: $NDK 或 ~/android/android-ndk-r26b）
  --android-api N            目标 Android API 级别（默认: 28）
  --python-version VERSION   CPython 版本（默认: 3.12.3，需与已编译的 CPython 一致）
  --build-dir PATH           构建根目录（默认: <项目>/build-android）
  --output-dir PATH          产物输出目录（默认: <项目>/prebuilt-packages）
  --keep-sources             保留下载的源码（调试用）
  -h, --help                 显示本帮助

环境变量（同上对应选项，优先级低于命令行参数）:
  NDK, ABI, ANDROID_API, PYTHON_VERSION, BUILD_DIR, OUTPUT_DIR, KEEP_SOURCES

示例:
  # 编译默认包列表到 arm64-v8a
  ./pybridge-build-packages.sh --abi arm64-v8a

  # 仅编译指定包
  ./pybridge-build-packages.sh --packages "Pillow,lxml,numpy"

  # 先运行 pybridge-build-android.sh，再运行本脚本
  ./pybridge-build-android.sh --abi arm64-v8a
  ./pybridge-build-packages.sh --abi arm64-v8a

产物:
  prebuilt-packages/{abi}/*.so                         交叉编译的共享库（→ jniLibs）
  prebuilt-packages/{abi}/{pkg}/                       包的 Python 源码
  prebuilt-packages/{abi}/deps_manifest.json           编译状态清单
EOF
}

# ----------------------------------------------------------------------------
# 参数解析
# ----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --packages)       PACKAGES+=( $(echo "$2" | tr ',' '\n') ); shift 2 ;;
        --abi)            ABI="$2"; shift 2 ;;
        --ndk)            NDK="$2"; shift 2 ;;
        --android-api)    ANDROID_API="$2"; shift 2 ;;
        --python-version) PYTHON_VERSION="$2"; PYTHON_MAJOR_MINOR="${2%.*}"; shift 2 ;;
        --build-dir)      BUILD_DIR="$2"; shift 2 ;;
        --output-dir)     OUTPUT_DIR="$2"; shift 2 ;;
        --keep-sources)   KEEP_SOURCES=1; shift ;;
        -h|--help)        usage; exit 0 ;;
        *) log_error "未知参数: $1"; echo; usage; exit 1 ;;
    esac
done

# 若未通过 --packages 指定，则使用默认列表
if [[ ${#PACKAGES[@]} -eq 0 ]]; then
    PACKAGES=( "${DEFAULT_PURE_PYTHON[@]}" "${DEFAULT_C_EXTENSIONS[@]}" )
fi

# ----------------------------------------------------------------------------
# ABI -> triple
# ----------------------------------------------------------------------------
case "$ABI" in
    arm64-v8a)     TARGET_TRIPLE="aarch64-linux-android" ;;
    armeabi-v7a)   TARGET_TRIPLE="armv7a-linux-androideabi" ;;
    x86)           TARGET_TRIPLE="i686-linux-android" ;;
    x86_64)        TARGET_TRIPLE="x86_64-linux-android" ;;
    *) log_error "不支持的 ABI: $ABI"; exit 1 ;;
esac
ABI_TAG="${ABI//-/_}"

# ----------------------------------------------------------------------------
# 优先 source pybridge-build-android.sh 写出的环境文件
# ----------------------------------------------------------------------------
ENV_FILE="$BUILD_DIR/env-$ABI.sh"
if [[ -f "$ENV_FILE" ]]; then
    log_info "加载环境文件: $ENV_FILE"
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    # 从环境文件中恢复关键变量（若存在）
    PYTHON_VERSION="${PYBRIDGE_PYTHON_VERSION:-$PYTHON_VERSION}"
    PYTHON_MAJOR_MINOR="${PYBRIDGE_PY_MAJOR_MINOR:-$PYTHON_MAJOR_MINOR}"
    TARGET_INSTALL="${PYBRIDGE_TARGET_INSTALL:-$BUILD_DIR/install-$ABI}"
    NDK_TOOLCHAIN="${PYBRIDGE_NDK_TOOLCHAIN:-$NDK/toolchains/llvm/prebuilt/linux-x86_64}"
    SYSCONFIG_NAME="${PYBRIDGE_SYSCONFIG_NAME:-_sysconfigdata__linux_android_${ABI_TAG}}"
    TARGET_TRIPLE="${PYBRIDGE_TARGET_TRIPLE:-$TARGET_TRIPLE}"
    ANDROID_API="${PYBRIDGE_ANDROID_API:-$ANDROID_API}"
else
    log_info "未找到环境文件 $ENV_FILE，使用命令行参数推导配置"
    NDK_TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
    TARGET_INSTALL="$BUILD_DIR/install-$ABI"
    SYSCONFIG_NAME="_sysconfigdata__linux_android_${ABI_TAG}"
fi

# ----------------------------------------------------------------------------
# 校验：CPython 是否已交叉编译
# ----------------------------------------------------------------------------
if [[ ! -d "$TARGET_INSTALL/lib/python$PYTHON_MAJOR_MINOR" ]]; then
    log_error "未找到已交叉编译的 CPython: $TARGET_INSTALL"
    log_error "请先运行: ./pybridge-build-android.sh --abi $ABI"
    exit 1
fi
if [[ ! -d "$NDK_TOOLCHAIN" ]]; then
    log_error "未找到 NDK 工具链: $NDK_TOOLCHAIN"
    log_error "请通过 --ndk 指定正确的 NDK 路径。"
    exit 1
fi

SYSROOT="$NDK_TOOLCHAIN/sysroot"
PY_INCLUDE="$TARGET_INSTALL/include/python$PYTHON_MAJOR_MINOR"
PY_LIBDIR="$TARGET_INSTALL/lib"

# 宿主 python3（用于运行 setup.py / 生成清单）
HOST_PYTHON="${PYBRIDGE_HOST_PYTHON:-python3}"
if ! command -v "$HOST_PYTHON" >/dev/null 2>&1; then
    HOST_PYTHON="$BUILD_DIR/host-install/bin/python3"
fi
if ! command -v "$HOST_PYTHON" >/dev/null 2>&1; then
    log_error "未找到可用的宿主 python3（用于运行 setup.py）"
    exit 1
fi

log_info "包编译配置:"
log_info "  待处理包数量 : ${#PACKAGES[@]}"
log_info "  目标 ABI     : $ABI (triple=$TARGET_TRIPLE)"
log_info "  Android API  : $ANDROID_API"
log_info "  CPython 安装 : $TARGET_INSTALL"
log_info "  sysconfigdata: $SYSCONFIG_NAME"
log_info "  宿主 Python  : $HOST_PYTHON ($($HOST_PYTHON --version 2>&1))"
log_info "  产物目录      : $OUTPUT_DIR/$ABI"

# ----------------------------------------------------------------------------
# 设置交叉编译工具链环境（供 setup.py build_ext / distutils 读取）
# ----------------------------------------------------------------------------
setup_cross_env() {
    export CC="$NDK_TOOLCHAIN/bin/${TARGET_TRIPLE}${ANDROID_API}-clang"
    export CXX="$NDK_TOOLCHAIN/bin/${TARGET_TRIPLE}${ANDROID_API}-clang++"
    export AR="$NDK_TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$NDK_TOOLCHAIN/bin/llvm-ranlib"
    export READELF="$NDK_TOOLCHAIN/bin/llvm-readelf"
    export STRIP="$NDK_TOOLCHAIN/bin/llvm-strip"
    export LD="$NDK_TOOLCHAIN/bin/ld.lld"
    export LDSHARED="$CC -shared"
    export LDCXXSHARED="$CXX -shared"

    # 让 distutils/sysconfig 读取目标的 _sysconfigdata
    export _PYTHON_SYSCONFIGDATA_NAME="$SYSCONFIG_NAME"
    export PYTHONPATH="$TARGET_INSTALL/lib/python$PYTHON_MAJOR_MINOR:$TARGET_INSTALL/lib/python$PYTHON_MAJOR_MINOR/lib-dynload"

    # 编译选项：sysroot + libpython 头文件/库
    export CFLAGS="-fPIC -O2 --sysroot=$SYSROOT -I$PY_INCLUDE"
    export CXXFLAGS="-fPIC -O2 --sysroot=$SYSROOT -I$PY_INCLUDE"
    export LDFLAGS="--sysroot=$SYSROOT -L$PY_LIBDIR -Wl,-rpath,\$ORIGIN"
    # 链接 libpython（C 扩展的初始化符号来自 libpython）
    export LIBS="-lpython$PYTHON_MAJOR_MINOR"
}

# ----------------------------------------------------------------------------
# 工作目录与清单
# ----------------------------------------------------------------------------
PKG_WORK_DIR="$BUILD_DIR/pkg-work-$ABI"
mkdir -p "$PKG_WORK_DIR"
mkdir -p "$OUTPUT_DIR/$ABI"

# 清单 TSV：name<TAB>type<TAB>status<TAB>version<TAB>so_count
MANIFEST_TSV="$PKG_WORK_DIR/.manifest.tsv"
: > "$MANIFEST_TSV"

# 记录一条清单
#   record_manifest <name> <type> <status> <version> <so_count>
record_manifest() {
    printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}" "${5:-0}" >> "$MANIFEST_TSV"
}

# ----------------------------------------------------------------------------
# 解压辅助：支持 .tar.gz / .tgz / .zip / .whl
# ----------------------------------------------------------------------------
extract_archive() {
    local archive="$1" dest="$2"
    mkdir -p "$dest"
    case "$archive" in
        *.tar.gz|*.tgz) tar xzf "$archive" -C "$dest" ;;
        *.tar.bz2)      tar xjf "$archive" -C "$dest" ;;
        *.zip|*.whl)    unzip -q -o "$archive" -d "$dest" ;;
        *) return 1 ;;
    esac
}

# ----------------------------------------------------------------------------
# 处理纯 Python 包：pip download noarch wheel 后解包
# ----------------------------------------------------------------------------
build_pure_python() {
    local pkg="$1" out_pkg_dir="$OUTPUT_DIR/$ABI/$pkg"
    log_pkg "纯 Python 包: $pkg"
    local dl_dir="$PKG_WORK_DIR/$pkg"
    mkdir -p "$dl_dir"

    # 优先下载 noarch wheel（py3-none-any / py2.py3-none-any）
    # --only-binary :all: + --platform any 强制获取与架构无关的 wheel
    if ! pip download --no-deps \
            --only-binary :all: \
            --platform any \
            --implementation py \
            --python-version "${PYTHON_MAJOR_MINOR//./}" \
            --abi none \
            -d "$dl_dir" "$pkg" 2>"$dl_dir/.pip.err"; then
        log_warn "未找到 noarch wheel，回退到 sdist（$pkg）"
        # 回退：下载 sdist 并解包其 .py 源码
        if ! pip download --no-deps --no-binary :all: -d "$dl_dir" "$pkg" 2>>"$dl_dir/.pip.err"; then
            log_error "下载失败: $pkg（见 $dl_dir/.pip.err）"
            record_manifest "$pkg" "pure_python" "failed" "" 0
            return 1
        fi
        local sdist
        sdist="$(find "$dl_dir" -maxdepth 1 \( -name '*.tar.gz' -o -name '*.zip' \) | head -n1)"
        extract_archive "$sdist" "$dl_dir/extract"
        local src_dir
        src_dir="$(find "$dl_dir/extract" -maxdepth 1 -mindepth 1 -type d | head -n1)"
        mkdir -p "$out_pkg_dir"
        # 复制顶层包目录与单文件模块
        copy_package_source "$src_dir" "$out_pkg_dir"
        local ver
        ver="$(detect_version "$src_dir")"
        record_manifest "$pkg" "pure_python" "ok" "${ver:-}" 0
        log_success "纯 Python 包就绪(sdist): $pkg -> $out_pkg_dir"
        return 0
    fi

    # 解包 noarch wheel
    local whl
    whl="$(find "$dl_dir" -maxdepth 1 -name '*.whl' | head -n1)"
    if [[ -z "$whl" ]]; then
        log_error "未获取到 wheel: $pkg"
        record_manifest "$pkg" "pure_python" "failed" "" 0
        return 1
    fi
    extract_archive "$whl" "$out_pkg_dir"
    local ver
    ver="$(echo "$whl" | sed -E 's/.*-([0-9][^-]*)-.*/\1/')"
    # 清理 wheel 中的 .pyc / __pycache__
    find "$out_pkg_dir" -type d -name '__pycache__' -exec rm -rf {} + 2>/dev/null || true
    find "$out_pkg_dir" -name '*.pyc' -delete 2>/dev/null || true
    record_manifest "$pkg" "pure_python" "ok" "${ver:-}" 0
    log_success "纯 Python 包就绪(wheel): $pkg ($ver) -> $out_pkg_dir"
}

# 复制顶层包源码（目录带 __init__.py 或顶层 .py 模块），排除构建产物
#   copy_package_source <src_dir> <dest_dir>
copy_package_source() {
    local src_dir="$1" dest="$2" entry
    # 优先使用 egg-info 的 top_level.txt
    ( cd "$src_dir" && "$HOST_PYTHON" setup.py egg_info >/dev/null 2>&1 ) || true
    local top_level="$src_dir"/*.egg-info/top_level.txt
    if ls $top_level >/dev/null 2>&1; then
        while IFS= read -r entry; do
            [[ -z "$entry" ]] && continue
            if [[ -d "$src_dir/$entry" ]]; then
                cp -r "$src_dir/$entry" "$dest/"
            elif [[ -f "$src_dir/$entry" ]]; then
                cp "$src_dir/$entry" "$dest/"
            fi
        done < $(ls $top_level | head -n1)
    else
        # 回退：复制所有顶层目录（含 __init__.py）与单文件 .py
        for d in "$src_dir"/*/; do
            [[ -f "$d/__init__.py" ]] && cp -r "$d" "$dest/"
        done
        for f in "$src_dir"/*.py; do
            [[ -f "$f" ]] && cp "$f" "$dest/"
        done
    fi
    # 复制 egg-info（含元数据）
    cp -r "$src_dir"/*.egg-info "$dest/" 2>/dev/null || true
    # 清理编译产物
    find "$dest" -type d -name '__pycache__' -exec rm -rf {} + 2>/dev/null || true
    find "$dest" \( -name '*.pyc' -o -name '*.so' -o -name '*.o' \) -delete 2>/dev/null || true
}

# 从源码目录探测版本号
detect_version() {
    local src_dir="$1"
    ( cd "$src_dir" && "$HOST_PYTHON" setup.py --version 2>/dev/null ) || true
}

# ----------------------------------------------------------------------------
# 处理 C 扩展包：setup.py build_ext 交叉编译
# ----------------------------------------------------------------------------
build_c_extension() {
    local pkg="$1" out_pkg_dir="$OUTPUT_DIR/$ABI/$pkg"
    log_pkg "C 扩展包: $pkg"
    local dl_dir="$PKG_WORK_DIR/$pkg"
    mkdir -p "$dl_dir"

    # 1) 下载源码分发包（sdist），保证可编译
    if ! pip download --no-deps --no-binary :all: -d "$dl_dir" "$pkg" 2>"$dl_dir/.pip.err"; then
        log_error "下载 sdist 失败: $pkg（见 $dl_dir/.pip.err）"
        record_manifest "$pkg" "c_extension" "failed" "" 0
        return 1
    fi
    local sdist
    sdist="$(find "$dl_dir" -maxdepth 1 \( -name '*.tar.gz' -o -name '*.zip' \) | head -n1)"
    if [[ -z "$sdist" ]]; then
        log_error "未找到 sdist: $pkg"
        record_manifest "$pkg" "c_extension" "failed" "" 0
        return 1
    fi
    rm -rf "$dl_dir/extract"
    extract_archive "$sdist" "$dl_dir/extract"
    local src_dir
    src_dir="$(find "$dl_dir/extract" -maxdepth 1 -mindepth 1 -type d | head -n1)"
    [[ -z "$src_dir" ]] && { log_error "解压后未找到源码目录: $pkg"; record_manifest "$pkg" "c_extension" "failed" "" 0; return 1; }

    local ver
    ver="$(detect_version "$src_dir")"

    # 2) 设置交叉编译环境
    setup_cross_env

    # 3) 按包名定制编译参数
    local extra_cflags="" extra_ldflags=""
    ( cd "$src_dir" && {
        case "$pkg" in
            Pillow|PIL)
                # Pillow 需要链接 libpython，并显式指定 Python 头文件/库目录
                log_info "  定制: Pillow — 链接 libpython"
                export LDFLAGS="$LDFLAGS -lpython$PYTHON_MAJOR_MINOR"
                export CFLAGS="$CFLAGS -I$PY_INCLUDE"
                "$HOST_PYTHON" setup.py build_ext \
                    --include-dirs="$PY_INCLUDE" \
                    --library-dirs="$PY_LIBDIR" \
                    --disable-platform-guessing \
                    --force
                ;;
            PyMuPDF|pymupdf|fitz)
                # PyMuPDF 内置 MuPDF 构建，需指定 release 模式
                log_info "  定制: PyMuPDF — release 模式构建 MuPDF"
                export PYMUPDF_SETUP_MUPDF_BUILD_TYPE=release
                export CFLAGS="$CFLAGS -I$PY_INCLUDE"
                export LDFLAGS="$LDFLAGS -L$PY_LIBDIR"
                "$HOST_PYTHON" setup.py build_ext --inplace --force
                ;;
            lxml)
                log_info "  定制: lxml — 指定 Python 头文件/库目录"
                export CFLAGS="$CFLAGS -I$PY_INCLUDE"
                export LDFLAGS="$LDFLAGS -L$PY_LIBDIR"
                "$HOST_PYTHON" setup.py build_ext \
                    --include-dirs="$PY_INCLUDE" \
                    --library-dirs="$PY_LIBDIR" \
                    --force
                ;;
            numpy)
                # numpy：禁用 BLAS / LAPACK，避免在交叉编译中寻找宿主的线性代数库
                log_info "  定制: numpy — 禁用 BLAS/LAPACK"
                export NPY_BLAS_ORDER=""
                export NPY_LAPACK_ORDER=""
                export NPY_USE_BLAS_ILP64=0
                export OPENBLAS=""
                export BLAS=""
                export LAPACK=""
                export CFLAGS="$CFLAGS -I$PY_INCLUDE"
                export LDFLAGS="$LDFLAGS -L$PY_LIBDIR"
                # numpy 1.26+ 已迁移到 meson；setup.py 在较旧版本仍可用
                "$HOST_PYTHON" setup.py build_ext \
                    --include-dirs="$PY_INCLUDE" \
                    --library-dirs="$PY_LIBDIR" \
                    --force 2>"$dl_dir/.build.err" || {
                    log_warn "setup.py build_ext 失败，numpy 可能需要 meson（见 $dl_dir/.build.err）"
                    record_manifest "$pkg" "c_extension" "failed" "${ver:-}" 0
                    return 1
                }
                ;;
            *)
                log_info "  通用 C 扩展编译参数"
                "$HOST_PYTHON" setup.py build_ext \
                    --include-dirs="$PY_INCLUDE" \
                    --library-dirs="$PY_LIBDIR" \
                    --force
                ;;
        esac
    } )

    # 4) 收集 .so 文件到 prebuilt-packages/{abi}/
    local so_count=0
    while IFS= read -r -d '' so; do
        local base
        base="$(basename "$so")"
        cp "$so" "$OUTPUT_DIR/$ABI/$base"
        # 去除调试符号以减小体积
        "$STRIP" --strip-unneeded "$OUTPUT_DIR/$ABI/$base" 2>/dev/null || true
        so_count=$((so_count + 1))
        log_info "  收集 .so: $base"
    done < <(find "$src_dir" -name '*.so' -type f -print0)

    if [[ "$so_count" -eq 0 ]]; then
        log_warn "未找到任何 .so 产物: $pkg"
    fi

    # 5) 收集 Python 源码到 prebuilt-packages/{abi}/{pkg}/
    mkdir -p "$out_pkg_dir"
    copy_package_source "$src_dir" "$out_pkg_dir"

    record_manifest "$pkg" "c_extension" "ok" "${ver:-}" "$so_count"
    log_success "C 扩展包就绪: $pkg ($ver) — .so×$so_count, 源码 -> $out_pkg_dir"

    # 可选清理源码
    [[ "$KEEP_SOURCES" == "1" ]] || rm -rf "$dl_dir/extract"
}

# ----------------------------------------------------------------------------
# 主流程：遍历包列表
# ----------------------------------------------------------------------------
log_step "开始编译 ${#PACKAGES[@]} 个包（ABI=$ABI）"

total="${#PACKAGES[@]}"
idx=0
for pkg in "${PACKAGES[@]}"; do
    [[ -z "$pkg" ]] && continue
    idx=$((idx + 1))
    progress "$idx" "$total" "$pkg"
    echo
    echo -e "${C_DIM}----------------------------------------------------------------${C_RESET}"
    if c_extension_p "$pkg"; then
        build_c_extension "$pkg" || log_warn "包 $pkg 编译失败，已记录到清单"
    else
        build_pure_python "$pkg" || log_warn "包 $pkg 处理失败，已记录到清单"
    fi
done
echo

# ----------------------------------------------------------------------------
# 生成 deps_manifest.json
# ----------------------------------------------------------------------------
log_step "生成 deps_manifest.json"

MANIFEST_JSON="$OUTPUT_DIR/$ABI/deps_manifest.json"
GEN_MANIFEST="$PKG_WORK_DIR/.gen_manifest.py"
cat > "$GEN_MANIFEST" <<'PYEOF'
"""读取 manifest TSV 并生成 deps_manifest.json。"""
import csv
import json
import os
import sys

if len(sys.argv) != 4:
    sys.exit("usage: gen_manifest.py <tsv> <out_json> <meta_json>")
tsv_path, out_path, meta_path = sys.argv[1:4]

with open(meta_path, "r", encoding="utf-8") as fh:
    meta = json.load(fh)

packages = []
with open(tsv_path, "r", encoding="utf-8") as fh:
    reader = csv.reader(fh, delimiter="\t")
    for row in reader:
        if not row or not row[0]:
            continue
        name, ptype, status, version, so_count = (row + ["", "", ""])[:5]
        packages.append({
            "name": name,
            "type": ptype,
            "status": status,
            "version": version,
            "so_count": int(so_count) if so_count.isdigit() else 0,
        })

ok = sum(1 for p in packages if p["status"] == "ok")
failed = sum(1 for p in packages if p["status"] == "failed")
manifest = {
    **meta,
    "total": len(packages),
    "succeeded": ok,
    "failed": failed,
    "packages": packages,
}
with open(out_path, "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, indent=2, ensure_ascii=False)
print("OK: %s" % out_path)
PYEOF

# 元数据 JSON
META_JSON="$PKG_WORK_DIR/.meta.json"
cat > "$META_JSON" <<EOF
{
  "python_version": "$PYTHON_VERSION",
  "abi": "$ABI",
  "abi_tag": "$ABI_TAG",
  "target_triple": "$TARGET_TRIPLE",
  "android_api": $ANDROID_API,
  "cpython_install": "$TARGET_INSTALL",
  "sysconfigdata": "$SYSCONFIG_NAME",
  "ndk": "$NDK"
}
EOF

"$HOST_PYTHON" "$GEN_MANIFEST" "$MANIFEST_TSV" "$MANIFEST_JSON" "$META_JSON"
log_success "清单已生成: $MANIFEST_JSON"

# ----------------------------------------------------------------------------
# 摘要
# ----------------------------------------------------------------------------
log_step "完成"
ok_count="$(awk -F'\t' '$3=="ok"' "$MANIFEST_TSV" | wc -l | tr -d ' ')"
fail_count="$(awk -F'\t' '$3=="failed"' "$MANIFEST_TSV" | wc -l | tr -d ' ')"
so_total="$(find "$OUTPUT_DIR/$ABI" -maxdepth 1 -name '*.so' | wc -l | tr -d ' ')"
log_info "成功: ${C_GREEN}$ok_count${C_RESET}   失败: ${C_RED}$fail_count${C_RESET}   .so 总数: ${C_BOLD}$so_total${C_RESET}"
log_info "产物目录: $OUTPUT_DIR/$ABI"
log_info "清单文件: $MANIFEST_JSON"
if [[ "$fail_count" -gt 0 ]]; then
    log_warn "存在失败的包，请查看上方日志与 deps_manifest.json 中的 status 字段。"
    exit 2
fi
