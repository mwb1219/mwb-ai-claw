# ============================================================
# mwb-ai-claw 本地安装脚本 (Windows PowerShell)
#
# 安装完成后可在任意目录直接执行 `mwb-ai-claw` 命令
# 进入 Agent Shell 交互模式（类似 claude 终端命令）。
#
# 用法:
#   .\install.ps1                # 安装 / 升级
#   .\install.ps1 -Uninstall     # 卸载
#   .\install.ps1 -Help          # 显示帮助
#
# 环境变量（可选）:
#   $env:MWB_AI_CLAW_HOME            安装根目录，默认 $HOME\.mwb-ai-claw
#   $env:MWB_AI_CLAW_APPROVAL_MODE   Shell 审批模式覆盖（auto/ask/read-only），默认 ask
#
# 注意: 若遇到执行策略限制，先执行:
#   powershell -ExecutionPolicy Bypass -File .\install.ps1
# 或当前会话:
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
# ============================================================
[CmdletBinding()]
param(
    [switch]$Uninstall,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

# ---------------- 常量 ----------------
$CommandName = "mwb-ai-claw"
$InstallDir = if ($env:MWB_AI_CLAW_HOME) { $env:MWB_AI_CLAW_HOME } else { Join-Path $HOME ".mwb-ai-claw" }
$LibDir = Join-Path $InstallDir "lib"
$BinDir = Join-Path $InstallDir "bin"
$EnvFile = Join-Path $InstallDir ".env"

# 脚本所在目录
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }

# 项目根目录定位（兼容两种部署位置）:
#   - 二进制分发包：install.ps1 在包根目录，同目录存在 lib/start.jar → PROJECT_ROOT = ScriptDir
#   - 源码树：       install.ps1 在 tools/ 下，上级有 pom.xml             → PROJECT_ROOT = ScriptDir/..
if (Test-Path (Join-Path $ScriptDir "lib\start.jar")) {
    $ProjectRoot = $ScriptDir
} else {
    $ProjectRoot = Split-Path -Parent $ScriptDir
}

# 二进制分发模式检测
$BundledJar = Join-Path $ProjectRoot "lib\start.jar"
$IsBinaryDist = Test-Path $BundledJar

# ---------------- 日志 ----------------
function Write-Info { param([string]$Msg) Write-Host "[mwb-ai-claw] $Msg" -ForegroundColor Cyan }
function Write-OK   { param([string]$Msg) Write-Host "[mwb-ai-claw] $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "[mwb-ai-claw] $Msg" -ForegroundColor Yellow }
function Write-Err  { param([string]$Msg) Write-Host "[mwb-ai-claw] $Msg" -ForegroundColor Red }

# ---------------- 帮助 ----------------
function Show-Help {
    @'
mwb-ai-claw 本地安装脚本 (Windows PowerShell)

用法:
    .\install.ps1                安装或升级 mwb-ai-claw 命令
    .\install.ps1 -Uninstall     卸载 mwb-ai-claw 命令及安装目录
    .\install.ps1 -Help          显示本帮助

运行模式（自动检测）:
    - 二进制分发模式：脚本同目录存在 lib\start.jar 时启用，直接安装预构建 jar（无需源码/mvn）
    - 源码模式：       否则用 mvn package 从源码构建后安装

安装位置 (可通过 $env:MWB_AI_CLAW_HOME 覆盖):
    {0}
        ├── lib\start.jar        构建产物
        ├── config\              Agent/编排/MCP 配置模板（修改后重启即生效）
        ├── skills\              技能模板（增删技能目录即自定义技能集）
        ├── bin\mwb-ai-claw.cmd  启动器批处理
        ├── .env.example         密钥模板副本（参考/重置用）
        └── .env                 全局密钥配置（DEFAULT_API_KEY 等）

PATH 配置:
    将 $env:LOCALAPPDATA\mwb-ai-claw-bin 加入用户 PATH（若尚未包含）。

Shell 审批模式（默认 ask）:
    默认高风险命令（git push、rm 等）会询问 y/N 确认；
    改为自动执行可在 .env 中设置 $env:MWB_AI_CLAW_APPROVAL_MODE=auto。

安装后任意目录执行 {1} 即可进入 Agent Shell。
'@ -f $InstallDir, $CommandName | Write-Host
}

# ---------------- 卸载 ----------------
function Do-Uninstall {
    Write-Info "开始卸载 $CommandName ..."

    # 1. 删除启动器副本（用户 PATH 目录）
    $userBin = Join-Path $env:LOCALAPPDATA "mwb-ai-claw-bin"
    $cmdPath = Join-Path $userBin "$CommandName.cmd"
    if (Test-Path $cmdPath) {
        Remove-Item $cmdPath -Force
        Write-OK "已移除启动器: $cmdPath"
    }

    # 2. 删除安装目录
    if (Test-Path $InstallDir) {
        Remove-Item $InstallDir -Recurse -Force
        Write-OK "已删除安装目录: $InstallDir"
    } else {
        Write-Warn "安装目录不存在: $InstallDir"
    }

    # 3. 从用户 PATH 移除
    Remove-PathEntry $userBin

    Write-OK "卸载完成。"
    Write-Warn "注意：各项目目录下的 .agent\ 会话与记忆数据未删除（按需手动清理）。"
}

# ---------------- PATH 管理 ----------------
function Test-PathContains {
    param([string]$Dir)
    $pathEntries = $env:PATH -split ';' | Where-Object { $_ -ne '' }
    foreach ($entry in $pathEntries) {
        if ($entry -ieq $Dir) { return $true }
    }
    return $false
}

function Add-PathEntry {
    param([string]$Dir)
    if (Test-PathContains $Dir) { return $false }
    $oldPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    if (-not $oldPath) { $oldPath = "" }
    $newPath = if ($oldPath) { "$Dir;$oldPath" } else { $Dir }
    [Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
    $env:PATH = $newPath  # 当前会话立即生效
    return $true
}

function Remove-PathEntry {
    param([string]$Dir)
    $oldPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    if (-not $oldPath) { return }
    $entries = $oldPath -split ';' | Where-Object { $_ -ne '' -and $_ -ine $Dir }
    $newPath = $entries -join ';'
    [Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
    $env:PATH = $newPath
}

# ---------------- 前置检查 ----------------
function Check-Prerequisites {
    $missing = @()
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        $missing += "java (JDK 8+)"
    }
    if (-not $IsBinaryDist) {
        if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
            $missing += "mvn (Maven 3.6+)"
        }
    }
    if ($missing.Count -gt 0) {
        Write-Err "缺少依赖: $($missing -join ', ')"
        Write-Err "请先安装后重试。"
        exit 1
    }
}

# ---------------- 解析 jar 路径 ----------------
function Resolve-Jar {
    if ($IsBinaryDist) {
        Write-Info "二进制分发模式：使用预构建 jar"
        return $BundledJar
    }

    Write-Info "源码模式：构建项目（mvn package, 跳过测试）..."
    Push-Location $ProjectRoot
    try {
        & mvn package -pl start -am -DskipTests -q
        if ($LASTEXITCODE -ne 0) {
            Write-Err "构建失败，请检查 Maven 输出"
            exit 1
        }
    } finally {
        Pop-Location
    }

    # 定位构建产物（排除 .original 后缀）
    $jars = Get-ChildItem -Path (Join-Path $ProjectRoot "start\target") -Filter "start-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike "*.original" }
    if (-not $jars -or $jars.Count -eq 0) {
        Write-Err "未找到构建产物 start\target\start-*.jar"
        exit 1
    }
    return $jars[0].FullName
}

# ---------------- 安装 ----------------
function Install-Files {
    param([string]$Jar)

    New-Item -ItemType Directory -Force -Path $LibDir | Out-Null
    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null

    # 1. 拷贝 jar（统一重命名为 start.jar）
    Copy-Item -Force $Jar (Join-Path $LibDir "start.jar")
    Write-OK "已安装 jar: $(Join-Path $LibDir 'start.jar')"

    # 2. 生成启动器批处理 .cmd（Windows 用户直接执行 .cmd）
    $cmdPath = Join-Path $BinDir "$CommandName.cmd"
    $cmdContent = @'
@echo off
setlocal

REM mwb-ai-claw launcher —— 任意目录执行进入 Agent Shell
REM 设计要点:
REM   - 全局密钥来自 %MWB_AI_CLAW_HOME%\.env（作为环境变量注入，仅作兜底；项目 .env 优先）
REM   - 不切换工作目录: .agent\ 会话与记忆落在当前目录（按项目隔离）
REM   - 透传所有参数, 可覆盖 Spring 配置

if "%MWB_AI_CLAW_HOME%"=="" set MWB_AI_CLAW_HOME=%USERPROFILE%\.mwb-ai-claw
set JAR_PATH=%MWB_AI_CLAW_HOME%\lib\start.jar

if not exist "%JAR_PATH%" (
    echo [mwb-ai-claw] 未找到 jar: %JAR_PATH%
    echo [mwb-ai-claw] 请先执行 install.ps1 完成安装。
    exit /b 1
)

REM 加载全局 .env（API Key 等敏感配置）
if exist "%MWB_AI_CLAW_HOME%\.env" (
    for /f "usebackq tokens=1,* delims==" %%a in ("%MWB_AI_CLAW_HOME%\.env") do (
        set "line=%%a"
        if not "!line:~0,1!"=="#" (
            set "%%a=%%b"
        )
    )
)

REM 透传用户参数；默认激活 shell profile
REM -Dmwb.ai.claw.home 注入安装目录：ConfigFileLocator 按「运行目录→安装目录 config→classpath」加载配置
java -Dmwb.ai.claw.home="%MWB_AI_CLAW_HOME%" -jar "%JAR_PATH%" --spring.profiles.active=shell %*
endlocal
'@
    # .cmd 需要 enabledelayedexpansion 才能用 !line! 语法，重写启用
    $cmdContent = @'
@echo off
setlocal enabledelayedexpansion

REM mwb-ai-claw launcher —— 任意目录执行进入 Agent Shell
REM   - 全局密钥来自 %MWB_AI_CLAW_HOME%\.env（作为环境变量注入，仅作兜底；项目 .env 优先）
REM   - 不切换工作目录: .agent\ 会话与记忆落在当前目录（按项目隔离）
REM   - 透传所有参数, 可覆盖 Spring 配置

if "%MWB_AI_CLAW_HOME%"=="" set MWB_AI_CLAW_HOME=%USERPROFILE%\.mwb-ai-claw
set JAR_PATH=%MWB_AI_CLAW_HOME%\lib\start.jar

if not exist "%JAR_PATH%" (
    echo [mwb-ai-claw] 未找到 jar: %JAR_PATH%
    echo [mwb-ai-claw] 请先执行 install.ps1 完成安装。
    exit /b 1
)

REM 加载全局 .env（API Key 等敏感配置），跳过注释行与空行
if exist "%MWB_AI_CLAW_HOME%\.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%MWB_AI_CLAW_HOME%\.env") do (
        set "%%a=%%b"
    )
)

REM Shell 审批模式：默认 ask（application.yml 内置，命中审批规则时询问用户 y/N）；
REM 可用 MWB_AI_CLAW_APPROVAL_MODE 环境变量覆盖（如 .env 中写 MWB_AI_CLAW_APPROVAL_MODE=auto）
if not "%MWB_AI_CLAW_APPROVAL_MODE%"=="" (
    java -Dmwb.ai.claw.home="%MWB_AI_CLAW_HOME%" -jar "%JAR_PATH%" --spring.profiles.active=shell --agent.security.shell-approval-mode=%MWB_AI_CLAW_APPROVAL_MODE% %*
) else (
    java -Dmwb.ai.claw.home="%MWB_AI_CLAW_HOME%" -jar "%JAR_PATH%" --spring.profiles.active=shell %*
)
endlocal
'@
    Set-Content -Path $cmdPath -Value $cmdContent -Encoding ASCII
    Write-OK "已生成启动器: $cmdPath"

    # 3. 同时生成 PowerShell 包装脚本 .ps1（供 PowerShell 用户更友好调用）
    $ps1Path = Join-Path $BinDir "$CommandName.ps1"
    $ps1Content = @'
# mwb-ai-claw launcher (PowerShell) —— 任意目录执行进入 Agent Shell
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args)

$home_dir = if ($env:MWB_AI_CLAW_HOME) { $env:MWB_AI_CLAW_HOME } else { Join-Path $HOME ".mwb-ai-claw" }
$jarPath = Join-Path $home_dir "lib\start.jar"

if (-not (Test-Path $jarPath)) {
    Write-Error "[mwb-ai-claw] 未找到 jar: $jarPath`n请先执行 install.ps1 完成安装。"
    exit 1
}

# 加载全局 .env
$envFile = Join-Path $home_dir ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            $idx = $line.IndexOf('=')
            if ($idx -gt 0) {
                $key = $line.Substring(0, $idx).Trim()
                $val = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
                Set-Item -Path "Env:$key" -Value $val
            }
        }
    }
}

# Shell 审批模式：默认 ask（application.yml 内置，命中审批规则时询问用户 y/N）；
# 可用 MWB_AI_CLAW_APPROVAL_MODE 环境变量覆盖（如 .env 中写 MWB_AI_CLAW_APPROVAL_MODE=auto）
# -Dmwb.ai.claw.home 注入安装目录：ConfigFileLocator 按「运行目录→安装目录 config→classpath」加载配置
$allArgs = @("-Dmwb.ai.claw.home=$home_dir", "--spring.profiles.active=shell")
if ($env:MWB_AI_CLAW_APPROVAL_MODE) {
    $allArgs += "--agent.security.shell-approval-mode=$env:MWB_AI_CLAW_APPROVAL_MODE"
}

# 透传用户参数；默认激活 shell profile
$allArgs += $Args
& java -jar $jarPath @allArgs
'@
    Set-Content -Path $ps1Path -Value $ps1Content -Encoding UTF8

    # 4. 复制用户可调整配置模板（agents.json / orchestrations.json / mcp-server.json.example）
    #    加载顺序：运行目录(user.dir) → 安装目录 config（本目录）→ classpath。
    #    用户直接修改本目录下的配置文件即可覆盖内置默认，重启后生效
    $ConfigSrc = Join-Path $ProjectRoot "config"
    if (Test-Path $ConfigSrc) {
        $ConfigDir = Join-Path $InstallDir "config"
        New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
        Copy-Item -Force (Join-Path $ConfigSrc "*") $ConfigDir
        Write-OK "已复制配置模板: $ConfigDir"
    }

    # 5. 复制 .env.example 密钥模板副本（参考/重置用；实际密钥写在 InstallDir/.env）
    $EnvExample = Join-Path $ProjectRoot ".env.example"
    if (Test-Path $EnvExample) {
        Copy-Item -Force $EnvExample (Join-Path $InstallDir ".env.example")
        Write-OK "已复制密钥模板: $(Join-Path $InstallDir '.env.example')"
    }

    # 6. 复制内置技能模板（skills\；加载顺序：运行目录 skills → 安装目录 skills（本目录）→ classpath。
    #    用户直接在安装目录增删技能目录即可自定义技能集，重启后生效）
    $SkillsSrc = Join-Path $ProjectRoot "skills"
    if (Test-Path $SkillsSrc) {
        $SkillsDir = Join-Path $InstallDir "skills"
        New-Item -ItemType Directory -Force -Path $SkillsDir | Out-Null
        Copy-Item -Force (Join-Path $SkillsSrc "*") $SkillsDir -Recurse
        Write-OK "已复制技能模板: $SkillsDir"
    }
}

# ---------------- 初始化 .env ----------------
function Init-Env {
    if (Test-Path $EnvFile) {
        Write-OK "已存在全局 .env: $EnvFile"
        return
    }

    $srcEnv = Join-Path $ProjectRoot ".env"
    $srcExample = Join-Path $ProjectRoot ".env.example"
    if (Test-Path $srcEnv) {
        Copy-Item $srcEnv $EnvFile
        Write-OK "已从项目 .env 复制到 $EnvFile"
    } elseif (Test-Path $srcExample) {
        Copy-Item $srcExample $EnvFile
        Write-Warn "已创建 $EnvFile（模板）, 请编辑填入 DEFAULT_API_KEY:"
        Write-Host "    notepad $EnvFile"
    } else {
        Write-Warn "未找到 .env 模板, 请手动创建 $EnvFile"
    }
}

# ---------------- 链接到 PATH ----------------
function Link-ToPath {
    # Windows 策略：将启动器 .cmd/.ps1 复制到 %LOCALAPPDATA%\mwb-ai-claw-bin\ 并加入用户 PATH
    # （不用符号链接，避免权限问题；副本保持与 BinDir 一致）
    $userBin = Join-Path $env:LOCALAPPDATA "mwb-ai-claw-bin"
    New-Item -ItemType Directory -Force -Path $userBin | Out-Null

    Copy-Item -Force (Join-Path $BinDir "$CommandName.cmd") (Join-Path $userBin "$CommandName.cmd")
    Copy-Item -Force (Join-Path $BinDir "$CommandName.ps1") (Join-Path $userBin "$CommandName.ps1")

    if (-not (Test-PathContains $userBin)) {
        Add-PathEntry $userBin | Out-Null
        Write-Warn "$userBin 不在 PATH 中, 已加入用户 PATH"
        Write-Warn "请重开终端使 PATH 生效"
    }
    return $userBin
}

# ---------------- main ----------------
if ($Help) { Show-Help; exit 0 }
if ($Uninstall) { Do-Uninstall; exit 0 }

# install
Write-Info "安装目录: $InstallDir"
Check-Prerequisites

$jar = Resolve-Jar
Install-Files $jar
Init-Env

$pathDir = Link-ToPath
Write-OK "已创建命令: $pathDir\$CommandName.cmd (.cmd / .ps1)"

Write-Host ""
Write-OK "安装完成!"
Write-Host ""
Write-Host "  用法: 在任意目录执行 $CommandName 进入 Agent Shell" -ForegroundColor White
Write-Host "  帮助: 进入后输入 /help 查看命令" -ForegroundColor White
Write-Host "  审批: 默认 ask（高风险命令询问 y/N）；改为自动可在 $EnvFile 中设 MWB_AI_CLAW_APPROVAL_MODE=auto" -ForegroundColor White
$ConfigDir = Join-Path $InstallDir "config"
if (Test-Path $ConfigDir) {
    Write-Host "  自定义: 将 $ConfigDir 下文件复制到运行目录即可覆盖默认配置（agents/orchestrations/mcp-server）" -ForegroundColor White
}
Write-Host ""

# 检查 .env 中 DEFAULT_API_KEY 是否为空
if (Test-Path $EnvFile) {
    $envContent = Get-Content $EnvFile -Raw
    if ($envContent -notmatch '(?m)^DEFAULT_API_KEY=\S') {
        Write-Warn "提醒: $EnvFile 中 DEFAULT_API_KEY 仍为空, 请先填入再使用"
    }
}
Write-Host ""
