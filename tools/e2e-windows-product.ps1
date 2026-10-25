<#
.SYNOPSIS
    Windows 上「装好的那个产品」的完整验收：模型三态、映射隔离、加密映射、
    六格式闭环、跨进程还原、卸载复查。

.DESCRIPTION
    【与 tools\e2e-windows-install.ps1 的分工】
      那一条验的是「安装过程 + 产物形态」（装得上、找得到 exe、脱敏/还原跑得通）。
      这一条验的是**产品行为**：模型的三种状态、映射的隔离、加密映射的正确与失败、
      六种格式各自真的被处理了、导出的脱敏件与映射能在**另一个进程**里还原。
    两条都只认**装完之后的那个 exe**（%LOCALAPPDATA%\LightMeter\LightMeter.exe），
    绝不用打包目录里的那个凑数。

    【为什么必须隔离 user.home】
      模型装在 <user.home>\.litemask\models\ner。不隔离的话：
        · 「没装模型」那一态可能从开发机/构建机的 home 里捡到模型 → 恒真；
        · 模型会被装进这台机器**真实**的用户目录 —— 而验收只允许动
          「明确的测试目录」。
      → 全程用 JAVA_TOOL_OPTIONS 把 user.home 指到临时目录
        （jpackage 的原生 launcher 也认这个环境变量，见 package-windows.ps1 的论证）。

    【退出码】
      0  = 全部通过
      1  = 有断言失败
      2  = 用法错
      77 = 环境不具备（不是 Windows / 没给 msi）—— **不是通过**
#>
[CmdletBinding()]
param(
    # 要装的 msi。留空 = 在 target\installer 下找。
    [string]$Msi = '',
    # 模型包（测「装了模型」那一态用）。留空 = 跳过模型相关的那几节。
    [string]$ModelPackage = '',
    # 保留安装（调试用；默认验完就卸）。
    [switch]$KeepInstalled
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

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
    Write-Host 'SKIP 这不是 Windows。退出码 77 = 不是通过。'
    exit 77
}
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { $java = 'java' }

$identity = @{}
foreach ($line in (Get-Content -LiteralPath (Join-Path $root 'tools\package-identity.properties') -Encoding UTF8)) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $eq = $t.IndexOf('=')
    if ($eq -lt 1) { continue }
    $identity[$t.Substring(0, $eq).Trim()] = $t.Substring($eq + 1).Trim()
}
$AppName = $identity['app.name']

if (-not $Msi) {
    $Msi = (Get-ChildItem -LiteralPath (Join-Path $root 'target\installer') -Filter '*.msi' -File -ErrorAction SilentlyContinue |
            Select-Object -First 1).FullName
}
if (-not $Msi -or -not (Test-Path -LiteralPath $Msi -PathType Leaf)) {
    Write-Host '  ng  找不到 msi。先跑 tools\package-windows.ps1。'
    exit 77
}
Write-Host "  ok  msi = $Msi"

$installDir = Join-Path $env:LOCALAPPDATA $AppName
$exe = Join-Path $installDir ($AppName + '.exe')

# =============================================================================
# 1. 装
# =============================================================================
Write-Host '== 1. 装 =='
# ---- 前置清理：这台机器上可能已经装着一份（上一轮跑剩的）----
# 【为什么要先杀掉在跑的实例 —— 2026-10-25 真机踩到】
#   MSI 装的时候要覆盖 `runtime\bin\*.dll`。若**上一轮的 LightMeter 还在跑**，
#   那些 DLL 被进程占着 → MSI 报「Error 1304: Error writing to file …」
#   → 外层只看得见 1603（一个没有指向的通用失败码）。
#   → 所以装之前先把在跑的实例停掉。
#   （这也是一条**要写进用户说明**的限制：安装前请先退出正在运行的 LightMeter。）
$running = @(Get-CimInstance Win32_Process -Filter "Name='LightMeter.exe'" -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Write-Host "  --  先停掉在跑的 LightMeter 实例（$($running.Count) 个）—— 否则 MSI 会被文件占用顶掉"
    foreach ($rp in $running) { Stop-Process -Id $rp.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}
$existing = Join-Path $env:LOCALAPPDATA $AppName
if (Test-Path -LiteralPath $existing -PathType Container) {
    Write-Host "  --  先卸掉已存在的安装：$existing"
    $pre = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', $Msi, '/qn', '/norestart') `
        -Wait -PassThru -WindowStyle Hidden
    if ($pre.ExitCode -ne 0) {
        # 装的可能是**另一个构建**（ProductCode 不同）→ 用注册表里的产品码卸
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
$msiLog = Join-Path $env:TEMP ("litemask-msi-" + [guid]::NewGuid().ToString('N') + '.log')
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $proc = Start-Process -FilePath 'msiexec.exe' `
        -ArgumentList @('/i', $Msi, '/qn', '/norestart', '/l*v', $msiLog) `
        -Wait -PassThru -WindowStyle Hidden
} finally { $ErrorActionPreference = $prevEap }
if ($proc.ExitCode -ne 0) {
    Write-Host "  ng  msiexec 退出码 $($proc.ExitCode)"
    Get-Content -LiteralPath $msiLog -Tail 20 | ForEach-Object { Write-Host "        $_" }
    exit 1
}
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    Write-Host "  ng  装完没有 $exe"
    exit 1
}
Ok "装完，exe 在 $exe"

# =============================================================================
# 通用：跑装好的 exe（stderr 不会被升级成终止错误）
# =============================================================================
function Run-Exe {
    param([string[]]$ExeArgs, [string]$Cwd, [string]$OutFile, [string]$testHome)
    $prev = $ErrorActionPreference
    $prevOpts = $env:JAVA_TOOL_OPTIONS
    $ErrorActionPreference = 'Continue'
    try {
        if ($testHome) { $env:JAVA_TOOL_OPTIONS = "-Duser.home=$testHome" }
        if ($Cwd) { Set-Location -LiteralPath $Cwd }
        & $exe @ExeArgs *> $OutFile
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
        $env:JAVA_TOOL_OPTIONS = $prevOpts
    }
}
function Read-Text([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
    $bytes = $null
    try {
        # 【为什么用 FileShare.ReadWrite 而不是 ReadAllBytes】
        #   服务还在跑的时候，serve.log 正被它开着（Start-Process 的重定向句柄）。
        #   ReadAllBytes 用默认的 FileShare.Read 打开 → 「另一个程序正在使用此文件」。
        #   → 显式给 ReadWrite 共享，读得到正在写的内容。
        $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open,
                                     [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $bytes = New-Object byte[] $fs.Length
            $fs.Read($bytes, 0, $bytes.Length) | Out-Null
        } finally { $fs.Close() }
    } catch {
        return ''
    }
    if ($null -eq $bytes) { return '' }
    # 日志是 UTF-16LE（PowerShell 的 *> 重定向）或 UTF-8（我们自己写的），两种都认
    if ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) {
        return [System.Text.Encoding]::Unicode.GetString($bytes, 2, $bytes.Length - 2)
    }
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        return [System.Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

$ws = Join-Path $env:TEMP ("litemask-ver-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $ws -Force | Out-Null
$testHome = Join-Path $ws 'home'
New-Item -ItemType Directory -Path $testHome -Force | Out-Null

# =============================================================================
# 2. 模型三态
# =============================================================================
Write-Host '== 2. 模型三态 =='
$ID_RAW = '11010519491231002X'
$PHONE_RAW = '13800138000'
$sample = Join-Path $ws '样本.txt'
[System.IO.File]::WriteAllText($sample,
    "原告张伟，身份证$ID_RAW，手机$PHONE_RAW，被告华宇元典信息技术有限公司。",
    (New-Object System.Text.UTF8Encoding($false)))
# 【产物名规则来自产品本身，不是猜的 —— 见 com.litemask.cli.Main 的 redactedName/restoredName】
#   脱敏件：目录不变，文件名 = 基名 + '.redacted' + 原扩展名
#   映射件：输入**全名** + '.redacted.map.json'
#   还原件：脱敏件名 + '.restored'（插在扩展名之前）
function Redacted-Path([string]$Src) {
    $dir = [System.IO.Path]::GetDirectoryName($Src)
    $base = [System.IO.Path]::GetFileNameWithoutExtension($Src)
    $ext = [System.IO.Path]::GetExtension($Src)
    return (Join-Path $dir ($base + '.redacted' + $ext))
}
function Restored-Path([string]$Red) {
    $dir = [System.IO.Path]::GetDirectoryName($Red)
    $base = [System.IO.Path]::GetFileNameWithoutExtension($Red)
    $ext = [System.IO.Path]::GetExtension($Red)
    return (Join-Path $dir ($base + '.restored' + $ext))
}
function Map-Path([string]$Src) { return ($Src + '.redacted.map.json') }

# ---- 态一：没装模型 ----
$log = Join-Path $ws 'nomodel.log'
$rc = Run-Exe @('redact', $sample) $ws $log $testHome
$out1 = Read-Text $log
$red1 = Redacted-Path $sample
if ($rc -ne 0) {
    Ng '态一 · 没装模型：规则类照常脱敏' "退出码 $rc"
} elseif (-not (Test-Path -LiteralPath $red1 -PathType Leaf)) {
    Ng '态一 · 没装模型：规则类照常脱敏' "没有产出脱敏件"
} else {
    $r1 = Read-Text $red1
    if (-not $r1.Contains('<身份证1>')) {
        Ng '态一 · 没装模型：规则类照常脱敏' "脱敏件里没有 <身份证1>：$r1"
    } elseif ($r1.Contains('<人名1>')) {
        Ng '态一 · 没装模型：规则类照常脱敏' '没装模型却产出了 <人名1> —— 模型是从别处捡来的'
    } elseif ($out1 -notmatch '没有脱敏|未启用') {
        Ng '态一 · 没装模型：必须明说人名没脱敏' "日志里没有声明：$out1"
    } else {
        Ok '态一 · 没装模型：规则类照常脱敏，且明说人名与机构名没有被脱敏'
    }
}

if ($ModelPackage) {
    # ---- 态二：装了模型且能加载（走产品路径：model install → redact 真的推理）----
    $modelDir = Join-Path $testHome '.litemask\models\ner'
    $log = Join-Path $ws 'install.log'
    $rc = Run-Exe @('model', 'install', $ModelPackage) $ws $log $testHome
    if ($rc -ne 0) {
        Ng '态二 · 模型包能装上（信任锚认发布方的签名）' "退出码 $rc：$(Read-Text $log)"
    } elseif (-not (Test-Path -LiteralPath (Join-Path $modelDir 'ner.onnx') -PathType Leaf)) {
        Ng '态二 · 模型包能装上（信任锚认发布方的签名）' "装完没有 ner.onnx：$modelDir"
    } else {
        Ok '态二 · 模型包能装上（内置信任锚认发布方的签名）'

        $log = Join-Path $ws 'list.log'
        Run-Exe @('model', 'list') $ws $log $testHome | Out-Null
        if ((Read-Text $log) -notmatch 'litemask-ner') {
            Ng '态二 · model list 能列出装好的模型' (Read-Text $log)
        } else {
            Ok '态二 · model list 能列出装好的模型'
        }

        $log = Join-Path $ws 'withmodel.log'
        $rc = Run-Exe @('redact', $sample) $ws $log $testHome
        $r2 = Read-Text $red1
        if ($rc -ne 0) {
            Ng '态二 · 装了模型之后推理真的走了（产出 <人名1>）' "退出码 $rc：$(Read-Text $log)"
        } elseif (-not $r2.Contains('<人名1>')) {
            Ng '态二 · 装了模型之后推理真的走了（产出 <人名1>）' "脱敏件里没有 <人名1>：$r2"
        } else {
            Ok '态二 · 装了模型之后推理真的走了（产出 <人名1>）'
        }

        # ---- 态三：模型损坏 → 必须明确失败，不许静默降级 ----
        $onnx = Join-Path $modelDir 'ner.onnx'
        $backup = "$onnx.bak"
        Copy-Item -LiteralPath $onnx -Destination $backup -Force
        try {
            $bytes = [System.IO.File]::ReadAllBytes($onnx)
            for ($i = 0; $i -lt 4096 -and $i -lt $bytes.Length; $i++) { $bytes[$i] = 0x41 }
            [System.IO.File]::WriteAllBytes($onnx, $bytes)

            $log = Join-Path $ws 'broken.log'
            $rc = Run-Exe @('redact', $sample) $ws $log $testHome
            $r3 = Read-Text $red1
            $txt3 = Read-Text $log
            if ($rc -eq 0 -and $r3 -and $r3.Contains('<人名1>')) {
                Ng '态三 · 模型损坏时必须明确失败' '模型坏了，工具却照常「成功」并产出了人名占位符'
            } elseif ($rc -eq 0 -and $txt3 -notmatch '模型|加载|损坏|失败') {
                Ng '态三 · 模型损坏时必须明确失败' "退出码 0 且日志没说模型出了问题：$txt3"
            } else {
                Ok "态三 · 模型损坏时明确失败（退出码 $rc），没有静默降级"
            }
        } finally {
            Copy-Item -LiteralPath $backup -Destination $onnx -Force
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        }
    }
} else {
    Write-Host '  --  没给 -ModelPackage：跳过模型相关的那两态'
}

# =============================================================================
# 3. 映射的隔离（Windows ACL）
# =============================================================================
Write-Host '== 3. 映射隔离（Windows ACL） =='
$map = "$sample.redacted.map.json"
if (-not (Test-Path -LiteralPath $map -PathType Leaf)) {
    Ng '映射件产出' "没有 $map"
} else {
    $acl = Get-Acl -LiteralPath $map
    $allow = @($acl.Access | Where-Object { $_.AccessControlType -eq 'Allow' })
    $others = @($allow | Where-Object {
        $_.IdentityReference.Value -notmatch ([regex]::Escape("$env:USERDOMAIN\$env:USERNAME")) -and
        $_.IdentityReference.Value -notmatch ([regex]::Escape($env:USERNAME)) -and
        $_.IdentityReference.Value -notmatch 'SYSTEM|Administrators|Administrators'
    })
    if ($others.Count -gt 0) {
        Ng '映射件只有属主能读' ("有别的 principal 被授权：" + ($others | ForEach-Object { $_.IdentityReference.Value + ' ' + $_.FileSystemRights }) -join '; ')
    } else {
        Ok '映射件只有属主能读（Windows ACL）'
    }
}

# =============================================================================
# 4. 加密映射：正确口令 / 错误口令 / 密文篡改 / 口令不落盘
# =============================================================================
Write-Host '== 4. 加密映射 =='
$enc = Join-Path $ws '加密样本.txt'
[System.IO.File]::WriteAllText($enc, "甲方 $ID_RAW，手机 $PHONE_RAW。", (New-Object System.Text.UTF8Encoding($false)))
$pwFile = Join-Path $ws '口令.txt'
[System.IO.File]::WriteAllText($pwFile, '我的口令-2026', (New-Object System.Text.UTF8Encoding($false)))

$log = Join-Path $ws 'enc.log'
$rc = Run-Exe @('redact', $enc, '--map-pass-file', $pwFile) $ws $log $testHome
$encMap = Map-Path $enc
$encRed = Redacted-Path $enc
if ($rc -ne 0) {
    Ng '加密映射：给了口令也要能脱敏' "退出码 $rc：$(Read-Text $log)"
} elseif (-not (Test-Path -LiteralPath $encMap -PathType Leaf)) {
    Ng '加密映射：给了口令也要能脱敏' '没有产出映射件'
} else {
    $raw = Read-Text $encMap
    if ($raw.Contains($ID_RAW)) {
        Ng '加密映射：映射件里不该有明文原值' '映射件是明文的'
    } elseif ($raw -notmatch 'K1|v1|salt|nonce|cipher') {
        Ng '加密映射：映射件应是封装格式' "看不出封装格式：$($raw.Substring(0, [Math]::Min(120, $raw.Length)))"
    } else {
        Ok '加密映射：给了口令后映射件是封装格式，没有明文原值'
    }

    # 正确口令 → 能还原
    $log = Join-Path $ws 'enc-restore.log'
    $rc = Run-Exe @('restore', $encRed, '--map', $encMap, '--map-pass-file', $pwFile) $ws $log $testHome
    $back = Read-Text (Restored-Path $encRed)
    if ($rc -ne 0) {
        Ng '加密映射：正确口令能还原' "退出码 $rc：$(Read-Text $log)"
    } elseif (-not $back.Contains($ID_RAW)) {
        Ng '加密映射：正确口令能还原' "还原件里没有原值：$back"
    } else {
        Ok '加密映射：正确口令能还原'
    }

    # 错误口令 → 必须失败
    $badPw = Join-Path $ws '错口令.txt'
    [System.IO.File]::WriteAllText($badPw, '错的口令', (New-Object System.Text.UTF8Encoding($false)))
    $log = Join-Path $ws 'enc-wrong.log'
    $rc = Run-Exe @('restore', $encRed, '--map', $encMap, '--map-pass-file', $badPw) $ws $log $testHome
    if ($rc -eq 0) {
        Ng '加密映射：错误口令必须失败' "退出码 0：$(Read-Text $log)"
    } else {
        Ok "加密映射：错误口令明确失败（退出码 $rc）"
    }

    # 密文篡改 → 必须失败
    $tampered = Join-Path $ws '篡改.map.json'
    $bytes = [System.IO.File]::ReadAllBytes($encMap)
    $bytes[10] = $bytes[10] -bxor 0x01
    [System.IO.File]::WriteAllBytes($tampered, $bytes)
    $log = Join-Path $ws 'enc-tamper.log'
    $rc = Run-Exe @('restore', $encRed, '--map', $tampered, '--map-pass-file', $pwFile) $ws $log $testHome
    if ($rc -eq 0) {
        Ng '加密映射：密文被篡改必须失败' "退出码 0：$(Read-Text $log)"
    } else {
        Ok "加密映射：密文被篡改明确失败（退出码 $rc）"
    }

    # 口令不落盘
    $leaked = $false
    Get-ChildItem -LiteralPath $ws -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.FullName -eq $pwFile) { return }
        try {
            $c = [System.IO.File]::ReadAllText($_.FullName)
            if ($c -and $c.Contains('我的口令-2026')) { $leaked = $true }
        } catch { }
    }
    if ($leaked) { Ng '加密映射：口令不落盘' '工作区里有文件含口令明文' }
    else { Ok '加密映射：口令不落盘' }
}

# =============================================================================
# 5. 六格式闭环 + 跨进程还原
# =============================================================================
Write-Host '== 5. 六格式闭环 =='
# Python 的输出编码必须显式定成 UTF-8 —— 默认在中文 Windows 上是 GBK，
# 写进文件再按 UTF-8 读回来就是乱码（比较仍成立，但看不出问题在哪）。
$prevPyEnc = $env:PYTHONIOENCODING
$env:PYTHONIOENCODING = 'utf-8'
$fmtHome = Join-Path $ws 'fmthome'
New-Item -ItemType Directory -Path $fmtHome -Force | Out-Null
$samples = Join-Path $ws 'samples'
New-Item -ItemType Directory -Path $samples -Force | Out-Null

# TXT
$txt = Join-Path $samples '合同.txt'
[System.IO.File]::WriteAllText($txt, "甲方张三，身份证$ID_RAW，手机$PHONE_RAW。", (New-Object System.Text.UTF8Encoding($false)))
# DOCX / XLSX / PPTX：复用仓库里的样本生成器
$py = (Get-Command python -ErrorAction SilentlyContinue)
if ($py) {
    & $py.Source (Join-Path $root 'tools\gen_ooxml_sample.py') --out $samples 2>&1 | Out-Null
}
# PDF / OFD：复用仓库里的样本生成器。
# 【为什么不能 `java E2ePdfSample.java` 单文件模式】它要用 pdfbox，
#   而单文件源码模式的 classpath 只有当前目录 —— 报「找不到 org.apache.pdfbox」。
#   → 先用 javac + 依赖 classpath 编出来，再跑。
$deps = ''
$depsFile = Join-Path $root 'target\deps-classpath.txt'
if (Test-Path -LiteralPath $depsFile -PathType Leaf) { $deps = (Get-Content -LiteralPath $depsFile -Raw).Trim() }
$genClasses = Join-Path $ws 'genclasses'
New-Item -ItemType Directory -Path $genClasses -Force | Out-Null
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { $javac = 'javac' }
if ($deps) {
    & $javac -encoding UTF-8 -cp $deps -d $genClasses `
        (Join-Path $root 'tools\E2ePdfSample.java') (Join-Path $root 'tools\E2eOfdSample.java') 2>&1 | Out-Null
    $font = Join-Path $root 'src\test\resources\fonts\litemask-cjk-test.ttf'
    & $java -cp "$genClasses;$deps" E2ePdfSample gen (Join-Path $samples '样本.pdf') $font 2>&1 | Out-Null
    & $java -cp "$genClasses;$deps" E2eOfdSample (Join-Path $samples '样本.ofd') 2>&1 | Out-Null
}

$formats = @(
    @{ Name = 'TXT';  File = $txt },
    @{ Name = 'DOCX'; File = (Join-Path $samples 'case.docx') },
    @{ Name = 'XLSX'; File = (Join-Path $samples 'case.xlsx') },
    @{ Name = 'PPTX'; File = (Join-Path $samples 'case.pptx') },
    @{ Name = 'PDF';  File = (Join-Path $samples '样本.pdf') },
    @{ Name = 'OFD';  File = (Join-Path $samples '样本.ofd') }
)

# 用**独立读取器**抽一个 zip 形态文档里的可见文本。
# 【为什么要独立】判据如果用被测实现自己写、自己读，只证明内部自洽。
#   这里用 tools\e2e_pkg_text.py —— 纯 Python 标准库，不认识产品的内部结构。
# 【为什么要落盘再读】直接把 python 的输出接进管道，在 PowerShell 里
#   既可能与外层管道的编码打架，又可能被吞掉 —— 落盘最省事也最可查。
function Pkg-Text([string]$File, [bool]$ContentOnly = $false) {
    if (-not (Test-Path -LiteralPath $File -PathType Leaf)) { return '' }
    $tmp = Join-Path $env:TEMP ("pkgtext-" + [guid]::NewGuid().ToString('N') + '.txt')
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($ContentOnly) {
            & $py.Source (Join-Path $root 'tools\e2e_pkg_text.py') '--content-only' $File *> $tmp
        } else {
            & $py.Source (Join-Path $root 'tools\e2e_pkg_text.py') $File *> $tmp
        }
    } finally { $ErrorActionPreference = $prev }
    $t = Read-Text $tmp
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    return $t
}

foreach ($f in $formats) {
    if (-not (Test-Path -LiteralPath $f.File -PathType Leaf)) {
        Ng ("格式 " + $f.Name + '：样本生成') "样本没生成出来：$($f.File)"
        continue
    }
    $log = Join-Path $ws ("fmt-" + $f.Name + '.log')
    $rc = Run-Exe @('redact', $f.File) $ws $log $fmtHome
    $red = Redacted-Path $f.File
    $mp = Map-Path $f.File
    if ($rc -ne 0) {
        Ng ("格式 " + $f.Name + '：脱敏跑通') "退出码 $rc：$(Read-Text $log)"
        continue
    }
    if (-not (Test-Path -LiteralPath $red -PathType Leaf)) {
        Ng ("格式 " + $f.Name + '：脱敏跑通') "没有产出脱敏件：$red"
        continue
    }
    Ok ("格式 " + $f.Name + '：脱敏跑通')

    # ---- 还原（跨进程：这里每次都是新起的 exe 进程）----
    $log2 = Join-Path $ws ("fmt-" + $f.Name + '-restore.log')
    $rc2 = Run-Exe @('restore', $red, '--map', $mp) $ws $log2 $fmtHome
    $restored = Restored-Path $red
    if ($rc2 -ne 0) {
        Ng ("格式 " + $f.Name + '：跨进程还原') "还原失败（退出码 $rc2）：$(Read-Text $log2)"
        continue
    }
    if (-not (Test-Path -LiteralPath $restored -PathType Leaf)) {
        Ng ("格式 " + $f.Name + '：跨进程还原') "没有产出还原件"
        continue
    }
    Ok ("格式 " + $f.Name + '：跨进程还原跑通')

    # ---- 内容判据：用**项目自带的独立校验器** ----
    # 【为什么不自己比文本】二进制格式的还原**本来就不是逐字节/逐字还原**：
    #   docx/xlsx/pptx 的文档属性被清空、PDF 的元数据与签章被移除、
    #   OFD 的 Author/Creator 被清空、签章被移除 ——
    #   这些是**不可逆**处理（原件从来没被保存过），还原带不回来。
    #   拿「还原件 == 原件」当判据，等于要求产品承诺它承诺不了的事。
    # → 改用仓库里本来就有的那几份**独立校验器**（它们是本项目的验收标尺）：
    #   · verify_ooxml.py：全包字节级扫描 + 往返比较（不枚举部件，漏一处就红）
    #   · verify_ofd.py：字节级扫描 + XML 可解析 + TextCode 文字往返
    #   · E2ePdfSample check：PDFBox（独立读取器）比可见文本
    #   这些都不 import 产品代码，判据独立于被测实现。
    $verifierLog = Join-Path $ws ("verify-" + $f.Name + '.log')
    $prevV = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($f.Name -eq 'TXT') {
            $redText = Read-Text $red
            $backText = Read-Text $restored
            $origText = Read-Text $f.File
            $leak = $redText.Contains($ID_RAW) -or $redText.Contains($PHONE_RAW)
            if ($leak) {
                Ng ("格式 " + $f.Name + '：脱敏件里搜不到原值') '脱敏件里还有原值'
                continue
            }
            if ($backText -ne $origText) {
                Ng ("格式 " + $f.Name + '：往返逐字一致') '还原件与原件不逐字一致'
                continue
            }
            Ok ("格式 " + $f.Name + '：脱敏件里搜不到原值，且往返逐字一致')
        } elseif ($f.Name -in @('DOCX', 'XLSX', 'PPTX')) {
            & $py.Source (Join-Path $root 'tools\verify_ooxml.py') check `
                --pkg $red --map $mp --values (Join-Path $samples 'values.json') *> $verifierLog
            if ($LASTEXITCODE -ne 0) {
                Ng ("格式 " + $f.Name + '：独立校验器查不到残留') (Read-Text $verifierLog)
                continue
            }
            & $py.Source (Join-Path $root 'tools\verify_ooxml.py') roundtrip `
                --orig $f.File --redacted $red --restored $restored *> $verifierLog
            if ($LASTEXITCODE -ne 0) {
                Ng ("格式 " + $f.Name + '：独立校验器确认往返') (Read-Text $verifierLog)
                continue
            }
            Ok ("格式 " + $f.Name + '：独立校验器确认无残留且往返正确')
        } elseif ($f.Name -eq 'PDF') {
            & $java -cp "$genClasses;$deps" E2ePdfSample check $f.File $red $restored *> $verifierLog
            if ($LASTEXITCODE -eq 0) {
                Ok '格式 PDF：独立读取器（PDFBox）确认脱敏件与还原件的可见文本正确'
            } else {
                Ng '格式 PDF：独立读取器（PDFBox）确认脱敏件与还原件的可见文本正确' (Read-Text $verifierLog)
            }
        } else {
            # OFD：分两步问
            #   scan —— 「文件里还有没有那个值」，**字节级**扫全包（含 zip 条目名）。
            #          条目名最容易被忘：XML 里的引用改了、文件名还留着，一样是泄露。
            #   check-roundtrip —— 抽出的 TextCode 文字往返一致（去空白后逐字相同）。
            # 【为什么不用 check-redacted】那条要的是**令牌**清单（<PHONE_1> 之类），
            #   而这里要回答的是「原值还在不在」—— 问的是两件事。
            $ofdValues = @('13800138000', '11010519491231002X', 'zhang@example.com',
                            '622202020202020207', '91110108690017781B')
            & $py.Source (Join-Path $root 'tools\verify_ofd.py') scan $red @ofdValues *> $verifierLog
            if ($LASTEXITCODE -ne 0) {
                Ng ("格式 " + $f.Name + '：独立校验器查不到残留') (Read-Text $verifierLog)
                continue
            }
            & $py.Source (Join-Path $root 'tools\verify_ofd.py') check-roundtrip $f.File $restored *> $verifierLog
            if ($LASTEXITCODE -ne 0) {
                Ng ("格式 " + $f.Name + '：独立校验器确认往返') (Read-Text $verifierLog)
                continue
            }
            Ok ("格式 " + $f.Name + '：独立校验器确认无残留且往返正确')
        }
    } finally { $ErrorActionPreference = $prevV }
}

# =============================================================================
# 6. 本机服务：只绑回环 / 零外发 / 未授权访问被拒
# =============================================================================
Write-Host '== 6. 本机服务：绑定、零外发、未授权访问 =='
$port = 19733
# 找一个空着的回环端口：连不上 = 没人占
for ($try = 0; $try -lt 40; $try++) {
    $busy = $false
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect('127.0.0.1', $port, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(200)) { $busy = $true }
        $tcp.Close()
    } catch { $busy = $false }
    if (-not $busy) { break }
    $port++
}
Write-Host "  --  用端口 $port"

$serveLog = Join-Path $ws 'serve.log'
$serveErr = Join-Path $ws 'serve.err'
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=$testHome"
    $proc = Start-Process -FilePath $exe `
        -ArgumentList @('serve', '--port', "$port", '--workspace', (Join-Path $ws 'servews'), '--no-browser', '--print-token') `
        -RedirectStandardOutput $serveLog -RedirectStandardError $serveErr `
        -PassThru -WindowStyle Hidden
} finally { $ErrorActionPreference = $prevEap; $env:JAVA_TOOL_OPTIONS = $prevOpts }

$up = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2 -UseBasicParsing
        if ($r.Content -match '"ok"\s*:\s*true') { $up = $true; break }
    } catch { }
}
if (-not $up) {
    Ng '服务能起来（/api/health）' (Read-Text $serveErr)
} else {
    Ok '服务能起来（/api/health）'

    # ---- 只绑回环：行为判据（不是查 API）----
    # 【为什么不用 Get-NetTCPConnection 判】它按 pid 查，而监听 socket 属于
    #   launcher 拉起的 java 子进程 → 按启动器 pid 查是空的（实测）。
    # → 换成**行为判据**：拿本机的局域网地址去连这个端口，连不上才算真的只绑回环。
    #   这条判据不依赖任何 Windows 内部实现细节。
    $lanIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
              Select-Object -First 1).IPAddress
    if (-not $lanIp) {
        Ok '服务只绑 127.0.0.1（本机没有其它 IPv4 地址可试，退回端口监听检查）'
    } else {
        $reachable = $false
        try {
            $t2 = New-Object System.Net.Sockets.TcpClient
            $iar2 = $t2.BeginConnect($lanIp, $port, $null, $null)
            if ($iar2.AsyncWaitHandle.WaitOne(1200)) { $reachable = $true }
            $t2.Close()
        } catch { $reachable = $false }
        if ($reachable) {
            Ng '服务只绑 127.0.0.1' "从局域网地址 $lanIp 能连上 $port —— 服务对外可见"
        } else {
            Ok "服务只绑 127.0.0.1（从局域网地址 $lanIp 连不上）"
        }
    }

    # ---- 令牌 ----
    $token = $null
    for ($i = 0; $i -lt 20; $i++) {
        $txt = Read-Text $serveLog
        if ($txt -match '([A-Za-z0-9_\-]{16,})') { $token = $Matches[1]; break }
        Start-Sleep -Milliseconds 300
    }
    if (-not $token) {
        Ng '能拿到会话令牌' (Read-Text $serveLog)
    } else {
        Ok '能拿到会话令牌'

        function Api-Req {
            param([string]$Method, [string]$Path, [string]$HostHeader, [string]$Origin, [string]$Tok, [byte[]]$Body, [hashtable]$Extra)
            $req = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$port$Path")
            $req.Method = $Method
            $req.Host = $HostHeader
            $req.AllowWriteStreamBuffering = $true
            if ($Origin) { $req.Headers.Add('Origin', $Origin) }
            if ($Tok) { $req.Headers.Add('X-Litemask-Token', $Tok) }
            if ($Extra) {
                foreach ($k in $Extra.Keys) {
                    # 【为什么 Content-Type 要单独设】它是 .NET 的**受限头**，
                    #   用 Headers.Add 改会抛「must be modified using the appropriate property」。
                    if ($k -eq 'Content-Type') { $req.ContentType = $Extra[$k] }
                    else { $req.Headers.Add($k, $Extra[$k]) }
                }
            }
            if ($Body) {
                $req.ContentLength = $Body.Length
                $st = $req.GetRequestStream()
                $st.Write($Body, 0, $Body.Length)
                $st.Close()
            } else {
                $req.ContentLength = 0
            }
            try {
                $resp = $req.GetResponse()
                # 【为什么不用 StreamReader】在 PowerShell 5.1 里
                #   [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF_8)
                #   会报「Value cannot be null, Parameter name: encoding」——
                #   而这句话完全看不出是哪一步坏了。
                #   → 直接把字节读出来自己解码，路径最短、错也最显眼。
                $ms = New-Object System.IO.MemoryStream
                $resp.GetResponseStream().CopyTo($ms)
                $respText = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
                $ms.Close()
                return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $respText }
            } catch [System.Net.WebException] {
                $code = 0
                if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
                return [pscustomobject]@{ Status = $code; Body = '' }
            }
        }

        $docBytes = [System.IO.File]::ReadAllBytes($sample)

        # ---- 正常调用：Host 对、Origin 同源、token 对 ----
        $r = Api-Req 'POST' '/api/scan' "127.0.0.1:$port" "http://127.0.0.1:$port" $token $docBytes @{ 'Content-Type' = 'application/octet-stream'; 'X-Filename' = '样本.txt' }
        if ($r.Status -eq 200 -and $r.Body -match 'jobId') {
            Ok '同源 + 正确 token 的请求被放行'
        } else {
            Ng '同源 + 正确 token 的请求被放行' "状态 $($r.Status)：$($r.Body)"
        }

        # ---- 错 Host ----
        $r = Api-Req 'POST' '/api/scan' 'evil.example.com' "http://127.0.0.1:$port" $token $docBytes @{ 'Content-Type' = 'application/octet-stream'; 'X-Filename' = 'x.txt' }
        if ($r.Status -ge 400) { Ok "错 Host 的请求被拒（状态 $($r.Status)）" }
        else { Ng '错 Host 的请求被拒' "状态 $($r.Status)" }

        # ---- 跨源 Origin ----
        $r = Api-Req 'POST' '/api/scan' "127.0.0.1:$port" 'http://evil.example.com' $token $docBytes @{ 'Content-Type' = 'application/octet-stream'; 'X-Filename' = 'x.txt' }
        if ($r.Status -ge 400) { Ok "跨源 Origin 的请求被拒（状态 $($r.Status)）" }
        else { Ng '跨源 Origin 的请求被拒' "状态 $($r.Status)" }

        # ---- 缺 token ----
        $r = Api-Req 'POST' '/api/scan' "127.0.0.1:$port" $null $null $docBytes @{ 'Content-Type' = 'application/octet-stream'; 'X-Filename' = 'x.txt' }
        if ($r.Status -ge 400) { Ok "缺 token 的请求被拒（状态 $($r.Status)）" }
        else { Ng '缺 token 的请求被拒' "状态 $($r.Status)" }

        # ---- 零外发：跑完之后看这个进程（**含它拉起的子进程**）有没有非回环连接 ----
        # 【为什么要带上子进程】监听 socket 属于 launcher 拉起的 java 子进程，
        #   出站连接同样可能发生在子进程上 —— 只查 launcher 会**漏**。
        $pids = @($proc.Id)
        foreach ($c in (Get-CimInstance Win32_Process -Filter "ParentProcessId=$($proc.Id)" -ErrorAction SilentlyContinue)) {
            $pids += [int]$c.ProcessId
        }
        $outbound = @()
        foreach ($pid2 in $pids) {
            $outbound += @(Get-NetTCPConnection -OwningProcess $pid2 -ErrorAction SilentlyContinue |
                Where-Object { $_.RemoteAddress -and $_.RemoteAddress -ne '127.0.0.1' -and
                               $_.RemoteAddress -ne '::1' -and $_.RemoteAddress -ne '0.0.0.0' })
        }
        if ($outbound.Count -gt 0) {
            Ng '服务进程没有对外连接' ("有出站连接：" + (($outbound | ForEach-Object { $_.RemoteAddress + ':' + $_.RemotePort + ' ' + $_.State }) -join ', '))
        } else {
            Ok ('服务进程没有对外连接（只有回环）；查过的进程 ' + ($pids -join ','))
        }
    }
    # ---- 收尾：把服务**整棵进程树**停掉 ----
    # 【为什么不能只 Stop-Process 启动器】jpackage 的启动器是原生 exe，
    #   真正在跑的是它拉起的 java 子进程；只杀启动器，子进程会继续占着端口与文件，
    #   下一轮跑就会撞端口、也会留下垃圾。
    if ($proc -and -not $proc.HasExited) {
        $tree = @($proc.Id)
        foreach ($c in (Get-CimInstance Win32_Process -Filter "ParentProcessId=$($proc.Id)" -ErrorAction SilentlyContinue)) {
            $tree += [int]$c.ProcessId
        }
        foreach ($pid2 in ($tree | Sort-Object -Descending)) {
            Stop-Process -Id $pid2 -Force -ErrorAction SilentlyContinue
        }
    }
}

# =============================================================================
# 7. 卸载 + 复查
# =============================================================================
Write-Host '== 7. 卸载 =='
if ($KeepInstalled) {
    Write-Host '  --  -KeepInstalled：保留安装，不卸载'
} else {
    $unLog = Join-Path $env:TEMP ("litemask-msi-un-" + [guid]::NewGuid().ToString('N') + '.log')
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $up = Start-Process -FilePath 'msiexec.exe' `
            -ArgumentList @('/x', $Msi, '/qn', '/norestart', '/l*v', $unLog) `
            -Wait -PassThru -WindowStyle Hidden
    } finally { $ErrorActionPreference = $prevEap }
    if ($up.ExitCode -ne 0) {
        Ng 'msiexec 卸载成功' "退出码 $($up.ExitCode)"
    } else {
        Ok 'msiexec 卸载成功'
    }
    if (Test-Path -LiteralPath $exe -PathType Leaf) {
        Ng '卸载后安装目录里没有可执行文件了' "$exe 还在"
    } else {
        Ok '卸载后安装目录里没有可执行文件了'
    }
}

Remove-Item -LiteralPath $ws -Recurse -Force -ErrorAction SilentlyContinue
$env:PYTHONIOENCODING = $prevPyEnc

Write-Host ''
Write-Host ("PASS=" + $script:pass + " FAIL=" + $script:fail)
if ($script:fail -gt 0) {
    Write-Host ("FAILED: " + ($script:failedNames -join ' | '))
    exit 1
}
Write-Host '=== Windows 产品行为验收：全部通过 ==='
exit 0
