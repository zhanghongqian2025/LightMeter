<#
.SYNOPSIS
    M4-Windows：打裁剪运行时 + exe/msi 安装包，并在打包后**真的把它跑起来**验证。

.NOTES
    # ⚠ 这个脚本在本项目里**从未被执行过**
    ------------------------------------------------------------------
    原因不是工作量，是物理限制：`jlink` 不能交叉构建，它需要目标平台的
    `jmods`，而 macOS 的 JDK 不带 Windows 的那套。所以本文件是按
    `tools/package-macos.sh` 的结构翻译过来的**待验证脚本**，不是产物。

    在 Windows 机器上第一次跑它之前，本文件里的一切声明都只是意图。
    请把每一步的实际输出抄回来，再据此改这个脚本。

    为什么照样要写：
    - 翻译的过程会把 macOS 侧「哪些验证是必须的」显式写下来，
      到了 Windows 上要一条条兑现，而不是临时决定验什么；
    - 三步验证（没装模型 / 装了模型 / 起服务）不是可选项，
      M3-2 的 fail-closed 表达要求与 jlink 模块集的实测教训都在这里。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\package-windows.ps1
    powershell -ExecutionPolicy Bypass -File tools\package-windows.ps1 -SkipInstaller
#>
[CmdletBinding()]
param(
    # 模型包目录。留空 = 核心包不带模型（方案 B：AI 包独立分发）。
    [string]$ModelDir = '',
    # 【2026-10-25 新增】**随程序分发的已签名模型包**所在目录。
    #
    # 【它与 -ModelDir 有什么不同 —— 这是两件事，别混】
    #   -ModelDir      ：**解开**的模型目录（manifest.json + ner.onnx + vocab.txt）。
    #                     程序直接去那儿读，不验签。
    #   -ModelPackageDir：**没解开**的模型包（一个 .zip + 它的 .sig）。
    #                     程序在**首次启动时**自己验签、原子安装到用户数据目录。
    #
    # 【为什么产品要走后者】
    #   普通用户不该被要求「装完程序之后再去装模型」——那是把交付做一半。
    #   而「把解开的模型塞进安装目录」意味着：模型没经过签名验证就躺在那儿，
    #   谁改一下都能被加载。签名必须在**安装那一刻**被验，
    #   而不只是「发布时验过一次」。
    [string]$ModelPackageDir = '',
    [switch]$SkipInstaller,
    # 服务端口。默认 19433；可用 -Port 或环境变量 LITEMASK_E2E_PORT 覆盖
    # （CI 环境里端口常被别人占住，届时要**明确说**「换一个端口」，
    #   而不是让服务起不来、最后只报一句「起服务失败」）。
    [int]$Port = 0,
    # 【2026-10-25 新增】跳过测试。
    # 原来这里写死了 -DskipTests，等于「打包脚本从不跑测试」——
    # 而测试恰恰是这个项目唯一会红的检查（见 pom 里的 surefire 配置）。
    # → 默认**跑**测试；要跳过必须显式 -SkipTests，
    #   且跳过这件事会在输出里留痕（发布清单里要能看出这一包跳过了测试）。
    [switch]$SkipTests,
    # 【2026-10-25 新增】按用户安装（不需要管理员权限）。
    # 默认开启：发布目标是「普通用户双击 MSI 就能装」。
    #   jpackage 的 msi 默认是**每台机器安装**（per-machine）→ 需要管理员/UAC。
    #   --win-per-user-install 把它改成每个用户安装，装到
    #   %LOCALAPPDATA%\Programs\<AppName>，普通用户直接装、不弹 UAC。
    #   要回到 per-machine（部署到 Program Files 给全机用）传 -MachineInstall。
    [switch]$MachineInstall
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---- 控制台输出编码：必须显式定成 UTF-8 --------------------------------
#
# 【为什么】本产品的 stdout 统一是 UTF-8（见 tools\package-identity.properties 的 jvm.args）。
#   而 **PowerShell 捕获原生命令输出时，用的是 [Console]::OutputEncoding 解码**，
#   那个值默认取**控制台的 OEM 代码页**（中文 Windows = 936/GBK，其它 locale = 437/1252）。
#   → 不设的话，本脚本读 CLI 的日志时会拿到一堆乱码，
#     于是「日志里必须出现『没有脱敏』」这类判据会**恒假**（或恒真，取决于乱码成什么样），
#     红灯的原因还长得像「产品没明说」。
#   → 显式定成 UTF-8：解码与产品输出一致，判据才是真的在判产品。
#
#   （本机实测：中文 Windows 默认代码页下，这几行不加就会读出乱码。）
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 某些宿主环境不允许改控制台编码 —— 不影响构建本身，只是日志里可能是乱码。
    Write-Host "    (改不了控制台输出编码，CLI 日志可能是乱码：$($_.Exception.Message))"
}

# ZipFile 在 PowerShell 5.1 里**不自动加载**，不显式 Add-Type 会在第 1 步就抛
# 「Unable to find type [System.IO.Compression.ZipFile]」——而这是在 Windows 上第一次
# 跑这个脚本时会撞到的第一个错误，所以放在最前面明确加载。
Add-Type -AssemblyName System.IO.Compression.FileSystem

# =============================================================================
# 可测的纯逻辑（块 28 抽出）
# =============================================================================

# ---- 原生命令的执行包装 --------------------------------------------------
#
# 【2026-10-25 真 Windows 实测踩到的真缺陷 —— 这个脚本在 Windows 上第一次跑就死在这】
#   脚本顶部是 `$ErrorActionPreference = 'Stop'`。
#   而在 **PowerShell 5.1** 里，「原生命令往 stderr 写了东西」会被升级成
#   **终止性错误** —— 哪怕退出码是 0。
#   `java -version` 的版本号恰恰就写在 **stderr** 上：
#     → 第 82 行 `(& $JavaExe -version 2>&1 | Out-String)` 当场抛 NativeCommandError，
#       脚本在读版本号这第一步就死了，而报错长得像「java 本身有问题」。
#   同样的坑还有：mvn（警告走 stderr）、jlink/jpackage（进度与警告走 stderr）、
#   CheckPlatformNatives、SizeGateCli。
#
# 【为什么可以放心放行】这些命令的成败**本来就用退出码判**，
#   脚本里每一处都有 `$LASTEXITCODE -ne 0` 的检查。
#   → 这里只需要把 stderr **收下来**，而不是让它变成异常。
function Invoke-External([scriptblock]$Action) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Action
    } finally {
        $ErrorActionPreference = $prev
    }
}
#
# 【为什么抽出来】
#   这个脚本在本项目里**从未被执行过**（见文件头 .NOTES）→ 本机永远发现不了它写错。
#   原来这些逻辑是**内联在流程里**的：即使在 macOS 上装了真的 PowerShell 解释器，
#   也只能「看着像对」，没法**真的调用**。
#   → 把它们抽成**纯函数**（只用路径拼接、字符串、Test-Path，不碰任何 Windows 专有东西），
#     流程里改成调用 → 于是可以在 macOS 上用 pwsh **真的跑一遍**，
#     断言的是**行为**，不是「字符串长这样」。
#   → 判据落在**报这一事实的那一行**：函数体就是事实的所在地。
#
# 【哪些能抽、哪些不能 —— 说清楚，别让人以为整个脚本都验过了】
#   能抽（跨平台纯逻辑）：端口解析、Java 版本闸门、JDK 工具定位、模型目录探测、父目录创建。
#   不能抽（Windows 专有）：jlink / jpackage 调用、MSI 生成、注册表、启动器、装完再验。
#   → 后者仍然只有静态判据 + 真机验收，本轮**没有**验证。
function Resolve-Port([int]$InPort) {
    if ($InPort -gt 0) { return $InPort }
    if ($env:LITEMASK_E2E_PORT) { return [int]$env:LITEMASK_E2E_PORT }
    return 19433
}

function Assert-JavaVersion([string]$JavaExe) {
    # 【2026-10-25】取版本号必须用 Invoke-External 兜着 ——
    #   `java -version` 把版本号写在 stderr 上，而脚本顶部是 ErrorActionPreference='Stop'
    #   → 在 PowerShell 5.1 里那会被升级成终止性错误，脚本第一步就死。
    $out = (Invoke-External { & $JavaExe -version 2>&1 | Out-String })
    # Java 8 的输出是 `version "1.8.0_xxx"` —— 直接取第一段数字会得到 **1**，
    # 报出来是「Java 是 1」，读的人完全看不懂是哪一代。
    # → 先特判 `1.` 前缀，再走现代的 `version "21…"` 形式。
    if ($out -match 'version "1\.(\d+)') {
        $major = [int]$Matches[1]
    } elseif ($out -match 'version "(\d+)') {
        $major = [int]$Matches[1]
    } else {
        throw "读不出 Java 版本（java -version 的输出：$out）"
    }
    # 【2026-10-25 修掉的真缺陷：闸门只查 ≥11，与 pom 的 release=21 对不上】
    #   本脚本用到的**不止**单文件源码模式（11+）：
    #     · mvn compile 的 maven.compiler.release=21 → 11/17 的 JDK 直接编译失败；
    #     · jlink / jpackage 必须与 java 来自**同一个 JDK**
    #       → 裁出来的运行时版本要和 jar 的字节码目标一致。
    #       用 11 的 jlink 裁运行时去装 21 字节码 = 装得上、启动就 UnsupportedClassVersionError，
    #       而那个错发生在**用户双击之后**，排查成本极高。
    #   → 闸门提到 21，与 pom.xml 对齐（pom 是 release 版本的唯一真源）。
    if ($major -lt 21) {
        throw "Java 是 $major，本项目需要 **21 或更高**：pom 的 maven.compiler.release=21，且 jlink/jpackage 必须与 java 出自同一个 JDK"
    }
    return $major
}

# jpackage 的 app-image 产物**不是** <dest>\<appName>.exe，
# 而是 <dest>\<appName>\<appName>.exe（中间多一层与 app 同名的目录）。
# 写死 <dest>\<appName>.exe 的话，第 4 步就跑的是一个不存在的路径 ——
# `& $exePath` 会报错，而报错信息（"无法将项识别为 cmdlet"）看不出真因。
# → 打完 app-image 后**真的去找一次**，找不到就 fail-closed。
function Resolve-AppImageExe([string]$AppDir, [string]$AppName) {
    $expected = Join-Path (Join-Path $AppDir $AppName) "$AppName.exe"
    if (Test-Path -LiteralPath $expected -PathType Leaf) { return $expected }
    # 兜底：递归搜一次。jpackage 布局将来若变，也不至于让这个脚本整体失效。
    $found = Get-ChildItem -LiteralPath $AppDir -Recurse -File -Filter "$AppName.exe" -ErrorAction SilentlyContinue |
             Select-Object -First 1
    if ($found) { return $found.FullName }
    throw "在 $AppDir 下找不到 $AppName.exe（jpackage app-image 的布局变了？）"
}

function Resolve-JdkTool([string]$Name, [string]$JdkBin) {
    $names = if ($env:OS -eq 'Windows_NT') { @("$Name.exe", $Name) } else { @($Name) }
    if ($JdkBin) {
        foreach ($n in $names) {
            $cand = Join-Path $JdkBin $n
            if (Test-Path $cand -PathType Leaf) { return $cand }
        }
    }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

# 模型包目录：**真的探测**（哪个存在用哪个），都不存在才用默认布局。
# 【块 28 修掉的真缺陷：原来根本没在探测】
#   原来写的是 `if (-not $dest) { $dest = $probe }` ——
#   第一次循环 $dest 是 null → 赋值；之后 $dest 非空 → 条件**永假** →
#   **永远取第一个候选**，后面的两个候选从来没被看过。
#   注释写着「先探测，别写死」，代码却是「无条件写死第一个」——
#   **注释与代码不符**，而模型目录名猜错 = 模型装不进包里。
#   → 现在真的探测：哪个存在用哪个；都不存在才用默认布局（由调用方建出来）。
function Resolve-ModelDir([string]$ExePath, [string]$JarName) {
    # 规则**来自产品本身**，不是猜的：
    #   加载器 NerModelRegistry.discover() 找模型的位置是
    #     ① <**jar 所在目录**>/models/ner   （programDirectory() = jar 的父目录）
    #     ② <user.home>/.litemask/models/ner
    #   → 脚本必须用**同一条规则**，否则模型装到的地方加载器根本不看。
    #
    # 【块 29 修掉的真缺陷】原来这里按 **exe 所在目录**去猜
    #   （候选 'models/ner' / 'app/models/ner' / '../models/ner'，默认取第一个），
    #   而 jpackage 的 app-image 把 jar 放在 **`app/` 子目录**里
    #   （本机 macOS 产物实证：Contents/app/ 下是 {*.cfg, desens-core.jar}，
    #     .cfg 里写着 app.classpath=$APPDIR/desens-core.jar）。
    #   → 装到 `<exe 同级>/models/ner` = **加载器不会看的地方**
    #   → Windows 上「装了模型」那一态必然判红，且红的原因与包本身无关。
    #
    # 【为什么用「搜」而不是写死 'app'】
    #   jpackage 的布局将来若变，写死的路径又会装错地方。
    #   → 先试两个已知布局（快），再兜底**递归搜 jar**（慢但一定对）。
    $appDir = Split-Path $ExePath -Parent
    $jar = $null
    foreach ($c in @((Join-Path (Join-Path $appDir 'app') $JarName), (Join-Path $appDir $JarName))) {
        if (Test-Path $c -PathType Leaf) { $jar = Get-Item $c; break }
    }
    if (-not $jar) {
        $found = Get-ChildItem -LiteralPath $appDir -Recurse -File -Filter $JarName -ErrorAction SilentlyContinue |
                 Select-Object -First 1
        if ($found) { $jar = $found }
    }
    if (-not $jar) {
        throw "在 $appDir 下找不到 $JarName —— 模型目录无从判断（jpackage 布局变了？）"
    }
    return (Join-Path $jar.DirectoryName 'models/ner')
}

# 目标父目录不存在时 Copy-Item 会直接抛异常（$ErrorActionPreference='Stop'），
# 打包在「装模型」这步崩掉，报错还看不出是缺目录。
#   → 先把父目录建出来。
function Ensure-ParentDir([string]$TargetPath) {
    $parent = Split-Path $TargetPath -Parent
    if ($parent -and -not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    return $parent
}

# 隔离 user.home 用的环境变量。
#
# 【为什么要隔离 —— 块 29 修掉的真缺陷②】
#   加载器的查找顺序是：<jar 目录>/models/ner → <user.home>/.litemask/models/ner。
#   构建机上只要装过模型（开发机 ~/.litemask/models/ner 里确实有 ner.onnx），
#   那么：
#     · 第 4 步「没装模型」形态 → 从 home 捡到模型 → 判「模型是从别处被捡到了」→ **必红**
#     · 第 6 步「装了模型」形态 → 即便模型装错了地方，也照样绿灯（**假绿**）
#   → 所以这一步必须隔离 user.home。
#
# 【为什么用 JAVA_TOOL_OPTIONS，而不是改 HOME / USERPROFILE】
#   · JDK_JAVA_OPTIONS 对 jpackage 的原生 launcher **不生效**（macOS 侧已实测：
#     仍然会找到真实 home 里的模型）。Windows 的 launcher 同样是原生 exe。
#   · JAVA_TOOL_OPTIONS 由 **JVM 自己**读取 → 对原生 launcher 也生效
#     （macOS 侧实测：JVM 打印了「Picked up JAVA_TOOL_OPTIONS: -Duser.home=…」，
#      且加载器日志里出现了隔离路径下的候选路径）。
#   · 改 HOME / USERPROFILE 在 Windows 上也未必生效（JVM 取 user.home 走的是
#     系统 API，不是环境变量；macOS 侧同样实测过改 HOME 无效）。
#   → 跨平台一致的做法就是 JAVA_TOOL_OPTIONS。
function Get-IsolatedHomeEnv([string]$EmptyHome) {
    return "-Duser.home=$EmptyHome"
}

# 判断「隔离是否真的生效」。
#
# 【为什么需要它 —— 判据必须自证】
#   「模型没被发现」有两种可能：
#     (a) 确实隔离到了空 home，那里没有模型；
#     (b) 没隔离，但这台机器上恰好也没装模型。
#   这两种情况在产物上**完全一样** → 不自证的话，
#   第 4 步就只是一句「这台机器碰巧干净」，而不是「隔离生效了」。
#   → 必须从**日志里**找出隔离路径的痕迹。
#
# 【日志里会出现什么 —— 本机 macOS 实测形态】
#   未启用时：加载器打印「依次找过：<programDir>/models/ner、<home>/.litemask/models/ner」
#             → 第二个候选里带隔离路径。
#   已启用时：加载器**不打印**候选路径（describe() 走已启用分支），
#             → 只能靠 JVM 自己打印的「Picked up JAVA_TOOL_OPTIONS: -Duser.home=…」。
#   → 两种形态都能靠「日志里出现了隔离目录名」自证。
function Test-IsolationEffective([string]$LogText, [string]$EmptyHomeMarker) {
    if (-not $LogText) { return $false }
    return $LogText.Contains($EmptyHomeMarker)
}

# ---- 端口：默认 19433，可被 -Port / LITEMASK_E2E_PORT 覆盖 ----
$Port = Resolve-Port $Port

# ---- java 可执行文件与版本（块 28 补的一道前置）----
#
# 【为什么要查】本脚本用到了 **Java 单文件源码模式**（`java Xxx.java` 直接跑
#   tools\CheckPlatformNatives.java），那是 **Java 11+** 才有的特性。
#   构建机上装的是 Java 8 的话，这一步会报一句
#   「Could not find or load main class」——看不出是版本不对。
#   → 提前查、提前说清。
# 【为什么优先用 JAVA_HOME】mvn 与 `java` 可能不是同一个 java：
#   mvn 用 JAVA_HOME 优先，命令行 `java` 用 PATH。
#   两者版本不一致时，最容易出的就是「mvn 能编译、但单文件源码模式跑不了」。
$javaExe = 'java'
if ($env:JAVA_HOME) {
    $jh = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $jh -PathType Leaf)) { throw "JAVA_HOME 指向的 java 不存在：$jh" }
    $javaExe = $jh
}
# 解析成**真实路径**：后面要用它所在目录去找**同一个 JDK** 里的 jpackage / jlink。
# （只留 'java' 这个名字，Split-Path 得到的是 '.'，拿不到 JDK 的 bin 目录。）
$javaCmd = Get-Command $javaExe -ErrorAction SilentlyContinue
if (-not $javaCmd) { throw "找不到 java（当前取 $javaExe）—— 构建机上需要装 JDK 21 或更高" }
$javaExe = $javaCmd.Source
$jdkBin = Split-Path -Parent $javaExe
$javaMajor = Assert-JavaVersion $javaExe
Write-Host "    java：$javaExe（$javaMajor）"

# ---- 工具存在性预检（块 28 补的一道前置）----
#
# 【为什么要】这个脚本在本项目里**从未被执行过**（见文件头 .NOTES）。
#   第一次在 Windows 上跑，最容易撞到的不是逻辑错，而是「工具没装 / 装的是老版本」：
#     - 没有 mvn      → 「mvn 不是可识别的命令」，看不出该装什么、装哪个版本
#     - 没有 jpackage → 同上（jpackage 随 JDK 11+ 提供，JDK 8 没有）
#     - jpackage / jlink 不在 PATH 上（常见：JDK 装了但 bin 没加进 PATH）→
#       脚本要跑到第 2 步才炸，报错位置离真因很远
#   → 一次性查清、一次性说清，把「环境没准备好」与「脚本有 bug」分开。
#
# 【为什么解析成完整路径，而不是继续用裸命令名】
#   裸 `jpackage` 走 PATH 解析，可能拿到**另一个 JDK** 的 jpackage
#   （构建机上装多个 JDK 时很常见）→ 裁出来的运行时版本和 java 对不上。
#   → 优先用**当前 java 所在 JDK 的 bin 目录**里那个；找不到再退到 PATH。
#   → 判据落在**消费方**（真正调用 jpackage / jlink 的那几行），不是声明处。
$jpackageExe = Resolve-JdkTool 'jpackage' $jdkBin
if (-not $jpackageExe) { throw "找不到 jpackage —— 它随 JDK 21 提供；当前 java 所在目录：$jdkBin" }
$jlinkExe = Resolve-JdkTool 'jlink' $jdkBin
if (-not $jlinkExe) { throw "找不到 jlink —— 它随 JDK 21 提供；当前 java 所在目录：$jdkBin" }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "找不到 mvn —— 需要 Maven 3.6+ 且在 PATH 上（https://maven.apache.org/download.cgi）"
}
Write-Host "    jpackage：$jpackageExe"
Write-Host "    jlink：$jlinkExe"

# ---- WiX 预检（2026-10-25 补：打 msi / exe 安装器要它）----
#
# 【为什么必须有这道】
#   jpackage 在 Windows 上打 msi / exe 安装器，**要调用 WiX Toolset v3** 的
#   candle.exe（编 .wxs）与 light.exe（链 .msi）。
#   JDK 21 的 jpackage **不自带** WiX：
#   本机实测 jdk.jpackage.jmod 里只有 `resources/wixhelper.dll`，没有 candle/light。
#   缺 WiX 时 jpackage 的报错是一段英文内部异常，看不出「该装什么、装哪个版本」。
#   → 提前查、提前说清；找到了就把它的 bin 加进 PATH（jpackage 靠 PATH 找这两个 exe）。
#
# 【只认 WiX v3】
#   WiX v4/v5 砍掉了 candle/light（改成 wix CLI + 新的 .wxs 语义），
#   jpackage 21 认不了 —— 装 v4/v5 会得到「找不到 candle」的怪错。
#   → 只在 PATH 与常见的 WiX v3 安装目录下找。
$wixBin = $null
$wixCandidates = @()
if ($env:WIX) {
    $wixCandidates += (Join-Path $env:WIX 'bin')
    # 【2026-10-25 补】WIX 也可能**直接指到 bin 目录**（便携版解包后就是这样：
    #   candle.exe / light.exe 就在那层，没有再套一层 bin）。
    #   原来只认「WIX/bin」这一种，于是便携版明明在眼前，脚本却说「没找到 WiX」，
    #   直到 jpackage 报出一段英文内部异常才暴露——真因与症状离得很远。
    $wixCandidates += $env:WIX
}
$wixCandidates += @(
    'C:\Program Files (x86)\WiX Toolset v3.11\bin',
    'C:\Program Files (x86)\WiX Toolset v3.14\bin',
    'C:\Program Files\WiX Toolset v3.11\bin',
    'C:\Program Files\WiX Toolset v3.14\bin'
)
foreach ($cand in $wixCandidates) {
    if (Test-Path -LiteralPath (Join-Path $cand 'candle.exe') -PathType Leaf) { $wixBin = $cand; break }
}
if (-not $wixBin) {
    $c = Get-Command 'candle.exe' -ErrorAction SilentlyContinue
    if (-not $c) { $c = Get-Command 'candle' -ErrorAction SilentlyContinue }
    if ($c) { $wixBin = Split-Path -Parent $c.Source }
}
if (-not $wixBin) {
    # 没有 WiX 时**不直接失败**：-SkipInstaller 只打 app-image，不需要 WiX。
    # 只有真的要打安装器时才需要它 → 把「要不要」的决定权交给调用方。
    Write-Host '    -- 没找到 WiX Toolset v3（打 msi/exe 安装器需要它）：先不拦，若要打安装器会在这里失败'
} else {
    if ($env:PATH -notlike "*$wixBin*") { $env:PATH = "$wixBin;$env:PATH" }
    Write-Host "    WiX：$wixBin"
}

# ---- 打包身份：唯一真源是 tools\package-identity.properties ----
#
# 【为什么不再在本文件里写死这些值】
#   这里原来有一份，tools\package-macos.sh 里还有一份 → **同一事实两处定义**，
#   改一处忘一处**不会红**，只会让两个平台打出版本号不一样的产物。
#   → 抽到跨端真源，两个脚本各读一次（先例：tools\size-budget.properties）。
#   模块集「为什么是这五个」的实测理由也搬到了真源文件旁边 ——
#   理由写在两个脚本里各一份，同样会漂移。
#
# 【编码】
#   真源文件是 UTF-8 **无 BOM**。PowerShell 5.1 读无 BOM 的文件会按 ANSI 猜，
#   所以这里**显式**给 -Encoding UTF8。
#   （块 25 那条「.ps1 必须带 UTF-8 BOM」的规矩针对的是 **PowerShell 要执行的脚本文件**
#   ——5.1 按 ANSI 读无 BOM 的 .ps1 → 中文全乱码；
#   **数据文件**是另一回事：读的时候显式给编码就稳。）
$identity = Join-Path $root 'tools\package-identity.properties'
if (-not (Test-Path $identity -PathType Leaf)) {
    throw "找不到打包身份真源：tools\package-identity.properties"
}
$identityMap = @{}
foreach ($line in (Get-Content -LiteralPath $identity -Encoding UTF8)) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $eq = $t.IndexOf('=')
    if ($eq -lt 1) { throw "打包身份真源里有一行不是 key=value：$t" }
    $identityMap[$t.Substring(0, $eq).Trim()] = $t.Substring($eq + 1).Trim()
}
# fail-closed：取不到就抛，不让它变成空字符串一路带着走
# （空的 AppVersion 会打出一个「没有版本号」的包，而且不报错）。
function IdProp([string]$key) {
    if (-not $identityMap.ContainsKey($key)) { throw "打包身份真源里没有 $key" }
    $v = $identityMap[$key]
    if ($v -eq '') { throw "打包身份真源里的 $key 是空的" }
    return $v
}
$AppName    = IdProp 'app.name'
$AppVersion = IdProp 'app.version'
$MainClass  = IdProp 'main.class'
$JarName    = IdProp 'jar.name'
$Modules    = IdProp 'jlink.modules'
$JvmArgs    = IdProp 'jvm.args'

$rtDir     = Join-Path $root 'target/runtime-image'
$appDir    = Join-Path $root 'target/app-image'
$instDir   = Join-Path $root 'target/installer'
# 【2026-10-25 修掉的真缺陷】原来这里写死 target\app-image\LightMeter.exe，
#   而 jpackage 的 app-image 实际产物是 **target\app-image\LightMeter\LightMeter.exe**
#   （中间多一层与 app 同名的目录）。
#   → 不写死：打完 app-image 后用 Resolve-AppImageExe **真的去找一次**。
$exePath   = $null

function Say($msg) { Write-Host "    $msg" }
function BytesOf($p) {
    if (Test-Path $p -PathType Leaf) { return (Get-Item $p).Length }
    $sum = 0
    Get-ChildItem -LiteralPath $p -Recurse -File -Force -ErrorAction SilentlyContinue |
        ForEach-Object { $sum += $_.Length }
    return $sum
}
function MiB($b) { return [math]::Round($b / 1MB, 2) }
function CleanDir($p) { if (Test-Path $p) { Remove-Item -LiteralPath $p -Recurse -Force } }

# ---- 把「已签名的模型包」放进 jpackage 的输入目录 ----------------------------
#
# 【为什么单独一个函数】
#   app-image（第 3 步）与安装包（第 7 步）**都要**带上这个模型包，
#   而两处各写一遍的话，改了一处就装错地方——
#   而症状是「程序装好了，却说没有模型」，与模型包本身无关。
#
# 【放在哪儿】
#   jpackage 的 --input 目录会被整个复制到 app 目录下，
#   所以放成 <input>/models/ner-package/ 之后，
#   程序里的位置就是 <jar 目录>/models/ner-package/
#   —— 与 ModelManager.BUNDLED_SUBDIR（com.litemask.ner.ModelManager）同一条规则。
#   **同一个事实只能有一处定义**：两边各写一个字符串，迟早会分叉。
#
# 【带什么】
#   模型包 zip **和它的 .sig**。两者必须一起走：
#   验签那一步是按「同名 + .sig」找签名文件的（ModelTrust.signatureFileFor），
#   只带 zip 不带 sig = 程序验不了 = 装不上。
#
# 【只带这两个文件】
#   刻意**不**整目录复制：会把开发残留（.bak、.DS_Store）打进发布件。
function Stage-ModelPackage([string]$InputDir, [string]$JarName) {
    if (-not $ModelPackageDir) { return $null }
    if (-not (Test-Path $ModelPackageDir -PathType Container)) {
        throw "模型包目录不存在：$ModelPackageDir"
    }
    # 模型包与它的签名必须成对：只有 zip 没有 sig → 程序验不了 → 装不上。
    $zips = @(Get-ChildItem -LiteralPath $ModelPackageDir -Filter '*.zip' -File |
              Where-Object { Test-Path -LiteralPath ($_.FullName + '.sig') -PathType Leaf })
    if ($zips.Count -eq 0) {
        throw "模型包目录里没有「zip + 同名 .sig」这一对：$ModelPackageDir`n" +
              "（签名的文件名必须是 <包名>.zip.sig；没有签名的话程序不会安装它）"
    }
    if ($zips.Count -gt 1) {
        throw "模型包目录里有 $($zips.Count) 个带签名的模型包，不知道该带哪个：$ModelPackageDir`n" +
              "（每个模型包一个目录；或者只留最新的那一个）"
    }
    $zip = $zips[0]
    $sig = Get-Item -LiteralPath ($zip.FullName + '.sig')

    $dest = Join-Path $InputDir 'models\ner-package'
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Copy-Item -LiteralPath $zip.FullName -Destination (Join-Path $dest $zip.Name) -Force
    Copy-Item -LiteralPath $sig.FullName  -Destination (Join-Path $dest $sig.Name)  -Force
    Say "+ 模型包 $($zip.Name)（含签名）→ models/ner-package/"
    return (Get-Item -LiteralPath (Join-Path $dest $zip.Name)).Length
}

# ---------------------------------------------------------------- 1. 打 fat jar
Write-Host '== 1/7 打 fat jar =='
Push-Location $root
# ---- 【为什么是「离线优先 + 失败才预热」—— 2026-10-22 块 28 修掉的真缺陷】----
#   原来这里只有一句 `mvn -B -o ...`（**离线**）。离线模式在「本地仓库已预热」
#   的机器上没问题（开发机就是这样），但 **Windows 构建机是干净机器**，
#   本地仓库是空的 → 离线模式**必然失败**，而且失败信息长这样：
#     "Cannot access central (https://repo.maven.apache.org/maven2) in offline mode
#      and the artifact org.apache.maven.plugins:maven-resources-plugin:jar:3.3.1
#      has not been downloaded from it before"
#   —— 报错完全看不出「你把 -o 写死了」这个真因，人只会以为是网络问题。
#   （本轮在本机用空仓库实测过：离线 + 空仓库 = PluginResolutionException，退出码 1。）
#
#   → 改成**回退链**：先离线构建（本地仓库已预热时**一次就成**，不触网、可复现）；
#     离线失败（= 本地仓库不完整）才联网预热一次，然后**仍然用离线**构建出包。
#   → 无论走哪条路，**出包那一次构建都是离线的**。
#
#   【为什么不是「无条件两段」】—— 本机实测（work/maven-fallback-probe.sh，三步全跑）：
#     第 1 步 空仓库 + 离线                → RC=1，用时 1s；仓库内 .lastUpdated = 0 个
#           （关键：离线失败**不会**在仓库里留下失败标记，
#             所以随后的联网预热不会被 maven 以
#             "resolution will not be reattempted" 拒绝重试）
#     第 2 步 联网预热（同一份 settings）  → RC=0，用时 27s
#     第 3 步 预热后的仓库 + 离线（同一份 settings）→ RC=0，用时 2s
#   → 仓库已预热时：**只构建一次**（无条件两段式会构建两次，白多花一次时间）
#   → 干净机器时：多花 1 秒，换来一句人话错误 + 自动预热
#
#   ⚠ 两段必须用**同一份** settings：maven 会在 _remote.repositories 里记下
#     「这个构件是从哪个仓库 id 下载下来的」，离线段若换了 settings
#     （仓库 id 集合不同），会以「仓库不完整」的**误导性**错误失败
#     （实测：预热带 -s、离线不带 -s → RC=1，且报错与真因无关）。
#     → 两段都拼同一份 $mvnArgs，不要各写各的。
#   → 国内网络慢可以给 MAVEN_SETTINGS 指向带镜像的 settings.xml
#     （本机实测：central 17 KB/s，aliyun 镜像 346 KB/s，差 20 倍）。
#
# 【2026-10-25 修掉的真缺陷：测试被写死跳过】
#   原来这里固定带 `-DskipTests`，于是**这个脚本从来没有跑过测试**，
#   而测试恰恰是本项目唯一会红的检查（pom 里的 surefire 配置 + 1466 条断言）。
#   「打包通过」与「测试通过」从此是两件互不相干的事。
#   → 默认**跑**测试；显式 -SkipTests 才跳过，且跳过这件事会打印出来留痕。
$mvnArgs = @('-B', '-Dstyle.color=never')
if ($SkipTests) {
    $mvnArgs += '-DskipTests'
    Write-Host '    !! -SkipTests：这一包**没有**跑测试，发布清单里必须如实标注'
} else {
    Write-Host '    本次构建会跑测试（未加 -SkipTests）'
}
if ($env:MAVEN_SETTINGS) { $mvnArgs += @('-s', $env:MAVEN_SETTINGS) }
Write-Host '    构建 fat jar（离线优先）'
Invoke-External { & mvn @mvnArgs -o package }
if ($LASTEXITCODE -ne 0) {
    Write-Host "    离线构建失败（退出码 $LASTEXITCODE）—— 本地 Maven 仓库不完整，联网预热一次"
    Invoke-External { & mvn @mvnArgs package }
    if ($LASTEXITCODE -ne 0) { throw "mvn package（联网预热）失败（退出码 $LASTEXITCODE）——检查网络，或用 MAVEN_SETTINGS 指向带镜像的 settings.xml" }
    Write-Host '    预热完成，重新用离线构建出包（保证出包过程不触网）'
    Invoke-External { & mvn @mvnArgs -o package }
    if ($LASTEXITCODE -ne 0) { throw "mvn package（预热后的离线构建）失败（退出码 $LASTEXITCODE）——本地仓库仍不完整" }
}
Pop-Location
$jar = Join-Path $root "target/$JarName"

# ---- 剔掉另一个平台的 ONNX Runtime 原生库 ----# com.microsoft.onnxruntime 的 Maven 包**同时带 win-x64 与 osx-aarch64 两套** .dll/.dylib
# （在 macOS 上实测：win-x64 11.65 MB、osx-aarch64 25.56 MB 未压缩）。
# 发到 Windows 就不该带 macOS 那套——纯浪费，而 90 MiB 是本项目唯一的硬指标。
#   实测（macOS 侧量的是同一个 jar 的两种剪法）：
#     排除 win-x64   → macOS 包省 4.26 MiB（jar 16.97 → 12.71 MiB）
#     排除 osx-aarch64→ Windows 包省 6.57 MiB（jar 16.97 → 10.39 MiB），并已验证推理结果不变。
# Windows 侧省得更多，所以这一步对 Windows 的预算尤其重要。
#
# ⚠ 用 .NET 的 ZipArchive 做，不用外部 zip 命令——Windows 上不一定有。
$before = (Get-Item $jar).Length
$zip = [System.IO.Compression.ZipFile]::Open($jar, 'Update')
try {
    $drop = @($zip.Entries | Where-Object { $_.FullName -like 'ai/onnxruntime/native/osx-*' })
    foreach ($e in $drop) { $e.Delete() }
} finally {
    $zip.Dispose()
}
$jarBytes = (Get-Item $jar).Length
if ($jarBytes -lt $before) {
    Say ("已剔除 osx-* 原生库：省 " + ($before - $jarBytes) + " B = " + (MiB ($before - $jarBytes)) + " MiB")
}
Say "$JarName = $jarBytes B = $(MiB $jarBytes) MiB"

# ---- 平台原生库完整性预检（fail-closed）----
# 与 tools/package-macos.sh 的同一步，读**同一张**策略表（唯一真源）。
# 它挡的是「静默少东西」：上面那段「剔掉 osx-*」只保证「别的平台那套没了」，
# **不保证「自己那套 win-x64 还在」**。上游依赖树一变（ONNX Runtime 换版、
# 或不再带 win-x64），这个包会静默地少掉原生库 —— 到用户机器上跑模型时才炸。
#
# 校验器是**纯 JDK 的单文件程序**（tools\CheckPlatformNatives.java）：
# Windows 构建机上不一定有 python，所以不能用 python 写。
Write-Host '== 1b/7 平台原生库完整性预检 =='
Invoke-External { & $javaExe (Join-Path $root 'tools\CheckPlatformNatives.java') `
    --policy (Join-Path $root 'tools\platform-native-policy.properties') `
    --platform windows-x64 $jar }
if ($LASTEXITCODE -ne 0) {
    throw '平台原生库预检失败：这个包缺 win-x64 原生库（或带了不该有的）——不要发'
}

# ------------------------------------------------------- 2. jlink 裁剪运行时
Write-Host "== 2/7 jlink 定制运行时（$Modules）=="
CleanDir $rtDir
Invoke-External { & $jlinkExe --add-modules $Modules `
        --strip-debug --no-header-files --no-man-pages --compress=2 `
        --output $rtDir }
if ($LASTEXITCODE -ne 0) { throw "jlink 失败（退出码 $LASTEXITCODE）" }
$rtBytes = BytesOf $rtDir
Say "运行时 = $rtBytes B = $(MiB $rtBytes) MiB"
Say ("实际模块：" + (((Invoke-External { & "$rtDir\bin\java.exe" --list-modules }) -replace '@.*', '') -join ' '))

# ---- 2b. 裁剪运行时**能力**自检（2026-10-25 加）----
#
# 【为什么要这一步 —— 这是真 Windows 上抓到的真缺陷】
#   jlink 把运行时裁到只剩几个模块。裁掉的东西里有一个是**产品要用的**：
#   `KeyFactory.getInstance("Ed25519")` 的实现（sun.security.ec.ed.EdDSAKeyFactory）
#   在 **jdk.crypto.ec** 里，**不在 java.base**。
#   → 少了它，**装好的产品里模型包一件都装不上**
#     （model install 报「Ed25519 KeyFactory not available」），
#     而核心包照常工作（规则类脱敏完全正常）——
#     这种「装得上、跑得起、就是装不了模型」的缺陷，单测永远抓不到
#     （单测跑在完整 JDK 上，那里有 63 个模块）。
#   → 所以：用**裁剪出来的那份 java** 跑一遍能力自检，缺一项就停在这里。
#     判据在产品里（com.litemask.pack.RuntimeCapabilityProbe），
#     跑的就是要发出去的那个 jar —— 不是临时脚本。
Write-Host '== 2b/7 裁剪运行时能力自检（用裁剪后的 java 跑）=='
Invoke-External { & "$rtDir\bin\java.exe" -cp $jar 'com.litemask.pack.RuntimeCapabilityProbe' }
if ($LASTEXITCODE -ne 0) {
    throw '裁剪运行时缺少产品要用的能力（例如 Ed25519 验签）—— 这个包不能发'
}

# ------------------------------------------------------- 3. jpackage app-image
# Windows 没有 macOS 那种 app-image；--type exe 产出的是「解压即用」的目录形态，
# 里面带一个 .exe 启动器 + 运行时，最后再用 --type msi / --type exe 打安装包。
Write-Host '== 3/7 jpackage 打 exe 形态 =='
$stage = Join-Path $env:TEMP ("litemask-stage-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item $jar $stage
# 随程序分发的已签名模型包（如果有）：放进 --input 目录，
# jpackage 会把整个输入目录复制到 app 目录 → 程序里的位置是
# <jar 目录>/models/ner-package/，与 ModelManager.BUNDLED_SUBDIR 同一条规则。
$modelPackageBytes = Stage-ModelPackage $stage $JarName
CleanDir $appDir
Invoke-External { & $jpackageExe --type app-image `
    --name $AppName --app-version $AppVersion `
    --input $stage --main-jar $JarName --main-class $MainClass `
    --runtime-image $rtDir --dest $appDir `
    --java-options $JvmArgs --win-console }
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image 失败（退出码 $LASTEXITCODE）" }
Remove-Item -LiteralPath $stage -Recurse -Force
# 【2026-10-25 新增】打完真的去找一次 exe —— 路径不再写死，布局变了也不至于整体失效。
$exePath = Resolve-AppImageExe $appDir $AppName
Say $exePath

# ---- 随附模型包**真的进包了吗**（fail-closed）------------------------------
#
# 【为什么必须查】
#   jpackage 把 --input 目录整体复制到 app 目录这件事，是**布局约定**，
#   不是被文档钉死的行为。而这一条一旦不成立，后果是：
#   「程序装好了，但首次启动说没有模型」——用户看到的是一个**功能缺失**的产品，
#   而真因是打包时文件没进去。
#   → 打包当场查一次，查不到就**停**。发一个自己都验过「模型不在包里」的包是最蠢的事。
if ($ModelPackageDir) {
    $appSub = Join-Path (Split-Path $exePath -Parent) 'app'
    $jarDir = $null
    $foundJar = Get-ChildItem -LiteralPath $appSub -Filter $JarName -Recurse -File -ErrorAction SilentlyContinue |
                Select-Object -First 1
    if ($foundJar) { $jarDir = $foundJar.DirectoryName }
    $bundledDir = if ($jarDir) { Join-Path $jarDir 'models\ner-package' } else { $null }
    if (-not $bundledDir -or -not (Test-Path -LiteralPath $bundledDir -PathType Container)) {
        throw "随附模型包没进 app-image（找 $bundledDir 失败）——这个包**不发**（用户装完会发现没有模型）"
    }
    $bundledZips = @(Get-ChildItem -LiteralPath $bundledDir -Filter '*.zip' -File)
    if ($bundledZips.Count -ne 1 -or
        -not (Test-Path -LiteralPath ($bundledZips[0].FullName + '.sig') -PathType Leaf)) {
        throw "随附模型包不完整（$bundledDir）：必须有且只有一个 .zip，且它的 .sig 也在"
    }
    Say ("ok  随附模型包已进包：" + $bundledZips[0].Name + "（" + (MiB $bundledZips[0].Length) + " MiB）")
}

$fail = 0

# ------------------------------------ 4. 验「没装模型」这一态（方案 B 的核心形态）
Write-Host '== 4/7 先验「没装模型」这一态 =='
# 为什么要先验：方案 B 下核心包不带模型，而「不带模型时必须明说人名没被脱敏」
# 是 M3-2 的硬要求。只在装了模型的形态上验，等于把这一态留给用户去发现。
$ws = Join-Path $env:TEMP ("litemask-ws-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $ws | Out-Null
# 【块 29 修掉的真缺陷②：这一步必须隔离 user.home】
#   加载器会依次找 <jar 目录>/models/ner 和 <user.home>/.litemask/models/ner。
#   构建机上只要装过模型（**开发机就有**：~/.litemask/models/ner 里躺着 ner.onnx），
#   这一步测的其实是「装了模型」，却拿「没装模型」的判据去判 → **必红**，
#   而红的原因与包本身无关。
#   → 隔离成空 home，并**自证隔离生效**（日志里必须出现隔离目录名）。
#   （macOS 侧 tools/package-macos.sh 第 4 步是同一套做法，本机实测过。）
# 隔离目录放在工作区里，名字固定为 empty-home —— 判据靠**目录名**出现在日志里。
$emptyHome = Join-Path $ws 'empty-home'
New-Item -ItemType Directory -Path $emptyHome -Force | Out-Null
# 先记住原来的环境变量，跑完必须还原（否则后面第 6 步的 jpackage 会带着空 home 跑）。
$savedJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = Get-IsolatedHomeEnv $emptyHome
$src = Join-Path $ws 't.txt'
Set-Content -LiteralPath $src -Value '张三丰的身份证是11010519491231002X，手机13800138000。' -Encoding utf8
$out = Join-Path $ws 't.redacted.txt'
$log = Join-Path $ws 'nomodel.log'

Invoke-External { & $exePath redact $src *> $log }
$rc4 = $LASTEXITCODE
$log4 = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
if ($null -eq $log4) { $log4 = '' }
# 立刻还原环境变量（第 6 步要重新设）
$env:JAVA_TOOL_OPTIONS = $savedJavaToolOptions
if ($rc4 -ne 0) {
    Write-Host '    ng  无模型形态 CLI 失败'; Get-Content $log | ForEach-Object { Write-Host "      $_" }; $fail = 1
} elseif (-not (Test-Path $out)) {
    Write-Host "    ng  产物名不对：期望 $out，实际：$((Get-ChildItem $ws -File).Name -join ' ')"
    $fail = 1
} elseif (-not (Select-String -Quiet -LiteralPath $out -Pattern '<身份证1>')) {
    Write-Host '    ng  无模型形态没产出 <身份证1>'; $fail = 1
} elseif (-not (Select-String -Quiet -LiteralPath $log -Pattern '没有脱敏|未启用')) {
    Write-Host '    ng  没装模型却没有声明「人名与机构名没有被脱敏」——用户会以为脱干净了'
    Get-Content $log | ForEach-Object { Write-Host "      $_" }; $fail = 1
} elseif (Select-String -Quiet -LiteralPath $out -Pattern '<人名1>') {
    Write-Host '    ng  没装模型却产出了 <人名1>——模型是从别处被捡到了'; $fail = 1
} elseif (-not (Test-IsolationEffective $log4 'empty-home')) {
    # 【自证】证明这次「没找到模型」不是因为恰好这台机器没装模型，
    # 而是真的隔离到了空 home。否则这一步和「开发机碰巧干净」无法区分。
    Write-Host '    ng  隔离没生效：日志里没有出现 empty-home 路径，无法证明测的是隔离后的形态'
    Get-Content $log | ForEach-Object { Write-Host "      $_" }; $fail = 1
} else {
    Write-Host '    ok  无模型时照常脱敏，且明说人名与机构名没有被脱敏（user.home 已隔离到空目录）'
}

# ------------------------------------------------- 5. 装模型包（验签 + 原子安装）
Write-Host '== 5/7 装模型包 =='
#
# 【两种形态，分开验】
#   -ModelPackageDir：**随程序分发**的已签名模型包。
#     这里用产品自己的 `model install` 把它装进一个**隔离的 user.home**，
#     于是这一步同时验了三件事：
#       ① 签名是真的（验不过会直接失败并说明原因）；
#       ② 换装是原子的（装完生效目录里就是一份完整的模型）；
#       ③ 模型**真的能加载**（ModelPackageInstaller 的校验器会真的把 ONNX 加载一遍）。
#     装完的那份，正好给第 6 步当「装了模型」那一态用。
#   -ModelDir：旧形态——**解开**的模型目录直接放进程序目录（不验签）。
#     保留是为了不破坏既有的打包用法；新产品走的是上面那条。
$emptyHome2 = Join-Path $ws 'empty-home2'
New-Item -ItemType Directory -Path $emptyHome2 -Force | Out-Null
# 隔离的 user.home 从这里一直用到第 6 步的 redact 结束：
# 第 5 步把模型装进去，第 6 步再从那儿捡到它 → 这条判据才不是恒真。
$savedJavaToolOptions2 = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = Get-IsolatedHomeEnv $emptyHome2
if ($ModelPackageDir) {
    $pkgZip = @(Get-ChildItem -LiteralPath $ModelPackageDir -Filter '*.zip' -File |
                Where-Object { Test-Path -LiteralPath ($_.FullName + '.sig') -PathType Leaf })[0]
    $installLog = Join-Path $ws 'model-install.log'
    Invoke-External { & $exePath model install $pkgZip.FullName *> $installLog }
    $rcInstall = $LASTEXITCODE
    $installedModel = Join-Path $emptyHome2 '.litemask\models\ner'
    if ($rcInstall -ne 0) {
        Write-Host '    ng  随附模型包装不上（签名/校验/换装失败）'
        Get-Content $installLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
        $fail = 1
    } elseif (-not (Test-Path -LiteralPath (Join-Path $installedModel 'ner.onnx') -PathType Leaf)) {
        Write-Host "    ng  装完了但生效目录里没有模型文件：$installedModel"
        $fail = 1
    } else {
        Write-Host '    ok  随附模型包装得上：签名通过、原子换装完成、模型能加载'
    }
}
if ($ModelDir) {
    # 模型目录 = **jar 所在目录**/models/ner —— 与加载器同一条规则（见 Resolve-ModelDir）。
    #
    # 【块 28 修掉的真缺陷：原来这里根本没在探测】
    #   原来写的是 `if (-not $dest) { $dest = $probe }` ——
    #   第一次循环 $dest 是 null → 赋值；之后 $dest 非空 → 条件**永假** →
    #   **永远取第一个候选**，后面的两个候选从来没被看过。
    #   注释写着「先探测，别写死」，代码却是「无条件写死第一个」——
    #   **注释与代码不符**，而模型目录名猜错 = 模型装不进包里。
    # 【块 29 修掉的真缺陷：候选本身选错了】
    #   就算真探测，候选也是按 **exe 目录**列的，而加载器看的是 **jar 目录** →
    #   装到的地方加载器根本不看 → 「装了模型」那一态必红。
    #   → 现在直接按加载器的规则算：jar 所在目录/models/ner。
    $dest = Resolve-ModelDir $exePath $JarName
    if (-not (Test-Path $dest -PathType Container)) {
        Say "（模型目录还不存在，建出来：$dest）"
    }
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    $mf = Get-Content (Join-Path $ModelDir 'manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
    # 清单里的 files 段：model 与 vocab 各带 path 与 sha256（见 NerModelPackage）
    $names = @('manifest.json') + ($mf.files.PSObject.Properties | ForEach-Object { $_.Value.path })
    foreach ($n in $names) {
        $target = Join-Path $dest $n
        # 【块 28 补的一处】path 里**可能带子目录**（如 "models/ner.onnx"）。
        #   目标父目录不存在时 Copy-Item 会直接抛异常（$ErrorActionPreference='Stop'）
        #   → 打包在「装模型」这步崩掉，报错还看不出是缺目录。
        #   → 先把父目录建出来（Ensure-ParentDir，块 28 抽成可测纯函数）。
        Ensure-ParentDir $target | Out-Null
        Copy-Item (Join-Path $ModelDir $n) $target
        Say "+ $n"
    }
    # 刻意不整目录复制：会把开发残留（.bak 之类）打进发布件。
    # 加载器本身是**忽略**未声明文件的，这是有意为之——Windows 资源管理器
    # 也会在用户浏览过目录后塞 desktop.ini 进来，拒绝未声明文件会让
    # 模型包在用户的一次正常点击后失效。
} elseif (-not $ModelPackageDir) {
    Say '（未指定 -ModelDir / -ModelPackageDir，核心包不带模型——符合方案 B）'
}

# ---------------------------------------------------- 6. 验「装了模型」这一态 + 起服务
Write-Host '== 6/7 验「装了模型」这一态：真的把它跑起来 =='
#
# 【块 29 修掉的真缺陷③：这一步也必须隔离 user.home】
#   「模型包被自动发现」这条判据，只有在**用户 home 里没有模型**时才是真的。
#   加载器的查找顺序是：<jar 目录>/models/ner → <user.home>/.litemask/models/ner。
#   构建机上只要装过模型（**开发机就有**：~/.litemask/models/ner 里躺着 ner.onnx），
#   那么即使**第 5 步把模型装错了地方**（装到加载器不看的位置），
#   这一步仍会从 home 捡到模型 → 照样产出 <人名1> → **照样绿灯**。
#   → 那这条判据就成了**恒真**：它证明不了「模型被自动发现了」，
#     只证明「这台机器上某个地方有个模型」。
#   → 隔离成空 home 之后，唯一能命中的是 <jar 目录>/models/ner，
#     而那正是第 5 步装的位置 —— 这条判据才真的在验它该验的东西。
#
#   隔离自证：「已启用」形态下加载器**不打印**候选路径（describe() 走已启用分支），
#   所以不能靠「依次找过」自证，只能靠 JVM 自己打印的
#   「Picked up JAVA_TOOL_OPTIONS: -Duser.home=…」这一行 → 日志里会出现隔离目录名。
#
#   （macOS 侧 tools/package-macos.sh 第 7 步是同一套做法，本机实测过。）
# 【empty-home2 已在第 5 步建好】
#   -ModelPackageDir 那一态下，第 5 步已经把模型**装进**了 empty-home2，
#   所以这里跑 redact 时它能捡到那个模型 → 判据成立。
$src2 = Join-Path $ws 't2.txt'
Set-Content -LiteralPath $src2 -Value '张三丰的身份证是11010519491231002X，手机13800138000。' -Encoding utf8
$out2 = Join-Path $ws 't2.redacted.txt'
$log2 = Join-Path $ws 'withmodel.log'
Invoke-External { & $exePath redact $src2 *> $log2 }
$rc6 = $LASTEXITCODE
$log2Text = Get-Content -LiteralPath $log2 -Raw -ErrorAction SilentlyContinue
if ($null -eq $log2Text) { $log2Text = '' }
# 立刻还原（后面第 6b 步的体积闸门要跑 java，不能带着空 home）
$env:JAVA_TOOL_OPTIONS = $savedJavaToolOptions2
if ($rc6 -ne 0) {
    Write-Host '    ng  产物形态 CLI 失败'; Get-Content $log2 | ForEach-Object { Write-Host "      $_" }; $fail = 1
} elseif (-not (Test-Path $out2)) {
    Write-Host "    ng  产物名不对：期望 $out2"; $fail = 1
} elseif (-not (Select-String -Quiet -LiteralPath $out2 -Pattern '<身份证1>')) {
    Write-Host '    ng  没产出 <身份证1>'; $fail = 1
} elseif ($ModelDir -or $ModelPackageDir) {
    # ---------- 形态一：装了模型 ----------
    if (-not (Select-String -Quiet -LiteralPath $out2 -Pattern '<人名1>')) {
        Write-Host '    ng  没有 <人名1>——模型包没被自动发现'; Get-Content $log2 | ForEach-Object { Write-Host "      $_" }; $fail = 1
    } elseif (-not (Test-IsolationEffective $log2Text 'empty-home2')) {
        # 【自证】证明这次「模型被自动发现」是因为包内的 models/ner 生效，
        # 而不是因为这台机器上别处有个模型。没有这一行 = 隔离没生效 = 判据恒真。
        Write-Host '    ng  隔离没生效：日志里没有出现 empty-home2 路径，无法证明模型是从包内被发现的'
        Get-Content $log2 | ForEach-Object { Write-Host "      $_" }; $fail = 1
    } else {
        Write-Host '    ok  CLI 脱敏正常，且模型包被自动发现（<身份证1> 与 <人名1> 都在，user.home 已隔离）'
    }
} else {
    # ---------- 形态二：没装模型（方案 B：核心包不带模型）----------
    #
    # 【为什么必须分形态，不能一刀切 —— 与 macOS 侧同一条理由】
    #   原来这一刀切地要求 <人名1> 出现。在「不带模型」这一态下，
    #   包里确实没有模型 → 那条判据**必然失败**，
    #   而红的原因说的是「模型包没被自动发现」——指向的排查方向（模型包坏了）
    #   与真因（这次压根没装模型）完全无关。**红灯原因不正确**。
    if (Select-String -Quiet -LiteralPath $out2 -Pattern '<人名1>') {
        Write-Host '    ng  核心包不带模型，却产出了 <人名1>——模型是从别处（用户 home）捡来的'
        Get-Content $log2 | ForEach-Object { Write-Host "      $_" }; $fail = 1
    } elseif (-not (Test-IsolationEffective $log2Text 'empty-home2')) {
        Write-Host '    ng  隔离没生效：日志里没有出现 empty-home2 路径，无法证明「没有 <人名1>」是因为包内确实没模型'
        Get-Content $log2 | ForEach-Object { Write-Host "      $_" }; $fail = 1
    } elseif (-not (Select-String -Quiet -LiteralPath $log2 -Pattern '没有脱敏|未启用')) {
        Write-Host '    ng  没装模型却没有声明「人名与机构名没有被脱敏」——用户会以为脱干净了'
        Get-Content $log2 | ForEach-Object { Write-Host "      $_" }; $fail = 1
    } else {
        Write-Host '    ok  核心包不带模型时：规则类照常脱敏，人名明说没脱（且不是从用户 home 捡来的模型）'
    }
}

# 起服务并从外部打 —— 与 macOS 侧同一条验证：服务只绑 127.0.0.1。
$serveLog = Join-Path $ws 'serve.log'
# 端口占用预检（块 28 补的一道）：被占时要明说「换一个端口」，
# 而不是让服务起不来、最后只报一句「起服务失败」——那看不出真因。
#
# 【为什么查两个端口】
#   下面要起**两个**服务：一个验「健康检查能通」，一个验「首次启动自动装模型」。
#   它们必须用**不同的端口**——用同一个的话，第二个要么抢不到端口，
#   要么健康检查打到了第一个上，于是「第二个服务起没起来」这件事根本没法验。
$portBusy = $false
foreach ($p in @($Port, ($Port + 1))) {
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect('127.0.0.1', $p, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(500)) { $portBusy = $true }
        $tcp.Close()
    } catch { }
}
if ($portBusy) { throw "端口 $Port 或 $($Port + 1) 已被占用——换一个（-Port 或 LITEMASK_E2E_PORT）" }

$proc = Start-Process -FilePath $exePath `
        -ArgumentList @('serve', '--port', "$Port", '--workspace', (Join-Path $ws 'wsdir'), '--no-browser') `
        -RedirectStandardOutput $serveLog -RedirectStandardError (Join-Path $ws 'serve.err') `
        -PassThru -WindowStyle Hidden
$ok = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2 -UseBasicParsing
        if ($r.Content -match '"ok"\s*:\s*true') { $ok = $true; break }
    } catch { }
}
if ($ok) {
    Write-Host '    ok  起服务并通过 /api/health'
} else {
    Write-Host '    ng  起服务失败'; Get-Content $serveLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
    Get-Content (Join-Path $ws 'serve.err') -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
    $fail = 1
}
if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }

# ---- 6a. 首次启动**自动**把随附模型装上（这是产品的核心承诺）--------------
#
# 【为什么必须单独验这一条】
#   前面的第 5 步是用**命令行**装模型的——那是给排障与脚本用的路径。
#   而产品承诺的是：**用户双击装好、打开程序，模型自己就绪**，
#   全程不碰终端。这一条只能靠「起服务 → 问 /api/model」来验。
#   不验它的话，「用户不用装模型」就只是一句没人验证过的说法。
#
# 【怎么做到可信】
#   用一个**全新的空 home** 起服务 → 服务里没有模型；
#   然后轮询 /api/model，等它自己变成 READY。
#   模型是从**包内**的随附模型包装上来的（验签 + 原子换装 + 真的加载），
#   所以状态变成 READY 只能是因为「自动安装这条路真的通了」。
#
# 【用另一个端口】
#   上一个服务刚被杀掉，同一个端口可能还在 TIME_WAIT；
#   而更糟的是：两个服务共用一个端口时，第二次的健康检查可能打到第一个上，
#   于是「第二次起没起来」这件事根本没被验到。
if ($ModelPackageDir) {
    $autoPort = $Port + 1
    $emptyHome3 = Join-Path $ws 'empty-home3'
    New-Item -ItemType Directory -Path $emptyHome3 -Force | Out-Null
    $savedJTO6a = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = Get-IsolatedHomeEnv $emptyHome3
    $autoLog = Join-Path $ws 'serve-auto.log'
    $autoProc = Start-Process -FilePath $exePath `
            -ArgumentList @('serve', '--port', "$autoPort", '--workspace', (Join-Path $ws 'wsdir2'), '--no-browser') `
            -RedirectStandardOutput $autoLog -RedirectStandardError (Join-Path $ws 'serve-auto.err') `
            -PassThru -WindowStyle Hidden
    $modelReady = $false
    $modelStateSeen = ''
    # 装模型要解压 13MB 并把 ONNX 模型加载起来：给足 180 秒。
    # 【为什么不用固定 sleep 然后查一次】那是在赌「几秒内一定装完」——
    #   慢的机器上会误判成失败，而真因是「它还在装」。
    for ($i = 0; $i -lt 180; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri "http://127.0.0.1:$autoPort/api/model" -TimeoutSec 3 -UseBasicParsing
            $j = $r.Content | ConvertFrom-Json
            if ($j.state -and $j.state -ne $modelStateSeen) {
                $modelStateSeen = $j.state
                Say "模型状态：$($j.stateLabel)"
            }
            if ($j.state -eq 'READY' -and $j.usable) {
                $modelReady = $true
                break
            }
        } catch { }
    }
    $env:JAVA_TOOL_OPTIONS = $savedJTO6a
    if ($autoProc -and -not $autoProc.HasExited) { Stop-Process -Id $autoProc.Id -Force }
    if ($modelReady) {
        Write-Host '    ok  首次启动自动把随附模型装好了（空 home → 状态变成「可用」），全程不用终端'
    } else {
        Write-Host "    ng  首次启动没有自动装好模型（最后状态：$modelStateSeen）"
        Get-Content $autoLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
        Get-Content (Join-Path $ws 'serve-auto.err') -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
        $fail = 1
    }
}

# ------------------------------------ 6b. 体积闸门（核心包 <= 90 MiB）
#
# 【为什么 Windows 侧也必须有这道闸门 —— 2026-10-21 块 25 修掉的真缺陷】
#   闸门原来只存在于 tools/measure-size.sh（**bash**），
#   而 Windows 构建机**不保证有 bash** → 这个脚本一直**没有**任何体积检查：
#   它会打印体积，然后照样打「PASS 打包并验证通过」。
#   → 90 MiB 是本项目**唯一的硬指标**，却只在半个平台上生效；
#     而这个脚本自己写着「Windows 包省 6.57 MiB」——
#     它知道体积会动，却没有人拦着它动。
#   → 现在判定逻辑在**纯 JDK**（com.litemask.pack.SizeGate）里，
#     与 macOS 侧**同一份**判定、**同一份**策略表
#     （tools/size-budget.properties —— 预算与三层口径的唯一真源）。
#
# 【三层口径（与 macOS 侧逐字一致）】
#   L0 jlink 定制运行时 / L1 应用与核心依赖（fat jar）/ L2 NER 模型包（可选）
#   L2 缺失不算失败（方案 B：核心包不带模型），
#   但它必须**显式**报成缺失 —— 默默少算一层，报告就会显得已经量过了。
Write-Host '== 6b/7 体积闸门（核心包 <= 90 MiB）=='
$policy = Join-Path $root 'tools\size-budget.properties'
if (-not (Test-Path $policy -PathType Leaf)) {
    Write-Host '    ng 找不到体积闸门策略表：tools\size-budget.properties'
    $fail = 1
} else {
    $l2Path = ''
    $haveModel = $false
    if ($ModelDir -and (Test-Path $ModelDir -PathType Container)) {
        $l2Path = (Resolve-Path $ModelDir).Path
        $haveModel = $true
    } elseif ($ModelPackageDir -and (Test-Path $ModelPackageDir -PathType Container)) {
        # 随包分发的形态：L2 量的是**模型包**（zip + 签名）。
        # 【为什么不量解压后的模型】用户机器上占地方的正是解压后的那份
        #   （13MB 的 zip 解出来是 14MB+），而发布件里躺的是 zip。
        #   两边都报，见下面「体积报告」那一段。
        $l2Path = (Resolve-Path $ModelPackageDir).Path
        $haveModel = $true
    }
    $gateArgs = @('-cp', $jar, 'com.litemask.pack.SizeGateCli',
                  '--policy', $policy,
                  '--l0-bytes', "$rtBytes", '--l0-path', $rtDir,
                  '--l1-bytes', "$jarBytes", '--l1-path', $jar)
    if ($haveModel) {
        $gateArgs += @('--l2-bytes', "$(BytesOf $l2Path)", '--l2-path', $l2Path)
    } else {
        $gateArgs += @('--l2-missing')
    }
    Invoke-External { & $javaExe @gateArgs }
    if ($LASTEXITCODE -ne 0) {
        Write-Host '    ng 体积闸门没过 —— 这个包**不发**（超预算，或必需组件缺失)'
        $fail = 1
    } else {
        Write-Host '    ok 体积闸门通过'
    }
}

Remove-Item -LiteralPath $ws -Recurse -Force -ErrorAction SilentlyContinue

if ($SkipInstaller) {
    Write-Host ''
    Write-Host '-SkipInstaller，跳过安装包'
    exit $fail
}

# ------------------------------------------------------------- 7. 打安装包
Write-Host '== 7/7 jpackage 打 msi 与 exe 安装包 =='
#
# 【2026-10-25 修掉的真缺陷：标题写「msi 与 exe」，实际只出 msi】
#   这个脚本的文件头与第 7 步标题都写着「exe/msi 安装包」，
#   而代码里**只有 `--type msi`** —— exe 从来没被产出过。
#   （app-image 里的 LightMeter.exe 是**启动器**，不是安装器；
#     把它当「exe 安装器」报出去是错的。）
#   → 现在**两个都打**：
#     · `--type msi` → LightMeter-<ver>.msi  （发布优先取得 MSI）
#     · `--type exe` → LightMeter-<ver>.exe  （自解压安装器，内部仍走 MSI）
#   两者都是 jpackage 的**安装器**，不是 app-image 里的启动器。
#
# 【--win-per-user-install：默认按用户装】
#   发布目标是「普通用户双击就能装」。
#   jpackage 的 msi 默认是 per-machine → 装进 Program Files，需要管理员/UAC。
#   --win-per-user-install 装到 %LOCALAPPDATA%\Programs\<AppName>，普通用户直接装。
#   要 per-machine（-MachineInstall）就装进 Program Files。
#
# 【--win-upgrade-uuid：升级要认得出「同一个产品」】
#   不给固定 UUID 的话，jpackage 每次构建随机生成一个 UpgradeCode
#   → 新版 MSI 认不出旧版 = 变成并排安装而不是升级，
#     用户机器上会同时存在两个 LightMeter。
#   → UUID 固定在 tools\package-identity.properties（跨端唯一真源）。
CleanDir $instDir
$stage = Join-Path $env:TEMP ("litemask-stage-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item $jar $stage
# 【2026-10-25】随程序分发的已签名模型包也进安装包。
# 之前这里是「刻意不带模型」（方案 B：模型与程序分开分发，用户自己装）。
# 现在模型**随程序分发**，由程序在首次启动时自己验签并安装——
# 「用户装完程序还要再装一次模型」这件事到此结束。
Stage-ModelPackage $stage $JarName | Out-Null

# 安装器共用的参数
$instCommon = @(
    '--name', $AppName, '--app-version', $AppVersion,
    '--input', $stage, '--main-jar', $JarName, '--main-class', $MainClass,
    '--runtime-image', $rtDir, '--dest', $instDir,
    '--java-options', $JvmArgs, '--win-console'
)
if ($MachineInstall) {
    Write-Host '    安装形态：per-machine（装进 Program Files，需要管理员权限）'
} else {
    $instCommon += '--win-per-user-install'
    Write-Host '    安装形态：per-user（装进 %LOCALAPPDATA%\Programs，普通用户可装）'
}
# 升级 UUID：取不到就**不放**这个参数（jpackage 会自己随机生成），
# 但那会让新版认不出旧版 —— 所以这里是 fail-closed：直接停。
$upgradeUuid = ''
try { $upgradeUuid = IdProp 'app.upgrade.uuid' } catch { }
if (-not $upgradeUuid) {
    throw '打包身份真源里没有 app.upgrade.uuid —— 升级路径会认不出「同一个产品」，不要发这个包'
}
$instCommon += @('--win-upgrade-uuid', $upgradeUuid)

Invoke-External { & $jpackageExe --type msi @instCommon }
if ($LASTEXITCODE -ne 0) { throw "jpackage msi 失败（退出码 $LASTEXITCODE）—— WiX 装了没？缺 WiX 的话 jpackage 只会报一段英文" }

# exe 安装器：jpackage 在 Windows 上把它做成「自解压 + 内部 MSI」。
# 它与 app-image 里的启动器是两回事：前者是安装器，后者是程序本体。
Invoke-External { & $jpackageExe --type exe @instCommon }
if ($LASTEXITCODE -ne 0) {
    # exe 安装器打不出来**不阻断** MSI 发布（发布优先取得 MSI），
    # 但必须说清楚 —— 不能让「没出 exe」这件事被静默吞掉。
    Write-Host "    ng exe 安装器失败（退出码 $LASTEXITCODE）—— MSI 不受影响，但标题承诺的 exe 安装器这次没有"
    Write-Host '       （原因多半是 WiX v3 的 light.exe 在链 exe 时报错；看上面 jpackage 的输出）'
} else {
    Write-Host '    ok exe 安装器已生成'
}
Remove-Item -LiteralPath $stage -Recurse -Force
Get-ChildItem $instDir | ForEach-Object { Say "$($_.Name) = $($_.Length) B" }
Say ("安装包合计 = " + (BytesOf $instDir) + " B = " + (MiB (BytesOf $instDir)) + " MiB")

# ---- 6c. 体积报告：安装包 / 模型 / 安装后，分开报 -------------------------
#
# 【为什么要分开报三个数】
#   上面那道闸门管的是「**核心包** ≤ 90 MiB」，那是发布件（app-image）的口径。
#   而用户关心的是另外三个数，它们**互相不等**：
#     · 安装包（MSI / EXE）—— 用户要下载的东西；
#     · 模型 —— 随程序分发的识别模型多大；
#     · 安装后占用 —— 装到机器上之后实际占多少磁盘
#       （运行时 + 应用 + 模型 + 安装器自身的开销）。
#   只报一个「合计」的话，用户既不知道下载要多少流量，也不知道装完要腾多少空间。
#
# 【安装后体积怎么算】
#   = app-image 的全部内容（L0 运行时 + L1 应用 + 随附模型包）
#     + 安装器自身的开销（MSI 压缩包解开后比 app-image 大一些）。
#   这里用 app-image 的实测值 + 安装包大小作为**上界**报出来，
#   并注明这是估算——比报一个看起来精确、其实没测过的数要诚实。
$msiFile = @(Get-ChildItem -LiteralPath $instDir -Filter '*.msi' -File -ErrorAction SilentlyContinue)[0]
$exeInstaller = @(Get-ChildItem -LiteralPath $instDir -Filter '*.exe' -File -ErrorAction SilentlyContinue)[0]
$appImageBytes = BytesOf $appDir
$modelBytes = 0
if ($ModelPackageDir -and (Test-Path $ModelPackageDir -PathType Container)) {
    $modelBytes = BytesOf $ModelPackageDir
}
Write-Host ''
Write-Host '== 体积报告（安装包 / 模型 / 安装后）=='
if ($msiFile) {
    Write-Host ("    安装包（MSI）        = " + $msiFile.Length + " B = " + (MiB $msiFile.Length) + " MiB")
} else {
    Write-Host '    安装包（MSI）        = 未产出'
}
if ($exeInstaller) {
    Write-Host ("    安装包（EXE 自解压）  = " + $exeInstaller.Length + " B = " + (MiB $exeInstaller.Length) + " MiB")
} else {
    Write-Host '    安装包（EXE 自解压）  = 未产出'
}
if ($modelBytes -gt 0) {
    Write-Host ("    模型（随程序分发）    = " + $modelBytes + " B = " + (MiB $modelBytes) + " MiB")
} else {
    Write-Host '    模型（随程序分发）    = 未随包分发'
}
Write-Host ("    安装后占用（估算上界）= " + $appImageBytes + " B = " + (MiB $appImageBytes) + " MiB")
Write-Host '    （安装后占用 = 运行时 + 应用 + 随附模型 + 安装器开销；上面是 app-image 的实测值 + 安装包大小作为上界）'

Write-Host ''
if ($fail -ne 0) {
    Write-Host 'X 打包完成但**验证没过**，不要发这个包'
    exit 1
}
Write-Host 'PASS 打包并验证通过'
exit 0
