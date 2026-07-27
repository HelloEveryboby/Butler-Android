#!/bin/bash
#
# sync-web-resources.sh
# 将 Vite 构建的 dist 目录内容同步到 HarmonyOS rawfile 目录
# 并自动修正 index.html 中的资源路径（绝对路径 -> 相对路径）
#

set -e

# 脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Web 资源源目录（dist 目录，位于上级目录）
DIST_DIR="${SCRIPT_DIR}/../dist"

# rawfile 目标目录
RAWFILE_DIR="${SCRIPT_DIR}/entry/src/main/resources/rawfile"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Butler AI - Web 资源同步脚本${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 检查 dist 目录是否存在
if [ ! -d "$DIST_DIR" ]; then
    echo -e "${RED}[错误] dist 目录不存在: ${DIST_DIR}${NC}"
    echo -e "${YELLOW}请先在项目根目录执行 'npm run build' 构建 Web 资源。${NC}"
    exit 1
fi

# 检查 dist/index.html 是否存在
if [ ! -f "$DIST_DIR/index.html" ]; then
    echo -e "${RED}[错误] dist/index.html 不存在${NC}"
    echo -e "${YELLOW}请确认 Vite 构建已成功完成。${NC}"
    exit 1
fi

echo -e "${GREEN}[1/4] 清空旧的 rawfile 内容...${NC}"

# 清空 rawfile 目录中的旧内容（保留 .gitkeep）
find "$RAWFILE_DIR" -mindepth 1 ! -name '.gitkeep' -delete 2>/dev/null || true

echo -e "${GREEN}[2/4] 复制 dist 内容到 rawfile...${NC}"

# 复制 dist 目录的所有内容到 rawfile
cp -r "$DIST_DIR"/* "$RAWFILE_DIR/"

echo -e "      已复制以下内容:"
ls -1 "$DIST_DIR" | while read -r item; do
    echo -e "        - ${item}"
done

echo -e "${GREEN}[3/4] 修正 index.html 中的资源路径...${NC}"

INDEX_FILE="${RAWFILE_DIR}/index.html"

# 检查是否存在绝对路径并修正
if grep -q '"/assets/' "$INDEX_FILE" || grep -q "'/assets/" "$INDEX_FILE"; then
    # 将 "/assets/ 替换为 "./assets/
    sed -i 's|"/assets/|"./assets/|g' "$INDEX_FILE"
    sed -i "s|'/assets/|'./assets/|g" "$INDEX_FILE"
    # 将 =/assets/ 替换为 =./assets/（处理无引号的情况）
    sed -i 's|=/assets/|=./assets/|g' "$INDEX_FILE"
    echo -e "      ${YELLOW}已将绝对路径 /assets/ 修正为相对路径 ./assets/${NC}"
else
    echo -e "      路径已是相对路径，无需修正"
fi

# 检查并修正 module 类型的 script 标签中的路径
if grep -q 'src="/' "$INDEX_FILE"; then
    sed -i 's|src="/|src="./|g' "$INDEX_FILE"
    echo -e "      ${YELLOW}已修正 script 标签中的绝对路径${NC}"
fi

if grep -q 'href="/' "$INDEX_FILE"; then
    sed -i 's|href="/|href="./|g' "$INDEX_FILE"
    echo -e "      ${YELLOW}已修正 link 标签中的绝对路径${NC}"
fi

echo -e "${GREEN}[4/4] 验证同步结果...${NC}"

# 验证文件
if [ -f "$RAWFILE_DIR/index.html" ]; then
    echo -e "      ${GREEN}index.html - OK${NC}"
else
    echo -e "      ${RED}index.html - 缺失!${NC}"
    exit 1
fi

if [ -d "$RAWFILE_DIR/assets" ]; then
    ASSET_COUNT=$(ls -1 "$RAWFILE_DIR/assets" | wc -l)
    echo -e "      ${GREEN}assets/ - OK (${ASSET_COUNT} 个文件)${NC}"
else
    echo -e "      ${RED}assets/ - 目录缺失!${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  同步完成!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "资源已同步到: ${RAWFILE_DIR}"
echo -e "现在可以在 DevEco Studio 中构建并运行鸿蒙应用。"
echo ""
