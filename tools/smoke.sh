#!/usr/bin/env bash
# ============================================================
# mwb-ai-claw PhaseC/D E2E 冒烟脚本
#
# 覆盖五个场景（web 模式 18080 + 本地 mock LLM Server）:
#   1. echo       LLM 成功 → success=true
#   2. fail       LLM 恒 500  → errCode=LLM_UNAVAILABLE
#   3. tool       工具执行超时兜底 → 轨迹含「工具执行超时」
#   4. anthropic  provider=anthropic 协议直连 → success=true
#   5. gemini     provider=gemini 协议直连 → success=true
#
# 用法:
#   ./smoke.sh                构建 jar 并全量冒烟
#   ./smoke.sh --skip-build   复用已构建 jar（start/target）
#   ./smoke.sh --help
# 退出码: 0=全部通过, 1=任一场景失败
# ============================================================
set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR=""
PORT=18080
MOCK_PORT=19996
PASS=0
FAIL=0

if [[ -t 1 ]]; then
    C_GREEN='\033[0;32m'; C_RED='\033[0;31m'; C_CYAN='\033[0;36m'; C_NC='\033[0m'
else
    C_GREEN=''; C_RED=''; C_CYAN=''; C_NC=''
fi
info() { printf "${C_CYAN}[smoke]${C_NC} %s\n" "$*"; }
ok()   { printf "${C_GREEN}[smoke]${C_NC} PASS %s\n" "$*"; PASS=$((PASS+1)); }
bad()  { printf "${C_RED}[smoke]${C_NC} FAIL %s\n" "$*" >&2; FAIL=$((FAIL+1)); }
warn() { printf "${C_CYAN}[smoke]${C_NC} WARN %s\n" "$*" >&2; }

case "${1:-}" in
    --help|-h)
        echo "用法: ./smoke.sh [--skip-build]"; exit 0 ;;
    --skip-build) SKIP_BUILD=true ;;
    "") SKIP_BUILD=false ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
esac

# ---------------- 构建 ----------------
if [[ "$SKIP_BUILD" != "true" ]]; then
    info "构建 start jar..."
    (cd "$PROJECT_ROOT" && mvn package -pl start -am -DskipTests -q) || { bad "构建失败"; exit 1; }
fi
JAR="$(ls "$PROJECT_ROOT"/start/target/start-*.jar 2>/dev/null | grep -vE '(-javadoc\.jar$|-sources\.jar$|\.original$)' | head -1 || true)"
if [[ -z "${JAR:-}" || ! -f "$JAR" ]]; then
    bad "未找到 start/target/start-*.jar（先执行 ./smoke.sh 构建）"
    exit 1
fi
info "使用 jar: $JAR"

# ---------------- 进程管理 ----------------
MOCK_PID=""; WEB_PID=""
cleanup() {
    [[ -n "$WEB_PID" ]] && kill "$WEB_PID" 2>/dev/null
    [[ -n "$MOCK_PID" ]] && kill "$MOCK_PID" 2>/dev/null
    wait 2>/dev/null
}
trap cleanup EXIT

start_mock() {
    local mode="$1"
    nohup python3 "$PROJECT_ROOT/tools/mock_llm.py" --port "$MOCK_PORT" --mode "$mode" \
        > /tmp/claw_smoke_mock.log 2>&1 &
    MOCK_PID=$!
    sleep 1
    curl -s --max-time 1 "http://127.0.0.1:$MOCK_PORT/v1" > /dev/null 2>&1 || true
}

start_web() {
    local extra="$1"
    CLAW_LOG_PATH=/tmp/clawlogs nohup java -jar "$JAR" \
        --spring.profiles.active=web --server.port="$PORT" \
        --agent.base-url="http://127.0.0.1:$MOCK_PORT/v1" --agent.model=mock-model \
        --agent.tools=shell $extra > /tmp/claw_smoke_web.log 2>&1 &
    WEB_PID=$!
    # 等待端口就绪（最多 20s）；用轻量 GET 探测，避免触发 LLM 调用
    for _ in $(seq 1 20); do
        if curl -s --max-time 2 "http://127.0.0.1:$PORT/agent/sessions" > /dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    bad "web 服务启动超时"; return 1
}

stop_web() {
    [[ -n "$WEB_PID" ]] && kill "$WEB_PID" 2>/dev/null
    WEB_PID=""
    # 等待端口释放，避免下一场景启动冲突（最多 10s）
    for _ in $(seq 1 10); do
        if ! curl -s --max-time 1 "http://127.0.0.1:$PORT/agent/sessions" > /dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    warn "端口 $PORT 未在 10s 内释放"
}

chat() {
    curl -s --max-time 60 -X POST "http://127.0.0.1:$PORT/agent/chat" \
        -H 'Content-Type: application/json' -d "$1"
}

# ---------------- 场景 1: LLM 成功 ----------------
info "场景 1/5: LLM 成功（echo）..."
start_mock echo
start_web "" || exit 1
RESP=$(chat '{"message":"hello"}')
if echo "$RESP" | grep -q '"success":true' && echo "$RESP" | grep -q '"reply"'; then
    ok "LLM 成功 → success=true"
else
    bad "LLM 成功响应异常: $RESP"
fi
stop_web; kill "$MOCK_PID" 2>/dev/null; MOCK_PID=""

# ---------------- 场景 2: LLM 恒 500 ----------------
info "场景 2/5: LLM 恒 500 → LLM_UNAVAILABLE..."
start_mock fail
start_web "" || exit 1
RESP=$(chat '{"message":"hello"}')
if echo "$RESP" | grep -q 'LLM_UNAVAILABLE'; then
    ok "LLM 失败 → errCode=LLM_UNAVAILABLE"
else
    bad "LLM 失败响应异常: $RESP"
fi
stop_web; kill "$MOCK_PID" 2>/dev/null; MOCK_PID=""

# ---------------- 场景 3: 工具执行超时兜底 ----------------
info "场景 3/5: 工具执行超时兜底（tool-timeout=2s）..."
start_mock tool
start_web "--agent.security.tool-timeout-seconds=2" || exit 1
RESP=$(chat '{"message":"执行那个慢命令"}')
if echo "$RESP" | grep -q '工具执行超时'; then
    ok "工具超时兜底 → 轨迹含「工具执行超时」"
else
    bad "工具超时响应异常: $RESP"
fi
stop_web; kill "$MOCK_PID" 2>/dev/null; MOCK_PID=""

# ---------------- 场景 4: Anthropic 协议直连 ----------------
info "场景 4/5: Anthropic 协议（provider=anthropic）..."
start_mock anthropic
start_web "--agent.provider=anthropic" || exit 1
RESP=$(chat '{"message":"hello"}')
if echo "$RESP" | grep -q '"success":true' && echo "$RESP" | grep -q '"reply"'; then
    ok "Anthropic 协议 → success=true"
else
    bad "Anthropic 协议响应异常: $RESP"
fi
stop_web; kill "$MOCK_PID" 2>/dev/null; MOCK_PID=""

# ---------------- 场景 5: Gemini 协议直连 ----------------
info "场景 5/5: Gemini 协议（provider=gemini）..."
start_mock gemini
start_web "--agent.provider=gemini --agent.base-url=http://127.0.0.1:$MOCK_PORT/v1beta" || exit 1
RESP=$(chat '{"message":"hello"}')
if echo "$RESP" | grep -q '"success":true' && echo "$RESP" | grep -q '"reply"'; then
    ok "Gemini 协议 → success=true"
else
    bad "Gemini 协议响应异常: $RESP"
fi

# ---------------- 汇总 ----------------
echo
if [[ "$FAIL" -eq 0 ]]; then
    printf "${C_GREEN}[smoke]${C_NC} 全部通过: PASS=%d FAIL=%d\n" "$PASS" "$FAIL"
    exit 0
else
    printf "${C_RED}[smoke]${C_NC} 存在失败: PASS=%d FAIL=%d\n" "$PASS" "$FAIL"
    exit 1
fi
