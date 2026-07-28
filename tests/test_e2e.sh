#!/bin/bash
# ============================================================
# 端到端测试脚本
# 验证从 skill 构建 → 市场上传 → 客户端下载 → PyBridge 执行
# 的完整链路
# ============================================================
set -euo pipefail

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PASS=0
FAIL=0
SKIP=0
CURRENT_TEST=""

log_info()    { echo -e "${BLUE}[INFO]${NC}  $1"; }
log_pass()    { echo -e "${GREEN}[PASS]${NC}  $1"; PASS=$((PASS+1)); }
log_fail()    { echo -e "${RED}[FAIL]${NC}  $1"; FAIL=$((FAIL+1)); }
log_skip()    { echo -e "${YELLOW}[SKIP]${NC}  $1"; SKIP=$((SKIP+1)); }
log_section() { echo -e "\n${BLUE}════════════════════════════════════════${NC}"; echo -e "${BLUE}  $1${NC}"; echo -e "${BLUE}════════════════════════════════════════${NC}"; }

# 项目路径
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPTS_DIR="$PROJECT_ROOT/scripts"
TEST_TMP="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP"' EXIT

# ============================================================
# 阶段 1: Skill 构建器测试
# ============================================================
log_section "阶段 1: Skill 构建器测试"

# 1.1 Python 单元测试
CURRENT_TEST="Skill 构建器单元测试"
if python3 "$PROJECT_ROOT/tests/test_skill_builder.py" > /dev/null 2>&1; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 1.2 创建测试 skill 目录
TEST_SKILLS="$TEST_TMP/skills"
mkdir -p "$TEST_SKILLS"

# 创建纯 Python skill
mkdir -p "$TEST_SKILLS/pdf_tool"
cat > "$TEST_SKILLS/pdf_tool/SKILL.md" << 'EOF'
---
name: pdf_tool
version: 1.0.0
description: PDF processing tool
category: document
author: test
---
# PDF Tool
EOF

cat > "$TEST_SKILLS/pdf_tool/main.py" << 'EOF'
def main(args):
    return {"result": "processed", "file": args.get("file_path", "unknown")}
EOF

cat > "$TEST_SKILLS/pdf_tool/requirements.txt" << 'EOF'
pdfplumber
openpyxl
EOF

# 创建含 C 扩展依赖的 skill
mkdir -p "$TEST_SKILLS/image_tool"
cat > "$TEST_SKILLS/image_tool/SKILL.md" << 'EOF'
---
name: image_tool
version: 1.0.0
description: Image processing tool
category: media
author: test
---
# Image Tool
EOF

cat > "$TEST_SKILLS/image_tool/main.py" << 'EOF'
from PIL import Image
def main(args):
    return {"result": "image_processed"}
EOF

cat > "$TEST_SKILLS/image_tool/requirements.txt" << 'EOF'
Pillow
pdfplumber
EOF

# 1.3 运行智能打包
CURRENT_TEST="智能打包流水线（批量）"
OUTPUT_DIR="$TEST_TMP/android_skills"
if python3 "$PROJECT_ROOT/smart_skill_builder.py" "$TEST_SKILLS" -o "$OUTPUT_DIR" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 1.4 验证 .bsk 文件生成
CURRENT_TEST="生成 pdf_tool.bsk"
if [ -f "$OUTPUT_DIR/pdf_tool.bsk" ]; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="生成 image_tool.bsk"
if [ -f "$OUTPUT_DIR/image_tool.bsk" ]; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 1.5 验证 .bsk 内容
CURRENT_TEST="pdf_tool.bsk 包含 manifest.json"
if python3 -c "
import zipfile, json
with zipfile.ZipFile('$OUTPUT_DIR/pdf_tool.bsk') as z:
    m = json.loads(z.read('manifest.json'))
    assert m['skill_type'] == 'pure_python'
    assert 'pdfplumber' in m['dependencies']['pure_python']
" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="image_tool.bsk 标记为 c_extension"
if python3 -c "
import zipfile, json
with zipfile.ZipFile('$OUTPUT_DIR/image_tool.bsk') as z:
    m = json.loads(z.read('manifest.json'))
    assert m['skill_type'] == 'c_extension'
    assert 'Pillow' in m['dependencies']['c_extensions']
" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="构建报告生成"
if [ -f "$OUTPUT_DIR/build_report.json" ]; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# ============================================================
# 阶段 2: Skill 市场后端测试
# ============================================================
log_section "阶段 2: Skill 市场后端测试"

BACKEND_DIR="$PROJECT_ROOT/skill-market/backend"

# 2.1 检查依赖
CURRENT_TEST="FastAPI 后端依赖检查"
if python3 -c "import fastapi, uvicorn" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_skip "$CURRENT_TEST (FastAPI 未安装)"
fi

# 2.2 启动后端
CURRENT_TEST="启动 Skill 市场后端"
if python3 -c "import fastapi" 2>/dev/null; then
    STORAGE_DIR="$TEST_TMP/skill_storage"
    mkdir -p "$STORAGE_DIR"
    
    export SKILL_MARKET_STORAGE_DIR="$STORAGE_DIR"
    cd "$BACKEND_DIR"
    python3 -m uvicorn app:app --host 127.0.0.1 --port 18099 &
    SERVER_PID=$!
    cd "$PROJECT_ROOT"
    
    # 等待启动
    sleep 2
    
    if kill -0 $SERVER_PID 2>/dev/null; then
        log_pass "$CURRENT_TEST"
    else
        log_fail "$CURRENT_TEST"
    fi
else
    log_skip "$CURRENT_TEST (FastAPI 未安装)"
    SERVER_PID=""
fi

# 2.3 健康检查
if [ -n "$SERVER_PID" ]; then
    CURRENT_TEST="后端健康检查"
    if curl -s http://127.0.0.1:18099/api/health | python3 -c "import sys,json; assert json.load(sys.stdin)['status']=='ok'" 2>/dev/null; then
        log_pass "$CURRENT_TEST"
    else
        log_fail "$CURRENT_TEST"
    fi
    
    # 2.4 上传 skill
    CURRENT_TEST="上传 pdf_tool.bsk"
    UPLOAD_RESULT=$(curl -s -X POST http://127.0.0.1:18099/api/skills/upload \
        -F "metadata={\"id\":\"pdf_tool\",\"name\":\"pdf_tool\",\"version\":\"1.0.0\",\"description\":\"test\",\"category\":\"document\",\"author\":\"test\",\"arch\":[\"arm64-v8a\"],\"min_app_version\":\"1.0.0\",\"skill_type\":\"pure_python\",\"language\":\"python\",\"entry\":\"__init__.py\",\"entry_function\":\"run\",\"dependencies\":{}}" \
        -F "file=@$OUTPUT_DIR/pdf_tool.bsk" 2>/dev/null)
    
    if echo "$UPLOAD_RESULT" | python3 -c "import sys,json; assert json.load(sys.stdin)['success']==True" 2>/dev/null; then
        log_pass "$CURRENT_TEST"
    else
        log_fail "$CURRENT_TEST"
    fi
    
    # 2.5 查询 skill 列表
    CURRENT_TEST="查询 skill 列表"
    if curl -s "http://127.0.0.1:18099/api/skills?arch=arm64-v8a" | python3 -c "import sys,json; d=json.load(sys.stdin); assert len(d['skills'])>=1" 2>/dev/null; then
        log_pass "$CURRENT_TEST"
    else
        log_fail "$CURRENT_TEST"
    fi
    
    # 2.6 搜索 skill
    CURRENT_TEST="搜索 skill"
    if curl -s "http://127.0.0.1:18099/api/skills?search=pdf" | python3 -c "import sys,json; d=json.load(sys.stdin); assert any(s['id']=='pdf_tool' for s in d['skills'])" 2>/dev/null; then
        log_pass "$CURRENT_TEST"
    else
        log_fail "$CURRENT_TEST"
    fi
    
    # 2.7 下载 skill
    CURRENT_TEST="下载 pdf_tool.bsk"
    DOWNLOAD_FILE="$TEST_TMP/downloaded.bsk"
    if curl -s -o "$DOWNLOAD_FILE" "http://127.0.0.1:18099/api/skills/pdf_tool/1.0.0/download" && [ -s "$DOWNLOAD_FILE" ]; then
        log_pass "$CURRENT_TEST"
    else
        log_fail "$CURRENT_TEST"
    fi
    
    # 2.8 验证下载文件完整性
    CURRENT_TEST="下载文件完整性"
    if python3 -c "
import zipfile
with zipfile.ZipFile('$DOWNLOAD_FILE') as z:
    assert 'manifest.json' in z.namelist()
" 2>/dev/null; then
        log_pass "$CURRENT_TEST"
    else
        log_fail "$CURRENT_TEST"
    fi
    
    # 2.9 获取分类列表
    CURRENT_TEST="获取分类列表"
    if curl -s "http://127.0.0.1:18099/api/categories" | python3 -c "import sys,json; d=json.load(sys.stdin); assert 'document' in d['categories']" 2>/dev/null; then
        log_pass "$CURRENT_TEST"
    else
        log_fail "$CURRENT_TEST"
    fi
    
    # 关闭服务器
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
fi

# ============================================================
# 阶段 3: 脚本语法检查
# ============================================================
log_section "阶段 3: 脚本和代码检查"

# 3.1 Bash 脚本语法
CURRENT_TEST="pybridge-build-android.sh 语法"
if bash -n "$SCRIPTS_DIR/pybridge-build-android.sh" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="pybridge-build-packages.sh 语法"
if bash -n "$SCRIPTS_DIR/pybridge-build-packages.sh" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 3.2 Python 语法
CURRENT_TEST="smart_skill_builder.py 语法"
if python3 -c "import py_compile; py_compile.compile('$PROJECT_ROOT/smart_skill_builder.py', doraise=True)" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="pybridge __init__.py 语法"
if python3 -c "import py_compile; py_compile.compile('$PROJECT_ROOT/pybridge-runtime/src/main/python/pybridge/__init__.py', doraise=True)" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="skill_loader.py 语法"
if python3 -c "import py_compile; py_compile.compile('$PROJECT_ROOT/pybridge-runtime/src/main/python/pybridge/skill_loader.py', doraise=True)" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="skill_market app.py 语法"
if python3 -c "import py_compile; py_compile.compile('$BACKEND_DIR/app.py', doraise=True)" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="skill_market storage.py 语法"
if python3 -c "import py_compile; py_compile.compile('$BACKEND_DIR/storage.py', doraise=True)" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 3.3 Kotlin 文件存在性检查
CURRENT_TEST="Gradle 插件文件完整性"
PLUGIN_DIR="$PROJECT_ROOT/pybridge-gradle-plugin/src/main/kotlin/com/pybridge"
if [ -f "$PLUGIN_DIR/PyBridgeExtension.kt" ] && [ -f "$PLUGIN_DIR/PyBridgePlugin.kt" ]; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="Skill 市场客户端文件完整性"
CLIENT_DIR="$PROJECT_ROOT/skill-market/client"
if [ -f "$CLIENT_DIR/SkillMarketManager.kt" ] && \
   [ -f "$CLIENT_DIR/SkillModels.kt" ] && \
   [ -f "$CLIENT_DIR/PyBridgeInterface.kt" ] && \
   [ -f "$CLIENT_DIR/SkillMarketViewModel.kt" ]; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 3.4 JNI / CMake 文件检查
CURRENT_TEST="JNI 桥接层文件"
if [ -f "$PROJECT_ROOT/pybridge-runtime/src/main/cpp/pybridge_jni.cpp" ]; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

CURRENT_TEST="CMakeLists.txt 文件"
if [ -f "$PROJECT_ROOT/pybridge-runtime/src/main/cpp/CMakeLists.txt" ]; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# ============================================================
# 阶段 4: .bsk 格式验证
# ============================================================
log_section "阶段 4: .bsk 格式验证"

# 4.1 验证 .bsk 是有效的 ZIP
CURRENT_TEST=".bsk 是有效的 ZIP 格式"
if python3 -c "
import zipfile
with zipfile.ZipFile('$OUTPUT_DIR/pdf_tool.bsk') as z:
    assert z.testzip() is None
" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 4.2 验证 manifest.json 必需字段
CURRENT_TEST="manifest.json 包含所有必需字段"
if python3 -c "
import zipfile, json
with zipfile.ZipFile('$OUTPUT_DIR/pdf_tool.bsk') as z:
    m = json.loads(z.read('manifest.json'))
    required = ['id','name','version','description','entry','entry_function',
                'category','language','min_app_version','arch','skill_type','dependencies']
    for f in required:
        assert f in m, f'Missing: {f}'
" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 4.3 验证入口包装器
CURRENT_TEST="入口包装器包含 run() 函数"
if python3 -c "
import zipfile
with zipfile.ZipFile('$OUTPUT_DIR/pdf_tool.bsk') as z:
    code = z.read('__init__.py').decode()
    assert 'def run(args' in code
    assert 'from skill.main import main' in code
" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# 4.4 验证 skill 代码完整性
CURRENT_TEST="skill 源码完整打包"
if python3 -c "
import zipfile
with zipfile.ZipFile('$OUTPUT_DIR/pdf_tool.bsk') as z:
    names = z.namelist()
    assert 'skill/main.py' in names
    assert 'skill/SKILL.md' in names
" 2>/dev/null; then
    log_pass "$CURRENT_TEST"
else
    log_fail "$CURRENT_TEST"
fi

# ============================================================
# 汇总
# ============================================================
log_section "测试汇总"
echo -e "  ${GREEN}通过: $PASS${NC}"
echo -e "  ${RED}失败: $FAIL${NC}"
echo -e "  ${YELLOW}跳过: $SKIP${NC}"
echo -e "  总计: $((PASS+FAIL+SKIP))"
echo ""

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}✓ 所有测试通过${NC}"
    exit 0
else
    echo -e "${RED}✗ 有 $FAIL 个测试失败${NC}"
    exit 1
fi
