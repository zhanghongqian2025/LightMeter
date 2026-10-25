<#
.SYNOPSIS
    用**装好的程序**验收「普通用户全程不碰终端」这条承诺。

.DESCRIPTION
    这个脚本模拟一个**干净环境**的普通用户：
      · 用一个**空的 user.home** 起程序（模拟「刚装好、还没用过」的机器）；
      · 只通过 **HTTP 接口**观察与操作（相当于点界面上的按钮）；
      · 处理一份**含人名与机构名**的合成文档，确认模型**真的参与了**。

    它**不**使用 `model install` 之类的命令来准备模型——
    模型必须由程序自己在首次启动时准备好，否则这条验收就没有意义。

    【为什么用 JAVA_TOOL_OPTIONS 隔离 user.home】
      加载器会依次找 `<程序目录>/models/ner` 与 `<user.home>/.litemask/models/ner`。
      开发机上只要装过模型，home 里就躺着模型 → 验收测的其实是「装了模型」，
      却拿「刚装好」的判据去判 → **必红**，而红的原因与包本身无关。
      → 隔离成空 home，并**自证**隔离生效（判据里看得到隔离目录名）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\acceptance-model.ps1 `
        -Exe "C:\Users\me\AppData\Local\LightMeter\LightMeter.exe" -Port 19911
#>
[CmdletBinding()]
param(
    # 装好的程序的启动器（默认取本机 per-user 安装位置）。
    [string]$Exe = (Join-Path $env:LOCALAPPDATA 'LightMeter\LightMeter.exe'),
    [int]$Port = 19911,
    # 验收用的临时工作目录。
    [string]$Work = (Join-Path $env:TEMP ('litemask-accept-' + [guid]::NewGuid().ToString('N')))
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$script:Pass = 0
$script:Fail = 0

function Say([string]$m) { Write-Host "    $m" }
function Ok([string]$m) { $script:Pass++; Write-Host "  ok  $m" -ForegroundColor Green }
function Ng([string]$m) { $script:Fail++; Write-Host "  ng  $m" -ForegroundColor Red }

# 把一个文件放进 zip 的某个条目名下。
function Add-ZipEntry($zip, [string]$src, [string]$name) {
    $entry = $zip.CreateEntry($name)
    $es = $entry.Open()
    try {
        $fs = [IO.File]::OpenRead($src)
        try { $fs.CopyTo($es) } finally { $fs.Dispose() }
    } finally { $es.Dispose() }
}

function Get-Token([int]$p) {
    # 令牌由服务端注入首页的 meta 标签 —— 这是它交给浏览器的唯一途径。
    $html = (Invoke-WebRequest -Uri "http://127.0.0.1:$p/" -TimeoutSec 5 -UseBasicParsing).Content
    if ($html -match 'name="litemask-token"\s+content="([0-9a-f]+)"') { return $Matches[1] }
    throw '首页里找不到会话令牌'
}

function Wait-Model([int]$p, [int]$seconds = 180) {    # 轮询到模型可用。**不**用固定 sleep：装模型要解压并加载 ONNX，
    # 慢的机器上固定 sleep 会把「还在装」误判成「装不上」。
    $last = ''
    for ($i = 0; $i -lt $seconds; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri "http://127.0.0.1:$p/api/model" -TimeoutSec 3 -UseBasicParsing
            $j = $r.Content | ConvertFrom-Json
            if ($j.state -and $j.state -ne $last) {
                $last = $j.state
                Say "模型状态：$($j.stateLabel)（$($j.state)）"
            }
            if ($j.state -eq 'READY' -and $j.usable) { return $j }
        } catch { }
    }
    return $null
}

if (-not (Test-Path -LiteralPath $Exe -PathType Leaf)) {
    Write-Host "找不到装好的程序：$Exe" -ForegroundColor Red
    exit 2
}

New-Item -ItemType Directory -Path $Work -Force | Out-Null
$emptyHome = Join-Path $Work 'empty-home'
New-Item -ItemType Directory -Path $emptyHome -Force | Out-Null

Write-Host ''
Write-Host '== 验收：普通用户全程不碰终端 =='
Say "程序：$Exe"
Say "隔离的 user.home：$emptyHome"

# ---------------------------------------------------------------- 起程序
$savedJTO = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$emptyHome"
$serveLog = Join-Path $Work 'serve.log'
$proc = Start-Process -FilePath $Exe `
        -ArgumentList @('serve', '--port', "$Port", '--workspace', (Join-Path $Work 'ws'), '--no-browser') `
        -RedirectStandardOutput $serveLog -RedirectStandardError (Join-Path $Work 'serve.err') `
        -PassThru -WindowStyle Hidden

try {
    # ---- 1. 服务起来了 ----
    $up = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try {
            $h = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2 -UseBasicParsing
            if ($h.Content -match '"ok"\s*:\s*true') { $up = $true; break }
        } catch { }
    }
    if ($up) { Ok '程序起来了，本地服务可用' }
    else { Ng '程序没起来'; Get-Content $serveLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }; exit 1 }

    # ---- 2. 首次启动自动把模型准备好 ----
    $model = Wait-Model $Port
    if ($null -eq $model) {
        Ng '首次启动没有把模型准备好（超时）'
        Get-Content $serveLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
        exit 1
    }
    Ok "首次启动自动把模型装好了（状态：$($model.stateLabel)）"
    if ($model.modelId) { Ok "模型版本：$($model.modelId)" }
    if ($model.personName -and $model.organizationName) {
        Ok '界面报出「支持人名识别」与「支持机构名识别」'
    } else {
        Ng '界面没有报出人名/机构名识别能力'
    }
    if ($model.state -ne 'READY' -or -not $model.usable) { Ng '模型状态不是「可用」' } else { Ok '状态是「可用」，且是真的可用（模型已加载）' }

    # ---- 3. 模型真的装到了用户数据目录 ----
    $installed = Join-Path $emptyHome '.litemask\models\ner\ner.onnx'
    if (Test-Path -LiteralPath $installed -PathType Leaf) {
        Ok "模型装到了用户数据目录（$installed）"
    } else {
        Ng "模型没装到用户数据目录：$installed"
    }

    # ---- 4. 处理含人名与机构名的合成文档，确认模型真的参与 ----
    $token = Get-Token $Port
    $doc = Join-Path $Work '合成文档.txt'
    # 里面既有规则能认的（身份证、手机号），也有**只有模型**能认的（人名、机构名）。
    Set-Content -LiteralPath $doc -Encoding utf8 -Value @'
本院受理的张伟明诉北京明诚科技有限公司买卖合同纠纷一案。
原告张伟明，身份证号11010519491231002X，手机13800138000。
被告北京明诚科技有限公司，法定代表人李静。
证人王海涛陈述：上述事实属实。
'@
    $bytes = [IO.File]::ReadAllBytes($doc)
    $headers = @{
        'X-Litemask-Token' = $token
        'X-Filename'       = [Uri]::EscapeDataString('合成文档.txt')
        'Content-Type'     = 'application/octet-stream'
    }
    $scan = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/scan" -Method Post -Headers $headers -Body $bytes -TimeoutSec 120
    $hits = @($scan.hits)
    # 命中是**归组**的：一行可能代表原文里挨着的几处命中。
    # → 判「有没有认出某个值」必须把 original 与 members 里的原值都算上。
    $texts = @()
    foreach ($h in $hits) {
        $texts += $h.original
        foreach ($mem in @($h.members)) { $texts += $mem.original }
    }
    $texts = @($texts | Select-Object -Unique)
    Say ("命中 " + $hits.Count + " 行 / " + $texts.Count + " 个值：" + ($texts -join '、'))
    $hasRule = $texts -contains '11010519491231002X'
    $hasPerson = ($texts -contains '张伟明') -or ($texts -contains '李静') -or ($texts -contains '王海涛')
    $hasOrg = $texts -contains '北京明诚科技有限公司'
    if ($hasRule) { Ok '规则类命中（身份证号）被脱敏' } else { Ng '规则类没有命中' }
    if ($hasPerson) { Ok '人名被识别并脱敏（模型确实参与了）' } else { Ng '人名没有被识别——模型没有真正参与' }
    if ($hasOrg) { Ok '机构名被识别并脱敏（模型确实参与了）' } else { Ng '机构名没有被识别——模型没有真正参与' }
    # 「NER 真的参与了」的权威标志：结果里报出了识别来源。
    $stats = $scan.stats
    $src = if ($stats -and $stats.PSObject.Properties['识别来源']) { $stats.'识别来源' } else { '' }
    if ($src) { Ok "结果里标明「识别来源：$src」（NER 参与了这次脱敏）" }
    else { Ng '结果没有标明识别来源——无法证明 NER 参与了' }
    # 反面判据：没启用 NER 时，结果里必须有那句「人名与机构名没有被脱敏」。
    $notEnabled = @($scan.notes) | Where-Object { $_ -match '没有被脱敏' }
    if ($notEnabled) { Ng '结果里仍说「人名与机构名没有被脱敏」，但模型明明可用' }
    else { Ok '结果里没有「人名与机构名没有被脱敏」那句（模型确实参与了）' }

    # ---- 5. 没装模型时必须持续提示、且要用户明确选择 ----
    # （这里用接口层验证：模型可用时那条提示不该出现）
    $warn = (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -TimeoutSec 5 -UseBasicParsing).Content
    if ($warn -match '当前未启用人名与机构名识别，结果需要人工处理') {
        Say '页面里带着「未启用」的常驻提示文案（模型可用时它被隐藏）'
    } else {
        Ng '页面里找不到「未启用」的常驻提示文案'
    }

    # ---- 6. 离线模型包：用户只选一个文件就能装 ----
    $bundle = Join-Path $Work '离线模型包.zip'
    $modelZip = Get-ChildItem -LiteralPath (Join-Path (Split-Path $Exe -Parent) 'app\models\ner-package') -Filter '*.zip' -File | Select-Object -First 1
    if ($modelZip) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $out = [IO.Compression.ZipFile]::Open($bundle, 'Create')
        try {
            # 【为什么要单独一个函数】PowerShell 的 @(@('a','b'), @('c','d')) 会把内层数组**摊平**，
            # 于是 foreach 拿到的是四个字符串而不是两组「来源/条目名」——
            # 表现出来就是 CreateEntry 收到一个空串（entryName 为空）。
            Add-ZipEntry $out $modelZip.FullName $modelZip.Name
            Add-ZipEntry $out ($modelZip.FullName + '.sig') ($modelZip.Name + '.sig')
        } finally { $out.Dispose() }
        $upHeaders = @{ 'X-Litemask-Token' = $token; 'X-Filename' = [Uri]::EscapeDataString('离线模型包.zip'); 'Content-Type' = 'application/octet-stream' }
        $bb = [IO.File]::ReadAllBytes($bundle)
        $r = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/model/import" -Method Post -Headers $upHeaders -Body $bb -TimeoutSec 300
        if ($r.state -eq 'READY' -and $r.usable) { Ok '离线模型包（用户只选一个文件）装得上' }
        else { Ng "离线模型包装不上：$($r.state) / $($r.failure)" }
    } else {
        Ng '装好的程序里找不到随附模型包'
    }

    # ---- 7. 坏包：装不上，而且不许把原来那份弄坏 ----
    # 【这里要断的是「装不上」而不是「模型不可用」】
    #   装一个坏包失败时，**原来那份能用的模型必须照旧能用**——
    #   那正是原子性的含义。失败的是「换装」这个动作，不是「当前这份模型」。
    $before = (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash
    $bad = Join-Path $Work '坏包.zip'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $out = [IO.Compression.ZipFile]::Open($bad, 'Create')
    try {
        $e = $out.CreateEntry('随便.json'); $es = $e.Open()
        try { $w = New-Object IO.StreamWriter($es, (New-Object Text.UTF8Encoding($false))); $w.Write('{}'); $w.Dispose() } finally { $es.Dispose() }
    } finally { $out.Dispose() }
    $badHeaders = @{ 'X-Litemask-Token' = $token; 'X-Filename' = [Uri]::EscapeDataString('坏包.zip'); 'Content-Type' = 'application/octet-stream' }
    $bbb = [IO.File]::ReadAllBytes($bad)
    $r2 = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/model/import" -Method Post -Headers $badHeaders -Body $bbb -TimeoutSec 120
    if ($r2.failure) { Ok "坏包装不上，并且说清了原因：$($r2.failure)" }
    else { Ng '坏包装上了（不应该）' }
    if ($r2.nextStep) { Ok "给出了用户能点的下一步：$($r2.nextStep)" } else { Ng '没有给出下一步' }
    if ($r2.usable) { Ok '装失败之后原来那份模型仍然可用（没有把用户降级）' }
    else { Ng '装失败之后原来那份模型被弄坏了' }
    $after = (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash
    if ($before -eq $after) { Ok '装失败之后原来那份模型**一点没变**（原子性）' } else { Ng '装失败之后原来那份模型被改了' }

    # ---- 8. 签名不对的包：一律不装（没有「跳过验证」这条路）----
    if ($modelZip) {
        $badSig2 = Join-Path $Work '签名不对的离线包.zip'
        $out = [IO.Compression.ZipFile]::Open($badSig2, 'Create')
        try {
            Add-ZipEntry $out $modelZip.FullName $modelZip.Name
            $sigBytes = [IO.File]::ReadAllBytes($modelZip.FullName + '.sig')
            # 翻转签名信封里的一个字节 → 签名与摘要都对不上
            $sigBytes[$sigBytes.Length - 1] = $sigBytes[$sigBytes.Length - 1] -bxor 0xFF
            $entry = $out.CreateEntry($modelZip.Name + '.sig')
            $es = $entry.Open()
            try { $es.Write($sigBytes, 0, $sigBytes.Length) } finally { $es.Dispose() }
        } finally { $out.Dispose() }
        $bsHeaders = @{ 'X-Litemask-Token' = $token; 'X-Filename' = [Uri]::EscapeDataString('签名不对的离线包.zip'); 'Content-Type' = 'application/octet-stream' }
        $bsb = [IO.File]::ReadAllBytes($badSig2)
        $r3 = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/model/import" -Method Post -Headers $bsHeaders -Body $bsb -TimeoutSec 120
        if ($r3.failure) { Ok "签名不对的包装不上：$($r3.failure)" }
        else { Ng '签名不对的包装上了（不应该）' }
        $after2 = (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash
        if ($before -eq $after2) { Ok '签名不对的包没有破坏原来那份模型' } else { Ng '签名不对的包把原来那份模型改了' }
    }
}
finally {
    $env:JAVA_TOOL_OPTIONS = $savedJTO
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}

Write-Host ''
Write-Host ("验收结果：通过 " + $script:Pass + "，失败 " + $script:Fail)
if ($script:Fail -ne 0) { exit 1 }
exit 0
