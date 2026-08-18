#!/usr/bin/env bash
# ============================================================
# mwb-ai-claw 二进制分发打包脚本
#
# 产出不含源码的可分发安装包（tar.gz），用户解压后执行 ./install.sh 即可安装。
# 安装包内容:
#   mwb-ai-claw-<version>-bin/
#   ├── install.sh          安装脚本（自适应二进制模式）
#   ├── lib/start.jar       预构建可执行 jar（无源码）
#   └── .env.example        密钥配置模板
#
# 用法:
#   ./package.sh                构建并打包
#   ./package.sh --skip-build   跳过 Maven 构建（复用已构建的 jar）
#   ./package.sh --help
# ============================================================
set -euo pipefail

# 脚本在 tools/ 下，项目根为上级目录
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------------- 颜色 ----------------
if [[ -t 1 ]]; then
    C_CYAN='\033[0;36m'; C_GREEN='\033[0;32m'; C_YELLOW='\033[1;33m'
    C_RED='\033[0;31m'; C_NC='\033[0m'
else
    C_CYAN=''; C_GREEN=''; C_YELLOW=''; C_RED=''; C_NC=''
fi
info()  { printf "${C_CYAN}[package]${C_NC} %s\n" "$*"; }
ok()    { printf "${C_GREEN}[package]${C_NC} %s\n" "$*"; }
warn()  { printf "${C_YELLOW}[package]${C_NC} %s\n" "$*"; }
err()   { printf "${C_RED}[package]${C_NC} %s\n" "$*" >&2; }

print_help() {
    cat <<EOF
mwb-ai-claw 二进制分发打包脚本

用法:
    ./package.sh                构建并打包二进制分发包
    ./package.sh --skip-build   跳过 Maven 构建（复用 start/target 下已有 jar）
    ./package.sh --help         显示本帮助

产物: dist/mwb-ai-claw-<version>-bin.tar.gz
EOF
}

# ---------------- 解析参数 ----------------
SKIP_BUILD=false
case "${1:-}" in
    --help|-h) print_help; exit 0 ;;
    --skip-build) SKIP_BUILD=true ;;
    "") ;;
    *) err "未知参数: $1"; print_help; exit 1 ;;
esac

# ---------------- 版本号 ----------------
# 从父 pom.xml 解析 <version>（首条非 dependencyManagement 的项目版本）
VERSION="$(grep -m1 '<version>[0-9]' "$PROJECT_ROOT/pom.xml" | sed 's/.*<version>\([^<]*\)<\/version>.*/\1/' || true)"
if [[ -z "${VERSION:-}" ]]; then
    err "无法从 pom.xml 解析版本号"
    exit 1
fi
DIST_NAME="mwb-ai-claw-${VERSION}-bin"
DIST_DIR="$PROJECT_ROOT/dist/$DIST_NAME"
ARCHIVE="$PROJECT_ROOT/dist/${DIST_NAME}.tar.gz"

info "版本: $VERSION"
info "产物目录: $DIST_DIR"
info "产物包:   $ARCHIVE"

# ---------------- 构建 ----------------
JAR=""
if [[ "$SKIP_BUILD" == "true" ]]; then
    info "跳过构建（--skip-build）"
else
    # 前置检查
    command -v mvn >/dev/null 2>&1 || { err "缺少 mvn，请先安装 Maven"; exit 1; }
    info "构建项目（mvn package, 跳过测试）..."
    (
        cd "$PROJECT_ROOT"
        mvn package -pl start -am -DskipTests -q
    ) || { err "构建失败"; exit 1; }
fi

# 定位构建产物（spring-boot-maven-plugin repackage 产出 start-<ver>.jar，
# 排除 .original 后缀文件）
JAR="$(ls "$PROJECT_ROOT"/start/target/start-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
if [[ -z "${JAR:-}" || ! -f "$JAR" ]]; then
    err "未找到构建产物 start/target/start-*.jar"
    err "请先执行 ./package.sh 或去掉 --skip-build"
    exit 1
fi
info "构建产物: $JAR"

# ---------------- 组装分发目录 ----------------
info "组装分发目录..."
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR/lib"

# 1. 预构建 jar（统一重命名为 start.jar）
cp -f "$JAR" "$DIST_DIR/lib/start.jar"

# 2. 安装脚本（自适应二进制模式：检测到 lib/start.jar 即跳过 mvn）
#    源码树中脚本位于 tools/，分发时拷贝到包根目录（与 lib/ 同级）
#    同时打包 bash 版（Linux/macOS）与 PowerShell 版（Windows），用户按平台选其一。
cp -f "$PROJECT_ROOT/tools/install.sh"  "$DIST_DIR/install.sh"
cp -f "$PROJECT_ROOT/tools/install.ps1" "$DIST_DIR/install.ps1"
chmod +x "$DIST_DIR/install.sh"

# 3. 密钥配置模板
if [[ -f "$PROJECT_ROOT/.env.example" ]]; then
    cp -f "$PROJECT_ROOT/.env.example" "$DIST_DIR/.env.example"
elif [[ -f "$PROJECT_ROOT/.env" ]]; then
    cp -f "$PROJECT_ROOT/.env" "$DIST_DIR/.env.example"
    warn "未找到 .env.example，已从 .env 复制为 .env.example（可能含密钥，请检查后再分发）"
fi

ok "分发目录内容:"
( cd "$PROJECT_ROOT/dist" && find "$DIST_NAME" -type f -exec ls -lh {} \; | awk '{printf "    %s %s\n", $5, $9}' )

# ---------------- 打包 ----------------
info "打包 tar.gz..."
rm -f "$ARCHIVE"
tar -czf "$ARCHIVE" -C "$PROJECT_ROOT/dist" "$DIST_NAME"

ok "打包完成!"
echo
printf "  产物: ${C_CYAN}${ARCHIVE}${C_NC}\n"
printf "  大小: %s\n" "$(ls -lh "$ARCHIVE" | awk '{print $5}')"
echo
printf "  ${C_GREEN}分发方式${C_NC}: 解压后执行\n"
printf "    tar -xzf %s\n" "$(basename "$ARCHIVE")"
printf "    cd %s && ./install.sh\n" "$DIST_NAME"
echo
