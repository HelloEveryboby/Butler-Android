#!/bin/bash
# ============================================================================
# Butler 混合后端一键编译脚本
# 编译 programs/ 下所有 C / C++ / Go / Rust 原生模块
# 用法: cd android/app/src/main/python && bash build_all.sh [module_name]
#   不带参数 → 编译全部
#   带参数   → 只编译指定模块，如: bash build_all.sh hybrid_net
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROGRAMS_DIR="${SCRIPT_DIR}/programs"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

passed=0
failed=0
skipped=0

log_ok()   { echo -e "${GREEN}[OK]${NC} $1"; ((passed++)) || true; }
log_fail() { echo -e "${RED}[FAIL]${NC} $1 — $2"; ((failed++)) || true; }
log_skip() { echo -e "${YELLOW}[SKIP]${NC} $1 — $2"; ((skipped++)) || true; }
log_info() { echo -e "${CYAN}[BUILD]${NC} $1"; }

# ── 检查工具链 ──────────────────────────────────────────────────

check_tool() {
    if command -v "$1" &>/dev/null; then
        return 0
    else
        return 1
    fi
}

check_requirements() {
    local missing=()
    check_tool gcc         || missing+=("gcc")
    check_tool g++         || missing+=("g++")
    check_tool go          || missing+=("go")
    check_tool cargo       || missing+=("cargo/rust")
    check_tool make        || missing+=("make")

    if [ ${#missing[@]} -gt 0 ]; then
        echo -e "${YELLOW}[WARN] 缺少工具: ${missing[*]}${NC}"
        echo -e "${YELLOW}       缺失工具对应的模块将被跳过${NC}"
        echo ""
    fi
}

# ── C 模块编译 ──────────────────────────────────────────────────

build_c_bcli() {
    local dir="${PROGRAMS_DIR}/bcli"
    log_info "bcli (C CLI + Python Bridge)"
    check_tool gcc || { log_skip "bcli" "缺少 gcc"; return; }
    (cd "$dir" && make clean 2>/dev/null; make -j"$(nproc)")
    log_ok "bcli"
}

build_c_word_counter() {
    local dir="${PROGRAMS_DIR}/c_word_counter"
    log_info "c_word_counter (C)"
    check_tool gcc || { log_skip "c_word_counter" "缺少 gcc"; return; }
    check_tool make || { log_skip "c_word_counter" "缺少 make"; return; }
    (cd "$dir" && make clean 2>/dev/null; make -j"$(nproc)")
    log_ok "c_word_counter"
}

build_c_hybrid_sysutil() {
    local dir="${PROGRAMS_DIR}/hybrid_sysutil"
    log_info "hybrid_sysutil (C)"
    check_tool gcc || { log_skip "hybrid_sysutil" "缺少 gcc"; return; }
    gcc -O2 "${dir}/sysutil.c" -o "${dir}/sysutil" -lm 2>/dev/null
    log_ok "hybrid_sysutil"
}

# ── C++ 模块编译 ───────────────────────────────────────────────

build_cpp_ble_framework() {
    local dir="${PROGRAMS_DIR}/ble_framework"
    log_info "ble_framework (C++ BLE)"
    check_tool g++ || { log_skip "ble_framework" "缺少 g++"; return; }
    # 蓝牙库可能在 Android NDK 环境下不可用，允许失败
    g++ -fPIC -shared "${dir}/BLEFramework.cpp" -o "${dir}/libble.so" -lbluetooth -pthread 2>/dev/null \
        || log_skip "ble_framework" "缺少蓝牙开发库 (-lbluetooth)"
    g++ "${dir}/ble_service.cpp" "${dir}/BLEFramework.cpp" -o "${dir}/ble_framework_exec" -lbluetooth -pthread 2>/dev/null \
        || log_skip "ble_framework" "缺少蓝牙开发库"
    log_ok "ble_framework"
}

build_cpp_hybrid_archive() {
    local dir="${PROGRAMS_DIR}/hybrid_archive"
    log_info "hybrid_archive (C++)"
    check_tool g++ || { log_skip "hybrid_archive" "缺少 g++"; return; }
    g++ -O3 "${dir}/archive_service.cpp" -o "${dir}/archive_service_exec" 2>/dev/null
    log_ok "hybrid_archive"
}

build_cpp_hybrid_compute() {
    local dir="${PROGRAMS_DIR}/hybrid_compute"
    log_info "hybrid_compute (C++)"
    check_tool g++ || { log_skip "hybrid_compute" "缺少 g++"; return; }
    g++ -O3 "${dir}/compute.cpp" -o "${dir}/hybrid_compute_exec" -lm 2>/dev/null
    log_ok "hybrid_compute"
}

build_cpp_hybrid_doc_processor() {
    local dir="${PROGRAMS_DIR}/hybrid_doc_processor"
    log_info "hybrid_doc_processor (C++)"
    check_tool g++ || { log_skip "hybrid_doc_processor" "缺少 g++"; return; }
    g++ -O3 "${dir}/processor.cpp" -o "${dir}/processor" 2>/dev/null
    log_ok "hybrid_doc_processor"
}

build_cpp_hybrid_math() {
    local dir="${PROGRAMS_DIR}/hybrid_math"
    log_info "hybrid_math (C++)"
    check_tool g++ || { log_skip "hybrid_math" "缺少 g++"; return; }
    g++ -O3 -I"${dir}/include" "${dir}/src/math_service.cpp" -o "${dir}/hybrid_math_exec" -lm 2>/dev/null
    log_ok "hybrid_math"
}

build_cpp_hybrid_vision() {
    local dir="${PROGRAMS_DIR}/hybrid_vision"
    log_info "hybrid_vision (C++)"
    check_tool g++ || { log_skip "hybrid_vision" "缺少 g++"; return; }
    g++ -O3 -I"${dir}/include" "${dir}/src/vision_service.cpp" -o "${dir}/hybrid_vision_exec" -lm 2>/dev/null
    log_ok "hybrid_vision"
}

# ── Go 模块编译 ─────────────────────────────────────────────────

build_go_butler_runner() {
    local dir="${PROGRAMS_DIR}/butler_runner"
    log_info "butler_runner (Go)"
    check_tool go || { log_skip "butler_runner" "缺少 go"; return; }
    (cd "$dir" && go build -ldflags="-s -w" -o butler_runner_exec .)
    log_ok "butler_runner"
}

build_go_hybrid_memory() {
    local dir="${PROGRAMS_DIR}/hybrid_memory"
    log_info "hybrid_memory (Go)"
    check_tool go || { log_skip "hybrid_memory" "缺少 go"; return; }
    (cd "$dir" && go build -ldflags="-s -w" -o memory_service memory_service.go)
    log_ok "hybrid_memory"
}

build_go_hybrid_memos() {
    local dir="${PROGRAMS_DIR}/hybrid_memos"
    log_info "hybrid_memos (Go)"
    check_tool go || { log_skip "hybrid_memos" "缺少 go"; return; }
    (cd "$dir" && go mod tidy 2>/dev/null; go build -ldflags="-s -w" -o memos_service memos_service.go)
    log_ok "hybrid_memos"
}

build_go_hybrid_net() {
    local dir="${PROGRAMS_DIR}/hybrid_net"
    log_info "hybrid_net (Go)"
    check_tool go || { log_skip "hybrid_net" "缺少 go"; return; }
    (cd "$dir" && go build -ldflags="-s -w" -o hybrid_net_exec net_service.go)
    log_ok "hybrid_net"
}

build_go_hybrid_system_executor() {
    local dir="${PROGRAMS_DIR}/hybrid_system_executor"
    log_info "hybrid_system_executor (Go)"
    check_tool go || { log_skip "hybrid_system_executor" "缺少 go"; return; }
    (cd "$dir" && go build -ldflags="-s -w" -o executor_service executor_service.go)
    log_ok "hybrid_system_executor"
}

build_go_hybrid_terminal() {
    local dir="${PROGRAMS_DIR}/hybrid_terminal"
    log_info "hybrid_terminal (Go)"
    check_tool go || { log_skip "hybrid_terminal" "缺少 go"; return; }
    (cd "$dir" && go mod tidy 2>/dev/null; go build -ldflags="-s -w" -o terminal_service terminal_service.go)
    log_ok "hybrid_terminal"
}

# ── Rust 模块编译 ───────────────────────────────────────────────

build_rust_hybrid_crypto() {
    local dir="${PROGRAMS_DIR}/hybrid_crypto"
    log_info "hybrid_crypto (Rust)"
    check_tool cargo || { log_skip "hybrid_crypto" "缺少 cargo"; return; }
    (cd "$dir" && cargo build --release 2>&1)
    log_ok "hybrid_crypto"
}

# ── 无需编译的模块 ─────────────────────────────────────────────

build_python_markitdown_gui() {
    log_skip "markitdown_gui" "Python 模块无需编译"
}

build_ts_hybrid_ts_tool() {
    log_skip "hybrid_ts_tool" "TypeScript 模块无需编译"
}

# ── 全部模块注册表 ─────────────────────────────────────────────

ALL_BUILDERS=(
    # C (3)
    build_c_bcli
    build_c_word_counter
    build_c_hybrid_sysutil
    # C++ (6)
    build_cpp_ble_framework
    build_cpp_hybrid_archive
    build_cpp_hybrid_compute
    build_cpp_hybrid_doc_processor
    build_cpp_hybrid_math
    build_cpp_hybrid_vision
    # Go (6)
    build_go_butler_runner
    build_go_hybrid_memory
    build_go_hybrid_memos
    build_go_hybrid_net
    build_go_hybrid_system_executor
    build_go_hybrid_terminal
    # Rust (1)
    build_rust_hybrid_crypto
    # 无需编译 (2)
    build_python_markitdown_gui
    build_ts_hybrid_ts_tool
)

# 模块名 → 构建函数映射
declare -A MODULE_MAP
MODULE_MAP[bcli]="build_c_bcli"
MODULE_MAP[c_word_counter]="build_c_word_counter"
MODULE_MAP[hybrid_sysutil]="build_c_hybrid_sysutil"
MODULE_MAP[ble_framework]="build_cpp_ble_framework"
MODULE_MAP[hybrid_archive]="build_cpp_hybrid_archive"
MODULE_MAP[hybrid_compute]="build_cpp_hybrid_compute"
MODULE_MAP[hybrid_doc_processor]="build_cpp_hybrid_doc_processor"
MODULE_MAP[hybrid_math]="build_cpp_hybrid_math"
MODULE_MAP[hybrid_vision]="build_cpp_hybrid_vision"
MODULE_MAP[butler_runner]="build_go_butler_runner"
MODULE_MAP[hybrid_memory]="build_go_hybrid_memory"
MODULE_MAP[hybrid_memos]="build_go_hybrid_memos"
MODULE_MAP[hybrid_net]="build_go_hybrid_net"
MODULE_MAP[hybrid_system_executor]="build_go_hybrid_system_executor"
MODULE_MAP[hybrid_terminal]="build_go_hybrid_terminal"
MODULE_MAP[hybrid_crypto]="build_rust_hybrid_crypto"
MODULE_MAP[markitdown_gui]="build_python_markitdown_gui"
MODULE_MAP[hybrid_ts_tool]="build_ts_hybrid_ts_tool"

# ── 主逻辑 ──────────────────────────────────────────────────────

build_all() {
    echo "============================================"
    echo "  Butler 混合后端编译  (${#ALL_BUILDERS[@]} 模块)"
    echo "============================================"
    echo ""
    check_requirements
    echo ""

    for builder in "${ALL_BUILDERS[@]}"; do
        "$builder" 2>/dev/null || true
    done

    echo ""
    echo "============================================"
    echo -e "  编译完成: ${GREEN}${passed} 成功${NC}  ${YELLOW}${skipped} 跳过${NC}  ${RED}${failed} 失败${NC}"
    echo "============================================"
}

build_single() {
    local name="$1"
    local builder="${MODULE_MAP[$name]:-}"

    if [ -z "$builder" ]; then
        echo -e "${RED}未知模块: ${name}${NC}"
        echo "可用模块: ${!MODULE_MAP[*]}"
        exit 1
    fi

    check_requirements
    echo ""
    "$builder"
}

# ── 入口 ────────────────────────────────────────────────────────

if [ $# -eq 0 ]; then
    build_all
else
    for name in "$@"; do
        build_single "$name"
    done
fi
