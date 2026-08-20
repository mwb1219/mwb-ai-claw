#!/usr/bin/env bash
# ============================================================
# mwb-ai-claw 本地安装脚本
#
# 安装完成后可在终端任意目录直接执行 `mwb-ai-claw` 命令
# 进入 Agent Shell 交互模式（类似 claude 终端命令）。
#
# 用法:
#   ./install.sh                # 安装 / 升级
#   ./install.sh --uninstall    # 卸载
#   ./install.sh --help
#
# 环境变量（可选）:
#   MWB_AI_CLAW_HOME            安装根目录，默认 ~/.mwb-ai-claw
#   MWB_AI_CLAW_APPROVAL_MODE   Shell 审批模式覆盖（auto/ask/read-only），默认 ask
# ============================================================
set -euo pipefail

# ---------------- 常量 ----------------
COMMAND_NAME="mwb-ai-claw"
INSTALL_DIR="${MWB_AI_CLAW_HOME:-$HOME/.mwb-ai-claw}"
LIB_DIR="$INSTALL_DIR/lib"
BIN_DIR="$INSTALL_DIR/bin"
ENV_FILE="$INSTALL_DIR/.env"

# 脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 项目根目录定位（兼容两种部署位置）:
#   - 二进制分发包：install.sh 在包根目录，同目录存在 lib/start.jar → PROJECT_ROOT = SCRIPT_DIR
#   - 源码树：       install.sh 在 tools/ 下，上级有 pom.xml             → PROJECT_ROOT = SCRIPT_DIR/..
# 先按二进制分发检测（lib/start.jar 是否存在），否则回退到上级目录。
if [[ -f "$SCRIPT_DIR/lib/start.jar" ]]; then
    PROJECT_ROOT="$SCRIPT_DIR"
else
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi

# 二进制分发模式检测：若 PROJECT_ROOT/lib/start.jar 存在，则认为是预构建的分发包，
# 直接使用该 jar，跳过 Maven 构建（无需源码、无需 mvn）。
BUNDLED_JAR="$PROJECT_ROOT/lib/start.jar"
IS_BINARY_DIST=false
[[ -f "$BUNDLED_JAR" ]] && IS_BINARY_DIST=true

# ---------------- 颜色 ----------------
if [[ -t 1 ]]; then
    C_CYAN='\033[0;36m'; C_GREEN='\033[0;32m'; C_YELLOW='\033[1;33m'
    C_RED='\033[0;31m'; C_BOLD='\033[1m'; C_NC='\033[0m'
else
    C_CYAN=''; C_GREEN=''; C_YELLOW=''; C_RED=''; C_BOLD=''; C_NC=''
fi
# 注意: 日志一律输出到 stderr。原因: build_project 等函数通过命令替换
# $(...) 捕获 stdout 作为返回值, 若日志写到 stdout 会污染返回值。
info()  { printf "${C_CYAN}[mwb-ai-claw]${C_NC} %s\n" "$*" >&2; }
ok()    { printf "${C_GREEN}[mwb-ai-claw]${C_NC} %s\n" "$*" >&2; }
warn()  { printf "${C_YELLOW}[mwb-ai-claw]${C_NC} %s\n" "$*" >&2; }
err()   { printf "${C_RED}[mwb-ai-claw]${C_NC} %s\n" "$*" >&2; }

# ---------------- 帮助 ----------------
print_help() {
    cat <<EOF
mwb-ai-claw 本地安装脚本

用法:
    ./install.sh                安装或升级 mwb-ai-claw 命令
    ./install.sh --uninstall    卸载 mwb-ai-claw 命令及安装目录
    ./install.sh --help         显示本帮助

运行模式（自动检测）:
    - 二进制分发模式：脚本同目录存在 lib/start.jar 时启用，直接安装预构建 jar（无需源码/mvn）
    - 源码模式：      否则用 mvn package 从源码构建后安装

安装位置 (可通过 MWB_AI_CLAW_HOME 覆盖):
    $INSTALL_DIR
        ├── lib/start.jar        构建产物
        ├── config/              Agent/编排/MCP 配置模板（修改后重启即生效）
        ├── skills/              技能模板（增删技能目录即自定义技能集）
        ├── bin/$COMMAND_NAME    启动器脚本
        ├── .env.example         密钥模板副本（参考/重置用）
        └── .env                 全局密钥配置（DEFAULT_API_KEY 等）

PATH 链接:
    优先写入 /usr/local/bin（若可写）；否则写入 ~/.local/bin。
    若该目录不在 PATH 中，脚本会提示添加方法。

安装后任意目录执行 \`$COMMAND_NAME\` 即可进入 Agent Shell。
EOF
}

# ---------------- 卸载 ----------------
do_uninstall() {
    info "开始卸载 $COMMAND_NAME ..."

    # 1. 删除 PATH 中的符号链接
    local target="$BIN_DIR/$COMMAND_NAME"
    local removed=()
    for dir in /usr/local/bin "$HOME/.local/bin"; do
        local link="$dir/$COMMAND_NAME"
        if [[ -L "$link" ]]; then
            local dest
            dest="$(readlink "$link" 2>/dev/null || true)"
            if [[ "$dest" == "$target" ]]; then
                rm -f "$link"
                removed+=("$link")
            fi
        fi
    done
    if (( ${#removed[@]} > 0 )); then
        ok "已移除 PATH 链接: ${removed[*]}"
    fi

    # 2. 删除安装目录
    if [[ -d "$INSTALL_DIR" ]]; then
        rm -rf "$INSTALL_DIR"
        ok "已删除安装目录: $INSTALL_DIR"
    else
        warn "安装目录不存在: $INSTALL_DIR"
    fi

    # 3. 移除 shell rc 中的 PATH 追加行（仅清理本脚本写入的标记行）
    remove_path_from_rc

    ok "卸载完成。"
    warn "注意：各项目目录下的 .agent/ 会话与记忆数据未删除（按需手动清理）。"
}

# 在 shell rc 中追/移 PATH，使用唯一标记行便于精确清理
RC_MARKER="# >>> mwb-ai-claw path >>>"
RC_MARKER_END="# <<< mwb-ai-claw path <<<"

add_path_to_rc() {
    local rc_file="$1"
    local path_dir="$2"
    # 已存在则跳过
    if grep -qF "$RC_MARKER" "$rc_file" 2>/dev/null; then
        return 0
    fi
    {
        echo ""
        echo "$RC_MARKER"
        echo "export PATH=\"$path_dir:\$PATH\""
        echo "$RC_MARKER_END"
    } >> "$rc_file"
}

remove_path_from_rc() {
    local rc
    for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.bash_profile"; do
        [[ -f "$rc" ]] || continue
        if grep -qF "$RC_MARKER" "$rc" 2>/dev/null; then
            # 用 sed 删除标记区间（macOS sed 与 GNU sed 通用写法）
            sed -i.bak "/$RC_MARKER/,/$RC_MARKER_END/d" "$rc" 2>/dev/null || true
            rm -f "${rc}.bak" 2>/dev/null || true
            ok "已从 $rc 移除 PATH 配置"
        fi
    done
}

# ---------------- 前置检查 ----------------
# 二进制分发模式仅需 java；源码模式需 java + mvn
check_prerequisites() {
    local missing=()
    command -v java >/dev/null 2>&1 || missing+=("java (JDK 8+)")
    if [[ "$IS_BINARY_DIST" == "false" ]]; then
        command -v mvn >/dev/null 2>&1 || missing+=("mvn (Maven 3.6+)")
    fi
    if (( ${#missing[@]} > 0 )); then
        err "缺少依赖: ${missing[*]}"
        err "请先安装后重试。"
        exit 1
    fi
}

# ---------------- 解析 jar 路径 ----------------
# 二进制分发模式：直接用包内预构建的 lib/start.jar
# 源码模式：mvn package 构建后定位 start/target/start-*.jar
resolve_jar() {
    if [[ "$IS_BINARY_DIST" == "true" ]]; then
        info "二进制分发模式：使用预构建 jar"
        echo "$BUNDLED_JAR"
        return
    fi

    info "源码模式：构建项目（mvn package, 跳过测试）..."
    (
        cd "$PROJECT_ROOT"
        mvn package -pl start -am -DskipTests -q
    ) || { err "构建失败，请检查 Maven 输出"; exit 1; }

    # 定位构建产物（spring-boot-maven-plugin repackage 产出 start-<ver>.jar，
    # 同时存在 start-<ver>.jar.original；这里取不含 .original 的可执行 jar）
    local jar
    jar="$(ls start/target/start-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
    if [[ -z "${jar:-}" || ! -f "$jar" ]]; then
        err "未找到构建产物 start/target/start-*.jar"
        exit 1
    fi
    echo "$jar"
}

# ---------------- 安装 ----------------
install_files() {
    local jar="$1"
    mkdir -p "$LIB_DIR" "$BIN_DIR"

    # 1. 拷贝 jar（统一重命名为 start.jar，避免版本号变化导致 wrapper 失效）
    cp -f "$jar" "$LIB_DIR/start.jar"
    ok "已安装 jar: $LIB_DIR/start.jar"

    # 2. 生成启动器脚本（写入 bin，再由 PATH 链接调用）
    cat > "$BIN_DIR/$COMMAND_NAME" <<'WRAPPER_EOF'
#!/usr/bin/env bash
# mwb-ai-claw launcher —— 任意目录执行进入 Agent Shell
# 设计要点:
#   - 全局密钥来自 $MWBC_HOME/.env（作为环境变量注入，仅作兜底；项目 .env 优先）
#   - 不切换工作目录: .agent/ 会话与记忆落在当前目录（按项目隔离）
#   - 支持常用参数: --help / --version / --approval-mode / --model / --orchestration / --session
#     其余参数透传 Java（--prompt/--resume/--mode/--bg/--agent/--verbose 及 Spring 配置覆盖）
set -euo pipefail

MWBC_HOME="${MWB_AI_CLAW_HOME:-$HOME/.mwb-ai-claw}"
JAR_PATH="$MWBC_HOME/lib/start.jar"

if [[ ! -f "$JAR_PATH" ]]; then
    echo "[mwb-ai-claw] 未找到 jar: $JAR_PATH" >&2
    echo "[mwb-ai-claw] 请先在项目根目录执行 ./install.sh 完成安装。" >&2
    exit 1
fi

# 版本号来自 jar 内 pom.properties（META-INF/maven/com.mwb.ai.claw/start/pom.properties）
print_version() {
    local v
    v="$(unzip -p "$JAR_PATH" META-INF/maven/com.mwb.ai.claw/start/pom.properties 2>/dev/null \
        | sed -n 's/^version=//p' | tr -d '\r')"
    echo "mwb-ai-claw ${v:-1.0.0-SNAPSHOT}"
}

print_cli_help() {
    cat <<'HELP_EOF'
mwb-ai-claw — Agent Shell 命令行客户端

用法:
    mwb-ai-claw [参数]
    mwb-ai-claw --prompt "问题"          # 单轮非交互问答
    echo "问题" | mwb-ai-claw           # 管道输入（非交互）

参数:
    -h, --help                 显示本帮助并退出
    -V, --version              显示版本号并退出
        --approval-mode <模式>    审批模式: auto | ask(默认) | read-only
        --model <模型名>          覆盖默认模型（等价 --agent.model=xxx）
        --orchestration <id>     覆盖默认编排（routing | team-discussion | todo-delegate 等）
        --session <会话id>        恢复指定会话进入交互（等价 --resume）
        --prompt <文本>           单轮非交互问答（等价 -p）
        --resume <会话id>         恢复指定会话进入交互
        --mode stream|sync       回复输出模式（默认 stream）
        --bg "任务描述"           启动后台 Agent 任务后进入交互
        --agent <专家id>          默认使用指定专家 Agent（如 coder / architect）
        --verbose                观察结果完整显示（默认缩写）
    其余参数透传 Spring 配置，如 --agent.tools=echo,shell

环境变量:
    MWB_AI_CLAW_HOME            安装根目录（默认 ~/.mwb-ai-claw）
    MWB_AI_CLAW_APPROVAL_MODE   审批模式覆盖（auto/ask/read-only，命令行 --approval-mode 优先）
    DEFAULT_API_KEY             在 $MWBC_HOME/.env 中配置

示例:
    mwb-ai-claw                                     # 进入交互模式
    mwb-ai-claw --approval-mode=read-only           # 只读模式（命中审批规则拒绝执行）
    mwb-ai-claw --model deepseek-chat               # 指定模型
    mwb-ai-claw --agent coder                       # 默认使用编码专家
    mwb-ai-claw --prompt "总结当前目录"              # 单轮问答
HELP_EOF
}

# 加载全局 .env（API Key 等敏感配置）；set -a 自动 export 所有变量
if [[ -f "$MWBC_HOME/.env" ]]; then
    set -a
    # shellcheck disable=SC1090,SC1091
    source "$MWBC_HOME/.env" 2>/dev/null || true
    set +a
fi

# ---------------- 参数解析 ----------------
# 解析并转换已知参数，其余参数透传 Java。
# 注意: bash 3.2 在 set -u 下空数组展开会报 unbound variable，
#       故透传参数用 ${arr[@]+"${arr[@]}"} 保护展开。
user_args=("$@")
approval_mode=""
model=""
orchestration=""
session_id=""
pass=()
i=0
while [[ $i -lt ${#user_args[@]} ]]; do
    a="${user_args[$i]}"
    case "$a" in
        --help|-h)
            print_cli_help
            exit 0
            ;;
        --version|-V)
            print_version
            exit 0
            ;;
        --approval-mode)
            approval_mode="${user_args[$((i+1))]:-}"; i=$((i+2)) ;;
        --approval-mode=*)
            approval_mode="${a#*=}"; i=$((i+1)) ;;
        --model)
            model="${user_args[$((i+1))]:-}"; i=$((i+2)) ;;
        --model=*)
            model="${a#*=}"; i=$((i+1)) ;;
        --orchestration)
            orchestration="${user_args[$((i+1))]:-}"; i=$((i+2)) ;;
        --orchestration=*)
            orchestration="${a#*=}"; i=$((i+1)) ;;
        --session)
            session_id="${user_args[$((i+1))]:-}"; i=$((i+2)) ;;
        --session=*)
            session_id="${a#*=}"; i=$((i+1)) ;;
        *)
            pass+=("$a"); i=$((i+1)) ;;
    esac
done

# 组装最终参数：命令行参数优先，其次环境变量 MWB_AI_CLAW_APPROVAL_MODE
set -- ${pass[@]+"${pass[@]}"}
if [[ -n "$session_id" ]]; then
    set -- --resume "$session_id" "$@"
fi
if [[ -n "$orchestration" ]]; then
    set -- --agent.orchestration="$orchestration" "$@"
fi
if [[ -n "$model" ]]; then
    set -- --agent.model="$model" "$@"
fi
if [[ -n "$approval_mode" ]]; then
    set -- --agent.security.shell-approval-mode="$approval_mode" "$@"
elif [[ -n "${MWB_AI_CLAW_APPROVAL_MODE:-}" ]]; then
    set -- --agent.security.shell-approval-mode="$MWB_AI_CLAW_APPROVAL_MODE" "$@"
fi

# 透传用户参数；默认激活 shell profile
# -Dmwb.ai.claw.home 注入安装目录：ConfigFileLocator 可按「运行目录→安装目录 config→classpath」加载配置
exec java -Dmwb.ai.claw.home="$MWBC_HOME" -jar "$JAR_PATH" --spring.profiles.active=shell "$@"
WRAPPER_EOF
    chmod +x "$BIN_DIR/$COMMAND_NAME"
    ok "已生成启动器: $BIN_DIR/$COMMAND_NAME"

    # 3. 复制用户可调整配置模板（agents.json / orchestrations.json / mcp-server.json.example）
    #    加载顺序：运行目录(user.dir) → 安装目录 config（本目录）→ classpath。
    #    用户直接修改本目录下的配置文件即可覆盖内置默认，重启后生效
    if [[ -d "$PROJECT_ROOT/config" ]]; then
        mkdir -p "$INSTALL_DIR/config"
        if cp -f "$PROJECT_ROOT"/config/* "$INSTALL_DIR/config/" 2>/dev/null; then
            ok "已复制配置模板: $INSTALL_DIR/config/"
        fi
    fi

    # 4. 复制 .env.example 密钥模板副本（参考/重置用；实际密钥写在 $INSTALL_DIR/.env）
    if [[ -f "$PROJECT_ROOT/.env.example" ]]; then
        cp -f "$PROJECT_ROOT/.env.example" "$INSTALL_DIR/.env.example"
        ok "已复制密钥模板: $INSTALL_DIR/.env.example"
    fi

    # 5. 复制内置技能模板（skills/；加载顺序：运行目录 skills → 安装目录 skills（本目录）→ classpath。
    #    用户直接在安装目录增删技能目录即可自定义技能集，重启后生效）
    if [[ -d "$PROJECT_ROOT/skills" ]]; then
        mkdir -p "$INSTALL_DIR/skills"
        if cp -Rf "$PROJECT_ROOT"/skills/. "$INSTALL_DIR/skills/" 2>/dev/null; then
            ok "已复制技能模板: $INSTALL_DIR/skills/"
        fi
    fi
}

# ---------------- 初始化 .env ----------------
init_env() {
    if [[ -f "$ENV_FILE" ]]; then
        ok "已存在全局 .env: $ENV_FILE"
        return 0
    fi

    if [[ -f "$PROJECT_ROOT/.env" ]]; then
        cp "$PROJECT_ROOT/.env" "$ENV_FILE"
        ok "已从项目 .env 复制到 $ENV_FILE"
    elif [[ -f "$PROJECT_ROOT/.env.example" ]]; then
        cp "$PROJECT_ROOT/.env.example" "$ENV_FILE"
        warn "已创建 ${ENV_FILE}（模板）, 请编辑填入 DEFAULT_API_KEY:"
        echo "    vi $ENV_FILE"
    else
        warn "未找到 .env 模板, 请手动创建 $ENV_FILE"
    fi
}

# ---------------- 链接到 PATH ----------------
link_to_path() {
    local target="$BIN_DIR/$COMMAND_NAME"
    local candidates=("/usr/local/bin" "$HOME/.local/bin")
    local chosen=""

    # 优先写入可写的目录（无需 sudo）
    for dir in "${candidates[@]}"; do
        if [[ -d "$dir" && -w "$dir" ]]; then
            ln -sf "$target" "$dir/$COMMAND_NAME"
            chosen="$dir"
            break
        fi
    done

    # 若都不存在/可写，则创建 ~/.local/bin
    if [[ -z "$chosen" ]]; then
        mkdir -p "$HOME/.local/bin"
        ln -sf "$target" "$HOME/.local/bin/$COMMAND_NAME"
        chosen="$HOME/.local/bin"
    fi
    echo "$chosen"
}

# 检查目录是否在 PATH 中
in_path() {
    local dir="$1"
    case ":$PATH:" in
        *":$dir:"*) return 0 ;;
        *) return 1 ;;
    esac
}

# ---------------- main ----------------
main() {
    local mode="install"
    case "${1:-}" in
        --help|-h) print_help; exit 0 ;;
        --uninstall|-u) mode="uninstall" ;;
        "") mode="install" ;;
        *) err "未知参数: $1"; print_help; exit 1 ;;
    esac

    if [[ "$mode" == "uninstall" ]]; then
        do_uninstall
        exit 0
    fi

    # install
    info "安装目录: $INSTALL_DIR"
    check_prerequisites

    local jar
    jar="$(resolve_jar)"
    install_files "$jar"
    init_env

    local path_dir
    path_dir="$(link_to_path)"
    ok "已创建命令链接: $path_dir/$COMMAND_NAME"

    # 若不在 PATH 中, 追加到 shell rc
    if ! in_path "$path_dir"; then
        local rc_file=""
        [[ -n "${ZSH_VERSION:-}" ]] && rc_file="$HOME/.zshrc"
        [[ -n "${BASH_VERSION:-}" ]] && rc_file="$HOME/.bashrc"
        [[ -z "$rc_file" && -f "$HOME/.zshrc" ]] && rc_file="$HOME/.zshrc"
        [[ -z "$rc_file" && -f "$HOME/.bashrc" ]] && rc_file="$HOME/.bashrc"
        [[ -z "$rc_file" ]] && rc_file="$HOME/.bashrc"

        add_path_to_rc "$rc_file" "$path_dir"
        warn "$path_dir 不在 PATH 中, 已写入 $rc_file"
        warn "请执行:  source $rc_file   (或重开终端)"
    fi

    echo
    ok "安装完成!"
    echo
    printf "  ${C_BOLD}用法${C_NC}: 在任意目录执行 ${C_CYAN}${COMMAND_NAME}${C_NC} 进入 Agent Shell\n"
    printf "  ${C_BOLD}帮助${C_NC}: ${C_YELLOW}${COMMAND_NAME} --help${C_NC} 查看启动参数 | 进入后输入 ${C_YELLOW}/help${C_NC} 查看命令\n"
    printf "  ${C_BOLD}审批${C_NC}: 默认 ${C_YELLOW}ask${C_NC}（高风险命令询问 y/N）；改为自动可在 ${ENV_FILE} 中设 ${C_CYAN}MWB_AI_CLAW_APPROVAL_MODE=auto${C_NC}\n"
    [[ -d "$INSTALL_DIR/config" ]] && {
        printf "  ${C_BOLD}自定义${C_NC}: 将 ${C_CYAN}%s${C_NC} 下文件复制到运行目录即可覆盖默认配置（agents/orchestrations/mcp-server）\n" "$INSTALL_DIR/config"
    }
    [[ -f "$ENV_FILE" && -z "$(grep -E '^DEFAULT_API_KEY=\S' "$ENV_FILE" 2>/dev/null || true)" ]] && {
        warn "提醒: $ENV_FILE 中 DEFAULT_API_KEY 仍为空, 请先填入再使用"
    }
    echo
}

main "$@"
