<#
.SYNOPSIS
    Windows 侧「装完再验」的**判定内核**：平台中立的纯逻辑 + 一个可执行的 CLI。

.DESCRIPTION
    【这一块补的是什么】
      Windows 侧打完 .msi 之后**没有任何验证**（块 29 / §三十三 登记的缺口）：
      「MSI 装出来能不能用」这件事，目前**没有任何自动化检查**。
      macOS 侧有 tools/package-dmg.sh 第 4/5 步做这件事
      （只读挂载 → codesign 验签 → 用**挂载卷里**的 .app 跑脱敏/还原往返）。
      → Windows 侧缺的就是这一段，两端至今**不对称**。

    【为什么判定内核能跨平台，而安装不能】
      「装完再验」= **装**（msiexec /i）+ **验**（判定产物）。
        · 装 —— Windows 专有（msiexec、Program Files、注册表），本机做不了；
        · 验 —— 读文件 + 字面比对 —— **平台中立**。
      → 把「验」抽成这个文件里的具名函数：
          Windows 上  tools/e2e-windows-install.ps1 调用它（真机跑）；
          macOS 上   work/windows-install-checks-test.ps1 用**真的 PowerShell 解释器**跑。
      → 剩下只有「装」这一下是 Windows 专有 —— 写进阶段文档的已知限制，不假装验过。

    【判据独立于被测实现 —— 本文件一条产品代码都不调用】
      判定全部靠读文件 + 字面比对：不 import 任何 Java 类、不调用产品、
      不复用 tools/ 下任何一个校验器。
      → 它验的是「装出来的**产物**对不对」，不是「产品内部自洽」。

    【事实清单是唯一真源】
      Get-InstallCheckFacts 返回全部被判定的事实（两种形态的并集）。
      work/windows-install-checks-test.ps1 对**每一条**都有一组差分式负控制，
      work/check-windows-install-pitfalls.py 现算比对条数 —— **不写死**。
      （写死的组数会漂；漂了不会红，只会让人对覆盖度产生错误印象。）

    【为什么判据落在「报这一事实的那一行」】
      每条事实 = 一处判定。事实名与判定结果里的 Fact 字段一一对应，
      行为测试按**名字**索引结果 —— 删掉某条判定 → 那条事实变成「没有结果」→
      行为测试立刻报红，而不是「少了一条但还是绿的」。

.NOTES
    本文件**不需要**在 Windows 上跑就能被验证；
    但它**描述的产物形态**（jpackage msi 的安装目录布局）来自 jpackage 文档与
    tools/package-windows.ps1 的调用参数，**尚未在 Windows 真机上实测过**。
    → 这一点写进阶段文档的已知限制，不当成已验证的事实。

    CLI 退出码：
      0 = 全部**适用**事实通过
      1 = 有适用事实不通过
      2 = 用法错（前提不成立）
#>
[CmdletBinding()]
param(
    # 只有显式给 -RunCli 才执行 CLI 主体。
    # → 被 dot-source 或被 AST 抽取函数体时，主体**不会**跑（块 28 建立的用法）。
    [switch]$RunCli,

    [ValidateSet('Verify', 'SelfTest')]
    [string]$Mode = 'Verify',

    # 原始输入文件（产物名由它推导）
    #
    # 【2026-10-25 真 Windows 实测踩到的真缺陷 —— 参数名撞了自动变量】
    #   原来这里写的是 `[string]$Input`。
    #   而 **`$input` 是 PowerShell 的自动变量**（管道输入的枚举器）——
    #   参数名与它重名时，**绑定会被静默忽略**：脚本里读到的 `$Input`
    #   永远是那个自动变量（空），于是哪怕调用方明明白白传了
    #   `-Input <路径>`，也一律报「必须给 -Input」。
    #   这条 CLI 主体此前**从未被真正执行过**（macOS 侧的行为测试是
    #   dot-source 函数体跑的，主体没跑），所以没人发现。
    #   → 参数改名为 $InputPath（不与任何自动变量冲突），
    #     并保留 -Input 作为别名，老调用方式照样能用。
    [Alias('Input')]
    [string]$InputPath = '',

    # redact 的日志文件（用来查「有没有如实声明没脱敏」）
    [string]$Log = '',

    # 要检查的两个原值（脱敏件里不该有、映射件里必须有）。
    # 留空 → 相关事实**判红**（前提不成立），不是「静默通过」。
    [string]$IdRaw = '',
    [string]$PhoneRaw = '',

    # 安装目录（Windows 上是 Program Files 下的安装根）
    [string]$InstallDir = '',

    # 应用名 / jar 名（留空 = 从打包身份真源取）
    [string]$AppName = '',
    [string]$JarName = '',

    # 有没有装 NER 模型包
    [switch]$ExpectModel,

    # 只验产物、不验安装目录布局（显式开关，不是「不传参数就静默跳过」）
    [switch]$SkipLayout
)

$ErrorActionPreference = 'Stop'

# =============================================================================
# 事实清单 —— **唯一真源**
# =============================================================================
#
# 【为什么清单住在被测代码里，而不是守卫里】
#   守卫（work/check-windows-install-pitfalls.py）要现算「判定覆盖了多少条事实」，
#   而事实的定义住在**判定内核**这一侧。
#   → 清单是唯一真源；守卫读它，行为测试逐条覆盖它，两者都不另抄一份。
#
# 【为什么两种形态的事实放在同一个并集里】
#   核心包**不带模型**（方案 B）→ 交付形态是 NoModel；
#   而「装了模型」那一态也要能验（否则模型装错地方这类缺陷没人查）。
#   → 两种形态的事实列在一起，各自在**不适用的形态**里标为「不适用」。
#     不用「不适用」冒充「通过」——CLI 汇总里 skip 与 pass 分开报。
function Get-InstallCheckFacts {
    return @(
        # ---- 安装目录布局（4 条：装完到底装成什么样）----
        'install-exe-exists'
        'install-app-dir-exists'
        'install-jar-exists'
        'install-runtime-dir-exists'
        # ---- 产物与内容（11 条：装出来的东西对不对）----
        'redacted-exists'
        'map-exists'
        'redacted-has-id-placeholder'
        'redacted-has-phone-placeholder'
        'redacted-lacks-id-raw'
        'redacted-lacks-phone-raw'
        'redacted-lacks-digit-fragment'
        'map-has-id-raw'
        'map-has-phone-raw'
        'restored-exists'
        'restored-identical'
        # ---- NoModel 形态（2 条：核心包不带模型时）----
        'nomodel-lacks-name-placeholder'
        'nomodel-log-declares-unredacted'
        # ---- WithModel 形态（2 条：装了模型时）----
        'model-has-name-placeholder'
        'model-log-no-false-unredacted'
    )
}

# =============================================================================
# 纯逻辑函数
# =============================================================================

# 造一条判定结果。
# 【为什么要有 Applicable 这一维】
#   「这一条在当前形态下不适用」与「这一条通过」是两件事。
#   差分式负控制要能说清「坏在哪一条」；如果不适用的条目混在通过里，
#   「只有预期那条红」这条断言就没法断言了。
function New-CheckResult {
    param([string]$Fact, [bool]$Ok, [bool]$Applicable, [string]$Detail)
    return @{ Fact = $Fact; Ok = $Ok; Applicable = $Applicable; Detail = $Detail }
}

# 读文本文件；读不到（不存在 / 是目录 / 解码失败）返回 $null。
# 【为什么不直接 Get-Content】Get-Content 会把内容拆成行、再按数组返回，
# 判「逐字一致」时容易因为换行处理而出岔子；这里要的是**原样的一整个字符串**。
function Read-TextFile {
    param([string]$Path)
    if (-not $Path) { return $null }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try {
        return [System.IO.File]::ReadAllText($Path)
    } catch {
        return $null
    }
}

# 文件是否存在**且非空**。
# 【为什么把「非空」并进「存在」】
#   jpackage 装完如果 jar 是 0 字节，那不叫「装上了」。分两条判据会让
#   「存在」单独成立、掩盖「装了个空壳」；并成一条，红灯原因才指向真问题。
function Test-ArtifactPresent {
    param([string]$Path)
    if (-not $Path) { return $false }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $fi = Get-Item -LiteralPath $Path
    return ($fi.Length -gt 0)
}

# 产物名推导。
#
# 【规则来自产品本身，不是猜的】
#   src/main/java/com/litemask/cli/Main.java:
#     redactedName : 目录不变，文件名 = 基名 + '.redacted' + 原扩展名
#     restoredName : 同上，再来一次（基名 + '.restored' + 原扩展名）
#     映射名       : 输入**全名** + '.redacted.map.json'（不改扩展名位置！）
#
# 【映射名为什么单列，它不是 redacted 规则】
#   产品里映射名是 `Path.of(input + ".redacted.map.json")` —— 拿**输入全名**加后缀，
#   而 redacted 是「换扩展名位置」。两种规则不同：
#     t.txt → 脱敏件 t.redacted.txt     映射件 t.txt.redacted.map.json
#   → 推导错一个字符，「装完再验」就会在「产物根本不存在」上红灯，
#     而真因是**判定内核自己算错了名字**。所以这里单列一条规则。
#
# 【为什么用 lastIndexOf('.') 而不是按固定后缀长度减】
#   多层扩展名（a.tar.gz）与无扩展名（README）都要走同一条路，
#   与产品 restoredName 的实现完全对称（Main.java:369）。
function Get-ArtifactPaths {
    param([string]$InputPath)

    $full = [System.IO.Path]::GetFullPath($InputPath)
    $dir = [System.IO.Path]::GetDirectoryName($full)
    $name = [System.IO.Path]::GetFileName($full)

    $dot = $name.LastIndexOf('.')
    if ($dot -lt 0) { $base = $name; $ext = '' } else { $base = $name.Substring(0, $dot); $ext = $name.Substring($dot) }

    $redactedName = $base + '.redacted' + $ext
    $mapName = $name + '.redacted.map.json'

    # restored：对 redacted 再走一次「换扩展名位置」
    $rdot = $redactedName.LastIndexOf('.')
    if ($rdot -lt 0) { $rbase = $redactedName; $rext = '' } else { $rbase = $redactedName.Substring(0, $rdot); $rext = $redactedName.Substring($rdot) }
    $restoredName = $rbase + '.restored' + $rext

    return @{
        Input    = $full
        Redacted = [System.IO.Path]::Combine($dir, $redactedName)
        Map      = [System.IO.Path]::Combine($dir, $mapName)
        Restored = [System.IO.Path]::Combine($dir, $restoredName)
    }
}

# 去占位符后，是否还留着**连续 6 位以上的裸数字**。
#
# 【为什么查碎片，而不只查完整原值】
#   18 位身份证被切成三条命中时，每条都不等于原文，正则查不出来，
#   但页面上就只剩「读不出是不是敏感信息」的东西（与 macOS 侧同一条理由）。
function Test-NoDigitFragment {
    param([string]$Text)
    if ($null -eq $Text) { return $true }
    $stripped = [regex]::Replace($Text, '<[^>]*>', '')
    return (-not [regex]::IsMatch($stripped, '[0-9]{6,}'))
}

# 判定内核的入口：跑完整套事实判定。
#
# 【返回】
#   数组，每项 = @{ Fact; Ok; Applicable; Detail }
#
# 【前提不成立一律判红，不静默跳过 —— 项目铁律】
#   没给安装目录 → 布局 4 条判红；没给输入文件 → 产物相关全部判红；
#   没给要检查的原值 → 与原值相关的事实判红。
#   「前提不成立」与「判定不通过」都归到红，但 Detail 说清是哪一种。
function Test-InstallArtifacts {
    param(
        [string]$InstallDir = '',
        [string]$AppName = 'LightMeter',
        [string]$JarName = 'desens-core.jar',
        [string]$InputPath = '',
        [string]$LogPath = '',
        [string]$IdRaw = '',
        [string]$PhoneRaw = '',
        [switch]$ExpectModel,
        [switch]$SkipLayout
    )

    $results = New-Object System.Collections.ArrayList

    # ---------------- 安装目录布局（4 条）----------------
    $layoutFacts = @('install-exe-exists', 'install-app-dir-exists', 'install-jar-exists', 'install-runtime-dir-exists')
    if ($SkipLayout) {
        foreach ($f in $layoutFacts) {
            [void]$results.Add((New-CheckResult $f $true $false '显式跳过（-SkipLayout）'))
        }
    } elseif (-not $InstallDir) {
        foreach ($f in $layoutFacts) {
            [void]$results.Add((New-CheckResult $f $false $true '前提不成立：没有给安装目录（-InstallDir）'))
        }
    } else {
        $exe = [System.IO.Path]::Combine($InstallDir, ($AppName + '.exe'))
        $appDir = [System.IO.Path]::Combine($InstallDir, 'app')
        $jar = [System.IO.Path]::Combine($appDir, $JarName)
        $runtime = [System.IO.Path]::Combine($InstallDir, 'runtime')

        $okExe = Test-ArtifactPresent $exe
        [void]$results.Add((New-CheckResult 'install-exe-exists' $okExe $true "安装目录下的可执行文件：$exe"))

        $okApp = Test-Path -LiteralPath $appDir -PathType Container
        [void]$results.Add((New-CheckResult 'install-app-dir-exists' $okApp $true "jpackage 把 jar 放在 app 子目录下：$appDir"))

        $okJar = Test-ArtifactPresent $jar
        [void]$results.Add((New-CheckResult 'install-jar-exists' $okJar $true "应用 jar：$jar"))

        $okRt = Test-Path -LiteralPath $runtime -PathType Container
        [void]$results.Add((New-CheckResult 'install-runtime-dir-exists' $okRt $true "jlink 裁剪后的运行时：$runtime"))
    }

    # ---------------- 产物与内容（11 条）----------------
    $contentFacts = @(
        'redacted-exists', 'map-exists',
        'redacted-has-id-placeholder', 'redacted-has-phone-placeholder',
        'redacted-lacks-id-raw', 'redacted-lacks-phone-raw', 'redacted-lacks-digit-fragment',
        'map-has-id-raw', 'map-has-phone-raw',
        'restored-exists', 'restored-identical'
    )
    $formFacts = @('nomodel-lacks-name-placeholder', 'nomodel-log-declares-unredacted',
                  'model-has-name-placeholder', 'model-log-no-false-unredacted')
    if (-not $InputPath) {
        foreach ($f in ($contentFacts + $formFacts)) {
            [void]$results.Add((New-CheckResult $f $false $true '前提不成立：没有给原始输入文件（-Input）'))
        }
        return $results
    }

    $paths = Get-ArtifactPaths $InputPath
    $redactedText = Read-TextFile $paths.Redacted
    $mapText = Read-TextFile $paths.Map
    $logText = Read-TextFile $LogPath

    $okRedacted = Test-ArtifactPresent $paths.Redacted
    [void]$results.Add((New-CheckResult 'redacted-exists' $okRedacted $true "脱敏件：$($paths.Redacted)"))

    $okMap = Test-ArtifactPresent $paths.Map
    [void]$results.Add((New-CheckResult 'map-exists' $okMap $true "映射件：$($paths.Map)"))

    $hasId = ($null -ne $redactedText) -and $redactedText.Contains('<身份证1>')
    [void]$results.Add((New-CheckResult 'redacted-has-id-placeholder' $hasId $true '脱敏件里应有 <身份证1>'))

    $hasPhone = ($null -ne $redactedText) -and $redactedText.Contains('<手机号1>')
    [void]$results.Add((New-CheckResult 'redacted-has-phone-placeholder' $hasPhone $true '脱敏件里应有 <手机号1>'))

    # ---- 与原值有关的几条：前提（原值）不成立就判红，不静默通过 ----
    if (-not $IdRaw) {
        foreach ($f in @('redacted-lacks-id-raw', 'map-has-id-raw')) {
            [void]$results.Add((New-CheckResult $f $false $true '前提不成立：没有给要检查的身份证原值（-IdRaw）'))
        }
    } else {
        $leakId = ($null -ne $redactedText) -and $redactedText.Contains($IdRaw)
        [void]$results.Add((New-CheckResult 'redacted-lacks-id-raw' (-not $leakId) $true '脱敏件里不该残留身份证原值'))
        $mapHasId = ($null -ne $mapText) -and $mapText.Contains($IdRaw)
        [void]$results.Add((New-CheckResult 'map-has-id-raw' $mapHasId $true '映射件里必须有完整的身份证原值'))
    }
    if (-not $PhoneRaw) {
        foreach ($f in @('redacted-lacks-phone-raw', 'map-has-phone-raw')) {
            [void]$results.Add((New-CheckResult $f $false $true '前提不成立：没有给要检查的手机号原值（-PhoneRaw）'))
        }
    } else {
        $leakPhone = ($null -ne $redactedText) -and $redactedText.Contains($PhoneRaw)
        [void]$results.Add((New-CheckResult 'redacted-lacks-phone-raw' (-not $leakPhone) $true '脱敏件里不该残留手机号原值'))
        $mapHasPhone = ($null -ne $mapText) -and $mapText.Contains($PhoneRaw)
        [void]$results.Add((New-CheckResult 'map-has-phone-raw' $mapHasPhone $true '映射件里必须有完整的手机号原值'))
    }

    $noFrag = Test-NoDigitFragment $redactedText
    [void]$results.Add((New-CheckResult 'redacted-lacks-digit-fragment' $noFrag $true '去掉占位符后不该有连续 6 位以上的裸数字'))

    $okRestored = Test-ArtifactPresent $paths.Restored
    [void]$results.Add((New-CheckResult 'restored-exists' $okRestored $true "还原件：$($paths.Restored)"))

    $origText = Read-TextFile $paths.Input
    $restoredText = Read-TextFile $paths.Restored
    $identical = ($null -ne $origText) -and ($null -ne $restoredText) -and ($origText -ceq $restoredText)
    [void]$results.Add((New-CheckResult 'restored-identical' $identical $true '还原件必须与原文逐字相同（区分大小写）'))

    # ---------------- 形态相关的四条 ----------------
    if ($ExpectModel) {
        [void]$results.Add((New-CheckResult 'nomodel-lacks-name-placeholder' $true $false '不适用：本次是 WithModel 形态'))
        [void]$results.Add((New-CheckResult 'nomodel-log-declares-unredacted' $true $false '不适用：本次是 WithModel 形态'))

        $mHasName = ($null -ne $redactedText) -and $redactedText.Contains('<人名1>')
        [void]$results.Add((New-CheckResult 'model-has-name-placeholder' $mHasName $true '装了模型就应产出 <人名1>'))

        $falseDecl = ($null -ne $logText) -and ([regex]::IsMatch($logText, '没有脱敏|未启用'))
        [void]$results.Add((New-CheckResult 'model-log-no-false-unredacted' (-not $falseDecl) $true '装了模型却声明「没有脱敏」——自相矛盾'))
    } else {
        [void]$results.Add((New-CheckResult 'model-has-name-placeholder' $true $false '不适用：本次是 NoModel 形态'))
        [void]$results.Add((New-CheckResult 'model-log-no-false-unredacted' $true $false '不适用：本次是 NoModel 形态'))

        $nHasName = ($null -ne $redactedText) -and $redactedText.Contains('<人名1>')
        [void]$results.Add((New-CheckResult 'nomodel-lacks-name-placeholder' (-not $nHasName) $true '核心包不带模型，不该产出 <人名1>（那说明模型是从别处捡来的）'))

        $declared = ($null -ne $logText) -and ([regex]::IsMatch($logText, '没有脱敏|未启用'))
        [void]$results.Add((New-CheckResult 'nomodel-log-declares-unredacted' $declared $true '没装模型就必须明说「人名与机构名没有被脱敏」'))
    }

    return $results
}

# =============================================================================
# CLI 主体（只有 -RunCli 时才跑）
# =============================================================================

if ($RunCli) {
    if ($Mode -eq 'SelfTest') {
        # 自检：事实清单必须非空、无重名。
        $facts = @(Get-InstallCheckFacts)
        $dup = @($facts | Group-Object | Where-Object { $_.Count -gt 1 })
        if ($dup.Count -gt 0) {
            Write-Host ("NG 事实清单里有重名：" + (($dup | ForEach-Object { $_.Name }) -join ', '))
            exit 1
        }
        if ($facts.Count -eq 0) {
            Write-Host 'NG 事实清单是空的 —— 判定内核没有事实可判'
            exit 1
        }
        Write-Host ("OK 事实清单 " + $facts.Count + " 条，无重名")
        exit 0
    }

    # 打包身份真源（与 tools/package-identity.properties 同一份，不另抄）。
    $here = Split-Path -Parent $PSCommandPath
    $identityPath = Join-Path (Join-Path $here '..') 'package-identity.properties'
    $identity = @{}
    if (Test-Path -LiteralPath $identityPath -PathType Leaf) {
        foreach ($line in (Get-Content -LiteralPath $identityPath -Encoding UTF8)) {
            $t = $line.Trim()
            if ($t -eq '' -or $t.StartsWith('#')) { continue }
            $eq = $t.IndexOf('=')
            if ($eq -lt 1) { continue }
            $identity[$t.Substring(0, $eq).Trim()] = $t.Substring($eq + 1).Trim()
        }
    }
    if (-not $AppName) { $AppName = $identity['app.name'] }
    if (-not $JarName) { $JarName = $identity['jar.name'] }
    if (-not $AppName) { $AppName = 'LightMeter' }
    if (-not $JarName) { $JarName = 'desens-core.jar' }

    if (-not $InputPath) {
        Write-Host 'NG 用法：-Mode Verify 必须给 -Input（原始输入文件）'
        exit 2
    }
    if (-not (Test-Path -LiteralPath $InputPath -PathType Leaf)) {
        Write-Host ("NG 原始输入文件不存在：$InputPath")
        exit 2
    }

    $rs = @(Test-InstallArtifacts -InstallDir $InstallDir -AppName $AppName -JarName $JarName `
                                   -InputPath $InputPath -LogPath $Log -IdRaw $IdRaw -PhoneRaw $PhoneRaw `
                                   -ExpectModel:$ExpectModel -SkipLayout:$SkipLayout)
    $pass = 0; $fail = 0; $skip = 0
    foreach ($r in $rs) {
        if (-not $r.Applicable) {
            $skip++
            Write-Host ("--  [$($r.Fact)] $($r.Detail)")
            continue
        }
        if ($r.Ok) {
            $pass++
            Write-Host ("OK  [$($r.Fact)] $($r.Detail)")
        } else {
            $fail++
            Write-Host ("NG  [$($r.Fact)] $($r.Detail)")
        }
    }
    Write-Host ("== 装完再验判定：pass=$pass fail=$fail skip=$skip ==")
    if ($fail -gt 0) { exit 1 }
    exit 0
}
