#!/usr/bin/env bash
# ============================================================
# mwb-ai-claw CI 脚本：compile + test + package + eval 回归门
#
# 阶段:
#   1. mvn clean test   全量编译 + 单元/集成测试
#   2. package.sh       复用打包脚本生成二进制分发包
#   3. eval 回归门      （opt-in）对比 baseline/current 报告，回归即失败
#
# 用法:
#   ./ci.sh                 全量 CI（clean test + package）
#   ./ci.sh --skip-tests    跳过测试仅编译打包（快速验证）
#   ./ci.sh --help
# 退出码: 0=通过, 1=失败
#
# 评测回归门（可选，配置以下任一环境变量即启用阶段 3）:
#   EVAL_BASELINE=基线报告JSON路径
#   EVAL_CURRENT=当前报告JSON路径
#   EVAL_FAIL_ON_REGRESSION=true|false（默认 true，回归即 fail）
# 未提供 EVAL_BASELINE / EVAL_CURRENT 时自动跳过阶段 3。
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

# 阶段 3: 评测回归门（opt-in）。配置 EVAL_BASELINE/EVAL_CURRENT 时启用：
#   1) 先安装插件模块，解析真实 revision；
#   2) 以 eval:diff 目标对比两张报告，回归且 EVAL_FAIL_ON_REGRESSION != false 时 fail。
verify_eval_gate() {
    local baseline="${EVAL_BASELINE:-}" current="${EVAL_CURRENT:-}"
    if [[ -z "$baseline" || -z "$current" ]]; then
        info "阶段 3/3: 跳过评测回归门（未提供 EVAL_BASELINE/EVAL_CURRENT）"
        return 0
    fi
    info "阶段 3/3: 评测回归门（eval:diff）..."
    (cd "$PROJECT_ROOT" && mvn -q -pl mwb-ai-claw-eval-maven-plugin -am install -DskipTests) \
        || { err "评测插件构建失败"; exit 1; }
    local revision
    revision="$(cd "$PROJECT_ROOT" && mvn -q help:evaluate -Dexpression=revision -DforceStdout 2>/dev/null)" \
        || { err "解析版本号失败"; exit 1; }
    (cd "$PROJECT_ROOT" && mvn -q "io.github.mwb1219:mwb-ai-claw-eval-maven-plugin:${revision}:diff" \
        -Deval.baseline="$baseline" -Deval.current="$current" \
        -Deval.failOnRegression="${EVAL_FAIL_ON_REGRESSION:-true}") \
        || { err "评测存在回归，详情见上方 eval:diff 输出"; exit 1; }
}

# ---------------- 阶段 1: 编译 + 测试 ----------------
if [[ "$SKIP_TESTS" == "true" ]]; then
    info "阶段 1/3: 编译（跳过测试）..."
    (cd "$PROJECT_ROOT" && mvn compile -q) || { err "编译失败"; exit 1; }
else
    info "阶段 1/3: 全量编译 + 测试（mvn clean test）..."
    (cd "$PROJECT_ROOT" && mvn clean test -q) || { err "编译或测试失败"; exit 1; }
fi
ok "编译与测试通过"

# ---------------- 阶段 2: 打包 ----------------
info "阶段 2/3: 打包二进制分发包（复用 package.sh）..."
(cd "$PROJECT_ROOT" && "$PROJECT_ROOT/tools/package.sh") || { err "打包失败"; exit 1; }
ok "打包完成"

# ---------------- 阶段 3: 评测回归门（opt-in） ----------------
verify_eval_gate
ok "评测回归门通过"

echo
printf "${C_GREEN}[ci]${C_NC} CI 全部通过\n"
exit 0
