# ============================================================
# mwb-ai-claw 一键打包 + 安装脚本 (Windows PowerShell)
#
# 顺序执行:
#   1. 调用 package.ps1 构建并打包分发包（zip）
#   2. 解压刚生成的包到临时目录
#   3. 以二进制模式执行包内 install.ps1 完成本地安装（验证包可用）
#   4. 清理临时目录
#
# 用法:
#   .\tools\setup.ps1               构建 + 打包 + 安装
#   .\tools\setup.ps1 -SkipBuild     跳过 mvn 构建（复用已构建 jar）+ 打包 + 安装
#   .\tools\setup.ps1 -Help          显示帮助
# ============================================================
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

# 脚本在 tools/ 下，项目根为上级目录
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
$ProjectRoot = Split-Path -Parent $ScriptDir

# ---------------- 日志 ----------------
function Write-Info { param([string]$Msg) Write-Host "[setup] $Msg" -ForegroundColor Cyan }
function Write-OK   { param([string]$Msg) Write-Host "[setup] $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "[setup] $Msg" -ForegroundColor Yellow }
function Write-Err  { param([string]$Msg) Write-Host "[setup] $Msg" -ForegroundColor Red }

function Show-Help {
    @'
mwb-ai-claw 一键打包 + 安装脚本 (Windows PowerShell)

顺序执行: 构建 → 打包分发包 → 用该包本地安装 → 清理临时目录。

用法:
    .\tools\setup.ps1               构建 + 打包 + 安装
    .\tools\setup.ps1 -SkipBuild     跳过 mvn 构建（复用已构建 jar）+ 打包 + 安装
    .\tools\setup.ps1 -Help          显示本帮助

步骤:
    1. 执行 tools\package.ps1 生成 dist\mwb-ai-claw-<version>-bin.zip
    2. 解压该包到临时目录
    3. 执行包内 install.ps1 以二进制模式安装（验证包可用，不重复 mvn）
    4. 清理临时目录

安装完成后任意目录执行 mwb-ai-claw 进入 Agent Shell。
'@ | Write-Host
}

if ($Help) { Show-Help; exit 0 }

# ---------------- 前置检查 ----------------
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Err "缺少 java (JDK 8+)"
    exit 1
}
if (-not $SkipBuild) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        Write-Err "缺少 mvn (Maven 3.6+)"
        exit 1
    }
}

# ---------------- 1. 打包 ----------------
Write-Info "步骤 1/3: 打包分发包"
$pkgArgs = @()
if ($SkipBuild) { $pkgArgs += "-SkipBuild" }
& (Join-Path $ScriptDir "package.ps1") @pkgArgs
if ($LASTEXITCODE -ne 0) {
    Write-Err "打包失败"
    exit 1
}

# ---------------- 解析产物路径 ----------------
$pomContent = Get-Content (Join-Path $ProjectRoot "pom.xml") -Raw
$versionMatch = [regex]::Match($pomContent, '<revision>([^<]+)</revision>')
if (-not $versionMatch.Success) {
    Write-Err "无法从 pom.xml 解析版本号"
    exit 1
}
$Version = $versionMatch.Groups[1].Value
$Archive = Join-Path $ProjectRoot "dist\mwb-ai-claw-$Version-bin.zip"
if (-not (Test-Path $Archive)) {
    Write-Err "打包产物不存在: $Archive"
    exit 1
}
Write-OK "分发包: $Archive"

# ---------------- 2. 解压 + 安装 ----------------
Write-Info "步骤 2/3: 解压并用包内 install.ps1 安装"
$TmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "mwb-setup-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null

try {
    Expand-Archive -Path $Archive -DestinationPath $TmpDir -Force
    $DistDir = Join-Path $TmpDir "mwb-ai-claw-$Version-bin"
    if (-not (Test-Path $DistDir)) {
        Write-Err "解压后未找到目录: $DistDir"
        exit 1
    }

    # 执行包内 install.ps1（自动识别二进制模式，跳过 mvn）
    & (Join-Path $DistDir "install.ps1")
    if ($LASTEXITCODE -ne 0) {
        Write-Err "安装失败"
        exit 1
    }
} finally {
    # ---------------- 3. 清理 ----------------
    if (Test-Path $TmpDir) {
        Remove-Item $TmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-OK "步骤 3/3: 清理临时目录"
Write-OK "一键打包 + 安装完成!"
Write-Host ""
Write-Host "  分发包: $Archive" -ForegroundColor Green
Write-Host "  命令:  任意目录执行 " -NoNewline -ForegroundColor White
Write-Host "mwb-ai-claw" -ForegroundColor Cyan
Write-Host "        进入 Agent Shell" -ForegroundColor White
Write-Host ""
