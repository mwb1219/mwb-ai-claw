# ============================================================
# mwb-ai-claw 二进制分发打包脚本 (Windows PowerShell)
#
# 产出不含源码的可分发安装包（zip），用户解压后执行 .\install.ps1 即可安装。
# 安装包内容:
#   mwb-ai-claw-<version>-bin\
#   ├── install.ps1         安装脚本（自适应二进制模式）
#   ├── CONFIG-GUIDE.md     配置指南（密钥 / Agent / 编排 / MCP 配置说明）
#   ├── lib\start.jar        预构建可执行 jar（无源码）
#   ├── config\              用户可调整配置模板（agents.json / orchestrations.json / mcp-server.json.example）
#   │                        加载顺序：运行目录 → 安装目录 config → classpath
#   └── .env.example        密钥配置模板
#
# 用法:
#   .\package.ps1                构建并打包
#   .\package.ps1 -SkipBuild    跳过 Maven 构建（复用已构建的 jar）
#   .\package.ps1 -Help         显示本帮助
# ============================================================
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

# 脚本在 tools/ 下，项目根为上级目录
$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $PSScriptRoot) { $ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path) }

# ---------------- 日志 ----------------
function Write-Info { param([string]$Msg) Write-Host "[package] $Msg" -ForegroundColor Cyan }
function Write-OK   { param([string]$Msg) Write-Host "[package] $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "[package] $Msg" -ForegroundColor Yellow }
function Write-Err  { param([string]$Msg) Write-Host "[package] $Msg" -ForegroundColor Red }

function Show-Help {
    @'
mwb-ai-claw 二进制分发打包脚本 (Windows PowerShell)

用法:
    .\package.ps1                构建并打包二进制分发包
    .\package.ps1 -SkipBuild     跳过 Maven 构建（复用 start\target 下已有 jar）
    .\package.ps1 -Help          显示本帮助

产物: dist\mwb-ai-claw-<version>-bin.zip
'@ | Write-Host
}

if ($Help) { Show-Help; exit 0 }

# ---------------- 版本号 ----------------
$pomContent = Get-Content (Join-Path $ProjectRoot "pom.xml") -Raw
$versionMatch = [regex]::Match($pomContent, '<version>([^<]+)</version>')
if (-not $versionMatch.Success) {
    Write-Err "无法从 pom.xml 解析版本号"
    exit 1
}
$Version = $versionMatch.Groups[1].Value
$DistName = "mwb-ai-claw-$Version-bin"
$DistDir = Join-Path $ProjectRoot "dist\$DistName"
$Archive = Join-Path $ProjectRoot "dist\$DistName.zip"

Write-Info "版本: $Version"
Write-Info "产物目录: $DistDir"
Write-Info "产物包:   $Archive"

# ---------------- 构建 ----------------
$jar = ""
if ($SkipBuild) {
    Write-Info "跳过构建（-SkipBuild）"
} else {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        Write-Err "缺少 mvn，请先安装 Maven"
        exit 1
    }

    # ---------------- 定位 JDK 1.8 用于编译 ----------------
    # 优先级: $env:JAVA_HOME > 常见 Windows 安装路径 > 默认 JDK（用 --release 8 交叉编译）
    $Jdk8Home = ""
    if ($env:JAVA_HOME) {
        $jver = & "$env:JAVA_HOME\bin\java" -version 2>&1 | Select-Object -First 1
        if ($jver -match '"1\.8') { $Jdk8Home = $env:JAVA_HOME }
    }
    if (-not $Jdk8Home) {
        # 常见 Windows JDK 1.8 安装路径
        $candidates = @(
            "${env:ProgramFiles}\Java\jdk1.8.0_*",
            "${env:ProgramFiles(x86)}\Java\jdk1.8.0_*",
            "${env:ProgramFiles}\Java\jre1.8.0_*"
        ) | ForEach-Object { Get-Item $_ -ErrorAction SilentlyContinue } | Sort-Object Name -Descending
        if ($candidates) { $Jdk8Home = $candidates[0].FullName }
    }

    if ($Jdk8Home) {
        Write-Info "使用 JDK 1.8 编译: $Jdk8Home"
        Push-Location $ProjectRoot
        try {
            $oldJavaHome = $env:JAVA_HOME
            $oldPath = $env:PATH
            $env:JAVA_HOME = $Jdk8Home
            $env:PATH = "$Jdk8Home\bin;$env:PATH"
            & mvn package -pl start -am -DskipTests -q
            $env:JAVA_HOME = $oldJavaHome
            $env:PATH = $oldPath
            if ($LASTEXITCODE -ne 0) {
                Write-Err "构建失败"
                exit 1
            }
        } finally {
            Pop-Location
        }
    } else {
        # 未找到 JDK 1.8，回退到默认 JDK + --release 8 交叉编译
        Write-Warn "未找到 JDK 1.8（可设置 `$env:JAVA_HOME 指向 JDK 1.8 以启用原生编译）"
        Write-Info "使用默认 JDK + --release 8 交叉编译..."
        Push-Location $ProjectRoot
        try {
            & mvn package -pl start -am -DskipTests -q "-Dmaven.compiler.release=8"
            if ($LASTEXITCODE -ne 0) {
                Write-Err "构建失败"
                exit 1
            }
        } finally {
            Pop-Location
        }
    }
}

# 定位构建产物
$jars = Get-ChildItem -Path (Join-Path $ProjectRoot "start\target") -Filter "start-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" }
if (-not $jars -or $jars.Count -eq 0) {
    Write-Err "未找到构建产物 start\target\start-*.jar"
    Write-Err "请先执行 .\package.ps1 或去掉 -SkipBuild"
    exit 1
}
$jar = $jars[0].FullName
Write-Info "构建产物: $jar"

# ---------------- 组装分发目录 ----------------
Write-Info "组装分发目录..."
if (Test-Path $DistDir) { Remove-Item $DistDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $DistDir "lib") | Out-Null

# 1. 预构建 jar
Copy-Item -Force $jar (Join-Path $DistDir "lib\start.jar")

# 2. 安装脚本（从 tools/ 拷贝到包根目录）
Copy-Item -Force (Join-Path $ProjectRoot "tools\install.ps1") (Join-Path $DistDir "install.ps1")

# 3. 密钥配置模板
$srcExample = Join-Path $ProjectRoot ".env.example"
$srcEnv = Join-Path $ProjectRoot ".env"
if (Test-Path $srcExample) {
    Copy-Item -Force $srcExample (Join-Path $DistDir ".env.example")
} elseif (Test-Path $srcEnv) {
    Copy-Item -Force $srcEnv (Join-Path $DistDir ".env.example")
    Write-Warn "未找到 .env.example，已从 .env 复制为 .env.example（可能含密钥，请检查后再分发）"
}

# 4. 用户可调整配置模板（agents / orchestrations / mcp-server）
#    加载顺序：运行目录(user.dir) → 安装目录 config（install 时复制）→ classpath。
#    安装后直接修改安装目录 config\ 下文件即可覆盖内置默认，无需重新打包
$ConfigSrc = Join-Path $ProjectRoot "start\src\main\resources"
if (Test-Path $ConfigSrc) {
    New-Item -ItemType Directory -Force -Path (Join-Path $DistDir "config") | Out-Null
    foreach ($cfg in @("agents.json", "orchestrations.json", "mcp-server.json.example")) {
        $cfgPath = Join-Path $ConfigSrc $cfg
        if (Test-Path $cfgPath) {
            Copy-Item -Force $cfgPath (Join-Path $DistDir "config\$cfg")
        }
    }
}

# 5. 配置指南（随包分发，供用户按说明配置）
$Guide = Join-Path $ProjectRoot "CONFIG-GUIDE.md"
if (Test-Path $Guide) {
    Copy-Item -Force $Guide (Join-Path $DistDir "CONFIG-GUIDE.md")
}

Write-OK "分发目录内容:"
Get-ChildItem -Recurse $DistDir | ForEach-Object {
    if (-not $_.PSIsContainer) {
        $rel = $_.FullName.Substring($DistDir.Length + 1)
        $size = "{0:N1}K" -f ($_.Length / 1KB)
        Write-Host ("    {0,-8} {1}" -f $size, $rel)
    }
}

# ---------------- 打包 zip ----------------
Write-Info "打包 zip..."
if (Test-Path $Archive) { Remove-Item $Archive -Force }
Compress-Archive -Path $DistDir -DestinationPath $Archive -CompressionLevel Optimal

$archiveSize = (Get-Item $Archive).Length
$sizeStr = if ($archiveSize -gt 1MB) { "{0:N1}M" -f ($archiveSize / 1MB) } else { "{0:N1}K" -f ($archiveSize / 1KB) }

Write-OK "打包完成!"
Write-Host ""
Write-Host "  产物: $Archive" -ForegroundColor Cyan
Write-Host "  大小: $sizeStr"
Write-Host ""
Write-Host "  分发方式: 解压后执行" -ForegroundColor Green
Write-Host "    Expand-Archive $DistName.zip"
Write-Host "    cd $DistName"
Write-Host "    .\install.ps1"
Write-Host ""
