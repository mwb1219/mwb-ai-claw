#!/usr/bin/env bash
# ============================================================
# mwb-ai-claw 一键打包 + 安装脚本
#
# 顺序执行:
#   1. 调用 package.sh 构建并打包分发包（tar.gz）
#   2. 解压刚生成的包到临时目录
#   3. 以二进制模式执行包内 install.sh 完成本地安装（验证包可用）
#   4. 清理临时目录
#
# 适合项目维护者本地"构建 + 验证打包 + 安装"一步完成。
# 若仅想安装（不打包），直接执行 ./install.sh 即可。
#
# 用法:
#   ./tools/setup.sh               构建 + 打包 + 安装
#   ./tools/setup.sh --skip-build   跳过 mvn 构建（复用已构建 jar）+ 打包 + 安装
#   ./tools/setup.sh --help
# ============================================================
set -euo pipefail

# 脚本在 tools/ 下，项目根为上级目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------------- 颜色 ----------------
if [[ -t 1 ]]; then
    C_CYAN='\033[0;36m'; C_GREEN='\033[0;32m'; C_YELLOW='\033[1;33m'
    C_RED='\033[0;31m'; C_NC='\033[0m'
else
    C_CYAN=''; C_GREEN=''; C_YELLOW=''; C_RED=''; C_NC=''
fi
info()  { printf "${C_CYAN}[setup]${C_NC} %s\n" "$*" >&2; }
ok()    { printf "${C_GREEN}[setup]${C_NC} %s\n" "$*" >&2; }
warn()  { printf "${C_YELLOW}[setup]${C_NC} %s\n" "$*" >&2; }
err()   { printf "${C_RED}[setup]${C_NC} %s\n" "$*" >&2; }

print_help() {
    cat <<EOF
mwb-ai-claw 一键打包 + 安装脚本

顺序执行: 构建 → 打包分发包 → 用该包本地安装 → 清理临时目录。

用法:
    ./tools/setup.sh               构建 + 打包 + 安装
    ./tools/setup.sh --skip-build   跳过 mvn 构建（复用已构建 jar）+ 打包 + 安装
    ./tools/setup.sh --help         显示本帮助

步骤:
    1. 执行 tools/package.sh 生成 dist/mwb-ai-claw-<version>-bin.tar.gz
    2. 解压该包到临时目录
    3. 执行包内 install.sh 以二进制模式安装（验证包可用，不重复 mvn）
    4. 清理临时目录

安装完成后任意目录执行 \`mwb-ai-claw\` 进入 Agent Shell。
EOF
}

# ---------------- 解析参数 ----------------
PKG_ARGS=()
case "${1:-}" in
    --help|-h) print_help; exit 0 ;;
    --skip-build) PKG_ARGS+=("--skip-build") ;;
    "") ;;
    *) err "未知参数: $1"; print_help; exit 1 ;;
esac

# ---------------- 前置检查 ----------------
command -v java >/dev/null 2>&1 || { err "缺少 java (JDK 8+)"; exit 1; }
# 打包需要 mvn（除非 --skip-build）
if [[ "${#PKG_ARGS[@]}" -eq 0 ]]; then
    command -v mvn >/dev/null 2>&1 || { err "缺少 mvn (Maven 3.6+)"; exit 1; }
fi

# ---------------- 1. 打包 ----------------
info "步骤 1/3: 打包分发包"
bash "$SCRIPT_DIR/package.sh" ${PKG_ARGS[@]+"${PKG_ARGS[@]}"}

# ---------------- 解析产物路径 ----------------
VERSION="$(grep -m1 '<revision>' "$PROJECT_ROOT/pom.xml" | sed 's/.*<revision>\([^<]*\)<\/revision>.*/\1/' || true)"
if [[ -z "${VERSION:-}" ]]; then
    err "无法从 pom.xml 解析版本号"
    exit 1
fi
ARCHIVE="$PROJECT_ROOT/dist/mwb-ai-claw-${VERSION}-bin.tar.gz"
if [[ ! -f "$ARCHIVE" ]]; then
    err "打包产物不存在: $ARCHIVE"
    exit 1
fi
ok "分发包: $ARCHIVE"

# ---------------- 2. 解压 + 安装 ----------------
info "步骤 2/3: 解压并用包内 install.sh 安装"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

tar -xzf "$ARCHIVE" -C "$TMP_DIR"
DIST_DIR="$TMP_DIR/mwb-ai-claw-${VERSION}-bin"
if [[ ! -d "$DIST_DIR" ]]; then
    err "解压后未找到目录: $DIST_DIR"
    exit 1
fi

# 执行包内 install.sh（自动识别二进制模式，跳过 mvn）
bash "$DIST_DIR/install.sh"

# ---------------- 3. 完成 ----------------
ok "步骤 3/3: 清理临时目录"
ok "一键打包 + 安装完成!"
echo
printf "  ${C_GREEN}分发包${C_NC}: $ARCHIVE\n"
printf "  ${C_GREEN}命令${C_NC}: 任意目录执行 ${C_CYAN}mwb-ai-claw${C_NC} 进入 Agent Shell\n"
echo
