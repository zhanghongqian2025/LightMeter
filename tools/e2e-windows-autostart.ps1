<#
.SYNOPSIS
    Windows 开机自启（注册表 Run 键）：端到端（原生 PowerShell 入口）。

.DESCRIPTION
    这是 tools/e2e-windows-autostart.sh 的 Windows 原生版本：
    那个脚本在 macOS 上只能做到「编译驱动 + 报告 SKIP」，
    真正跑注册表往返要在这边。

    它做三件事：
      1. 编译 tools/E2eWindowsAutostart.java（保证交给 Windows 的代码不会因为笔误跑不起来）；
      2. 找出打包好的 LightMeter.exe（可用 -Exe 指定）；
      3. 跑驱动：装 → 用 reg.exe 独立读回比对 → 看状态 → 卸 → 再卸（幂等）。

    驱动里有一条「转义形式探测」：它会用两种引号写法各写一个探针值再读回来，
    直接告诉你这台机器上的 reg.exe 到底认哪一种 —— 那是本块唯一没法推理、
    只能实测的东西。

.NOTES
    判据独立于被测实现：驱动自己调 reg.exe、自己拼期望值、
    自己写了解析 reg query 输出的那几行，一条都不复用产品代码。

    退出码：
      0  = 全部通过
      1  = 有断言失败
      77 = 环境不具备（不是 Windows）

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\e2e-windows-autostart.ps1
    powershell -ExecutionPolicy Bypass -File tools\e2e-windows-autostart.ps1 -Exe "C:\Program Files\LightMeter\LightMeter.exe"
#>
[CmdletBinding()]
param(
    # 产品可执行文件的完整路径。留空 = 在 target/app-image 下找。
    [string]$Exe = '',
    # 自启服务要占用的端口。默认与产品一致。
    [int]$Port = 8090
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$work = Join-Path $env:TEMP ("litemask-win-e2e-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null

# 【为什么这里自己找 javac/java，而不是直接用 PATH 上的】
#   装了 JDK 不等于 javac 在 PATH 上 —— 常见的是只设了 JAVA_HOME，
#   而 %JAVA_HOME%\bin 没有加进 PATH。
#   → 先用 JAVA_HOME 下的，找不到再退回 PATH 上的那个。
#   （那个 bash 版本 tools/e2e-windows-autostart.sh 用的是同一条思路。）
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac' }
if ($env:JAVA_HOME -and -not (Test-Path -LiteralPath $javac)) { $javac = 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if ($env:JAVA_HOME -and -not (Test-Path -LiteralPath $java)) { $java = 'java' }

try {
    Write-Host '== 0. 编译端到端驱动 =='
    Write-Host "   javac = $javac"
    & $javac -encoding UTF-8 -d $work (Join-Path $root 'tools\E2eWindowsAutostart.java')
    if ($LASTEXITCODE -ne 0) {
        throw "驱动编译失败（退出码 $LASTEXITCODE）—— 交给 Windows 的那份代码是坏的"
    }
    Write-Host '   OK 驱动编译通过'

    Write-Host '== 1. 找产品可执行文件 =='
    if (-not $Exe) {
        foreach ($cand in @(
                'target\app-image\LightMeter.exe',
                'target\app-image\LightMeter\LightMeter.exe')) {
            $p = Join-Path $root $cand
            if (Test-Path -LiteralPath $p) { $Exe = $p; break }
        }
    }
    if (-not $Exe -or -not (Test-Path -LiteralPath $Exe)) {
        throw '找不到 LightMeter.exe —— 先跑 tools\package-windows.ps1，或用 -Exe 指定它的完整路径'
    }
    Write-Host "   $Exe"

    Write-Host '== 2. 跑端到端 =='
    & $java -cp $work E2eWindowsAutostart $Exe $Port
    $rc = $LASTEXITCODE
    if ($rc -eq 77) {
        Write-Host 'SKIP 驱动认为环境不具备（退出码 77）—— 不是通过'
    }
    exit $rc
}
finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
