<#
.SYNOPSIS
    Windows 侧「装完再验」：**真的把 msi 装上，装完用装好的程序跑一遍脱敏/还原**，
    判定交给 tools/windows-install-checks.ps1（平台中立的判定内核）。

.DESCRIPTION
    【这一块补的是哪个缺口】
      块 29 / §三十三 登记过：**Windows 侧打完 msi 之后没有任何验证**。
      「MSI 装出来能不能用」这件事，此前**没有任何自动化检查**。
      macOS 侧有 tools/package-dmg.sh 第 4/5 步做同类的事
      （只读挂载 → 验签 → 用挂载卷里的 .app 跑脱敏/还原往返）。
      → 本脚本是 Windows 侧对应的那一段。

    【为什么「装」与「验」要分开】
      装（msiexec /i、Program Files、注册表）是 Windows 专有，本机做不了；
      验（判定产物）是平台中立。
      → 判定逻辑全部放在 tools/windows-install-checks.ps1，
        本脚本只负责**编排**：装 → 找到装到哪 → 跑 → 调判定内核 → 卸载。
      → 这样「验」那一半在 macOS 上也能被真解释器验证（work/windows-install-checks-test.ps1），
        而「装」那一半的零验证被明确登记成已知限制。

    【关键取舍：验的是「装出来的那个 exe」，不是打包目录里的那个】
      jpackage 的 msi 会把 app-image 的内容装进 Program Files\<AppName>\。
      → 验打包目录（target/app-image）只能证明「打包没坏」，
        证明不了「装完能用」——安装过程（权限、路径、shortcut、卸载项）全在范围外。
      → 所以本脚本**只**认安装目录里的 exe；找不到就报红（前提不成立），
        绝不去用打包目录的那个凑数。

    【为什么必须卸载】
      「装完再验」如果只装不卸，这一轮验证会**污染机器**：
      下次跑会撞上「已经装过了」，而 msiexec 的重装行为与首次安装不同。
      → 验完一定卸载，并且**卸载后再查一遍安装目录确实没了**。

    【退出码】
      0  = 装完再验全部通过
      1  = 有断言失败
      2  = 用法错
      77 = 环境不具备（不是 Windows / 没给 msi）—— **不是通过**

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\e2e-windows-install.ps1 -Msi target\installer\LightMeter-1.0.0.msi

.NOTES
    本脚本**从未在 Windows 上执行过**（本机没有 Windows）。
    它的可验证部分（判定内核 + 参数接线 + 退出码约定）由
    work/check-windows-install-pitfalls.py 覆盖；
    真机部分（msiexec 装/卸、Program Files 路径）**只能靠 Windows 真机**。
#>
[CmdletBinding()]
param(
    # 要装的 msi。留空 = 在 target\installer 下找。
    [string]$Msi = '',

    # 安装目录。留空 = 用 jpackage msi 的默认（%ProgramFiles%\<AppName>）。
    [string]$InstallDir = '',

    # 有没有装 NER 模型包（决定判定内核跑哪种形态）
    [switch]$ExpectModel,

    # 保留安装（调试用；默认验完就卸）
    [switch]$KeepInstalled
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---- 控制台输出编码：显式定成 UTF-8 ------------------------------------
# 【为什么】本产品的 stdout 统一是 UTF-8，而 PowerShell 捕获原生命令输出时
#   用 [Console]::OutputEncoding 解码，那个值默认取**控制台 OEM 代码页**
#   （中文 Windows = 936/GBK）。不设的话日志里是乱码，
#   「日志里必须出现『没有脱敏』」这类判据就恒假，红灯还长得像产品坏了。
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    Write-Host "  (改不了控制台输出编码，CLI 日志可能是乱码：$($_.Exception.Message))"
}

$script:pass = 0
$script:fail = 0
$script:failedNames = @()
function Ok([string]$name) {
    $script:pass++
    Write-Host "  ok  $name"
}
function Ng([string]$name, [string]$detail) {
    $script:fail++
    $script:failedNames += $name
    Write-Host "  ng  $name —— $detail"
}

# =============================================================================
# 0. 环境前置
# =============================================================================
Write-Host '== 0. 环境前置 =='

if ($env:OS -ne 'Windows_NT') {
    Write-Host 'SKIP 这不是 Windows（$env:OS 不是 Windows_NT）。'
    Write-Host '     Windows「装完再验」必须在真 Windows 上跑；本机只完成「判定内核」那一半。'
    Write-Host '     退出码 77 = 不是通过。'
    exit 77
}

$checksPath = Join-Path $root 'tools\windows-install-checks.ps1'
if (-not (Test-Path -LiteralPath $checksPath -PathType Leaf)) {
    Write-Host "  ng  找不到判定内核：$checksPath"
    exit 1
}
Write-Host "  ok  判定内核：$checksPath"

# 打包身份真源（不另抄一份）
$identityPath = Join-Path $root 'tools\package-identity.properties'
$identity = @{}
foreach ($line in (Get-Content -LiteralPath $identityPath -Encoding UTF8)) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $eq = $t.IndexOf('=')
    if ($eq -lt 1) { continue }
    $identity[$t.Substring(0, $eq).Trim()] = $t.Substring($eq + 1).Trim()
}
$AppName = $identity['app.name']
$JarName = $identity['jar.name']
if (-not $AppName -or -not $JarName) {
    Write-Host '  ng  打包身份真源缺 app.name 或 jar.name'
    exit 1
}
Write-Host "  ok  应用名 = $AppName，jar = $JarName"

# ---- 找 msi ----
if (-not $Msi) {
    $instDir = Join-Path $root 'target\installer'
    if (Test-Path -LiteralPath $instDir -PathType Container) {
        $found = Get-ChildItem -LiteralPath $instDir -Filter '*.msi' -File -ErrorAction SilentlyContinue |
                 Select-Object -First 1
        if ($found) { $Msi = $found.FullName }
    }
}
if (-not $Msi -or -not (Test-Path -LiteralPath $Msi -PathType Leaf)) {
    Write-Host '  ng  找不到要装的 msi。先跑 tools\package-windows.ps1，或用 -Msi 指定完整路径。'
    exit 77
}
Write-Host "  ok  msi = $Msi"

# 安装目录：默认按「先 per-user、再 per-machine」的顺序**探测**。
# 【为什么不能写死 %ProgramFiles%】jpackage 的 msi 默认是 per-machine（要管理员），
#   而本项目的发布目标是「普通用户双击就能装」，所以打包时带了 --win-per-user-install。
#   → 装到 **%LOCALAPPDATA%\<AppName>**（本机实测：msiexec 装完就是这个位置；
#     注意不是 %LOCALAPPDATA%\Programs\… —— 那是别的安装器的惯例，jpackage 不那样）。
#   而**旧包**（per-machine）装在 %ProgramFiles%\<AppName>。
#   写死一个的话，换了安装形态就报「装完找不到 exe」——而那个红是真的
#   （程序确实装在另一个地方），只是答案被写死了。
if (-not $InstallDir) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA $AppName),
        (Join-Path $env:LOCALAPPDATA ('Programs\' + $AppName)),
        (Join-Path ${env:ProgramFiles} $AppName)
    )
    $InstallDir = $null
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c -PathType Container) { $InstallDir = $c; break }
    }
    if (-not $InstallDir) {
        # 还没装：按 per-user 约定给出默认路径（装完再验时它就是那个位置）
        $InstallDir = $candidates[0]
    }
}
Write-Host "  ok  安装目录 = $InstallDir"

# =============================================================================
# 1. 装
# =============================================================================
Write-Host '== 1. 装 =='
# ---- 前置：停掉在跑的实例 ----
# 【为什么】MSI 装的时候要覆盖 runtime\bin\*.dll；若上一轮的 LightMeter 还在跑，
#   那些 DLL 被占着 → MSI 报「Error 1304」→ 外层只看得见 1603（通用失败码）。
$running = @(Get-CimInstance Win32_Process -Filter "Name='LightMeter.exe'" -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Write-Host "  --  先停掉在跑的 LightMeter 实例（$($running.Count) 个）"
    foreach ($rp in $running) { Stop-Process -Id $rp.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}
# msiexec 的日志要落盘：静默安装失败时，msiexec 自己只会给一个退出码，
# 不看日志根本不知道是「权限不够」「版本冲突」还是「文件被占用」。
$msiLog = Join-Path $env:TEMP ("litemask-msi-" + [guid]::NewGuid().ToString('N') + ".log")

# ---- 前置：卸掉可能已经存在的旧安装 ----
# 【为什么】jpackage 按「应用名 + 版本」**确定性地**生成 ProductCode，
#   同一个版本的两次构建 ProductCode 相同 → 直接装会走进「重配置」分支，
#   报 1638「已安装这个产品的另一个版本」。
#   → 先卸干净，让这一轮从**干净状态**开始（否则验的是「覆盖安装」不是「安装」）。
$existing = Join-Path $env:LOCALAPPDATA $AppName
if (Test-Path -LiteralPath $existing -PathType Container) {
    Write-Host "  --  先卸掉已存在的安装：$existing"
    $pre = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', $Msi, '/qn', '/norestart') `
        -Wait -PassThru -WindowStyle Hidden
    if ($pre.ExitCode -ne 0) {
        $code = (Get-ChildItem 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue |
                 ForEach-Object { (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue) } |
                 Where-Object { $_.DisplayName -eq $AppName } |
                 Select-Object -First 1).PSChildName
        if ($code) {
            Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', $code, '/qn', '/norestart') `
                -Wait -WindowStyle Hidden | Out-Null
        }
    }
}

$msiArgs = @('/i', $Msi, '/qn', '/norestart', '/l*v', $msiLog)
$proc = Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArgs -Wait -PassThru -WindowStyle Hidden
if ($proc.ExitCode -ne 0) {
    Write-Host "  ng  msiexec 退出码 $($proc.ExitCode)（安装失败）"
    if (Test-Path -LiteralPath $msiLog) {
        Write-Host '      msi 日志末尾：'
        Get-Content -LiteralPath $msiLog -Tail 30 | ForEach-Object { Write-Host "        $_" }
    }
    exit 1
}
Write-Host '  ok  msiexec 装完（退出码 0）'

# =============================================================================
# 2. 找到装到哪（**只认安装目录**，不去打包目录凑数）
# =============================================================================
Write-Host '== 2. 定位安装目录 =='
$exe = Join-Path $InstallDir ($AppName + '.exe')
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    Ng '装完能找到可执行文件' "安装目录下没有 $exe（目录本身存在=$([bool](Test-Path -LiteralPath $InstallDir -PathType Container))）"
    if (Test-Path -LiteralPath $InstallDir -PathType Container) {
        Get-ChildItem -LiteralPath $InstallDir -Recurse -Depth 2 -ErrorAction SilentlyContinue |
            Select-Object -First 30 | ForEach-Object { Write-Host "        $($_.FullName)" }
    }
    exit 1
}
Ok '装完能找到可执行文件'

# =============================================================================
# 3. 用装好的 exe 跑脱敏 + 还原
# =============================================================================
Write-Host '== 3. 用装好的程序跑脱敏 + 还原 =='
$ws = Join-Path $env:TEMP ("litemask-ws-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $ws -Force | Out-Null

# 隔离 user.home：测的是**新用户形态**（装完的人家里没有模型），
# 理由与 tools/package-windows.ps1 第 6 步完全一致（那里有详细论证）。
# 不隔离的话，「没装模型」这一态可能从开发机/构建机的 home 里捡到模型 → 恒真。
$emptyHome = Join-Path $ws 'empty-home'
New-Item -ItemType Directory -Path $emptyHome -Force | Out-Null
$savedOpts = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$emptyHome"

$src = Join-Path $ws 't.txt'
$text = '张三丰的身份证是11010519491231002X，手机13800138000。'
[System.IO.File]::WriteAllText($src, $text, (New-Object System.Text.UTF8Encoding($false)))
$redactLog = Join-Path $ws 'redact.log'

# 【2026-10-25】原生命令的 stderr 在 PowerShell 5.1 里会被 ErrorActionPreference='Stop'
#   升级成终止性错误（详见 tools\package-windows.ps1 里的 Invoke-External 注释）。
#   这里跑的是**装好的那个 exe**，它把提示写在 stderr 上 → 必须包一层。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $exe redact $src *> $redactLog
    $rcRedact = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $prevEap
}
$env:JAVA_TOOL_OPTIONS = $savedOpts

if ($rcRedact -ne 0) {
    Ng '装好的程序能跑脱敏' "退出码 $rcRedact"
    Get-Content -LiteralPath $redactLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
    exit 1
}
Ok '装好的程序能跑脱敏'

# 还原：装完再验要验的是**往返**，不是单向。
$redacted = Join-Path $ws 't.redacted.txt'
$map = Join-Path $ws 't.txt.redacted.map.json'
$restoreLog = Join-Path $ws 'restore.log'
$rcRestore = 0
if (Test-Path -LiteralPath $redacted -PathType Leaf) {
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $exe restore $redacted --map $map *> $restoreLog
        $rcRestore = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
} else {
    $rcRestore = -1
}
if ($rcRestore -ne 0) {
    Ng '装好的程序能跑还原' "退出码 $rcRestore（脱敏件存在=$([bool](Test-Path -LiteralPath $redacted)))"
    Get-Content -LiteralPath $restoreLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
} else {
    Ok '装好的程序能跑还原'
}

# =============================================================================
# 4. 判定（交给平台中立的判定内核）
# =============================================================================
Write-Host '== 4. 判定（tools\windows-install-checks.ps1）=='
# 【为什么这里调 CLI 而不是把判定抄一份】
#   抄一份 = 两份判据必然漂移，而漂移了不会红。
#   → 判定只有一份实现，在 tools\windows-install-checks.ps1；
#     这份实现本身在 macOS 上被 work\windows-install-checks-test.ps1 真跑过。
# 【2026-10-25 真 Windows 实测踩到的两个真缺陷】
#
# ① 变量名打错：原来写的是 `& $checkPath`，而上面定义的变量叫 **$checksPath**（多个 s）
#    → $checkPath 是 null → PowerShell 报「'&' 之后的表达式产生了无效对象」，
#      而那句话看不出「变量名打错了一个字母」。
#    这条脚本此前**从未在 Windows 上执行过**，所以没人发现。
#
# ② **数组 splat 到 PowerShell 脚本是按位置绑定的**：
#    原来写的是 `$checkArgs = @('-RunCli','-Mode','Verify',…)` + `& $checksPath @checkArgs`，
#    结果 '-RunCli' 被当成 **-Mode 的值** → ValidateSet 报错
#    「参数 -RunCli 不属于集合 Verify,SelfTest」。
#    → 具名参数必须用**哈希表 splat**（@{} 才是按名传的；@数组 是按位置传的）。
#    （这跟「对原生命令用数组 splat」不一样：给 .exe 传时那些字符串原样透传，
#      由 exe 自己解析；而 .ps1 是 PowerShell 自己绑定参数。）
$checkParams = @{
    RunCli     = $true
    Mode       = 'Verify'
    InputPath  = $src
    Log        = $redactLog
    InstallDir = $InstallDir
    AppName    = $AppName
    JarName    = $JarName
    IdRaw      = '11010519491231002X'
    PhoneRaw   = '13800138000'
}
if ($ExpectModel) { $checkParams['ExpectModel'] = $true }
& $checksPath @checkParams
$rcCheck = $LASTEXITCODE
if ($rcCheck -eq 0) {
    Ok '判定内核：全部适用事实通过'
} else {
    Ng '判定内核：全部适用事实通过' "退出码 $rcCheck"
}

# =============================================================================
# 5. 卸载 + 复查
# =============================================================================
Write-Host '== 5. 卸载 =='
if ($KeepInstalled) {
    Write-Host '  --  -KeepInstalled：保留安装，不卸载'
} else {
    $uninstLog = Join-Path $env:TEMP ("litemask-msi-un-" + [guid]::NewGuid().ToString('N') + ".log")
    $up = Start-Process -FilePath 'msiexec.exe' `
            -ArgumentList @('/x', $Msi, '/qn', '/norestart', '/l*v', $uninstLog) `
            -Wait -PassThru -WindowStyle Hidden
    if ($up.ExitCode -ne 0) {
        Ng 'msiexec 卸载成功' "退出码 $($up.ExitCode)"
        if (Test-Path -LiteralPath $uninstLog) {
            Get-Content -LiteralPath $uninstLog -Tail 20 | ForEach-Object { Write-Host "      $_" }
        }
    } else {
        Ok 'msiexec 卸载成功'
    }
    # 卸载后必须**再看一眼磁盘**：msiexec 退 0 不等于文件没了。
    if (Test-Path -LiteralPath $exe -PathType Leaf) {
        Ng '卸载后安装目录里没有可执行文件了' "$exe 还在"
    } else {
        Ok '卸载后安装目录里没有可执行文件了'
    }
}

Remove-Item -LiteralPath $ws -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host ("PASS=" + $script:pass + " FAIL=" + $script:fail)
if ($script:fail -gt 0) {
    Write-Host ("FAILED: " + ($script:failedNames -join ' | '))
    exit 1
}
Write-Host '=== Windows 装完再验：全部通过 ==='
exit 0
