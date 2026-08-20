#!/usr/bin/env bash
# ============================================================
# mwb-ai-claw CI 脚本：compile + test + package 全量
#
# 阶段:
#   1. mvn clean test   全量编译 + 单元/集成测试
#   2. package.sh       复用打包脚本生成二进制分发包
#
# 用法:
#   ./ci.sh                 全量 CI（clean test + package）
#   ./ci.sh --skip-tests    跳过测试仅编译打包（快速验证）
#   ./ci.sh --help
# 退出码: 0=通过, 1=失败
# ============================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -t 1 ]]; then
    C_CYAN='\033[0;36m'; C_GREEN='\033[0;32m'; C_RED='\033[0;31m'; C_NC='\033[0m'
else
    C_CYAN=''; C_GREEN=''; C_RED=''; C_NC=''
fi
info() { printf "${C_CYAN}[ci]${C_NC} %s\n" "$*"; }
ok()   { printf "${C_GREEN}[ci]${C_NC} %s\n" "$*"; }
err()  { printf "${C_RED}[ci]${C_NC} %s\n" "$*" >&2; }

SKIP_TESTS=false
case "${1:-}" in
    --help|-h) echo "用法: ./ci.sh [--skip-tests]"; exit 0 ;;
    --skip-tests) SKIP_TESTS=true ;;
    "") ;;
    *) err "未知参数: $1"; exit 1 ;;
esac

command -v mvn >/dev/null 2>&1 || { err "缺少 mvn，请先安装 Maven"; exit 1; }

# ---------------- 阶段 1: 编译 + 测试 ----------------
if [[ "$SKIP_TESTS" == "true" ]]; then
    info "阶段 1/2: 编译（跳过测试）..."
    (cd "$PROJECT_ROOT" && mvn compile -q) || { err "编译失败"; exit 1; }
else
    info "阶段 1/2: 全量编译 + 测试（mvn clean test）..."
    (cd "$PROJECT_ROOT" && mvn clean test -q) || { err "编译或测试失败"; exit 1; }
fi
ok "编译与测试通过"

# ---------------- 阶段 2: 打包 ----------------
info "阶段 2/2: 打包二进制分发包（复用 package.sh）..."
(cd "$PROJECT_ROOT" && "$PROJECT_ROOT/tools/package.sh") || { err "打包失败"; exit 1; }
ok "打包完成"

echo
printf "${C_GREEN}[ci]${C_NC} CI 全部通过\n"
exit 0
