#!/usr/bin/env bash
# 对照实验：逐个破坏实现，确认测试确实会红。
# 绿灯本身不说明问题——只有"撤掉实现就红"，才能证明测试有牙齿。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 切到项目根：maven 是在**当前目录**找 pom.xml 的，而这个脚本是用脱离式启动器
# （run-detached.py）跑的——启动器继承调用方的当前目录，于是从别处起它时
# maven 会报「这个目录里没有 pom.xml」，退出码 1。
# 症状极像「工作区是脏的」：起飞前基线报红、却一个失败类都列不出来
# （编译错误不是「Tests run:」那种行，grep 自然捞不到）。
# → 判据要落在「这一处」：脚本自己切目录，与调用方从哪起无关。
cd "$ROOT" || { echo "X 进不去项目根：$ROOT"; exit 1; }
export JAVA_HOME="/Users/zhanghongqian/Documents/Codex项目/REA/work/tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
# 不要加 -q：quiet 模式下 surefire 只在**失败**时打印汇总，全绿时什么都不输出，
# 于是基线复核只剩一个没有用例数的 GREEN，等于没证据。
# -Dstyle.color=never：否则汇总行带 ANSI 色码，正则匹配不到。
MVN="/Users/zhanghongqian/Documents/Codex项目/REA/work/tools/maven/bin/mvn -B -o -Dstyle.color=never"

# 逐测试类汇报，而不是解析总计行。
# 原因：RuleSampleTest 用的是 @TestFactory 动态测试，surefire 会把每个 Run N 单独列出来，
# 末尾那条 "Tests run: 24, Failures: 1" 是**容器计数**不是用例数（实测 360 条动态用例里挂 10 条时，
# 总计行仍显示 24/1）。信它等于报假数据。每个测试类那行 "-- in <类名>" 才是稳定可靠的。
# 输出形如：RedactionRoundTripTest 8 F0E0 ValidatorTest 7 F0E0 ... RuleSampleTest 360 F10E0
test_counts() {
  echo "$1" | grep -E '^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+, Time elapsed.*-- in ' \
    | sed -E 's/^\[[A-Z]+\] Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: [0-9]+, Time elapsed.*-- in (.+)$/\4 \1 用例 失败\2 错误\3/' \
    | tr '\n' ' '
}

ENGINE="$ROOT/src/main/java/com/litemask/core/RedactionEngine.java"
ALLOC="$ROOT/src/main/java/com/litemask/core/TokenAllocator.java"
REWRITE="$ROOT/src/main/java/com/litemask/pdf/PdfStreamRewriter.java"
PDFRED="$ROOT/src/main/java/com/litemask/pdf/PdfRedactor.java"
PDFCODES="$ROOT/src/main/java/com/litemask/pdf/PdfCodes.java"
PDFTOKENS="$ROOT/src/main/java/com/litemask/pdf/PdfTokens.java"
ADVSIM="$ROOT/src/main/java/com/litemask/pdf/TextAdvanceSimulator.java"
OFD="$ROOT/src/main/java/com/litemask/ofd/OfdRedactor.java"
OFDINFO="$ROOT/src/main/java/com/litemask/ofd/OfdDocInfo.java"
VALIDATOR="$ROOT/src/main/java/com/litemask/core/Validator.java"
ZIPPKG="$ROOT/src/main/java/com/litemask/zipxml/ZipXmlPackage.java"
RULES="$ROOT/src/main/resources/rules/core-rules.json"
ENGINE2="$ROOT/src/main/java/com/litemask/core/RedactionEngine.java"
SECPOL="$ROOT/src/main/java/com/litemask/server/SecurityPolicy.java"
WORKSPACE="$ROOT/src/main/java/com/litemask/server/Workspace.java"
INSTALLER="$ROOT/src/main/java/com/litemask/ner/ModelPackageInstaller.java"
STORE="$ROOT/src/main/java/com/litemask/ner/ModelPackageStore.java"
CLIIV="$ROOT/src/main/java/com/litemask/cli/CliInvocation.java"
MODELSIG="$ROOT/src/main/java/com/litemask/ner/ModelPackageSignature.java"
MODELTRUST="$ROOT/src/main/java/com/litemask/ner/ModelTrust.java"
CLEANER="$ROOT/src/main/java/com/litemask/cli/AppDataCleaner.java"
TASKRUNNER="$ROOT/src/main/java/com/litemask/server/TaskRunner.java"
LOCALSERVER="$ROOT/src/main/java/com/litemask/server/LocalServer.java"
JOBSTORE="$ROOT/src/main/java/com/litemask/server/JobStore.java"
APIHANDLERS="$ROOT/src/main/java/com/litemask/server/ApiHandlers.java"
EXCLUSIONS="$ROOT/src/main/java/com/litemask/core/Exclusions.java"
DOCSVC="$ROOT/src/main/java/com/litemask/server/DocumentService.java"
# 这一组的变异目标是**测试夹具**（src/test），不是产品代码。
# 理由：这一轮新增的守卫测的是「测试资源与夹具行为」，没有产品代码参与，
# 唯一的证伪办法就是把夹具自己拆开看守卫会不会红。
# 所以这里必须写清楚，否则读日志的人会以为产品代码被改了。
PDFFIXTURES="$ROOT/src/test/java/com/litemask/pdf/PdfFixtures.java"
# 归组这一组：产品代码是 core/HitGrouping，呈现层是 server/DocumentService + server/JobStore，
# 浏览器那半边是 resources/web/app.js。
GROUPING="$ROOT/src/main/java/com/litemask/core/HitGrouping.java"
APPJS="$ROOT/src/main/resources/web/app.js"
APPCSS="$ROOT/src/main/resources/web/app.css"
# 第 12 块：模型包下载。变异目标是「把包从网上取回来」这一层（ModelFetcher）
# 与「取回来之后装上」这一层（ModelCommand）。
FETCHER="$ROOT/src/main/java/com/litemask/cli/ModelFetcher.java"
MODELCMD="$ROOT/src/main/java/com/litemask/cli/ModelCommand.java"
CROSSUNIT="$ROOT/src/main/java/com/litemask/core/CrossUnitContinuation.java"
# 长任务进度与取消这一组：作用域在 core/TaskContext，任务表在 server/TaskRunner，
# 检查点在 core/RedactionSession，产物清理由 server/JobStore 负责，
# 浏览器那半边是 resources/web/app.js 与 app.css。
TASKCTX="$ROOT/src/main/java/com/litemask/core/TaskContext.java"
TASKRUNNER="$ROOT/src/main/java/com/litemask/server/TaskRunner.java"
SESSION="$ROOT/src/main/java/com/litemask/core/RedactionSession.java"
# 第 17 块：macOS 开机自启（LaunchAgent）。
# 这一组的「内容」在 plist 生成器（PLIST）里，
# 「安装/卸载/停服务」在 AutostartCommand 里，
# 「卸载清单」在 AppDataCleaner 里，
# 「卸载时先停服务」这个顺序在 UninstallCommand 里。
PLIST="$ROOT/src/main/java/com/litemask/cli/LaunchAgentPlist.java"
AUTOSTART="$ROOT/src/main/java/com/litemask/cli/AutostartCommand.java"
UNINSTALL="$ROOT/src/main/java/com/litemask/cli/UninstallCommand.java"
# 第 18 块：Windows 开机自启（注册表 Run 键）。
# 这一组的「约定」（键 / 值名 / 命令行长什么样）在 WindowsRunKey 里，
# 「装 / 卸 / 看状态」的流程在 WindowsAutostart 里，
# 「平台分发」在 AutostartCommand 里，
# 「卸载时把自启也清掉」在 UninstallCommand 里。
WINKEY="$ROOT/src/main/java/com/litemask/cli/WindowsRunKey.java"
WINAUTO="$ROOT/src/main/java/com/litemask/cli/WindowsAutostart.java"

# 第 25 块：核心包体积闸门（90 MiB 是本项目唯一的硬指标）。
# 判定逻辑在纯 JDK 的 SizeGate 里 —— Windows 侧调的是**同一份**判定。
SIZEGATE="$ROOT/src/main/java/com/litemask/pack/SizeGate.java"

# 第 36 块：进程外调用这一维度。
# 「拉起了外部程序」本身不构成外发，**「拉起的是什么、参数里装了什么」才是**——
# 而参数这件事静态层看不见（标尺只看常量池），只能靠单测守住。
# 这两个文件正是本项目里**唯一**两处进程外调用的所在地。
LOCAL_SERVER="$ROOT/src/main/java/com/litemask/server/LocalServer.java"
BROWSER="$ROOT/src/main/java/com/litemask/cli/BrowserLauncher.java"

# 第 20 块：映射件的加密封装与权限收紧。
# 「封装格式」在 SecretMap 里，「落盘权限」在 PrivateFile 里，
# 「口令从哪来、什么时候用」在 PassphraseSource 与 Main 里。
SECRETMAP="$ROOT/src/main/java/com/litemask/core/SecretMap.java"
PRIVATEFILE="$ROOT/src/main/java/com/litemask/core/PrivateFile.java"

CLASSES="$ROOT/target/classes"

pass=0; fail=0; invalid=0

# 删目录：不用 `rm -rf`。
#
# 【为什么 —— 2026-10-20 实测踩到的坑】
#   本机的网关会把 shell 里的 `rm -rf` **路由到 mavis-trash**，
#   而 mavis-trash 在本机**挂死**（实测：删一个 /tmp 下的空目录都卡住不返回）。
#   → CE 的第 87..92 片因此以「起飞前基线就不是绿的」失败：
#     `rm -rf "$CLASSES"` 没删掉上一条变异的字节码 → 基线跑的是变异体 → 报红。
#   **这个红是守卫工作正确**（它抓到了「构建产物脏了」，正是它该抓的），
#   但它不该由环境故障引起 —— 环境故障会让整片作废，结论就没了。
#   → 改用 python 的 shutil.rmtree：不经 shell 的命令路由，行为确定。
#   语义与 `rm -rf` 等价：目录不存在时什么都不做，目录存在时整棵删掉。
#
# 【为什么不干脆去掉清理、让 Maven 自己重编】
#   因为 `mv .bak 源文件` 会把源文件 mtime 还原到变异**之前**（下一条注释里那个坑），
#   增量编译会判定"无需重编" → 后续 `mvn test` 全在跑变异体。清理是必需的一环。
rm_rf() {
  python3 -c 'import os,shutil,sys; p=sys.argv[1]; shutil.rmtree(p) if os.path.isdir(p) else None' "$1"
}

# 变异的 class 必须真正被丢弃。
# 踩过的坑：`mv .bak 源文件` 会把源文件 mtime 还原到变异**之前**，于是它比刚编译出的
# 变异 class 更旧，Maven 增量编译判定"无需重编"，后续所有 `mvn test` 都在跑变异体——
# 表面上测试红了，实际是构建产物脏了。这正是"红灯原因必须正确"要防的东西。
force_rebuild() {
  [ -d "$CLASSES" ] && rm_rf "$CLASSES"
}

# 清理上一次实验可能残留的备份
#
# AUDIT=1 跳过这一段：体检只读源码，不碰 .bak，也不该被残留拦住。
# 但**要把残留报出来**——它是「上一轮实验被中断」的唯一现场证据，
# 静默跳过就等于把一个真实的脏工作区信号丢掉了。
if [ -n "${AUDIT:-}" ]; then
  for f in "$ENGINE.bak" "$ALLOC.bak" "$REWRITE.bak" "$PDFRED.bak" "$PDFCODES.bak" \
           "$PDFTOKENS.bak" "$ADVSIM.bak" \
           "$OFD.bak" "$OFDINFO.bak" "$VALIDATOR.bak" "$ZIPPKG.bak" "$RULES.bak" \
           "$SECPOL.bak" "$WORKSPACE.bak" "$LOCALSERVER.bak" "$ENGINE2.bak" "$JOBSTORE.bak" \
           "$EXCLUSIONS.bak" "$DOCSVC.bak" "$PDFFIXTURES.bak" \
           "$GROUPING.bak" "$APPJS.bak" "$APPCSS.bak" \
           "$TASKCTX.bak" "$TASKRUNNER.bak" "$SESSION.bak" \
           "$PLIST.bak" "$AUTOSTART.bak" "$UNINSTALL.bak" \
           "$WINKEY.bak" "$WINAUTO.bak"; do
    [ -e "$f" ] && echo "! 发现残留备份 ${f}（体检不因此中止，但上一轮实验很可能被中断过）"
  done
else
for f in "$ENGINE.bak" "$ALLOC.bak" "$REWRITE.bak" "$PDFRED.bak" "$PDFCODES.bak" \
         "$PDFTOKENS.bak" "$ADVSIM.bak" \
         "$OFD.bak" "$OFDINFO.bak" "$VALIDATOR.bak" "$ZIPPKG.bak" "$RULES.bak" \
         "$SECPOL.bak" "$WORKSPACE.bak" "$LOCALSERVER.bak" "$ENGINE2.bak" "$JOBSTORE.bak" \
         "$EXCLUSIONS.bak" "$DOCSVC.bak" "$PDFFIXTURES.bak" \
         "$GROUPING.bak" "$APPJS.bak" "$APPCSS.bak" \
         "$TASKCTX.bak" "$TASKRUNNER.bak" "$SESSION.bak" \
         "$PLIST.bak" "$AUTOSTART.bak" "$UNINSTALL.bak" \
         "$WINKEY.bak" "$WINAUTO.bak"; do
  [ -e "$f" ] && { echo "X 发现残留备份 ${f}，先中止"; exit 1; }
done
fi

# 起飞前先把基线打绿。没有这一步，后面所有的红都分不清是「实验抓到了问题」
# 还是「工作区进来时就已经是脏的」——实测踩过：一次 run_case_multi 的参数写漏了文件路径，
# 脚本在 set -u 下撞 unbound variable 直接退出，第一组变异已经落盘而还原代码没机会跑；
# 下一次运行拿到的备份就是那份变异源码，于是锚点「未命中」、基线复核报红，
# 看起来像新代码有问题，实际是上一次实验留下的。
#
# AUDIT=1 是纯静态体检（只读源码、不跑测试、不改文件），所以**跳过**这一步——
# 否则体检要白等一次全量测试，而体检的全部意义就是秒出结果。
if [ -z "${SKIP_PREFLIGHT:-}" ] && [ -z "${AUDIT:-}" ]; then
  force_rebuild
  pre_out=$($MVN test 2>&1); pre_code=$?
  if [ $pre_code -ne 0 ]; then
    echo "X 起飞前基线就不是绿的（退出码 ${pre_code}）——工作区进来时已经不干净"
    echo "  下面这些类有失败；若是上一次实验被中断留下的变异源码，先手工还原再跑"
    # 【为什么不能按空格拆开再筛 —— 2026-10-20 修掉的真缺陷】
    #   原来写的是 `test_counts ... | tr ' ' '\n' | grep -E '失败[1-9]|错误[1-9]'`，
    #   而 test_counts 的输出是「类名 用例数 失败数 错误数」空格分隔的一长串。
    #   按空格拆开后**类名与计数被分到了不同的行**，grep 只捞得到「失败1」这个
    #   **计数词**，**类名被丢掉了** —— 报错只说「有一个类挂了」，不说「哪个类」。
    #   判据落到「这一类」而不是「这一处」，后果是：基线偶发红时无法判断
    #   是「工作区真的脏」还是「某个测试偶发」，连定位都做不到。
    #   → 现在**按类整体**打印（类名 + 用例数 + 失败数 + 错误数），
    #     并把 maven 打印的**失败方法名**一并带出来。
    echo "$pre_out" \
      | grep -E '^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+, Time elapsed.*--? in ' \
      | grep -E 'Failures: [1-9]|Errors: [1-9]' | head -8
    echo "  失败的方法（maven 原始输出）："
    echo "$pre_out" | grep -E '^\[ERROR\]   ' | head -12
    exit 1
  fi
  echo "起飞前基线 GREEN"
fi

mutate() { # $1=文件 $2=原文 $3=替换
  python3 - "$1" "$2" "$3" <<'PY'
import sys
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding='utf-8').read()
if old not in s:
    # 未命中时把**最相近的一行**打出来。
    #
    # 只打印 MUTATION_TARGET_MISSING 的话，修锚点要去 grep 整个源码树，
    # 而锚点漂移往往只是「加了参数」「换了行」「代码搬到了另一个类」，
    # 光看文件根本猜不出该改哪里——实测一次全量里有 5 条这样失效，
    # 每条都要翻两三个文件才定位得到。下面这段把「找」的活交给机器。
    key = old.strip().split('\n')[0].strip()
    best, best_score = None, -1.0
    for line in s.split('\n'):
        st = line.strip()
        if not st:
            continue
        # 用字符级重合度粗排，够用了：漂移通常是「整行多/少几个 token」。
        common = len(set(key) & set(st))
        score = common / max(len(set(key)), 1)
        if score > best_score:
            best, best_score = st, score
    if best is not None and best_score >= 0.5:
        print(f"MUTATION_TARGET_MISSING 锚点首行={key!r}")
        print(f"  文件里最相近的一行：{best[:140]!r}")
    else:
        print(f"MUTATION_TARGET_MISSING 锚点首行={key!r}（未找到相近行）")
    sys.exit(9)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
}

# 取出「红在哪些用例上」+「哪些测试类红了」，供下游判据核对。
#
# 【为什么必须在这一刻取 —— 2026-10-20 实测】
#   surefire 每次 mvn test 都覆盖 target/surefire-reports/ 下的同名文件。
#   而本脚本收尾的「基线复核」会**再跑一遍全量测试**，
#   于是事后读磁盘上的报告，读到的永远是**基线那份全绿**，
#   变异那次红在哪个用例上的信息**已经不存在了**。
#   实测踩过：4b 步骤据此判「没红」，把一次真通过误报成失败。
#   → 证据只能在变异跑完、源码还原之前，从**当次的控制台输出**里取。
#
# 【格式来自实测，不是猜的 —— 2026-10-20】
#   maven 控制台里失败用例行长这样（**有行号，类名是简名**）：
#     [ERROR]   TaskTimeoutTest.超时的任务真的停了且不留产物:218 断言消息 ==> expected: <x> but was: <y>
#   而 surefire 的 .txt 报告里是另一种格式（**没有行号**，类名是全限定名）：
#     com.litemask.server.TaskTimeoutTest.超时的任务真的停了且不留产物 -- Time elapsed: 0.762 s <<< FAILURE!
#   两者不能混用——按控制台格式去 grep .txt 报告，永远匹配不到。
#
# 【哪些类红了】
#   控制台里每个测试类都有一行汇总，带 <<< FAILURE! 的那行就是这个类红了：
#     [ERROR] Tests run: 7, Failures: 1, ... <<< FAILURE! -- in com.litemask.server.TaskTimeoutTest
#
# 输出格式：`红在=<用例名列表>|红类=<类名列表>`（两个列表都可能为空，用「无」占位）。
red_detail() {
  echo "$1" | python3 -c '
import re, sys
txt = sys.stdin.read()
cases = []
for m in re.finditer(r"^\[ERROR\]\s+([A-Za-z0-9_$.]+)\.([^:\s]+):\d+ ", txt, re.M):
    cases.append(m.group(2))
classes = []
for m in re.finditer(r"^\[ERROR\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+, Time elapsed:.*<<< (?:FAILURE|ERROR)! -- in (\S+)", txt, re.M):
    classes.append(m.group(1))
seen = set()
uc = []
for c in cases:
    if c not in seen:
        seen.add(c)
        uc.append(c)
seen = set()
ucl = []
for c in classes:
    if c not in seen:
        seen.add(c)
        ucl.append(c)
print("红在=" + (" ".join(uc) if uc else "无") + "|红类=" + (" ".join(ucl) if ucl else "无"))
'
}

_judge() { # $1=名称 $2=期望 $3=退出码 $4=输出
  local name="$1" expect="$2" code="$3" out="$4" n got
  if echo "$out" | grep -q "COMPILATION ERROR"; then
    echo "INVALID ${name} 变异体编译失败，红灯原因不对 —— 实验作废"; invalid=$((invalid+1)); fail=$((fail+1)); return
  fi
  if [ "$code" -ne 0 ]; then got=RED; else got=GREEN; fi
  n=$(test_counts "$out")
  if [ "$expect" = CRASH ]; then
    if [ "$got" = RED ] && echo "$out" | grep -qE 'The forked VM terminated|error in the forked process|OutOfMemoryError|Java heap space'; then
      echo "PASS ${name} -> CRASH (期望如此：fork 被打死，不是断言失败) ${n}"; pass=$((pass+1))
    else
      echo "INVALID ${name} 期望进程崩溃，实际 got=${got} 且未见 surefire fork 崩溃 —— 实验作废 | ${n}"
      invalid=$((invalid+1)); fail=$((fail+1))
    fi
    return
  fi
  if [ "$got" = "$expect" ]; then
    # 靠抛异常红 ≠ 测试咬住了实现。
    # 变异体把列表里塞了个 Integer 进去、于是 flushTj 强转 Double 时炸掉——那是"崩溃了"，
    # 不是"断言发现文档不对"。这种红灯必须判无效，否则实验等于自证。
    if [ "$expect" = RED ] && ! echo "$n" | grep -q '失败[1-9]'; then
      echo "INVALID ${name} 变体是抛异常红的（断言失败 0 条），红灯原因不对 —— 实验作废 | ${n}"
      invalid=$((invalid+1)); fail=$((fail+1)); return
    fi
    echo "PASS ${name} -> ${got} (expected ${expect}) ${n} $(red_detail "$out")"; pass=$((pass+1))
  else
    echo "FAIL ${name} -> ${got} (expected ${expect}) ${n} <<< 测试没咬住 $(red_detail "$out")"; fail=$((fail+1))
  fi
}

# 还原源码，并把 mtime 刷成现在。
#
# 【为什么不能直接 `mv "$file.bak" "$file"` —— 2026-10-21 实测踩到的坑】
#   mv 会把 mtime 还原成**备份那一刻**的时间，也就是变异**之前**。
#   于是源文件比 target/classes 里的变异体拷贝更旧 →
#   Maven 增量判定「这个资源无需重拷贝」→ 跳过 →
#   之后打出来的 jar 里装的是**变异体**，不是源码。
#   实测后果：浏览器端到端三条断言全红（折叠没生效 / 排除项没进报文 /
#   提示语不更新），查源码却是对的 —— 红在构建产物上。
#   → 还原之后必须让 mtime 变成现在，下一次构建才会重新拷贝这个资源。
#
# 删 .bak 不用 `rm -f`：网关会把 shell 里的 rm 路由到 mavis-trash（本机挂死），
# 用 python 的 os.remove。
restore_src() {
  cp "$1.bak" "$1"
  python3 -c 'import os,sys; p=sys.argv[1]; os.remove(p) if os.path.exists(p) else None' "$1.bak"
}

_run_case() { # $1=名称 $2=文件 $3=原文 $4=替换 $5=期望(RED/GREEN/CRASH)
  local name="$1" file="$2" old="$3" new="$4" expect="$5"
  cp "$file" "$file.bak"
  if ! mutate "$file" "$old" "$new"; then
    echo "X ${name} 变异点未命中，实验无效"; restore_src "$file"
    # 同上：未命中 = 变异体没写进去、测试一次都没跑，与「跑了没咬住」结论相反。
    invalid=$((invalid+1)); fail=$((fail+1)); return
  fi
  force_rebuild
  out=$($MVN test 2>&1); code=$?
  restore_src "$file"
  force_rebuild
  if echo "$out" | grep -q "COMPILATION ERROR"; then
    echo "INVALID ${name} 变异体编译失败，红灯原因不对 —— 实验作废"; invalid=$((invalid+1)); fail=$((fail+1)); return
  fi
  if [ $code -ne 0 ]; then got=RED; else got=GREEN; fi
  n=$(test_counts "$out")
  # CRASH 必须**先于** RED/GREEN 比较来判。
  # 死循环 / 内存爆炸这类变异在物理上不可能产生断言失败——进程先被打死，于是 got 必然是 RED，
  # 和 expect=CRASH 永不相等。CRASH 分支若写在 `[ "$got" = "$expect" ]` 里面就是死代码：
  # 第一轮 28 项里唯一的 FAIL 就是这么来的（脚本自己判的，不是测试没咬住），
  # 而单独复现时 surefire 确实报了 `There was an error in the forked process` + `Java heap space`。
  # 判它有效，但**必须同时确认崩溃真的发生了**，否则一个无关的红灯也能混进来。
  _judge "$name" "$expect" "$code" "$out"
}

# 复合变异：一次实验里拆掉多处（M1-f 的 XXE 防护就是这种情况）。
# 参数：名称，然后是若干组「文件 原文 替换」。
# 单点变异抓不住不是探针失效——实测把 StAX 那一层单独放开，对面 DOM 层照样拦；
# 反过来也一样。只有整条链一起拆，外部实体的内容才会真的进到 DOM 里。
_run_case_multi() {
  local name="$1"; shift
  # 踩过的坑（会静默污染工作区）：同一个文件被连续变异多次时，每轮都 cp 一次备份，
  # 后一次 cp 拿到的是**已变异的状态**，于是最后只回滚了后面几步——源码留在变异态，
  # 而 .bak 已经不在，看起来"清理干净了"。正确做法：先把所有涉及的文件各备份一次，
  # 再做全部变异，最后统一还原。
  #
  # 备份放 mktemp -d 而不是数组：macOS 自带 bash 3.2 在 set -u 下展开空数组会报
  # unbound variable，路径里又有中文，用字符串列表 + 临时目录最省事。
  local total=$# i f o n tmpdir unique=""
  # 参数守卫：每组必须是「文件 原文 替换」三个，少一个都不行。
  # 这不是洁癖：漏掉文件参数时第二个循环会拿「原文」当文件名去 cp/mutate，
  # 在 set -u 下撞 unbound variable 让脚本**直接退出**——而第一组已经变异过了，
  # 还原代码根本没机会执行，工作区从此留着变异源码，下一轮实验的备份也拿它当基线。
  if [ "$total" -eq 0 ] || [ $((total % 3)) -ne 0 ]; then
    echo "X ${name} 参数格式错误：应为若干组「文件 原文 替换」，收到 ${total} 个参数（必须是 3 的倍数）"
    fail=$((fail + 1)); return
  fi
  tmpdir="$(mktemp -d)"
  for ((i = 1; i <= total; i += 3)); do
    f="${!i}"
    case "$unique" in
      *"|$f|"*) ;;
      *) unique="$unique|$f|"; cp "$f" "$tmpdir/$(basename "$f").orig" ;;
    esac
  done
  for ((i = 1; i <= total; i += 3)); do
    o=$((i + 1)); n=$((i + 2))
    if ! mutate "${!i}" "${!o}" "${!n}"; then
      echo "X ${name} 变异点未命中，实验无效"
      _restore_multi "$tmpdir" "$unique"
      # 锚点未命中 = 这条实验**没提供任何信息**（变异体压根没写进去，测试一次都没跑），
      # 与「跑了但测试没咬住」（真 FAIL）结论相反，必须分开计数。
      invalid=$((invalid + 1)); fail=$((fail + 1)); return
    fi
  done
  force_rebuild
  local out code
  out=$($MVN test 2>&1); code=$?
  _restore_multi "$tmpdir" "$unique"
  force_rebuild
  _judge "$name" RED "$code" "$out"
}

# 把备份还原回源码，并清掉临时目录。
#
# 【为什么必须整目录删掉，而不是 rmdir —— 2026-10-19 实测】
#   原来只 `rmdir "$tmpdir"`。rmdir **只能删空目录**，于是每条 run_case_multi
#   都会留下一个临时目录。实测一轮 118 条跑完，TMPDIR 下积了 11 个这样的目录。
#
#   那里面躺着什么？两样：
#   1) 备份文件（*.orig）——源码的完整副本；
#   2) **.DS_Store**——macOS 的 Finder 会自动往任何被它看过的目录里塞这个文件。
#      它与本项目毫无关系，却足以让 rmdir 永远失败。
#
#   先删 .orig 再 rmdir 也不够：.DS_Store 照样会让 rmdir 报错，
#   于是「备份已清、目录还在」这个半吊子状态被当成成功——
#   实测改完仍然留下 12 个目录。
#
#   所以直接整目录删。这里用 rm -rf 而不是 mavis-trash：
#   这个目录是**脚本自己刚 mktemp 出来的**（不是用户的文件），
#   而且它必须在同一轮实验里立刻消失——留着它等于留着源码副本。
#   路径来自 mktemp -d，不含任何用户输入，删错不了。
_restore_multi() { # $1=临时目录 $2=unique 串（|f1|f2|）
  local tmpdir="$1" unique="$2" f
  for f in ${unique//|/ }; do
    cp "$tmpdir/$(basename "$f").orig" "$f"
  done
  rm_rf "$tmpdir"
  [ -d "$tmpdir" ] && echo "! 临时目录未能清空（${tmpdir}）——请手工检查"
  return 0
}

# 子集运行：ONLY='破坏㉕' ./tools/control-experiment.sh
# 只跑匹配名字的变异项，省时间调脚本用。子集结果**不能**当"全绿"报——开头结尾都会标出来。
#
# AUDIT=1：只静态体检锚点，不跑任何测试、不改任何文件。
#
# 【为什么要有这个模式 —— 2026-10-19 实测】
#   改一个方法的签名，就会把它对应的对照实验锚点改没了。那条实验于是判 INVALID
#   （「变异点未命中，实验无效」）。而 INVALID 的含义是**这一项没提供任何信息**——
#   变异体压根没写进去，测试一次都没跑，它咬不咬得住是未知的。
#
#   问题在于发现得太晚：整场跑完（几十分钟）才在收尾时看到，
#   而此时「这一条原本守的是什么」已经没人记得了。
#   锚点体检是**纯静态**的，读源码、数命中次数，秒出结果。
#
#   复用 bash 自己的参数解析（而不是另写一个解析器）：
#   锚点里有多行文本、有嵌套引号、有空串，任何独立解析器都会与 bash 的语义有偏差——
#   实测自己写的 Python 解析器漏判了 41 条（多行锚点被切开），报出来的「问题」是假的。
#   这里让 bash 把参数原样递进来，语义与真正执行时完全一致。
audit_count=0
audit_bad=0
_audit_one() { # $1=名称 $2=文件 $3=锚点
  local name="$1" file="$2" anchor="$3" n
  audit_count=$((audit_count + 1))
  if [ ! -f "$file" ]; then
    echo "  ✗ ${name}"
    echo "      目标文件不存在: ${file}"
    audit_bad=$((audit_bad + 1)); return
  fi
  n=$(python3 - "$file" "$anchor" <<'PY'
import sys
p, anchor = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
print(s.count(anchor))
PY
)
  if [ "$n" -eq 0 ]; then
    echo "  ✗ ${name}"
    echo "      锚点未命中（源码已漂移）: ${file}"
    audit_bad=$((audit_bad + 1))
  elif [ "$n" -gt 1 ]; then
    echo "  ✗ ${name}"
    echo "      锚点命中 ${n} 次（不唯一，变异点不确定）: ${file}"
    audit_bad=$((audit_bad + 1))
  fi
}

run_case() {
  if [ -n "${AUDIT:-}" ]; then
    _audit_one "$1" "$2" "$3"; return
  fi
  if [ -n "${ONLY:-}" ] && ! printf '%s' "$1" | grep -qE "$ONLY"; then
    return
  fi
  _run_case "$@"
}

run_case_multi() {
  if [ -n "${AUDIT:-}" ]; then
    local name="$1"; shift
    local total=$# i
    # 参数组是「文件 原文 替换」三元素。
    # 锚点要用 ${!j} 取**位置参数**，不能用 "$((j+1))"——
    # 那是算术求值，会把「第 2 个参数」算成一个数字 2。
    for ((i = 1; i <= total; i += 3)); do
      local j=$((i + 1))
      _audit_one "$name" "${!i}" "${!j}"
    done
    return
  fi
  if [ -n "${ONLY:-}" ] && ! printf '%s' "$1" | grep -qE "$ONLY"; then
    return
  fi
  _run_case_multi "$@"
}

# 锚点体检的汇总放在**所有 run_case 调用之后**（脚本尾部）——
# 放这里会一个锚点都数不到，因为调用点还在后面。
# 这里只做一件事：体检模式下不要去跑基线复核（那是跑测试，体检不该等它）。

# 基线断言：所有实验跑完，工作区必须回到"源码=磁盘 class=绿灯"的初始状态。
# 没有这一步，上一轮实验留下的脏 class 会一路带进后续开发，而你只看到莫名其妙的红灯。
# ---------------------------------------------------------------------------
# OFD（M1-e）。这一组咬住的都是「文件正常生成、工具正常报告成功，内容还在」——
# OFD 的坑全在正文之外：模板页不显示、文档属性里躺着证件号、附件名带手机号。
# 只看正文的实现这些一个都测不出来，而单测很容易跟着实现一起想当然。

run_case "破坏㉙：只扫 Pages 目录（模板页里的身份证整段漏脱）" "$OFD" \
  '            if (!ZipXmlPackage.isXmlPart(name)) {
                continue;
            }
            Document doc = pkg.parse(pkg.read(entry));
            boolean dirty = false;

            // A. 文本槽' \
  '            if (!ZipXmlPackage.isXmlPart(name) || !name.contains("/Pages/")) {
                continue;
            }
            Document doc = pkg.parse(pkg.read(entry));
            boolean dirty = false;

            // A. 文本槽' RED

run_case "破坏㉚：同一对象内不跨 TextCode 拼接（拆开的手机号整段漏脱）" "$OFD" \
  '            if (OFD_NS.equals(child.getNamespaceURI()) && "TextCode".equals(child.getLocalName())) {
                out.add(child);
            }' \
  '            if (OFD_NS.equals(child.getNamespaceURI()) && "TextCode".equals(child.getLocalName())) {
                if (out.isEmpty()) {
                    out.add(child);
                }
            }' RED
run_case "破坏㉛：不处理没有 TextObject 祖先的 TextCode（孤儿文本漏脱）" "$OFD" \
  '            if (!claimed.contains(code)) {
                groups.add(List.of(code));
            }' \
  '            if (false) {
                groups.add(List.of(code));
            }' RED

run_case "破坏㉜：不处理 TextCode 的关键字属性（正文脱了但检索仍搜得到原文）" "$OFD" \
  '                if (redactTextAttribute(code, "Text", session, name, "TC" + i, hits, collector)) {' \
  '                if (false && redactTextAttribute(code, "Text", session, name, "TC" + i, hits, collector)) {' RED

run_case "破坏㉝：不处理文档属性（发票号码与纳税人识别号留在 OFD.xml 里）" "$OFD" \
  '            NodeList info = doc.getElementsByTagNameNS(OFD_NS, "DocInfo");
            for (int i = 0; i < info.getLength(); i++) {
                Element docInfo = (Element) info.item(i);' \
  '            NodeList info = doc.getElementsByTagNameNS(OFD_NS, "NotDocInfo");
            for (int i = 0; i < info.getLength(); i++) {
                Element docInfo = (Element) info.item(i);' RED

run_case "破坏㉞：不清空 Author（作者姓名留在文档属性里）" "$OFDINFO" \
  '            if (CLEARED.contains(name)) {' \
  '            if (false) {' RED

run_case "破坏㉟：顺手把 DeltaX 按新长度裁掉（还原时再也回不到原字距）" "$OFD" \
  '                for (int s = 0; s < group.size(); s++) {
                    ZipXmlPackage.setText(group.get(s), mapped.texts().get(s));
                }' \
  '                for (int s = 0; s < group.size(); s++) {
                    Element slot = group.get(s);
                    ZipXmlPackage.setText(slot, mapped.texts().get(s));
                    if (slot.hasAttribute("DeltaX")) {
                        String[] adv = slot.getAttribute("DeltaX").trim().split("\\s+");
                        StringBuilder sb = new StringBuilder();
                        int want = mapped.texts().get(s).length();
                        for (int k = 0; k < want && k < adv.length; k++) {
                            if (k > 0) {
                                sb.append((char) 32);
                            }
                            sb.append(adv[k]);
                        }
                        if (sb.length() == 0) {
                            slot.removeAttribute("DeltaX");
                        } else {
                            slot.setAttribute("DeltaX", sb.toString());
                        }
                    }
                }' RED

run_case "破坏㉠：不移除签章部件（留下一个必然验不过的死签名）" "$OFD" \
  '            if (isSignatureEntry(name)) {
                dropped.add(name);
                continue;
            }' \
  '            if (false) {
                dropped.add(name);
                continue;
            }' RED

run_case "破坏㉡：附件条目不改名（文件名里的手机号留在 zip 里）" "$OFD" \
  '            full.put(name, dir(name) + redacted);' \
  '            if (false) {
                full.put(name, dir(name) + redacted);
            }' RED

run_case "破坏㉢：条目改名后不更新 XML 里的引用（引用指向不存在的附件）" "$OFD" \
  '        if (plan.isEmpty()) {
            return false;
        }' \
  '        if (true) {
            return false;
        }' RED

run_case "破坏㉣：还原只处理 TextCode（路径引用不还原，指向错文件）" "$OFD" \
  '            if (!hasElementChild(element)) {
                String before = element.getTextContent();
                String after = RedactionEngine.restore(before, mapping);
                if (!after.equals(before)) {
                    ZipXmlPackage.setText(element, after);
                    restored++;
                }
            }' \
  '            if (!hasElementChild(element) && "TextCode".equals(element.getLocalName())) {
                String before = element.getTextContent();
                String after = RedactionEngine.restore(before, mapping);
                if (!after.equals(before)) {
                    ZipXmlPackage.setText(element, after);
                    restored++;
                }
            }' RED

run_case "破坏㉤：还原不还原 zip 条目名（文件名回去了、引用还指着新名）" "$OFD" \
  '                    if (!back.equals(name)) {
                        renames.put(name, back);
                        restored++;
                    }' \
  '                    if (false) {
                        renames.put(name, back);
                        restored++;
                    }' RED

# ---------------------------------------------------------------------------
# 块 16（OFD 侧跨单元续接）。
#
# 【为什么 OFD 要单独一组判据，不能照抄 OOXML 的】
#   OOXML（docx/xlsx/pptx）是**流式**版式：一段文字被拆成多个 run/单元格，
#   那是「同一段被切开」，按文档顺序拼回去是对的。
#   OFD 是**绝对定位**版式：每个 TextObject 自己带 Boundary（毫米坐标）。
#   于是「顺序相邻」在空间上未必挨着——左右两栏的 y 完全相同、x 相距几十毫米，
#   只看顺序就会把两栏拼成一条**根本不存在**的字符串。
#   → OFD 侧的续接必须由**几何**说了算：同一部件、同一行、水平紧邻。
#
# 【这一组每条只咬一件事】
#   判据是「同部件 / 同行 / 水平紧邻 / 拿不到边界就不合并」四条**独立**的闸门。
#   把它们合并成一条变异是错的：只要有一条闸门还站着，整条就照样 PASS，
#   另几条的空转会被**静默**掩盖。所以这里一条变异拆一个闸门，
#   每条都要在自己的用例上红。

# 破坏㊲：把「按几何分组」整个拆掉（每个单元自成一组）。
# 症状：同一行紧挨着的两段身份证不再续接 → 前半段 110105 原样留在文档里。
# 这条守的是**续接能力本身**——没有它，OFD 侧与块 14 之前的 xlsx 侧同构。
run_case "破坏㊲：不按几何分组（每个单元自成一组，跨对象续接整段失效）" "$OFD" \
  '            current.add(unit.text());
            previous = unit;' \
  '            groups.add(List.of(unit.text()));
            previous = unit;' RED

# 破坏㊳：把水平紧邻阈值放大到无穷。
# 症状：左右两栏、以及同一行里隔了 30 毫米的两个对象，都被当成「同一段被切开」，
# 于是两栏被拼成一条完整身份证 → 版面事故（OFD 是绝对定位，跨栏拼接不可逆）。
run_case "破坏㊳：水平紧邻阈值放大到无穷（左右两栏被拼成一条身份证）" "$OFD" \
  '    static final double MAX_HORIZONTAL_GAP_MM = 1.0;' \
  '    static final double MAX_HORIZONTAL_GAP_MM = 1.0e9;' RED

# 破坏㊴：去掉「组不能跨部件」这一条。
# 症状：相邻两个页面里**坐标完全相同**的两个对象被当成同一行 →
# 跨页续接 → 身份证的前半在第一页、后半在第二页，却被当成一个整体。
# 坐标一样不代表是同一段文字：它们在不同的 zip 部件里。
run_case "破坏㊴：不检查部件（相邻两页里坐标相同的对象被当成同一行）" "$OFD" \
  '        if (!java.util.Objects.equals(previous.part(), current.part())) {
            return false;
        }' \
  '        // 变异体：部件检查被拆掉' RED

# 破坏㊵：把纵向容差放大到无穷。
# 症状：上下两行（y 差 40 毫米）的两段被拼成一条身份证。
# 只看「水平紧邻」不够——x 方向紧挨着但 y 不同的，是两段独立内容。
run_case "破坏㊵：纵向容差放大到无穷（上下两行被拼成一条身份证）" "$OFD" \
  '    static final double LINE_TOLERANCE_MM = 0.2;' \
  '    static final double LINE_TOLERANCE_MM = 1.0e9;' RED

# 破坏㊶：拿不到 Boundary 的对象整个跳过（不喂引擎）。
# 症状：没有 Boundary 的 TextObject、以及没有 TextObject 祖先的孤儿 TextCode，
# 文本原样留在文档里。
# 【这条守的是 fail-closed 的边界】
#   几何判据不成立时，正确行为是「这个对象自己算一段，照常脱敏」，
#   不是「这个对象不处理」。后者是**静默漏脱**——文件正常生成、
#   工具正常报成功，只有拿结果去比对原文的人才会发现。
run_case "破坏㊶：拿不到边界的对象整个跳过（没有 Boundary 的文本静默漏脱）" "$OFD" \
  '                if (collector != null) {
                    String full = SlotTextMapper.join(before);
                    if (!full.isEmpty()) {
                        collector.collect(new Unit(full, Kind.TEXT_OBJECT, boundsOf(group), name));
                    }
                }
                SlotTextMapper.Mapped mapped = SlotTextMapper.map(before, session);' \
  '                if (collector != null) {
                    String full = SlotTextMapper.join(before);
                    if (!full.isEmpty()) {
                        collector.collect(new Unit(full, Kind.TEXT_OBJECT, boundsOf(group), name));
                    }
                }
                if (boundsOf(group) == null) {
                    continue;
                }
                SlotTextMapper.Mapped mapped = SlotTextMapper.map(before, session);' RED

assert_baseline() {
  local leftovers
  leftovers=$(ls "$ROOT"/src/main/java/com/litemask/*/*.bak 2>/dev/null)
  if [ -n "$leftovers" ]; then
    echo "X 基线断言失败：残留备份文件 -> ${leftovers}"; fail=$((fail+1)); return
  fi
  force_rebuild
  local out code
  out=$($MVN test 2>&1); code=$?
  if [ $code -eq 0 ]; then
    n=$(test_counts "$out")
    echo "PASS 基线复核 -> GREEN | ${n}"
    case "$n" in
      *失败[1-9]*|*错误[1-9]*) echo "X 退出码为 0 但仍报出失败/错误，证据自相矛盾"; fail=$((fail+1)) ;;
    esac
    case "$n" in
      "") echo "X 基线复核没拿到任何测试类汇总，证据不足"; fail=$((fail+1)) ;;
    esac
  else
    echo "X 基线复核失败：撤除全部变异后仍不是绿的，实验污染了工作区"
    # 【证据必须够定位，否则这次失败就白丢】
    #   上一版只打 `grep -E 'Tests run:|FAIL|ERROR\]   ' | head -20` 的**前 20 行**：
    #   失败点若落在第 20 个测试类之后，日志里**一行指向它的都没有**，
    #   根因彻底查不到。实测踩到过：2026-10-21 块 24 的全量对照实验里，
    #   w1 路有一片基线复核失败过一次（退出码非 0），重跑又绿 —— **间歇性**。
    #   → 现在一次给四样，缺一不可：
    #     ① maven 输出的**尾部**（BUILD 汇总行、fork 崩溃提示都在尾部）
    #     ② **非绿**的汇总行（Failures/Errors 非 0 的 Tests run 行）与全部 [ERROR] 行
    #     ③ surefire 的崩溃现场文件（fork 崩了会留下 dump/dumpstream）
    #     ④ 磁盘余量（磁盘满会让 maven 以非零码退出）
    #   → 诊断行**统一缩进 4 个空格**：合并脚本按「行首 PASS/FAIL/INVALID/X + 空格」
    #     认变异结论行，诊断行混进去会被误当成「串段／跑重了」。
    echo "    ---- 基线失败的 maven 退出码：${code} ----"
    echo "    ---- maven 输出尾部 40 行 ----"
    printf '%s\n' "$out" | tail -40 | sed 's/^/    /'
    echo "    ---- 非绿汇总行与 [ERROR] 行（最多 40 条）----"
    printf '%s\n' "$out" \
      | grep -aE 'Failures: *[1-9]|Errors: *[1-9]|\[ERROR\]|BUILD FAILURE|forked VM|Crashed tests' \
      | head -40 | sed 's/^/    /' || true
    echo "    ---- surefire 崩溃现场（dump/dumpstream）----"
    ls -1 "$ROOT"/target/surefire-reports/*.dump* 2>/dev/null | sed 's/^/    /' \
      || echo "    （没有 dump/dumpstream 文件）"
    echo "    ---- 磁盘余量 ----"
    df -h "$ROOT" | tail -1 | sed 's/^/    /'
    fail=$((fail+1))
  fi
}

MAPPER="$ROOT/src/main/java/com/litemask/core/SlotTextMapper.java"
DOCX="$ROOT/src/main/java/com/litemask/docx/DocxRedactor.java"
SESSION="$ROOT/src/main/java/com/litemask/core/RedactionSession.java"
XLSX="$ROOT/src/main/java/com/litemask/xlsx/XlsxRedactor.java"
PPTX="$ROOT/src/main/java/com/litemask/pptx/PptxRedactor.java"

echo "=== control experiment start ==="
if [ -n "${ONLY:-}" ]; then
  echo "!!! 子集运行：只跑名字匹配 '${ONLY}' 的变异项 —— 结果不能当'全绿'报"
fi

run_case "破坏①：从文首而非文末应用替换（偏移失效）" "$ENGINE" \
  'for (int i = byPosition.size() - 1; i >= 0; i--) {' \
  'for (int i = 0; i < byPosition.size(); i++) {' RED

run_case "破坏②：绕过校验器（校验位形同虚设）" "$ENGINE" \
  'if (!rule.validator().isValid(hit)) {
                    continue;
                }' \
  '' RED

run_case "破坏③：不消解重叠（优先级失效）" "$ENGINE" \
  'byPriority.sort(Comparator.comparingInt(Match::sortOrder).reversed()
                .thenComparingInt(Match::start)
                .thenComparing(Comparator.comparingInt(Match::length).reversed()));' \
  'byPriority.sort(Comparator.comparingInt(Match::start));' RED

run_case "破坏④：不裁剪首尾空白（偏移算错）" "$ENGINE" \
  'while (from < to && Character.isWhitespace(raw.charAt(from))) {
                    from++;
                }' \
  '' RED

run_case "破坏⑤：占位符不避让原文既有编号（撞号）" "$ALLOC" \
  'Matcher matcher = EXISTING_TOKEN.matcher(sourceText);' \
  'Matcher matcher = java.util.regex.Pattern.compile("(?!)").matcher(sourceText);' RED

# 下面几条针对 docx：它们咬住的是"静默漏脱"——文件照样生成、工具照样报告成功，
# 只是原文里还留着敏感信息。这类缺陷不会被任何"输出非空"式的断言发现。

run_case "破坏⑥：段落不跨槽拼接（Word 拆 run 导致整段漏脱）" "$MAPPER" \
  '        return String.join("", slotTexts);' \
  '        return slotTexts.isEmpty() ? "" : slotTexts.get(0);' RED

run_case "破坏⑦：不处理修订留痕 delText（屏幕上看不见的敏感信息）" "$DOCX" \
  'if ("t".equals(local) || "delText".equals(local)) {' \
  'if ("t".equals(local)) {' RED

run_case "破坏⑧：docx 不清文档属性（作者姓名留在属性面板）" "$DOCX" \
  'int cleared = DocPropsScrubber.scrub(doc, name);' \
  'int cleared = 0;' RED

run_case "破坏⑨：丢失 xml:space=preserve（Word 重开后首尾空格被吃掉）" "$DOCX" \
  'slot.setAttributeNS(XML_NS, "xml:space", "preserve");' \
  'slot.setAttribute("data-no-space", "true");' RED

# 变异目标跟着方法签名一起改，已经改过三次：M2-b 把 redact 换成四参重载，
# M2-c1 又加了五参（带 OccurrenceNumbering），M3-1f 接入 sources 时
# RedactionSession.redactInternal 的两条三元分支**合并成了一条**——
# 现在 redact() 与 redactHidden() 都走 redactInternal(text, numbered)，
# 分支在方法**内部**（numbered ? this::nextOccurrence : null）而不是在调用点。
#
# 锚点漂移的判据是脚本打印 MUTATION_TARGET_MISSING 并判 INVALID——
# 脚本没有放它过关，但**「未命中」不等于「没风险」，只等于这一项的覆盖没了**。
#
# 所以 mutator 现在会在未命中时打出「文件里最相近的一行」：
# 三次漂移的原因分别是「换了签名」「拆了分支」「代码搬到了另一个类」，
# 光看 MISSING 这五个字是猜不出该改哪里的。
run_case "破坏⑩：每次调用新建分配器（跨段落同值不同符）" \
  "$SESSION" \
  '                text, rules, allocator, exclusions,' \
  '                text, rules, new TokenAllocator(text), exclusions,' RED

# xlsx：Excel 会把敏感数据放进一些很容易被忘掉的角落，每一条都是"文件正常、报告成功、内容还在"

run_case "破坏⑪：共享字符串富文本不跨片段拼接（身份证被拆进多个 run）" "$XLSX" \
  'if (redactSlots(si, slots, session, name, "si#" + i, hits)) {' \
  'if (redactSlots(si, slots.subList(0, 1), session, name, "si#" + i, hits)) {' RED

run_case "破坏⑫：数值单元格不转文本（Excel 仍显示原始数字）" "$XLSX" \
  'cell.setAttribute("t", "inlineStr");' \
  'cell.setAttribute("t", "n");' RED

run_case "破坏⑬：工作表名不脱敏（文件名/标签页露姓名）" "$XLSX" \
  'if (!redacted.equals(original)) {
                    sheet.setAttribute("name", redacted);' \
  'if (false) {
                    sheet.setAttribute("name", redacted);' RED

run_case "破坏⑭：批注不脱敏" "$XLSX" \
  'if (name.startsWith("xl/comments")) {' \
  'if (false) {' RED

run_case "破坏⑮：xlsx 不清文档属性" "$XLSX" \
  'int cleared = DocPropsScrubber.scrub(doc, name);' \
  'int cleared = 0;' RED

# pptx：图表把数据内嵌在文件里、打开时不会重算，缓存值就是屏幕上显示的那一份

run_case "破坏⑯：幻灯片不跨 run 拼接（PPT 同样会拆 run）" "$PPTX" \
  'SlotTextMapper.Mapped mapped = SlotTextMapper.map(before, session);' \
  'SlotTextMapper.Mapped mapped = SlotTextMapper.map(before.subList(0, Math.min(1, before.size())), session);' RED

run_case "破坏⑰：图表数据缓存不脱敏（坐标轴标签里留着身份证）" "$PPTX" \
  'NodeList chartValues = doc.getElementsByTagNameNS(C_NS, "v");' \
  'NodeList chartValues = doc.getElementsByTagNameNS(C_NS, "notExist");' RED

run_case "破坏⑱：备注页不清文档属性" "$PPTX" \
  'int cleared = DocPropsScrubber.scrub(doc, name);' \
  'int cleared = 0;' RED

# PDF：字符被编码进内容流算子里，"看得见的字"和"读得到的字"是两回事。
# 这一组的每一条破坏都对应一类**文件正常、报告成功、内容还在**的静默泄露。

run_case "破坏⑲：跨算子续段删了字符却不补位移（后文压在令牌上）" "$REWRITE" \
  'buf.add(shiftUnits(op, advance[q], warnings));' \
  'buf.add(0.0);' RED

run_case "破坏⑳：跨元素的命中只对第一个字符串算子生效（后面的数字原样留下）" "$REWRITE" \
  'while (q < sorted.size() && sorted.get(q).end() <= elemStart) {' \
  'while (q < sorted.size() && sorted.get(q).start() < elemStart) {' RED

run_case "破坏㉑：还原时把调整数直接丢掉（版面多出一段空白）" "$REWRITE" \
  'buf.add(adjustNextNumber ? d * numberRescale + numberDelta : d);' \
  'buf.add(d);' RED

run_case "破坏㉒：补偿宽度只按第一个字符串元素量（命中跨元素时补偿不足）" "$REWRITE" \
  'if (!(e instanceof byte[])) {
                continue;
            }' \
  'if (!(e instanceof byte[])) {
                continue;
            }
            if (si > 0) {
                break;
            }' RED

run_case "破坏㉓：不遍历表单 XObject（模板里的敏感信息整段跳过）" "$PDFRED" \
  'if (resources == null || depth > MAX_FORM_DEPTH) {' \
  'if (resources == null || depth > 0) {' RED

run_case "破坏㉔：不清元数据（作者姓名、附件名、XMP 全留着）" "$PDFRED" \
  'List<String> removed = opt.scrubMetadata' \
  'List<String> removed = false' RED

run_case "破坏㉕：解码循环两道守卫全去掉（Type0 字体空转到 JVM 被撑爆）" "$PDFCODES" \
  'while (in.available() > 0) {
            int before = in.available();
            int code = font.readCode(in);
            int consumed = before - in.available();
            if (consumed <= 0) {
                return;
            }' \
  'while (true) {
            int before = in.available();
            int code = font.readCode(in);
            int consumed = before - in.available();
            if (consumed < 0) {
                return;
            }' CRASH

run_case "破坏㉖：还原时只换编码不换 Tf（中文按令牌字体解释，整段成乱码）" "$REWRITE" \
  '            boolean fontChanged = !nativeFontName.equals(op.fontName());' \
  '            boolean fontChanged = false;' RED

run_case "破坏⑴：还原时既不换 Tf 也不换 Tz（原文按令牌的缩放画回去）" "$REWRITE" \
  '            boolean scaleChanged = restoreState != null
                    && Math.abs(restoreState.hPercent() - op.hScale() * 100f) > 1e-4f;' \
  '            boolean scaleChanged = false;' RED

run_case "破坏㉗：命中横跨同一 TJ 的多个字符串时每个元素都写一次令牌" "$REWRITE" \
  'if (!done[q]) {
                    done[q] = true;' \
  'if (true) {' RED

run_case "破坏㉘：还原时不记账（报告说还原 0 处，文件里其实换回去了）" "$PDFRED" \
  'all.addAll(found);' \
  '' RED

# ---------------------------------------------------------------------------
# M1-f（不信任文档 + VIN 校验位 + 发票号码）。
#
# 这一组的共同点：被破坏的东西**不会让工具报错**，只会让它安静地少做一件事——
# 不限额的 zip 照样"脱敏成功"，只是把 600MB 塞进了堆；少了穿越检查照样写出文件，
# 只是文件名能逃出包外。所以红灯必须来自断言，而不是来自崩溃。

run_case "破坏㊿：把校验位加进 VIN 门禁（真实欧洲车架号原样留在文档里）" "$VALIDATOR" \
  '            return hasLetter;' \
  '            return hasLetter && vinCheckDigitOk(candidate);' RED

run_case "破坏⒀：读取不扣预算（597KB 的 zip 展开 600MB 进堆）" "$ZIPPKG" \
  '                    charge(n, entry.getName());
                    out.write(buffer, 0, n);' \
  '                    out.write(buffer, 0, n);' RED

run_case "破坏⒁：写回未改动条目时不扣预算（炸弹塞进 word/media 就能写满磁盘）" "$ZIPPKG" \
  '                    charge(n, entry.getName());
                    sink.write(buffer, 0, n);' \
  '                    sink.write(buffer, 0, n);' RED

run_case "破坏⒂：条目名不查上级目录穿越（文件名能逃出包外）" "$ZIPPKG" \
  'if (segment.equals("..")) {' \
  'if (false && segment.equals("..")) {' RED

run_case "破坏⒃：解析前不做流式深度扫描（20000 层深树直接建 DOM 吃内存）" "$ZIPPKG" \
  '            guardXml(xml);' \
  '' RED

# XXE 防护是**六层**叠加。M1-f 逐层实测过（探针真的把本机文件内容读进了 DOM 才作数）：
#   StAX：SUPPORT_DTD=false、IS_SUPPORTING_EXTERNAL_ENTITIES=false
#   DOM ：disallow-doctype-decl、FEATURE_SECURE_PROCESSING、ACCESS_EXTERNAL_DTD、EntityResolver
# 单独撤掉任何一层，对面那层都还在拦（探针里逐个试过：撤 disallow-doctype-decl 会得到
# "解析成功但内容为空"，撤 SUPPORT_DTD 会得到 "entity not declared"）。
# **任何单点变异都抓不住**——这不是探针失效，是纵深防御在挡。
# 所以这一项把六层一起拆；这才是"这条不变式真的有人守着"的证明。
# 副作用是很有用的一件事：六层全部写进了脚本，将来有人删掉其中任何一层，都看得见。
run_case_multi "破坏⒈：六层 XXE 防护一起拆（外部实体的内容真的进到文档里）" \
  "$ZIPPKG" 'factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);' \
            'factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.TRUE);' \
  "$ZIPPKG" 'factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);' \
            'factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.TRUE);' \
  "$ZIPPKG" 'factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);' \
            'factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);' \
  "$ZIPPKG" 'factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");' \
            'factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");' \
  "$ZIPPKG" 'factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");' \
            'factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "all");' \
  "$ZIPPKG" 'builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));' \
            ''

run_case "破坏⒅：VIN 换值表里 S 写成 1（真实 VIN 的校验位全算错）" "$VALIDATOR" \
  '"12345678123457923456789"' \
  '"12345678123457913456789"' RED

run_case "破坏⒆：发票规则去掉上下文引导词（裸 8 位数字全被当发票号码）" "$RULES" \
  '"(?<!\\d)\\d{20}(?!\\d)|(?<=发票(?:号码|号)[：:\\s]{0,5})\\d{8}(?!\\d)"' \
  '"(?<!\\d)(?:\\d{20}|\\d{8})(?!\\d)"' RED

# ---------------------------------------------------------------------------
# M2-a（本地服务）。这一组咬住的是「本机服务被外部网页当作跳板」与「上传路径穿越」。
# 它们共同的特点是：全绿时看不出任何问题，只有把防护拆掉才知道测没测到。
# 而「外部页面能不能构造写请求」这件事，一旦将来有人加了 CORS 头或放宽了 Origin，
# 后果是用户本机的文件被别的网站静默处理——所以这几条必须钉死。

run_case "破坏⒉：不校验 Host 头（DNS rebinding 直接成立，服务等于对外公开）" "$SECPOL" \
  '        if (!lower.equals("127.0.0.1")
                && !lower.equals("localhost")
                && !lower.equals("[::1]")) {
            return false;
        }
        return portText == null || portText.equals(Integer.toString(port));' \
  '        return true;' RED

run_case "破坏⒊：不校验会话令牌（任何知道端口的进程都能驱动本机脱敏）" "$SECPOL" \
  '        return isAllowedHost(hostHeader)
                && isAllowedOrigin(originHeader)
                && token.equals(presentedToken);' \
  '        return isAllowedHost(hostHeader)
                && isAllowedOrigin(originHeader);' RED

run_case "破坏⒋：不校验 Origin（外部网页的跨源写请求畅通）" "$SECPOL" \
  '        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("http://127.0.0.1:" + port)
                || lower.equals("http://localhost:" + port);' \
  '        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return true;
        }
        return true;' RED

# 变异点选的是「直接把客户端文件名拼进路径」，而不是「忘记剥 basename」。
# 后者其实仍落在会话目录内（../../../canary/x.pdf 剥完只剩 x.pdf），测它证明不了穿越——
# 真正会出事的是原始字符串直接进 resolve。
#
# 【2026-10-20 改锚点的理由：攻击面位移了，不是消失了】
# 「上传先落盘」重构之后，原来那两行（String diskName = randomId() + ext;）
# 搬进了 store(StagedFile)，而那里**连 clientFilename 这个变量都不存在**——
# 磁盘名 = randomId() + 白名单后缀，客户端文件名进不来。
# 所以原来那条变异已经**写不出去了**（锚点漂移，体检会判 INVALID）。
#
# 但「外部字符串参与路径拼接」这个面还在，只是**挪到了暂存阶段**：
# stage() 里暂存件的路径要是改用 clientFilename，一样能把原文档写到工作目录之外
# （newOutputStream 还会把同名文件截断），后面的「认领」只是把它挪回来——
# 中间那一瞬它已经在外面了。
# 于是把锚点移到 stage()，并用**两行**做锚（与 ➊ 的单行锚点不是同一个字符串），
# 免得两条实验的锚点撞在一起。
#
# 配套的判据是 WorkspaceTest.暂存件的落盘位置不受客户端文件名影响：
# 盯的是**暂存件**的落盘位置，不是最终位置——最终位置那条（路径穿越写不出去）
# 在这条路径上反而会被「认领」掩盖过去。
run_case "破坏⒍：暂存件路径直接用客户端文件名（上传路径穿越，文件写到工作目录之外）" "$WORKSPACE" \
  '        Path tmp = sessionDir.resolve("upload-" + randomId() + ".tmp");
        long total = 0;' \
  '        Path tmp = sessionDir.resolve(clientFilename);
        long total = 0;' RED

# ---------------------------------------------------------------------------
# M2-b（排除误报）。这一组咬的是「用户点了别脱，文档里却还是被脱了」——
# 它的可怕之处不在于报错，而在于**不报错**：界面一切正常，提示一切正常，
# 只有拿结果去比对原文的人才会发现。所以这三条顺序语义必须有人守着。
#
# ⒏ 同时也是「排除被放到重叠消解之后」那个 bug 的替身：
# 两种错法的可观测表现一样——被排除的跨度被别人接管了。
# 拆跨度保护，三条顺序语义会一起红，这本身就是它们在互相守着的证据。

run_case "破坏⒏：排除只按值删命中，不保护整个跨度（别的规则会从被排除的值里再脱一段）" "$ENGINE2" \
  '            boolean covered = false;
            for (Match p : protectedSpans) {
                if (m.start() < p.end() && p.start() < m.end()) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                kept.add(m);
            }' \
  '            kept.add(m);' RED

# 换一个方向：以为「用户传了排除项却一条都没对上」就该保守处理，于是整篇不脱。
# 这是 fail-open——方向反了，一次手滑（规则键写错）就让整份文档原样送到下游。
run_case "破坏⒑：排除项对不上任何命中时整篇放弃脱敏（fail-open）" "$ENGINE2" \
  '        if (protectedSpans.isEmpty()) {
            return matches;               // 排除项没对上任何命中：不做无谓的过滤
        }' \
  '        if (protectedSpans.isEmpty()) {
            return List.of();
        }' RED

# 接线点：五个 redactor 各自持有一个 RedactionSession，漏传 exclusions 是最可能发生的错，
# 而且它**不报错**：编译通过、输出文件照常生成、报告数字照常漂亮，
# 只是「用户点了别脱」这一项不起作用。所以五个一起改回去，一个实验守住五个接线点。
# 注意替换值必须是 Exclusions.NONE 而不是 List.of()：
# 第三参的类型是 Exclusions（record），传 List 会让变异体**编译失败**，
# 于是这条被判 INVALID「红灯原因不对」——而不是红或绿。
# 这正是脚本区分 INVALID 的价值：一次类型写错，结论完全不同，
# 而如果不区分就会把它当成「测试咬住了」，等于自证。
run_case_multi "破坏⒒：五个 redactor 都不把排除项传给会话（界面点「别脱」完全不起作用）" \
  "$DOCX" 'new RedactionSession(plan.fullText(), rules, exclusions, sources,' \
         'new RedactionSession(plan.fullText(), rules, Exclusions.NONE, sources,' \
  "$XLSX" 'new RedactionSession(plan.fullText(), rules, exclusions, sources,' \
         'new RedactionSession(plan.fullText(), rules, Exclusions.NONE, sources,' \
  "$PPTX" 'new RedactionSession(plan.fullText(), rules, exclusions, sources,' \
         'new RedactionSession(plan.fullText(), rules, Exclusions.NONE, sources,' \
  "$OFD"  'new RedactionSession(fullText, rules, exclusions, sources,' \
         'new RedactionSession(fullText, rules, Exclusions.NONE, sources,' \
  "$PDFRED" 'new RedactionSession(collectAllText(units), rules, exclusions, sources,' \
           'new RedactionSession(collectAllText(units), rules, Exclusions.NONE, sources,'

# 写接口的 Content-Type 校验是 M2-a 记下的缺口，M2-b 补上了。
run_case "破坏⒓：写接口不校验 Content-Type（HTML 表单可直接打进来）" "$JOBSTORE" \
  '        if (actual == null || !actual.toLowerCase(java.util.Locale.ROOT).startsWith(expected)) {' \
  '        if (false) {' RED

# M2-c3 任务 LRU。这两条咬的都是「界面上早就看不见那份文件了，磁盘上却还在」——
# 用户以为处理完就清掉了，原始文档其实一直躺在临时目录里。挑这种变异是因为
# 它们不报错、不影响功能，只影响一份文件什么时候消失。
run_case "破坏⒗：淘汰时不删磁盘文件（原始文档留在临时目录）" "$JOBSTORE" \
  '        workspace.delete(job.sourceName);
        workspace.delete(job.previewName);
        workspace.delete(job.outputName);' \
  '        // 变异：只在内存里抹掉条目，文件留着' RED

run_case "破坏⒘：淘汰改成插入序而非访问序（正在挑排除项的文件被踢掉）" "$JOBSTORE" \
  '            new LinkedHashMap<>(16, 0.75f, true);' \
  '            new LinkedHashMap<>(16, 0.75f, false);' RED

# 绑定地址与启动自检**一起**拆：只拆自检，绑定本来就是对的，什么也测不出来；
# 只拆绑定，自检会拦住它、测试以 Error 而非 Failure 变红，而判据是「断言失败数>0」，会误判无效。
# 两个一起拆，才是「真的对外开了一个端口」这个状态——那时能抓住它的只有
# 「从局域网地址连得上」那条断言。
run_case_multi "破坏⒎：绑 0.0.0.0 且关掉启动自检（服务对整个局域网可达）" \
  "$LOCALSERVER" 'private static final String BIND_ADDRESS = "127.0.0.1";' \
                'private static final String BIND_ADDRESS = "0.0.0.0";' \
  "$LOCALSERVER" 'if (bound == null || !SecurityPolicy.isLoopback(bound)) {' \
                'if (false) {'

# M2-c1 位置级排除。三条都是「程序看起来一切正常」型故障：
# 文件正常生成、令牌正常写入、报告数字照常漂亮，只是用户勾的那一处没生效。
run_case "破坏⒔：位置级排除退化成值级排除（勾「第 1 次」变成整条别脱）" "$EXCLUSIONS" \
  '            return occurrence >= 0 && actualOccurrence >= 0 && occurrence == actualOccurrence;' \
  '            return true;' RED

# 这条是本轮真实踩过的坑：序号本来是回填在排除**之后**的列表上，
# 于是喂给排除校验的命中序号还是 -1，位置级排除一条都匹配不上——
# 而输出看上去与「完全不排除」一模一样。把它钉住，免得改回那个顺序。
run_case "破坏⒕：序号在排除之后才回填（位置级排除静默失效）" "$ENGINE2" \
  '        if (numbering != null) {
            collected = attachOccurrences(collected,
                    assignOccurrences(resolveOverlaps(collected), numbering));
        }

        List<Match> kept = excl.isEmpty() ? collected : applyExclusions(collected, excl);
        kept = resolveOverlaps(kept);' \
  '        List<Match> kept = excl.isEmpty() ? collected : applyExclusions(collected, excl);
        if (numbering != null) {
            kept = attachOccurrences(kept, assignOccurrences(resolveOverlaps(kept), numbering));
        }
        kept = resolveOverlaps(kept);' RED

# TXT 分支曾经调的是四参重载（numbering=null），txt 整体不参与编号，
# 位置级排除对它完全失效——而值级排除照常工作、接口返 200、报告数字正常，
# 只有「勾第 N 次」这一项静默不生效。退回四参重载复现这个状态。
run_case "破坏⒙：txt 不参与出现编号（纯文本的位置级排除静默失效）" "$DOCSVC" \
  '                RedactionSession session = new RedactionSession(text, rules, excl, srcs);
                RedactionResult r = session.redact(text);' \
  '                RedactionResult r = RedactionEngine.redact(text, rules,
                        new com.litemask.core.TokenAllocator(text), excl);' RED

# 聚合键用空串拼时 ("phone","138") 与 ("phone1","38") 都是 "phone138"，
# 两类命中的计数与位置混成一坨，界面上的「第 N 次」从此指不到地方。
#
# 这条变异**第一次跑出来是绿的**：18 条内置规则键两两之间没有前缀包含关系，
# 构造不出串键组合——是探针空转，不是变异无效。修法是让 AggregateKeyTest
# 用 RuleLoader.load(Path) 现造一组 ("a","xy") 与 ("ax","y")（拼出来都是 axy）。
# 注意那条测试里「排除只放开一条」的前置断言不能删：被排除的命中不进 changes，
# 聚合时只剩另一条、键恰好与正确实现一致，删了它整条测试又会变回空转。
run_case "破坏⒚：聚合键不用分隔符（两类命中串成一坨）" "$DOCSVC" \
  '        return ruleKey + '"'"'\0'"'"' + original;' \
  '        return ruleKey + original;' RED

# 隐形路径（PDF 元数据、xlsx 表名/定义名称/批注、OFD 附件名）若改回普通 redact，
# 它们会开始消耗出现序号：界面上数的「第 N 次」与文件里的第 N 处从此对不上，
# 用户勾一处、实际作用在另一处，而且全程没有任何报错。
run_case_multi "破坏⒖：隐形路径不再走 redactHidden（界面序号与文件位置错位）" \
  "$PDFRED" 'PdfMetadataScrubber.scrub(doc, t -> session.redactHidden(t).sanitizedText())' \
            'PdfMetadataScrubber.scrub(doc, t -> session.redact(t).sanitizedText())' \
  "$XLSX" 'sanitizeSheetName(session.redactHidden(original).sanitizedText())' \
          'sanitizeSheetName(session.redact(original).sanitizedText())' \
  "$OFD" 'String redacted = session.redactHidden(leaf).sanitizedText();' \
         'String redacted = session.redact(leaf).sanitizedText();'

# ---------------------------------------------------------------------------
# M3 NER 解码层。这一组的共性是**错了不报错，只是安静地少脱一点或多脱一点**：
# 解码出的标签路径变成「没有开头」或「多出半截」，肉眼扫一遍报告数字根本看不出来。
DECODER="$ROOT/src/main/java/com/litemask/ner/SequenceDecoder.java"
WINDOWER="$ROOT/src/main/java/com/litemask/ner/SlidingWindowNer.java"
ONNXSCAN="$ROOT/src/main/java/com/litemask/ner/OnnxSelfContained.java"
PROTOWIRE="$ROOT/src/main/java/com/litemask/ner/ProtoWire.java"

# 去掉 BIO 结构约束。解码器退化成「逐位置看转移分数谁高就选谁」，
# 于是能解出 [I-人名] 这种没有 B- 开头的实体——正文里凭空多出一个单字实体。
run_case "破坏⒛：Viterbi 不施加 BIO 结构约束（解出没有开头的实体）" "$DECODER" \
  '                    if (!scheme.allows(from, to)) {
                        continue;
                    }' \
  '                    if (false) {
                        continue;
                    }' RED

# 只去掉开头约束而保留转移约束：序列首字可以是 I-，后面的转移仍然合法，
# 于是「文档第一行被脱掉一个字」而全文其余部分完全正常。
run_case "破坏⒜：序列开头允许 I- 标签（文档第一个字被凭空脱掉）" "$DECODER" \
  '            if (!scheme.allowsStart(tag)) {' \
  '            if (false) {' RED

# 抽实体时丢弃「没有 B- 打头」的 I-。方向是少脱：窗口首位的实体直接消失。
run_case "破坏⒝：抽实体时丢弃没有 B- 开头的 I-（实体凭空消失）" "$DECODER" \
  '                if (openType == null || openType != type) {' \
  '                if (true || openType == null || openType != type) {' RED

# 滑窗的「边缘观测不可信」规则失效。跨窗口截断出来的半截实体被采信，
# 于是文档里凭空多出几处不存在的实体；而真正跨边界的那个仍然会丢——
# 因为半截版本与完整版本撞了键。这是本轮实测踩过的第二个坑的反面。
#
# 锚点说明：第一版的锚点写的是 `return touchesStartCut || touchesEndCut;`，
# 那正是**修复左切边 bug 之前**的老代码。修完之后这行被删了，变异就变成
# MUTATION_TARGET_MISSING（判 INVALID），而不是红或绿——
# 「变异点未命中」不支撑任何结论，必须先改锚点再判。
# 守这条规则的是 SlidingWindowNerTest.边缘截断不采信。
run_case "破坏⒞：滑窗采信紧贴切边的观测（采信被截断的半截实体）" "$WINDOWER" \
  '        return touchesEndCut;' \
  '        return false;' RED

# 跨窗口不去重：同一实体被两个窗口各报一次时，去重的键里多带了一个窗口号，
# 于是两条观测落到不同的键上、各自保留下来。
# 注意这里**不能**把 putIfAbsent 换成 put 就算完事——两者写入的值完全相同，
# Map 里照样只剩一条，那是个空操作变异，第一版就是这么写的，跑出来是 GREEN。
# 空操作变异和「测试不敏感」都会让这一项显示 GREEN，但修法完全不同：
# 前者要改变异，后者要改测试。
run_case "破坏⒟：跨窗口去重的键多带了窗口号（同一实体出现两次）" "$WINDOWER" \
  '                collected.putIfAbsent(global.type().code() + '"'"'@'"'"' + gStart + '"'"'-'"'"' + gEnd, global);' \
  '                collected.putIfAbsent(global.type().code() + '"'"'@'"'"' + gStart + '"'"'-'"'"' + gEnd + '"'"'#'"'"' + w.index(), global);' RED

# 「模型必须自包含」这道关卡。它挡的是「一个看起来无害的模型包，让工具去读本机任意文件」。
# 守不住的后果不是脱敏不准，是**本地文件被读走**。
# 锚点漂移过一次：M3-2a 把 protobuf 游标抽成 ProtoWire 之后，
# `pos += (int) len` 搬到了 ProtoWire.java，这里只剩调用方；
# 同时外部数据那个 if 条件被折成两行（行宽限制），原来的一行锚点也不再命中。
# 两处的修法不同：前者换文件，后者换成两行的字面量。
# 教训是「抽公共类」这种重构必然动锚点，重构完就该重跑这几条，
# 而不是等下一次全量才发现它们已经不作废了。
run_case_multi "破坏⒠：不检查 TensorProto 的外部数据引用" \
  "$ONNXSCAN" '            if (f.number == TENSOR_EXTERNAL_DATA
                    && f.wireType == ProtoWire.WireType.LENGTH_DELIMITED) {' \
              '            if (false && f.number == TENSOR_EXTERNAL_DATA
                    && f.wireType == ProtoWire.WireType.LENGTH_DELIMITED) {' \
  "$ONNXSCAN" '            if (f.number == TENSOR_DATA_LOCATION && f.wireType == ProtoWire.WireType.VARINT' \
              '            if (false && f.number == TENSOR_DATA_LOCATION && f.wireType == ProtoWire.WireType.VARINT'

# 退化成「按字节扫字段号」的等价物：长度分隔字段的内容不再整段跳过，
# 于是会被当成后续字段继续解析。权重里 0x6A / 0x70 到处都是 → 每一个真实模型都被误拒。
# 判据是 OnnxSelfContainedTest.权重里长得像字段号的字节不会被误判 —— 那条测试存在的全部意义就在这。
run_case "破坏⒡：长度分隔字段不整段跳过（退化成字节扫描，真实模型全被误拒）" "$PROTOWIRE" \
  '                    pos += (int) len;' \
  '                    pos += 0;' RED

# ---- M3-1c：推理链路（字符表 / ORT 封装 / 坐标换算 / 滑窗契约）----
NERVOCAB="$ROOT/src/main/java/com/litemask/ner/CharVocab.java"
NERRUNNER="$ROOT/src/main/java/com/litemask/ner/OnnxRunner.java"
NERRECOG="$ROOT/src/main/java/com/litemask/ner/NerRecognizer.java"
NERSRC="$ROOT/src/main/java/com/litemask/ner/NerEntitySource.java"

# 跳过自包含闸门。ORT 会照着 external_data 里的路径去打开本机文件——
# 这不是脱敏不准，是本地文件被读走。
run_case "破坏⒦：OnnxRunner 加载前不做自包含校验" "$NERRUNNER" \
  '        OnnxSelfContained.assertSelfContained(onnx);' \
  '        if (false) { OnnxSelfContained.assertSelfContained(onnx); }' RED

# 坐标换算里的起点取错：拿「上一个码点」的偏移当成自己的。
# 只有当实体的<b>前一个字符是代理对</b>时才会错位，纯中文文档完全看不出来。
# 现场是「实体往前多吃一个字」，而且脱敏后的文档看着还挺正常。
#
# 注：三维张量 [1,T,C] → [T][C] 的降维<b>刻意不做变异实验</b>。
# 第一版写了「只接受二维输出」，跑出来是 INVALID——红灯全是抛异常，
# 没有一条断言失败。这条路径是 fail-closed 的：形状不认得就报错，
# 而「报错」本身不可能被断言判成「结果错了」。
# 按质量红线第 1 条，抛异常红不算「测试发现问题」，所以这里如实记为不测，
# 由 OnnxRunnerTest.logitsMatchExpectedTable 的正向断言守住。
run_case "破坏⒧：UTF-16 区间起点取上一个码点的偏移（实体往前多吃一个字）" "$NERVOCAB" \
  '        int utfStart = enc.offsets()[start];' \
  '        int utfStart = enc.offsets()[Math.max(0, start - 1)];' RED

# 把「左切边也丢」这条老 bug 原样装回去。
# 守不住的后果是实体<b>整个消失且零报错</b>：完整落在某个窗口里、却因为起点
# 恰好压在左切边上被丢，而前一个窗口看到的半截又因为终点贴右切边被丢。
# 变异体自带判断式，不依赖主类里的局部变量——第一版引用了已被删掉的
# touchesStartCut，直接编译失败，判 INVALID。
run_case "破坏⒩：实体起点贴着窗口左切边也丢弃（跨窗口实体会整个消失）" "$WINDOWER" \
  '        return touchesEndCut;' \
  '        return (gStart == w.start() && w.start() > 0) || touchesEndCut;' RED

# 坐标换算里那个最自然的错误：终点用「起点 + 1」。
# 只有当紧邻的是一个<b>代理对字符</b>时才会错位，所以纯中文文档完全看不出来——
# 而合同、邮件正文里表情并不罕见。现场是「含表情的文档里，实体后面多吃/少脱一个字」。
run_case "破坏⒨：UTF-16 区间终点按「偏移 + 1」算（代理对被截断或吞掉）" "$NERVOCAB" \
  '        int utfEnd = end < enc.size() ? enc.offsets()[end] : enc.utf16Length();' \
  '        int utfEnd = end < enc.size() ? enc.offsets()[end] + 1 : enc.utf16Length();' RED

# 不校验 overlap ≥ 最长实体。后果同样是静默漏脱，而且没有任何报错——
# 这个契约是 M3-1c 才推导出来并强制执行的。
run_case "破坏⒪：不校验「重叠宽度 ≥ 最长实体长度」" "$NERRECOG" \
  '        if (overlap < maxEntityChars) {' \
  '        if (overlap < 0) {' RED

# 不校验模型通道数与标签方案是否配套。「换了模型包忘了换标签方案」时，
# 解码器会安静地只读前 C 个通道，不报错，只是结果全错。
run_case "破坏⒫：不校验模型输出通道数与标签方案一致" "$NERRECOG" \
  '        if (actual != scheme.size()) {' \
  '        if (false) {' RED

# ============ M3-1e：NER 接入脱敏管线 ============

# 引擎早退条件只看规则、不看来源。后果：只启用 NER、不启用任何正则时
# 直接返回原文，一个字都不脱，界面上还显示「0 处命中」。
run_case "破坏⒬：引擎早退时忽略非正则来源（只开 NER 时整篇不脱）" "$ENGINE" \
  '        if (text == null || text.isEmpty() || (noRules && srcs.isEmpty() && noInjections)) {' \
  '        if (text == null || text.isEmpty() || noRules) {' RED

# 早退条件正确、但 collect 漏了来源调用。症状与上一条相近但成因不同：
# 这一条是「根本没调」，上一条是「调了但结果被扔掉」。
run_case "破坏⒭：引擎不收集非正则来源的命中" "$ENGINE" \
  '        collected.addAll(collectFromSources(text, srcs, ctx));' \
  '        collected.addAll(List.of());' RED

# AI 命中不过滤已占位化的片段。上一轮脱敏的结果里那些 <机构名1> 会被再脱一次，
# 正文套娃成 <机构名2><机构名2>，而报告一切正常。
run_case "破坏⒮：AI 命中不过滤已占位化的片段（令牌被套娃脱敏）" "$ENGINE" \
  '                if (isTokenLike(m.text())) {' \
  '                if (false) {' RED

# NER 优先级压到正则之下。「北京某某科技有限公司」会被只命中「北京」的规则
# 从左边缘切开，正文变成 <机构名1>某某科技有限<地名1>公司。
run_case "破坏⒯：NER 优先级压到正则之下（机构名被切成两截）" "$NERSRC" \
  '    public static final int SORT_ORDER = 900;' \
  '    public static final int SORT_ORDER = 1;' RED

# 越界区间不再拒绝、而是截断到文本长度。被截断的那一段等于没脱，
# 而报告里只会有「识别出一个实体」，看不出它短了几个字。
run_case "破坏⒰：越界的 NER 区间被截断而不是拒绝" "$NERSRC" \
  '        if (s.start() < 0 || s.end() > textLength || s.end() <= s.start()) {' \
  '        if (false) {' RED

# 识别失败降级成空列表（静默漏脱）。这是本项目最不能犯的一类错：
# 模型没加载、显存不够、native 缺了，界面上一片正常，文档却少脱了人名。
# 锚点用两行（带消息文字），不用单行的 `throw new NerRecognizer...`：
# 那个字符串在同文件里出现三次，只锚第一处等于赌它永远排在最前面。
run_case "破坏⒱：NER 推理失败时返回空列表而不是报错" "$NERSRC" \
  '            throw new NerRecognizer.NerInferenceException(
                    "NER 推理失败' \
  '            return List.of(); // mutated' RED

# 返回 null 的来源被当成「识别器坏了」而中止整篇。
# 判据反转：接口文档要求返回空列表，但一个第三方实现返回 null
# 也不该让整篇文档脱不出来。
run_case "破坏⒲：NER 来源返回 null 时中止整篇（一个坏来源毁掉全部脱敏）" "$NERSRC" \
  '        if (spans == null) {
            throw new NerRecognizer.NerInferenceException(
                    "NER 来源 " + name + " 对 " + text.length() + " 字文本返回了 null；"
                            + "「没识别出来」必须返回空列表，返回 null 会把故障说成「没有实体」");
        }' \
  '        if (spans == null) { spans = List.of(); }' RED

# 落败命中整条丢弃（回到 2026-10 之前的行为）。这个变异还原的是一个**真实泄漏**：
# 模型把拼音邮箱的头 4 个字认成人名时，邮箱那条被整条丢掉，只有被盖住的前半段
# 换成令牌，尾巴 gsan@corp.example.com 原样留在交付物里。
# 特征是「往返照样无损」——映射只记了令牌对应的原值，裸露的尾巴压根没进映射，
# 所以还原之后看不出问题，只有直接看脱敏件才发现邮箱还在。
run_case "破坏⑶：重叠消解把落败命中整条丢弃（残余片段裸露）" "$ENGINE" \
  '            pending.addAll(remainderOf(candidate, holes));' \
  '            continue; // mutated: 落败命中的非重叠部分无人脱敏' RED

# ---------------------------------------------------------------------------
# 来源贯穿整篇（M3-1f：把 EntitySource 接进五条格式管线）
# ---------------------------------------------------------------------------

# 会话丢掉来源：文档正文里一个人名都不脱，文件正常生成、报告说成功。
# 这就是「适配层写得对但没接线」的典型形态——引擎侧的 20 个用例全绿，
# 因为它们直接 new RedactionSession 并显式传来源。
run_case "破坏⒳：会话丢弃非正则来源（格式管线里 NER 完全不生效）" "$SESSION" \
  '        this.sources = sources == null ? List.of() : List.copyOf(sources);' \
  '        this.sources = List.of(); // mutated' RED

# 隐藏路径不跑来源：附件条目名、PDF 作者字段、xlsx 工作表名里的机构名原样留着。
# 注意这一条与 ⒴ 相反：⒳ 摘掉的是**所有**来源，⒴ 摘掉的是**隐藏路径**的来源，
# 两条分别对应「正文也不脱」和「只有正文脱、边角不脱」，故障表现完全不同。
run_case "破坏⒴：隐藏路径不跑来源（附件名/元数据里的机构名留着）" "$SESSION" \
  '                numbered ? this::nextOccurrence : null, sources,' \
  '                numbered ? this::nextOccurrence : null, java.util.List.of(),' RED

# 每个部件各自 new 一个 RedactionSession（而不是全篇共用一个）。
# 这条最阴：所有「同值同符」的单测照样全绿（两个分配器各自从头发号，
# 同一个值恰好拿到相同编号），文档也生成成功，坏的是**还原映射**——
# 同一个机构名在不同部件拿到不同令牌，用户拿还原件回去比对时对不上。
run_case "破坏⒵：docx 每个段落各建会话（跨段落编号与还原对不上）" "$DOCX" \
  '            RedactionSession session = new RedactionSession(plan.fullText(), rules, exclusions, sources,
                    plan.groups(), ContinuationScope.ANY);
            Rewrite rewrite = rewrite(pkg, session);' \
  '            Rewrite rewrite = rewrite(pkg, new RedactionSession(null, rules, exclusions, sources, true));
            RedactionSession session = new RedactionSession(plan.fullText(), rules, exclusions, sources,
                    plan.groups(), ContinuationScope.ANY);' RED

# ---------------------------------------------------------------------------
# M3-1d 砍 java.desktop：ProcessBuilder 开浏览器（jlink 实测省 14.51 MiB）
# ---------------------------------------------------------------------------
BROWSE="$ROOT/src/main/java/com/litemask/cli/BrowserLauncher.java"

# 把异常再抛出去，而不是吞掉：系统里没有 rundll32 时 ProcessBuilder.start() 抛的正是它。
#
# 两次写错的教训，都记在这里免得第三次再犯：
#  ① 写成"把 catch 换成 UncheckedIOException" → 编不过（IOException 是受检异常，
#     而方法没有 throws）。
#  ② 写成"在 catch 开头插一行 throw" → `unreachable statement`，
#     因为后面原有的 return 变得不可达。
# 正确形态是**整体替换 catch 体**，把 return 换成 throw。
# 这两种失败都是 INVALID 作废，**编译失败不是「测试发现了问题」**。
#
# 这条只有在"命令真的缺失"时才被咬住，所以测试里刻意加了可注入命令的接缝——
# 否则在 macOS 上跑时 rundll32 永远不被执行，这个变异会得到 GREEN。
run_case "破坏Ⓐ：命令不存在时把异常抛出去而不是返回 false" "$BROWSE" \
  '        } catch (IOException | RuntimeException ignored) {
            // 命令不存在 / 没有权限 / 平台不支持：都不该让服务起不来。
            // 地址已经打印在终端上，用户可以自己复制。
            return false;
        }' \
  '        } catch (IOException | RuntimeException ignored) {
            throw new IllegalStateException("mutated", ignored);
        }' RED

# 不限制协议：file:// 会被交给系统打开。本地文件读取面就这么被开了。
run_case "破坏Ⓑ：放行非 http/https 协议（file:// 也能交给系统打开）" "$BROWSE" \
  '        return u.startsWith("http://") || u.startsWith("https://");' \
  '        return true; // mutated' RED

# 撤掉地址校验守卫。
#
# **判据必须配一个能真的跑起来的命令**。用「不存在的命令」去测的话，
# 撤掉守卫后 open() 仍然返回 false（启动失败），测试照样全绿——空操作变异，
# 对照实验会给出一个假的 GREEN。第一版 Ⓒ 就踩了这个坑（那时候还叠了一层
# 根本不改变行为的 startsWith("-") 死代码，去掉它测试照样全绿）。
run_case "破坏Ⓒ：撤掉地址校验守卫（file:// 也会被真的启动）" "$BROWSE" \
  '        if (!isLaunchable(url) || cmd == null || cmd.isEmpty()) {
            return false;
        }' \
  '        if (cmd == null || cmd.isEmpty()) {
            return false;
        }' RED

# Windows 用错命令：open 在 Windows 上不存在，浏览器永远打不开。
run_case "破坏Ⓓ：Windows 用错命令（open 在 Windows 上不存在）" "$BROWSE" \
  '            return List.of("rundll32", "url.dll,FileProtocolHandler", url);' \
  '            return List.of("open", url);' RED

# ---------------------------------------------------------------------------
# M3-2 模型包加载器：七道闸，每道闸都要证明撤掉它测试就会红
# ---------------------------------------------------------------------------
PKG="$ROOT/src/main/java/com/litemask/ner/NerModelPackage.java"

# Ⓔ 撤掉「嵌入行数 == 词表字数 + 1」这道对账。
#
# 这是本项目**实际踩过**的 bug 的防线：词表少一个字 → 每个字符的 id 整体错位一格
# → 训练日志 F1 0.8397、产物重打分 0.1209、没有任何报错。
# 撤掉这道闸之后，一个词表与模型不配套的包会被正常加载，
# 而 NerModelPackageTest.rejectsEmbeddingRowMismatch 会红。
run_case "破坏Ⓔ：撤掉模型与词表的行数对账（错位一格也不报错）" "$PKG" \
  '        if (rows != vocab.idCount()) {' \
  '        if (false) {' RED

# Ⓕ 撤掉路径安全检查（**整簇**，不是只撤其中一道）。
#
# 与 zip-slip 同一类入口，只是这里走的是 JSON 而不是 zip 条目名：
# 清单里写 "../../../etc/passwd" 就该在加载模型时被挡住。
#
# 第一版这里只撤了「含路径分隔符就拒」那一条，结果拿到 GREEN。判别之后确认
# **不是空操作变异**：resolveInside 里还有两道重叠检查——
#   ① "." / ".." 直接拒；
#   ② resolve 之后拿 normalize().getParent() 与包目录比对。
# ①②把 ①②之外的所有穿越形态都挡住了，于是撤掉第三条仍然红不了。
# 这正是「纵深防御让单点变异天然抓不住」：单撤一层对面还有一层。
#
# 单点变异在这里证明不了任何东西，所以改成**复合变异**：三道一起撤。
# 复合变异的附带好处是三道都写进了脚本，将来删掉任何一道都看得见。
#
# （顺带补上了测试的覆盖缺口：含 NUL 的文件名在 Unix 上会被
# Path.resolve 抛成 InvalidPathException，而 "..\\x" 在 Unix 上是合法文件名、
# 在 Windows 上才是穿越。两个平台行为不同，必须在换平台之前就钉住。）
run_case "破坏Ⓕ：撤掉整簇路径安全检查（../ner.onnx 会被放行）" "$PKG" \
  "        if (name.indexOf('/') >= 0 || name.indexOf('\\\\') >= 0 || name.indexOf('\\0') >= 0) {
            throw new InvalidModelPackage(\"清单里的文件名不能包含路径分隔符: \" + name);
        }
        if (\".\".equals(name) || \"..\".equals(name)) {
            throw new InvalidModelPackage(\"清单里的文件名不能是 . 或 ..: \" + name);
        }
        Path resolved = directory.resolve(name);
        // 再兜一次 normalize 后的父目录，防止有构造能绕过上面的字符检查。
        if (!resolved.normalize().getParent().equals(directory)) {
            throw new InvalidModelPackage(\"清单里的文件名越出了模型包目录: \" + name);
        }" \
  "        Path resolved = directory.resolve(name);" RED

# Ⓖ 词表空行改成「跳过」而不是「拒绝」。
#
# 这就是 v3 那个 bug 的原始形态。加载端跳过空行，4586 个字只加载回 4585 个，
# 每个字符 id 从那行起错位一格，全过程零报错。
# 注意：即使这一条被撤掉，Ⓔ 那道行数对账仍然会挡住——这正是**纵深防御**的样子，
# 单撤一层对面还有一层。所以这里的两条是各自独立成立的：Ⓖ 撤掉的是
# "空行必须让加载失败"这个性质本身，Ⓔ 撤掉的是"对不上就不许加载"。
run_case "破坏Ⓖ：词表空行改成跳过而不是拒绝（复现 v3 的 id 错位）" "$PKG" \
  '                    if (i < text.length()) {
                        throw new InvalidModelPackage("词表第 " + lineNo + " 行是空行："' \
  '                    if (false) {
                        throw new InvalidModelPackage("词表第 " + lineNo + " 行是空行："' RED

# Ⓗ 摘要校验改成永远通过：传输损坏 / 包被替换都检测不出来。
run_case "破坏Ⓗ：摘要校验改成永远通过（坏文件也能加载）" "$PKG" \
  '        if (!actual.equalsIgnoreCase(expected)) {' \
  '        if (false) {' RED

# Ⓘ 不把导出时的 -1e4 还原成「不可能」。
#
# 训练侧屏蔽值是 -inf，导出成 ONNX 时换 -1e4（-inf 会被某些后端算成 NaN）。
# 不还原的话 SequenceDecoder 那条「全路径 -Infinity 就报错」的安全网会被绕过——
# 全是有限的 -1e4，isInfinite 不触发，于是模型包配错时不报错，
# 而是编出一条结构非法的路径。那就是静默错脱。
run_case "破坏Ⓘ：转移矩阵里的 -1e4 不还原成「不可能」" "$PKG" \
  '                if (!scheme.allows(from, to) || v <= EXPORT_MASK_VALUE) {' \
  '                if (false) {' RED

# Ⓙ 标签后缀不再查白名单：模型包可以凭空造出一条新的脱敏规则。
run_case "破坏Ⓙ：tagTypes 不查 EntityType 白名单（模型包自造脱敏规则）" "$PKG" \
  '            if (type == null) {' \
  '            if (false) {' RED

# ⑷ / ⑸ / ⑹ CLI 输出路径推导。
#
# 这块此前**完全没有对照实验**，而它守的是两个真实发生过的问题：
# 输出名丢掉目录（用户在原目录找不到结果，还以为脱敏失败了），
# 以及 .redacted 的位置不统一（txt 曾产出 合同.txt.redacted，后缀没了双击打不开）。
# 有测试 ≠ 验过测试有牙齿——这正是「绿灯必须自证」要管的。
CLI="$ROOT/src/main/java/com/litemask/cli/Main.java"
# 块 38 新增：serve 自己解析参数、自己认 --help，变异目标与 Main 不同。
SERVECMD="$ROOT/src/main/java/com/litemask/cli/ServeCommand.java"

run_case "破坏⑷：输出名把 .redacted 放到扩展名后面（命名规则又分叉）" "$CLI" \
  '        return parent == null ? Path.of(base + ".redacted" + ext) : parent.resolve(base + ".redacted" + ext);' \
  '        return parent == null ? Path.of(base + ext + ".redacted") : parent.resolve(base + ext + ".redacted");' RED

run_case "破坏⑸：输出名丢掉目录（结果写到当前目录，用户在原处找不到）" "$CLI" \
  '        return parent == null ? Path.of(base + ".redacted" + ext) : parent.resolve(base + ".redacted" + ext);' \
  '        return Path.of(base + ".redacted" + ext);' RED

run_case "破坏⑹：还原件名把 .restored 放到扩展名后面（docx 与 txt 又分叉）" "$CLI" \
  '        return parent == null ? Path.of(base + ".restored" + ext) : parent.resolve(base + ".restored" + ext);' \
  '        return parent == null ? Path.of(base + ext + ".restored") : parent.resolve(base + ext + ".restored");' RED

# ---------------------------------------------------------------------------
# PDF 版面（M3-3 §1.19）。这一组咬的是「文件能打开、文字抽得出来，但版面已经废了」——
# 它不会让任何一条已有的断言变红，因为往返文本仍然逐字一致、原文也确实没了。
# 唯一能看见它的是字形坐标与渲染出来的图。
run_case "破坏⑺：令牌不按槽宽压缩（窄槽位里的令牌直接盖住后面的字）" "$REWRITE" \
  '        double s = slotWidth / naturalWidth;' \
  '        double s = TOKEN_H_SCALE;' RED

run_case "破坏ⓑ：还原时不换算 TJ 调整数（整段文字偏出几个点）" "$REWRITE" \
  '                            numberRescale *= tokenH / backH;' \
  '                            numberRescale *= 1.0;' RED

run_case "破坏ⓒ：还原时不写回原 Tz（原文被按令牌的缩放画回去）" "$REWRITE" \
  '                && Math.abs(restoreState.hPercent() - op.hScale() * 100f) > 1e-4f;' \
  '                && false;' RED

run_case "破坏ⓓ：NER 不收短前缀（令牌 14~19 字符，把版面撑爆）" "$PDFTOKENS" \
  '            java.util.Map.entry("ner_person", "PER"),' \
  '            java.util.Map.entry("ner_person", "NER_PERSON"),' RED

run_case "破坏ⓔ：版面比较器恒返回「没问题」（版面自检变成永远为真的空转）" "$ADVSIM" \
  '        for (int i = 0; i < a.length; i++) {' \
  '        for (int i = 0; i < 0; i++) {' RED

# ---------------------------------------------------------------------------
# 测试夹具这一组（ⓕⓖⓗⓘ）：变异目标是 **src/test 的夹具**，不是产品代码。
#
# 为什么要单独开一组、并且必须写清楚：
# 这一轮新增的守卫测的是「测试资源与夹具行为」——仓库自带的字体子集在不在、
# 覆盖检查有没有真的拦住缺字、夹具产出的 PDF 有没有自带字体。
# 这些**没有产品代码参与**，唯一的证伪办法就是把夹具自己拆开，
# 看新加的守卫会不会红。把这四条混进产品代码那一组会让人误以为改了产品。
#
# 背景（都是实测，不是推理）：
#   - PDFBox 的 PDType0Font.load 读不了字体集（.ttc），抛 "'head' table is mandatory"；
#     Windows 上唯一的中文字体 msyh.ttc 正是字体集。
#   - embedSubset=true 在本夹具的用法下产出的是坏文件：没有 FontFile2、文本抽回来是乱码。
#   - 缺字时 PDFBox 自己也抛 IllegalArgumentException，所以守卫必须断言
#     「给出了自己的修复提示」，否则分不出是谁在报错。

run_case "破坏ⓕ：夹具不拦字体集（.ttc 直接扔底层异常给读日志的人）" "$PDFFIXTURES" \
  '        if (name.endsWith(".ttc")) {' \
  '        if (false) {' RED

run_case "破坏ⓖ：夹具不检查字体覆盖（缺字时不再点名是哪个字）" "$PDFFIXTURES" \
  '                assertCovers(font, line);' \
  '                // assertCovers(font, line);' RED

run_case "破坏ⓗ：夹具改用子集嵌入（产出的 PDF 不带字体，文本成乱码）" "$PDFFIXTURES" \
  '            return PDType0Font.load(doc, in, false);' \
  '            return PDType0Font.load(doc, in, true);' RED

run_case "破坏ⓘ：覆盖检查吞掉缺字（缺字清单永远是空的，检查变成空转）" "$PDFFIXTURES" \
  '                missing.add(String.format("U+%04X(%s)", (int) c, c));' \
  '                // 变异：吞掉缺字' RED

# ---------------------------------------------------------------------------
# 归组（§1.21）。一个标识符被多条规则分别认领时会被切成好几段，
# 呈现层把「原文里紧挨着」的那几段合成一行，**映射不变**。
# 这一组的守卫盯的是三条硬条件：严格相邻且同单元、每个成员的值全文档唯一、
# 拼接结果不撞已有的原值。少一条，用户就会在一个其实不相干的地方做决定。

run_case "破坏ⓚ：整个不做归组（一个地址在清单里仍然是好几行）" "$DOCSVC" \
  '        List<HitGrouping.Group> groups = HitGrouping.group(positioned);' \
  '        List<HitGrouping.Group> groups = new ArrayList<>();
        for (HitGrouping.PositionedHit p : positioned) {
            groups.add(new HitGrouping.Group(List.of(p)));
        }' RED

run_case "破坏ⓛ：不要求成员的值全文档唯一（勾选粒度开始骗人）" "$GROUPING" \
  '            if (valueCounts.getOrDefault(new ValueKey(h.ruleKey(), h.original()), 0) > 1) {
                return false;
            }' \
  '            if (false) {
                return false;
            }' RED

run_case "破坏ⓜ：不要求严格相邻（中间隔着字也拼成一条）" "$GROUPING" \
  '        return a.end() == b.start();' \
  '        return a.end() <= b.start();' RED

run_case "破坏ⓝ：不检查拼接结果是否撞上已有原值（界面上出现两行一样的值）" "$GROUPING" \
  '        return !allOriginals.contains(joined);' \
  '        return true;' RED

run_case "破坏ⓞ：归组行只排除一部分也不报错（留下保留了一半的标识符）" "$JOBSTORE" \
  '        verifyGroupClosure(job, excl);' \
  '        // 变异：不检查归组行是否整行排除' RED

run_case "破坏ⓟ：清单的 JSON 不回传 members（界面无法把一行拆成多条排除项）" "$JOBSTORE" \
  '            item.put("members", members);' \
  '            item.put("members", List.of());' RED

# 浏览器那半边没有自动化浏览器（明确的验证缺口），只有静态断言守着。
# 这一条证明那个静态断言不是死的：把展开逻辑拆掉，断言必须红。
run_case "破坏ⓠ：界面只发第一条成员（被勾的那几处里其余照常被脱）" "$APPJS" \
  '      members.forEach(function (m) {
        excluded.push({ ruleKey: m.ruleKey, original: m.original });
      });' \
  '      if (members.length > 0) {
        excluded.push({ ruleKey: members[0].ruleKey, original: members[0].original });
      }' RED

# ============ 映射出口与还原（GUI 侧的「可逆」） ============
#
# 这一组盯的是本工具最重要的承诺：**脱敏件能被还原回去**。
# 此前映射只活在 job.applied.mapping() 这块内存里，进程一退出就没了——
# 界面显示「脱敏成功」，而那份文件实际上**永久不可逆**，
# 且界面上看不出任何异常。六条变异逐条拆掉这个承诺的一个前提。

run_case "破坏ⓡ：应用之后不落映射件（脱敏件变成永久不可逆的）" "$JOBSTORE" \
  '        writeMapping(job, passphrase);' \
  '        // 变异：不落映射件' RED

run_case "破坏ⓢ：映射里不写原值（映射是个空壳，还原不回来）" "$JOBSTORE" \
  '        payload.put("mapping", job.applied.mapping());' \
  '        payload.put("mapping", Map.of());' RED

run_case "破坏ⓣ：还原时不换回原值（还原件里还全是令牌）" "$DOCSVC" \
  '        Map<String, String> m = mapping == null ? Map.of() : mapping;' \
  '        Map<String, String> m = Map.of();' RED

run_case "破坏ⓤ：淘汰任务时不删映射件（原值留在磁盘上而界面上已看不见）" "$JOBSTORE" \
  '        workspace.delete(job.mappingName);' \
  '        // 变异：映射件不删' RED

# 浏览器那半边仍然没有自动化浏览器（明确的验证缺口），只有静态断言守着。
# 这一条证明那些静态断言不是死的：把接线拆掉，断言必须红。
run_case "破坏ⓥ：界面上没有映射下载的入口（按钮点不动）" "$APPJS" \
  '  $("mappingBtn").addEventListener("click", function () {' \
  '  // 变异：没有映射下载入口' RED

run_case "破坏ⓦ：界面不读还原处数（永远显示「已还原 0 处」）" "$APPJS" \
  '        var n = r.headers.get("X-Litemask-Restored");' \
  '        var n = null;' RED

# ============ 长任务进度反馈与取消 ============
#
# 这一组盯的是「用户能看见进度、能中途放弃」这条承诺。
#
# 取消尤其要盯紧：取消如果只是打个标记，而原件与半成品还留在磁盘上，
# 用户以为「我没上传过」，实际却躺着一份敏感文档——
# 对一个脱敏工具来说，这是最不能出现的那类落差。
#
# 符号换了系列：小写圈码 ⓑ-ⓦ 上一组已经用完
#   （本组第 1 条原为 ⓐ，因与块 20 撞号，块 24 已改为 ⑺），
# 这里接着用 ⓧⓨⓩ，后面用 ❶-❽（U+2776 起）继续。

run_case "破坏ⓧ：取消不生效（点了取消，任务照跑到底）" "$TASKCTX" \
  '        cancelled.set(true);' \
  '        // 变异：取消不生效' RED

run_case "破坏ⓨ：取消后不删原件（用户以为没上传过，原文档还躺在磁盘上）" "$JOBSTORE" \
  '            workspace.delete(stored.diskName());' \
  '            // 变异：取消后不删原件' RED

run_case "破坏ⓩ：进度不递增（界面上的进度永远停在 1）" "$SESSION" \
  '        unitCounter++;' \
  '        // 变异：进度不递增' RED

run_case "破坏❶：总量报一个假数（界面画出 47%，而那个数是编的）" "$SESSION" \
  '        ctx.progress("脱敏", unitCounter, -1);' \
  '        ctx.progress("脱敏", unitCounter, 100);' RED

run_case "破坏❷：进度回调根本不调（界面永远停在「准备中」）" "$TASKCTX" \
  '            listener.onProgress(phase, done, total);' \
  '            // 变异：不报进度' RED

run_case "破坏❸：已跑完的任务被谎报成已取消（界面说取消了，磁盘上明明有成品）" "$TASKRUNNER" \
  '        if (task.status == Status.RUNNING) {
            task.context.cancel();
        }' \
  '        if (task.status == Status.RUNNING) {
            task.context.cancel();
        } else {
            task.status = Status.CANCELED;
        }' RED

run_case "破坏❹：任务表满了不拒绝，默默删掉一个正在跑的任务" "$TASKRUNNER" \
  '            throw new IOException("同时进行的任务太多，请等手上的任务跑完再试");' \
  '            // 变异：默默删掉一个正在跑的任务
            tasks.remove(tasks.keySet().iterator().next());' RED

run_case "破坏❺：任务跑完不回结果（界面上只剩一个空清单）" "$TASKRUNNER" \
  '            task.payload = JobStore.describe(job, job.scan, "scanned", 0);' \
  '            task.payload = null;' RED

run_case "破坏❻：界面改回同步接口（长任务上界面又装死了）" "$APPJS" \
  '      function () { return postFile("/api/task-scan", state.file); },' \
  '      function () { return postFile("/api/scan", state.file); },' RED

run_case "破坏❼：取消按钮没接上事件（点了毫无反应）" "$APPJS" \
  '  $("cancelBtn").addEventListener("click", function () {' \
  '  // 变异：取消按钮没接上事件' RED

# 浏览器那半边没有自动化浏览器，静态断言是唯一的守门人。
# 而静态断言全是字符串匹配——它们看不见「这段根本不是合法的 JavaScript」。
# 这一条模拟的就是那种破坏：脚本少一个右括号，浏览器把整个文件丢掉，
# 界面变成空白，而服务端那几十条单测**全绿**。
run_case "破坏❾：脚本少一个右括号（浏览器丢掉整个脚本，界面空白而服务端全绿）" "$APPJS" \
  '  function hideTask() {
    $("taskCard").hidden = true;' \
  '  function hideTask() {
    $("taskCard").hidden = true;
    if (false) {' RED

# 这一条是被**浏览器端到端**逼出来的（tools/e2e-browser.sh，真 Chrome 驱动）：
# 扫完之后「生成脱敏文件」按钮仍然是禁用的 —— 界面能扫、不能脱。
# 那几处代码单看都合理（showTask 禁用、hideTask 只在应用任务结束时放开），
# 服务端几十条单测全绿也没人发现；真正抓到它的是浏览器。
#
# 这里补的是**静态代理断言**：它只能证明「按钮的可用状态由 jobId 决定」这句话还在，
# 证明不了运行时真的对。真正的牙仍然是浏览器那条。
run_case "破坏❿：扫完之后应用按钮还是禁着（界面能扫、不能脱）" "$APPJS" \
  '    $("applyBtn").disabled = !state.jobId;' \
  '    $("applyBtn").disabled = true;' RED

# 同一个类名在 CSS 里出现两次（主规则 + 减少动态效果那条），
# 只改一处的话，样式表里还留着半个 .progress-fill.indeterminate，
# 断言照样过——所以两处一起改。
run_case_multi "破坏❽：不确定态的样式改了名（界面加的类名对不上，进度条成了一根死条）" \
  "$APPCSS" '.progress-fill.indeterminate {
  width: 38%;' '.progress-fill.x-indeterminate {
  width: 38%;' \
  "$APPCSS" '  .progress-fill.indeterminate {
    animation: none;' '  .progress-fill.x-indeterminate {
    animation: none;'

# ---------------------------------------------------------------------------
# 「上传先落盘」这一组（2026-10-20 补）：关掉「排队中的任务持有 64×256MB 上传字节」那个缺口。
#
# 这一组的四条都是**静默**失效形状：接口照样回 202，界面照样显示「已受理」，
# 而磁盘上的东西已经不对了。静态断言看不出来，只有产物层面的判据能咬住。
#
# ➊  暂存件落在会话目录之外 —— 进程退出时没人能清掉它（原文档留在磁盘上）
# ➋  任务表满时不清暂存件 —— 用户以为没上传成功，原文档却还在
# ➌  后缀白名单被放宽 —— 不支持的文件类型被收下，界面报「已受理」然后才失败
# ➍  上传体积不拦 —— 超限的文件被写进盘里
# ➎  超限报错说的是 256MB 那个常量 —— 用户被告知了一个错的数

run_case "破坏➊：暂存件落在会话目录之外（进程退出时没人能清掉它）" "$WORKSPACE" \
  '        Path tmp = sessionDir.resolve("upload-" + randomId() + ".tmp");' \
  '        Path tmp = Files.createTempFile("upload-", ".tmp");' RED

run_case "破坏➋：任务表满时不清暂存件（用户以为没上传成功，原文档却还在）" "$TASKRUNNER" \
  '            // 任务表满了：没人会来认领这份上传，它不能留在盘上。
            // 留着等于「用户以为没上传成功、原文档却还在」。
            workspace().discard(staged);
            throw e;' \
  '            // 任务表满了：没人会来认领这份上传，它不能留在盘上。
            // 留着等于「用户以为没上传成功、原文档却还在」。
            throw e;' RED

run_case "破坏➌：后缀白名单被放宽（不支持的文件类型被收下）" "$WORKSPACE" \
  '        if (!ALLOWED_EXTENSIONS.contains(ext)) {' \
  '        if (false) {' RED

run_case "破坏➍：上传体积不拦（超限的文件被写进盘里）" "$WORKSPACE" \
  '                    if (total > maxUploadBytes) {' \
  '                    if (false) {' RED

# ➎  超限报错里说的上限是那个 256MB 的静态常量 —— 用户被告知了一个错的数。
# 这条是被自己发现的一个真缺陷：上限改成实例参数之后，报错文案没跟着改。
# 判据是「报错里说的是不是实际上限」，不是「有没有报错」。
run_case "破坏➎：超限报错说的是 256MB 那个常量（用户被告知了一个错的数）" "$WORKSPACE" \
  '            super("文件超过 " + humanSize(limit) + " 上限（本次 " + humanSize(size) + "）");' \
  '            super("文件超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB 上限（本次 " + humanSize(size) + "）");' RED

# ---------------------------------------------------------------------------
# 「崩溃后的残留清理」这一组（2026-10-20 补）：关掉「进程被强杀后原文档留在盘上」那个缺口。
#
# 判据是**盘上少了什么**：死会话的目录和文档该被清掉，活会话的一个字都不能碰。
# 两条都是静默失效形状——接口照样回 200，界面照样正常，只有盘上的东西不对。
#
# ➏  清扫时不管锁直接删 —— 活着的实例被当成崩溃残留，用户文档连根拔起
# ➐  清扫什么都不删 —— 崩溃残留原样留在盘上，正是这块要修的那个缺口

# ➏  主人还活着（锁被占着）时，清扫必须**跳过**。
# 变异体只去掉那句 continue：跳过没了，删除照做，于是活实例的会话目录被删。
# 红灯必须是断言失败（活实例的目录还在），不是抛异常——
# 所以下面那个 `held != null` 的保护不能省：它保证删到一半也不会 NPE。
run_case "破坏➏：清扫时不管锁直接删（活着的实例被当成崩溃残留）" "$WORKSPACE" \
  '            if (!主人已死) {
                // 主人在位：它的会话目录和文档正在被使用，一个字都不能碰
                closeQuietly(channel);
                continue;
            }' \
  '            if (!主人已死) {
                // 主人在位：它的会话目录和文档正在被使用，一个字都不能碰
                closeQuietly(channel);
            }' RED

# ➐  清扫要真的把死会话的目录删掉。
# 变异体把删除目标换成一个不存在的名字：编译得过、跑得动，但什么也没删。
run_case "破坏➐：清扫什么都不删（崩溃残留原样留在盘上）" "$WORKSPACE" \
  '                deleteRecursively(sessionsDir.resolve(sid));' \
  '                deleteRecursively(sessionsDir.resolve(sid + ".stale"));' RED

# ---------------------------------------------------------------------------
# 模型包独立安装包（M4）。这一组咬住的是「把一份不受信任的压缩文件铺到磁盘上，
# 然后让它生效」这件事里最容易出事的三处。
#
# 为什么这三处值得单独一组：它们全都是**静默失败**型缺陷——
# 越界不拦不会报错，校验失败也装上不会报错，换装失败不把旧的放回去也不会报错。
# 它们的代价分别是：文件被写到用户机器上别处、一份坏模型包被当成好模型包用、
# 用户原来那份能用的模型包凭空消失。全都是「系统看起来一切正常」的那种坏。

# ➑  越界条目必须被拦。
# 变异体去掉那句 throw：条目路径不再被检查，一个 "../" 条目就能把文件写到
# 安装目录之外去。红灯必须是断言失败（文件真的写到了外面、且没抛错），
# 不是编译失败。
run_case "破坏➑：越界条目不拦（zip slip 能把文件写到安装目录之外）" "$INSTALLER" \
  '                if (!out.startsWith(root)) {
                    throw new IOException("模型包里有越出安装目录的路径条目：" + name);
                }' \
  '                if (!out.startsWith(root)) {
                    // 变异体：越界条目不拦
                }' RED

# ➒  校验不通过必须装不上。
# 变异体把两个 catch 都改成「记一个假 id 继续走」：一份坏模型包会被当成好的装上去，
# 而用户从此用着一个摘要对不上的模型——表现是识别结果大面积离谱却不报错。
run_case "破坏➒：校验失败也照样装上（坏模型包被当成好模型包用）" "$INSTALLER" \
  '            } catch (IOException e) {
                // 默认校验器已经把消息写成人话了，原样带走。
                throw e;
            } catch (Exception e) {
                throw new IOException("模型包校验不通过：" + e.getMessage(), e);
            }' \
  '            } catch (IOException e) {
                // 变异体：校验失败也照样装上
                modelId = "未校验";
            } catch (Exception e) {
                modelId = "未校验";
            }' RED

# ➓  换装失败必须把原来那份放回去。
# 变异体去掉「放回去」那一步：装失败时用户原来那份模型包就躺在 backup 目录里，
# 不在原位了。红灯必须是断言失败（原来那份不在原位），不是编译失败。
# ➔  文件头必须查。
# 变异体去掉那句 requireZip：用户传错文件（把 .txt / .onnx 当成模型包）时，
# 错误消息变成「模型包是空的」——方向指错了，用户会以为包坏了，去查包，
# 而真正的原因是「你传的根本不是 zip」。
run_case "破坏➔：不查文件头（传错文件时只说「包是空的」，用户会查错方向）" "$INSTALLER" \
  '        requireZip(packageFile);' \
  '        // 变异体：不查文件头' RED

# ➕  非法的条目名要变成人话。
# 变异体去掉那个 catch：条目名里带 NUL 时，Path.resolve 抛的是
# InvalidPathException（**非受检**），它会绕过 install 的 IOException 契约，
# 一路冒到最外层变成一句 JVM 的原始报错。
# 红灯必须是断言失败（assertThrows 等的是 IOException，收到的却是别的类型）。
run_case "破坏➕：非法条目名不拦（NUL 抛非受检异常，一路冒到最外层）" "$INSTALLER" \
  '                Path out;
                try {
                    out = root.resolve(name).normalize();
                } catch (InvalidPathException e) {
                    // 条目名里带 NUL 之类的非法字符时，Path 会抛**非受检**异常，
                    // 那会绕过 install 的 IOException 契约，一路冒到最外层变成一句
                    // JVM 的原始报错。这里换成人能看懂的话。
                    throw new IOException("模型包里有非法的条目名（含有不能出现在路径里的字符）：" + name, e);
                }' \
  '                Path out = root.resolve(name).normalize();' RED

run_case "破坏➓：换装失败时不把旧的放回去（用户原来那份凭空消失）" "$INSTALLER" \
  '                if (hadOld) {
                    try {
                        mover.move(backup, target);
                    } catch (IOException restoreFailure) {
                        // 放不回去是**最坏**的一种：用户原来那份模型包现在躺在 backup 里，
                        // 不在原来的位置了。这件事必须让用户知道——
                        // 失败时没有 Result 可言，所以它必须进异常消息（消息会直接显示给用户）。
                        // 不说的话，用户会以为「装失败 = 什么都没变」，
                        // 实际上他原来那份已经不在原位了。
                        throw new IOException(e.getMessage()
                                + "；而且原来那份模型包没能放回原位，它现在在 " + backup
                                + "，请手动把它挪回 " + target, e);
                    }
                }' \
  '                // 变异体：换装失败时不把旧的放回去' RED

run_case "破坏➖：装新版本时把旧版本删掉（用户就再也回不去了）" "$STORE" \
  '            String previousKey;
            try {
                previousKey = activate(dest);
            } catch (IOException e) {' \
  '            String previousKey;
            try {
                previousKey = activate(dest);
                // 变异体：把换下来的旧版本删掉，不留历史
                if (previousKey != null) {
                    ModelPackageInstaller.deleteRecursively(versionsDir.resolve(previousKey));
                }
            } catch (IOException e) {' RED

run_case "破坏➗：版本名对不上也当成功（用户以为改的是自己指定的那一版）" "$STORE" \
  '        if (hits.isEmpty()) {
            throw new IOException("没有这个版本：" + s
                    + (candidates.isEmpty() ? "（现在一个历史版本都没有）"
                    : "。现有版本：" + String.join("、", candidates.stream().map(Version::key).toList())));
        }' \
  '        if (hits.isEmpty()) {
            // 变异体：对不上也当成功，随便挑一个
            if (candidates.isEmpty()) {
                throw new IOException("没有这个版本：" + s);
            }
            return candidates.get(0);
        }' RED

run_case "破坏➘：删除时拿用户输入拼路径（../ 能删到历史目录外面去）" "$STORE" \
  '            Version target = resolve(selector, candidates);
            if (!ModelPackageInstaller.deleteRecursively(target.dir())) {
                throw new IOException("删不掉这个版本（可能正被别的进程占用）：" + target.dir());
            }' \
  '            // 变异体：直接拿用户输入拼路径去删
            Path evil = versionsDir.resolve(selector).normalize();
            if (!ModelPackageInstaller.deleteRecursively(evil)) {
                throw new IOException("删不掉这个版本（可能正被别的进程占用）：" + evil);
            }' RED

run_case "破坏➙：回退时切到最旧的那一版（而不是最近换下来的）" "$STORE" \
  '            Version target;
            if (selector == null || selector.isBlank()) {
                target = history.get(0);
            } else {' \
  '            Version target;
            if (selector == null || selector.isBlank()) {
                // 变异体：回退到最旧的那一版
                target = history.get(history.size() - 1);
            } else {' RED

run_case "破坏➚：调用前缀写死成一个固定名字（这台机器上未必有那个可执行文件）" "$CLIIV" \
  '        return prefix.length() > MAX_PREFIX ? "" : prefix;' \
  '        // 变异体：不管用户是怎么调起工具的，一律写死一个程序名
        return "litemask";' RED

run_case "破坏➛：建议给用户的命令写死程序名（用户照着敲就是 command not found）" "$CLIIV" \
  '        if (prefix == null || prefix.isBlank()) {
            return sub;
        }
        return prefix + " " + sub;' \
  '        // 变异体：写死一个程序名，忽略实际调用方式
        return "litemask " + sub;' RED

# ---------------------------------------------------------------------------
# 第 7 块：模型包的签名与信任锚。
#
# 【这一组咬住的是什么】
#   模型包是**独立分发**的产物。一个「自洽的坏包」（清单合法、模型合法、
#   但故意漏识别人名）能通过现有的**全部**校验：格式对、摘要对、模型能加载。
#   装上去之后工具照常跑、照常说「脱敏完成」，而人名就留在输出文件里了。
#
#   旁挂的 .sha256 挡不住这个——摘要和包是一起分发的，一起换掉就过了。
#   签名挡得住：它把「来源」和「包」绑在一起，而私钥不在分发链路上。
#
#   所以这一组要证明的是**四道门各自都真的在**：
#   算法不能降级、签名绑定到具体哪个包、钥匙必须在信任锚里、装之前必须验。
#   少一道，那道门后面的攻击就是通的。

run_case "破坏➜：不检查签名算法（算法能降级就不能拿来当信任依据）" "$MODELTRUST" \
  '        if (!ModelPackageSignature.ALG.equals(env.alg())) {
            throw new IOException("签名算法不是 " + ModelPackageSignature.ALG
                    + "（是 " + env.alg() + "）：不接受。算法能降级就不能拿来当信任依据。");
        }' \
  '        // 变异体：不看算法名了' RED

# 摘要比对在**两处**（ModelTrust 与 ModelSignature），所以这里必须两处一起拆。
#
# 【为什么不能只拆一处】只拆 ModelTrust 那一处 → ModelPackageSignature.verify
# 里的比对还在，结果是「拒绝，但理由从『签的是另一个包』变成『验签不过』」——
# 测试仍然红，但红在另一条断言上，而且这条变异其实**没有**真的打开缺口
# （签名仍然绑在包上）。两处一起拆，才是「签名不再绑定到具体哪个包」这个真缺口。
run_case_multi "破坏➝：签名不再绑定到具体哪个包（摘要比对在两处，一起拆）" \
  "$MODELTRUST" '        String actual = ModelPackageSignature.sha256Hex(packageFile);
        if (!actual.equalsIgnoreCase(env.sha256())) {
            throw new IOException("签名覆盖的是另一个包：签名里的 sha256 是 " + env.sha256()
                    + "，这个包的是 " + actual + "。拿错包了，或者包在签名之后被改过。");
        }' \
              '        // 变异体：不比摘要' \
  "$MODELSIG" '        if (!hex(digest).equalsIgnoreCase(env.sha256())) {
            // 摘要对不上时**先**报摘要：签的是另一个包，
            // 拿这份签名去验签当然也过不了，但用户真正要知道的是「你拿错了包」。
            return false;
        }' \
              '        // 变异体：不比摘要'

run_case "破坏➞：不查这把钥匙在不在信任锚里（拿任意一把去验）" "$MODELTRUST" \
  '        ModelPackageSignature.Anchor matched = null;
        for (ModelPackageSignature.Anchor a : anchors) {
            if (a.keyId().equals(env.keyId())) {
                matched = a;
                break;
            }
        }' \
  '        // 变异体：不看 keyId，随便拿一把信任锚去验
        ModelPackageSignature.Anchor matched = anchors.isEmpty() ? null : anchors.get(0);' RED

run_case "破坏➟：装模型包之前根本不验签" "$STORE" \
  '        if (!allowUnsigned) {
            // 先验签再动磁盘：验签不过时，这台机器上还没有多出任何东西。
            trustChecker.check(zip);
        }' \
  '        // 变异体：装之前不验签' RED

# ---------------------------------------------------------------------------
# 块 8：卸载清理。
#
# 【这一组要证明的是三件不同的事】
#   1. 清单**完整**：漏掉一项，那个目录就会留在磁盘上——
#      而「卸载说清了、但有个目录还在」是**不会自己喊出来**的失败。
#   2. **分级**对：工作区（脱敏产物与映射文件）是用户数据，
#      默认模式删它 = 用户还没取走的文件被静默删掉。
#   3. **不越界**：清的位置不能是「用户主目录」这类不属于本工具的东西——
#      这是最危险的一类变异：删掉的是用户自己的文件。
#
# 【为什么第 3 条要单独测】
#   前两条测的是「该删的删了没」，第 3 条测的是「不该删的删没删」。
#   后者后果更重：用户的合同、照片被 uninstall 清掉。
run_case "破坏➠：清单里漏掉信任锚（卸载说清了，但那个目录还留在盘上）" "$CLEANER" \
  '        out.add(new Target(userHome.resolve(com.litemask.ner.ModelTrust.USER_ANCHORS_SUBPATH),
                Scope.APP, "信任锚（你加过的公钥）"));' \
  '        // 变异体：清单里漏掉信任锚' RED

run_case "破坏➡：工作区被当成应用数据（默认就删掉用户还没取走的映射文件）" "$CLEANER" \
  '        out.add(new Target(workspaceDir, Scope.USER, "任务工作区（脱敏产物与映射文件）"));' \
  '        out.add(new Target(workspaceDir, Scope.APP, "变异体：工作区被当成应用数据"));' RED

run_case "破坏➢：清的位置写成用户主目录本身（把用户自己的文件一起删了）" "$CLEANER" \
  '        out.add(new Target(userHome.resolve(com.litemask.ner.NerModelRegistry.USER_MODEL_SUBPATH),
                Scope.APP, "模型包（当前生效的那份与历史版本）"));' \
  '        out.add(new Target(userHome, Scope.APP, "变异体：清到用户主目录本身"));' RED

# ---- 第 9 块：长任务超时 ----
#
# 【为什么超时也要有对照实验】
#   超时的三条前提，任何一条被撤掉，后果都不同：
#     撤掉「真的停」   → 任务照常跑完，用户等来的是一份「本该被掐掉」的结果；
#     撤掉「与取消分开」→ 用户以为是自己按的取消，去找根本不存在的「误操作」原因；
#     撤掉「排队不算」  → 任务刚排上队就被掐掉，用户完全看不懂发生了什么。
#   而且这三条的失败形态**不一样**（终态变成 done / 变成 canceled / 提前变 timed_out），
#   所以必须逐条抓「失败用例名」核对，不能只看「红了几条」。

run_case "破坏➣：超时不真的停（只打标记不取消，任务照常跑完）" "$TASKRUNNER" \
  '            t.context.cancel();' \
  '            // 变异体：超时不真的停' RED

# 【这一条是 2026-10-20 锚点体检查出来的遗留问题，不是本轮新引入的】
#   原来锚点只有那一行，而那一行在 TaskRunner 里出现**两次**：
#   扫描路径与「应用」路径各有一处「取消时把终态定成 canceled / timed_out」。
#   变异函数替换的是**第一处**（扫描路径），于是这条实验只盖住了扫描那一条路，
#   「应用」路径的终态没人盯。锚点体检会把它判成「变异点不确定」。
#
#   改成两处一起拆（_run_case_multi）：这句话在两条路径上都必须成立，
#   只拆一处的话，实验比它声称的弱——而这类「只测了一半」最难发现，
#   因为实验照样是绿的。
run_case_multi "破坏➤：超时的终态偷懒复用 canceled（用户以为是自己按的；扫描与应用两条路径一起拆）" \
  "$TASKRUNNER" '            task.status = Status.DONE;
            task.message = "扫描完成";
        } catch (TaskContext.CanceledException e) {
            task.status = task.timedOut ? Status.TIMED_OUT : Status.CANCELED;' \
              '            task.status = Status.DONE;
            task.message = "扫描完成";
        } catch (TaskContext.CanceledException e) {
            task.status = Status.CANCELED; // 变异体：超时终态复用 canceled' \
  "$TASKRUNNER" '            task.status = Status.DONE;
            task.message = "应用完成";
        } catch (TaskContext.CanceledException e) {
            task.status = task.timedOut ? Status.TIMED_OUT : Status.CANCELED;' \
              '            task.status = Status.DONE;
            task.message = "应用完成";
        } catch (TaskContext.CanceledException e) {
            task.status = Status.CANCELED; // 变异体：超时终态复用 canceled'

run_case "破坏➦：时限从提交算起（排队等待也算进时限）" "$TASKRUNNER" \
  '            this.deadlineAt = timeoutMs > 0 ? now + timeoutMs : 0;' \
  '            this.deadlineAt = timeoutMs > 0 ? this.startedAt + timeoutMs : 0; // 变异体：时限从提交算起' RED

# ---- 第 10 块：PDF 的位置信息与归组 ----
#
# 【这一组要证明的是两件不同的事】
#   1. 位置信息**真的被记下来了**：页码与文本块是查得到的，
#      不是一句「大概知道在哪」；
#   2. 位置信息**真的被用上了**：归组靠的是「同一个文本块」这个判断，
#      拆掉它，PDF 就退回「一串不相干的命中各占一行」。
#
# 【为什么「丢掉单元标识」是有效变异】归组的三条硬条件里，
# 「严格相邻且**同一个文本单元**」是第一条。PDF 的单元标识是「内容流 + 文本块」，
# 把它丢掉就等于把两段不相干的文本当成同一段——
# 那正是「把两页正文接成一个根本不存在的标识符」这种事故的形状。
#
# 【为什么不在引擎里直接「不记命中」当变异】那样会触发引擎里那条
# 「命中位置数与改动数对不上」的不变式检查，测试是**抛异常**红的——
# 而抛异常红不等于「断言发现了问题」（脚本会判 INVALID）。
# 所以这里的变异都打在「记下来了但记错了」这一侧：
# 红灯必须来自断言。

run_case "破坏➥：PDF 的命中不记页码（位置退化成一句不含真实页码的话）" "$PDFRED" \
  '                        hits.add(new Hit(unit.id(), blockLocator(blockIndex), c.original(),' \
  '                        hits.add(new Hit("第 ? 页", blockLocator(blockIndex), c.original(),' RED

# 锚点必须**唯一**：docx/xlsx/pptx 三个分支的 raws.add 长得一模一样，
# 只取那三行会命中 3 次，变异点就不确定了（锚点体检会判它有问题）。
# 所以这里带上 PDF 分支独有的那一行（for (PdfRedactor.Hit h : r.hits())）。
run_case "破坏➧：PDF 的命中不参与归组（内容流单元标识被丢掉）" "$DOCSVC" \
  '                for (PdfRedactor.Hit h : r.hits()) {
                    raws.add(new Raw(h.ruleKey(), h.original(), h.token(),
                            h.part() + " " + h.locator(),
                            h.unit(), h.start(), h.end(), h.occurrence()));' \
  '                for (PdfRedactor.Hit h : r.hits()) {
                    raws.add(new Raw(h.ruleKey(), h.original(), h.token(),
                            h.part() + " " + h.locator(),
                            null, h.start(), h.end(), h.occurrence()));' RED

# ------------------------------------------------------------------ 第 11 块：出现序号
#
# 【这一块为什么必须有变异】
#   「界面上勾的第 N 处」= 「引擎判的第 N 次」，靠的是**同一个数**从引擎一路传到界面。
#   任何一环把这个数弄错，用户勾的就是别的地方，而且程序看起来一切正常。
#   两条变异分别打在这条链的**两头**，红灯都来自断言（不是抛异常、不是编译失败）。
#
# 破坏➨：引擎报出的序号变成常量 0。
#   引擎内部判「第 N 次」用的仍是真序号（那条通路没动），
#   所以脱敏行为不变——只是**报给界面的数**全成了 0。
#   → 界面上的「第 1 处」永远指向第 0 次，用户勾了第 2 处，文件里留的是第 0 处。
run_case "破坏➨：引擎报出的出现序号变成常量 0" "$ENGINE" \
  '                    tokenByStart.get(match.start()), match.start(), match.end(), match.ruleKey(),
                    match.occurrence()));' \
  '                    tokenByStart.get(match.start()), match.start(), match.end(), match.ruleKey(),
                    0));' RED

# 破坏➩：下发给界面的位置说明里不带序号。
#   服务端照常脱敏，只是清单里少了一个字段——
#   界面拿不到序号就挂不出「只保留这一处」的勾选框，那个入口整个消失。
#   → 界面上的「位置」还在，但它不再能指出「这是第几次」。
run_case "破坏➩：下发的位置说明里丢掉出现序号" "$JOBSTORE" \
  '                one.put("occurrence", l.occurrence());' \
  '                one.put("occurrenceIgnored", l.occurrence());' RED

# 破坏➪：界面提交时不带出现序号（逐处保留悄悄变成整条保留）。
#   前两条变异打在**服务端**那一头（引擎报数、服务端下发），
#   这一条打在**客户端**这一头：app.js 组装提交报文时把 occurrence 漏掉。
#   服务端对「省略」的理解是 ANY = 整条不脱敏 →
#   用户勾的是「只保留第 1 处出现」，文件里留下的是**这个值的所有出现处**。
#   界面看不出任何异常（提示语只报「已保留 N 个值」）。
#   → 红灯必须来自断言（WebUiTest.逐处排除带序号），
#     不是抛异常、也不是编译失败 —— 否则会被判 INVALID。
run_case "破坏➪：界面提交时不带出现序号（逐处保留变成整条保留）" "$APPJS" \
  '    occBoxes().filter(function (o) { return o.checked; }).forEach(function (o) {
      excluded.push({
        ruleKey: o.dataset.ruleKey,
        original: o.dataset.original,
        occurrence: Number(o.dataset.occurrence)
      });
    });' \
  '    occBoxes().filter(function (o) { return o.checked; }).forEach(function (o) {
      excluded.push({
        ruleKey: o.dataset.ruleKey,
        original: o.dataset.original
      });
    });' RED

# ============ 块 22：界面上「只保留第 N 处」要能选到每一次出现 ============
#
# 这一组盯的是块 22 补上的那个洞：服务端早就支持位置级排除
# （序号校验比的是 hit.occurrences()，不是那份被截断的位置清单），
# 但界面上「出现处」那一格以前只列 3 条位置 → 第 4 次及以后的出现
# **在界面上根本没有入口**：用户看得见「出现 10 次」，却没法只保留第 7 次。
#
# 四条变异各打在一处：铺行的依据、序号的来源、没有位置说明的那几次、折叠规则。
# 变异体打的是 app.js / app.css（资源）→ 编译永远成功 →
# 红灯只可能来自 OccurrencePickerTest 的**断言**，那正是「测试发现了问题」。

# 破坏Ⓨ：界面按**位置条数**铺行。
#   位置说明最多 20 条（服务端 DocumentService.MAX_LOCATORS），出现次数可以更多 →
#   按位置条数铺行，第 21 次以后在界面上又没有入口了。
#   → 红灯必须来自 OccurrencePickerTest.按出现序号铺行
run_case "破坏Ⓨ：界面按位置条数铺行（第 21 次以后选不到）" "$APPJS" \
  '        for (var oi = 0; oi < total; oi++) {' \
  '        for (var oi = 0; oi < locs.length; oi++) {' RED

# 破坏Ⓩ：逐处勾选框的序号取模。
#   序号必须**原样**用引擎分配的那个号（行号 oi）。自己重数、取模，
#   界面上「第 4 次」就会指到别的位置上去——错在密的那一侧。
#   → 红灯必须来自 OccurrencePickerTest.每行都挂勾选框
run_case "破坏Ⓩ：逐处勾选框的序号取模（第 4 次指到别处）" "$APPJS" \
  '            ob.dataset.occurrence = String(oi);' \
  '            ob.dataset.occurrence = String(oi % 2);' RED

# 破坏➀：没有位置说明的那几次不占行。
#   服务端把位置说明截断到 20 条之后，剩下的几次**照样要能选**——
#   位置说明是给人看的，序号才是机器认的。
#   → 红灯必须来自 OccurrencePickerTest.没位置说明的也占一行
run_case "破坏➀：没有位置说明的那几次在界面上不占行（选不到）" "$APPJS" \
  '            locText || ("第 " + (oi + 1) + " 次出现（没有位置说明）")));' \
  '            locText));' RED

# 破坏➁：折叠规则失效（折叠起来的那几处也全都显示出来）。
#   折叠的是**显示**，不是「能不能选」——折叠起来的那几处照样挂勾选框、
#   照样能被勾上。把 display:none 去掉，用户看到的是二十条位置糊成一片。
#   → 红灯必须来自 OccurrencePickerTest.展开入口与样式
run_case "破坏➁：折叠规则失效（折叠起来的那几处也全都显示）" "$APPCSS" \
  'table.hits td.num .locs .loc.locHidden { display: none; }' \
  'table.hits td.num .locs .loc.locHidden { display: flex; }' RED

# ------------------------------------------------------------------ 第 12 块：模型包下载
#
# 【这一块为什么必须有变异】
#   模型包是**独立分发的产物**，「用户从哪儿拿到它」是这一块的全部。
#   下载这一层有一堆「看起来对、其实漏了」的地方：协议降级、体积写爆磁盘、
#   签名文件取不到就当没事、下载来的东西不验签就装上去。
#   它们的共同形状是：**程序看起来一切正常，而用户拿到的是一份没脱干净的东西**。
#   每一条变异打在这一层，红灯必须来自断言（不是抛异常、不是编译失败）。
#
# 破坏➫：重定向的「https 降到 http」检查被去掉。
#   签名能挡住内容被换，挡不住「谁在下载模型包」被看见。
#   少了这道检查，一个中间人就能把发布方的下载点改成明文。
#   → 红灯必须来自 ModelDownloadTest.重定向不许从https降到http
#     （assertThrows 失败：期望抛 IOException，结果什么都没抛）。
run_case "破坏➫：重定向不再拒绝从 https 降到 http" "$FETCHER" \
  '        if ("https".equalsIgnoreCase(from.getScheme()) && "http".equals(lower)) {' \
  '        if (false && "https".equalsIgnoreCase(from.getScheme()) && "http".equals(lower)) {' RED

# 破坏➬：体积上限被去掉。
#   服务器（或中间人）可以一直发，磁盘就被写满了。
#   机器上还有别的东西要写，满盘之后**别的程序**会开始出错——
#   而这件事与用户「装一个模型包」毫无关系，却由用户承担。
#   → 红灯必须来自 ModelDownloadTest.体积超上限时报错且不装
#     （assertThrows 失败：期望超限时抛 IOException，结果整个下载成功了）。
run_case "破坏➬：下载不再有体积上限" "$FETCHER" \
  '                if (total > maxBytes) {' \
  '                if (false && total > maxBytes) {' RED

# 破坏➭：协议白名单被去掉。
#   只放行 http/https 是为了「从网上取模型包」这件事有明确的形状。
#   放开之后 file:// 也能进——本地文件被当成远程包，
#   而失败原因会变得极难解释。
#   → 红灯必须来自 ModelDownloadTest.非http协议被拒 与
#     ModelDownloadTest.地址检查拒掉缺协议的与缺主机的。
run_case "破坏➭：不再限制只走 http / https" "$FETCHER" \
  '        if (!"http".equals(lower) && !"https".equals(lower)) {
            throw new IOException(what + "只支持 http 或 https（这里是 " + scheme + "）：" + raw);
        }' \
  '        if (false) {
            throw new IOException(what + "只支持 http 或 https（这里是 " + scheme + "）：" + raw);
        }' RED

# 破坏➮：下载来的包**跳过验签**就装上去。
#   这一条是这一块最要紧的判定：下载来的东西来源不由用户掌握，
#   恰恰是最需要验签的。跳过验签之后，一个中间人（或者一个坏掉的发布点）
#   递过来的包会被照装不误，而工具照样说「脱敏完成」。
#   → 红灯必须来自 ModelDownloadTest.钥匙不受信时装不上 与
#     ModelDownloadTest.包被改过时装不上。
run_case "破坏➮：下载来的模型包跳过验签就装上" "$MODELCMD" \
  '                r = stores.create(dir).install(fetched.packageFile(), false);' \
  '                r = stores.create(dir).install(fetched.packageFile(), true);' RED

# 破坏➯：临时下载目录不清理。
#   临时目录里躺着刚下载的 zip 与签名（模型包十几 MB）。
#   留着不只是占磁盘：下一次下载用的是**同一个目录**，
#   里面若有同名文件，「这次下到的到底是哪一份」就不确定了。
#   → 红灯必须来自 ModelDownloadTest.下载用的临时目录不留下残留。
run_case "破坏➯：下载用的临时目录不清理" "$MODELCMD" \
  '            ModelPackageInstaller.deleteRecursively(tmp);' \
  '            // 变异体：故意不清理临时下载目录' RED

# 破坏➱：跨单元续接整体失效。
#   身份证被段落／单元格切断以后，两段碎片各自都匹配不上任何规则。
#   续接一失效，碎片就原样留在交付物里，而工具照样报告「脱敏成功」——
#   这是**泄漏**，不是标签错。
#   → 红灯必须来自 CrossUnitContinuationTest 与三个格式的跨段用例。
run_case "破坏➱：跨单元续接整体失效" "$CROSSUNIT" \
  '        if (groups == null || groups.isEmpty() || rules == null || rules.isEmpty()) {' \
  '        if (groups == null || groups.isEmpty() || rules == null || rules.isEmpty() || true) {' RED

# 破坏➲：续接时不再过校验器。
#   校验位是「这串数字确实是一个完整身份证」的**权威证据**。
#   去掉它以后，任何两段数字凑够长度就算续接，误判面从千分之几放大到百分之几。
#   → 红灯必须来自 CrossUnitContinuationTest.校验位不对：拼起来像身份证也不算续接。
run_case "破坏➲：续接不再校验校验位" "$CROSSUNIT" \
  '            if (!rule.validator().isValid(candidate)) {' \
  '            if (false) {' RED

# 破坏➳：续接的片段范围算成整段。
#   碎片只是段落里的一段（"身份证110105号" 里的 "110105"），
#   把整段都算进去会把上下文一起脱掉——用户看到的是「<身份证1>」而不是「身份证<身份证1>号」。
#   → 红灯必须来自 CrossUnitContinuationTest.切成 6+12 与三个格式的跨段用例。
run_case "破坏➳：续接的片段范围算成整段" "$CROSSUNIT" \
  '                    out.add(new Override(offset + i, tail.start(), tail.end(),' \
  '                    out.add(new Override(offset + i, 0, unit.length(),' RED

# 破坏➴：会话不再核对「脱敏遍历的顺序」与「全文里的顺序」。
#   覆盖是按单元序号算出来的；顺序对不上时，序号会指错单元——
#   把 A 的碎片按 B 的类型脱掉。对齐检查是这套机制的安全底座。
#   → 红灯必须来自 CrossUnitContinuationTest.会话：遍历顺序与全文对不上时，续接整个关掉并记账。
run_case "破坏➴：会话不核对单元顺序" "$SESSION" \
  '        if (unitCursor >= units.size() || !units.get(unitCursor).equals(text)) {' \
  '        if (unitCursor >= units.size()) {' RED

# 破坏➵：覆盖的优先级被压到最低。
#   碎片 "19491231002X" 本身长得像手机号（11 位 + 后面跟个 X）。
#   覆盖必须带着身份证的优先级（120）才能压过手机号（90），
#   压低了就会出现「碎片被脱了，但标成手机号」——标签错，用户按身份证找就找不到。
#   → 红灯必须来自三个格式的跨段用例（断言碎片不得出现 <手机号）。
run_case "破坏➵：续接覆盖的优先级被压低" "$SESSION" \
  '                    o.ruleKey(), o.tokenPrefix(), o.sortOrder()));' \
  '                    o.ruleKey(), o.tokenPrefix(), 0));' RED

# 破坏➶：pptx 的单元序列里混进了 run 级分段。
#   单元序列的分段必须与脱敏遍历的单元粒度一致（一个 <a:p> 是一个单元）。
#   两者对不上时，会话的顺序对齐当场判红，续接被整个关掉——
#   跨段的身份证又变成漏脱，而且没有任何报错指向它。
#   → 红灯必须来自 PptxRedactorTest.身份证被相邻段落切断。
#
# 锚点只取「单元入列」那一行：Java 的字符字面量 '\n' 里带单引号，
# 而 bash 的单引号串里不能直接出现单引号（要写成 '\''\n'\''，可读性会塌），
# 所以锚点取不含它的那一行。
run_case "破坏➶：pptx 单元序列分段粒度与脱敏遍历对不上" "$PPTX" \
  '                partUnits.add(text.toString());' \
  '                // 变异体：单元序列退化成 run 级分段
                for (Element slot : slots) {
                    partUnits.add(slot.getTextContent());
                }' RED

# 破坏➷：PDF 侧的跨块续接整体失效。
#   块 13 的实测：身份证被文本块切成 14 + 3 时，PDF 侧是 **0 处命中、整张身份证
#   原样留在交付物里**——那是泄漏，不是「标签不符」。
#   续接一失效，两个碎片各自都匹配不上任何规则，就又变回块 13 的样子：
#   文件正常、报告说「脱敏成功」、敏感信息整张留着。
#   → 红灯必须来自 PdfCrossUnitTest：碎片还在交付物里 + 还原不回去。
run_case "破坏➷：PDF 侧跨块续接整体失效" "$PDFRED" \
  '                    unitGroups(units), ContinuationScope.CHECK_DIGIT_ONLY);' \
  '                    unitGroups(List.of()), ContinuationScope.CHECK_DIGIT_ONLY);' RED

# 破坏➸：PDF 侧的续接不再限同一内容流。
#   续接的前提是「两段本来就该是连着的」。PDF 里相邻的两个块可能压根不相关——
#   同一页左栏的末尾与右栏的开头也是「相邻」，不同页之间更是毫无关系。
#   分组（一个内容流一组）把「可以连着」的边界说出来；拍平它，跨页的碎片就会被
#   拼成一个根本不存在的身份证（或手机号）。
#   → 红灯必须来自 PdfCrossUnitTest.跨内容流（两页）不续接。
run_case "破坏➸：PDF 侧续接不再限同一内容流" "$PDFRED" \
  '        for (Unit unit : units) {
            List<String> texts = new ArrayList<>();
            for (TextBlock block : splitBlocks(unit)) {
                texts.add(block.joined());
            }
            groups.add(texts);
        }' \
  '        List<String> all = new ArrayList<>();
        for (Unit unit : units) {
            for (TextBlock block : splitBlocks(unit)) {
                all.add(block.joined());
            }
        }
        groups.add(all);' RED

# 破坏➹：PDF 侧放开无校验位的规则参与续接。
#   PDF 的相邻块是**空间**相邻；两栏里各一个不相干的数字串，拼起来也可能凑成
#   一个合法手机号（11 位、1 开头、第二位 3-9）。手机号「过校验器」等于「什么都收」，
#   所以 PDF 侧只允许有**真校验位**的规则参与续接（见 Validator.hasCheckDigit）。
#   放开之后，误判面从「身份证被切断」放大到「任意两段数字凑够长度」。
#   → 红灯必须来自 PdfCrossUnitTest.手机号被切断不续接。
run_case "破坏➹：PDF 侧放开无校验位的规则参与续接" "$PDFRED" \
  '                    unitGroups(units), ContinuationScope.CHECK_DIGIT_ONLY);' \
  '                    unitGroups(units), ContinuationScope.ANY);' RED

# 破坏➺：还原时切回原缩放不写分隔符。
#   规范要求算子以空白或分隔符结束（PDF 32000-1 §7.8.2）。
#   而「<原 Tz> Tz」这段是**紧跟在前面那个算子后面**的：前面若是「68.2317 Tz」，
#   两者一粘就成了「68.2317 Tz100 Tz」——「Tz100」是个谁也不认识的算子，
#   读者会忽略它，原文于是按 68% 的宽度画回去（版面歪了）；
#   我们自己的内容流扫描器也读不懂它，版面自检因此直接崩掉。
#   → 红灯必须来自 PdfCrossUnitTest.还原后的内容流里，算子必须仍然以空白或分隔符结束。
run_case "破坏➺：还原时切回原缩放不写分隔符" "$REWRITE" \
  '                StringBuilder sw = new StringBuilder("\n");' \
  '                StringBuilder sw = new StringBuilder();' RED

# xlsx 的单元序列必须与「编号路径真正喂的文本」一致：元数据（工作表名 / 定义名称）
# 走隐藏路径、不喂编号路径，就不该进单元序列。
#
# 【这条钉住的是块 14 真实踩过的坑】
#   旧的单元序列是「全文按 '\n' 切」，而全文里含工作表名 → 单元序列第一个是工作表名，
#   第一个喂进去的却是单元格文本 → 对不上 → 续接被**静默**关掉 →
#   被切断的身份证碎片原样留在包里。更糟的是它**依赖 zip 里部件的先后**：
#   workbook.xml 排在工作表前面才犯，单测的夹具把它排在最后所以一直是绿的。
#
# 【为什么这条不会被别的东西顺带咬住】
#   换成「全文按 '\n' 切」之后，只有**工作表名排在工作表前面**的那两条用例会红
#   （XlsxRedactorTest.continuationIndependentOfPartOrder / sameResultRegardlessOfPartOrder），
#   其余用例仍绿——所以这条变异是**精准**的：红的正是「顺序依赖」那两条。
run_case "破坏➻：xlsx 单元序列混入元数据（工作表名进序列 → 续接被静默关掉）" "$XLSX" \
  '            RedactionSession session = new RedactionSession(plan.fullText(), rules, exclusions, sources,
                    plan.groups(), ContinuationScope.ANY);' \
  '            RedactionSession session = new RedactionSession(plan.fullText(), rules, exclusions, sources,
                    List.of(List.of(plan.fullText().split("\n"))), ContinuationScope.ANY);' RED

# 破坏➼ / 破坏➽：docx 与 pptx 的单元序列**由全文推导**（而不是显式声明）。
#   触发条件与 xlsx 那条不同：单元文本里若自带换行符（生成器把多行文本直接写进
#   <w:t> / <a:t>），「全文按 '\n' 切」会把一个单元切成两段 →
#   单元序列与实际喂进去的文本对不上 → 续接被**静默**关掉 →
#   **整篇文档**的碎片都漏（110105 残留、19491231002X 被错标成手机号）。
#
#   为什么分成三条而不是用 run_case_multi 一次覆盖三个格式：
#   多条合并时，只要有一个格式还咬得住，这条就照样 PASS——
#   某个格式的守卫悄悄失效（探针空转）会被**静默**掩盖。
#   分开之后，哪个格式的探针空转，那一条就会当场报 FAIL（期望 RED 却拿到 GREEN）。
#   → 红灯必须来自 DocxRedactorTest / PptxRedactorTest 的「单元自带换行符」用例。
run_case "破坏➼：docx 单元序列由全文推导（单元自带换行符时被切成两段 → 续接被静默关掉）" "$DOCX" \
  '                    plan.groups(), ContinuationScope.ANY);' \
  '                    List.of(List.of(plan.fullText().split("\n"))), ContinuationScope.ANY);' RED

run_case "破坏➽：pptx 单元序列由全文推导（单元自带换行符时被切成两段 → 续接被静默关掉）" "$PPTX" \
  '                    plan.groups(), ContinuationScope.ANY);' \
  '                    List.of(List.of(plan.fullText().split("\n"))), ContinuationScope.ANY);' RED

# 破坏➾ / 破坏➿ / 破坏㊱：OOXML 的单元**分组**被摊平成一组（退回块 13 的整篇一组）。
#
# 【这条钉住的是块 15 修掉的那个限制】
#   分组的意义是「组内可以续接，组间一律不续接」。摊平之后，组间边界消失，
#   页眉末尾的 16 位数字与正文开头的字母 X 会被拼成一个形态合法的 VIN
#   （6222021234567890X），于是两段都被按车架号脱敏——
#   **误判 + 标签错**：用户看到的类型与原文语义无关，还原时把一个订单编号
#   还原成一个车架号。
#
# 【为什么拆成三条而不是一条 run_case_multi】
#   合并后只要有一个格式还咬得住，这条就照样 PASS——
#   某个格式的守卫悄悄失效（探针空转）会被**静默**掩盖。
#   拆开之后，哪个格式的探针空转，那一条就会当场报 FAIL（期望 RED 却拿到 GREEN）。
#   → 红灯必须来自 DocxRedactorTest / XlsxRedactorTest / PptxRedactorTest
#     的「跨部件不得续接」那一条用例。
#
# 【为什么是「摊平」而不是「去掉分组」】
#   去掉分组（unitGroups 传空列表）会让续接**整体关掉**——碎片不漏，但也不脱敏了：
#   那条红来自「碎片还在文档里」，测的是另一件事（续接整体失效，已有破坏➱ 覆盖）。
#   摊平才是块 15 之前**真实存在过**的行为，红在「跨部件假续接」这一条上。
run_case "破坏➾：docx 单元分组被摊平成一组（跨部件被拼成假车架号）" "$DOCX" \
  '                    plan.groups(), ContinuationScope.ANY);' \
  '                    List.of(plan.units()), ContinuationScope.ANY);' RED

run_case "破坏➿：xlsx 单元分组被摊平成一组（跨部件被拼成假车架号）" "$XLSX" \
  '                    plan.groups(), ContinuationScope.ANY);' \
  '                    List.of(plan.units()), ContinuationScope.ANY);' RED

run_case "破坏㊱：pptx 单元分组被摊平成一组（跨部件被拼成假车架号）" "$PPTX" \
  '                    plan.groups(), ContinuationScope.ANY);' \
  '                    List.of(plan.units()), ContinuationScope.ANY);' RED

# ------------------------------------------------------------------ 第 17 块：macOS 开机自启
#
# 【这一块为什么必须有变异】
#   开机自启是**唯一一块「装上以后要过很久才发现不对」的功能**：
#   plist 写错、参数漏一个、KeepAlive 写反了，
#   现场表现都是「命令说成功了，但服务没起来 / 起来就退出 / 无限重启」——
#   而用户第一次发现的时候，往往是在**几个月后**。
#   更糟的是这一块的失败大多**静默**：launchd 对一份读不懂的 plist 什么都不说。
#   → 所以每一条变异都打在这块上，红灯必须来自断言（不是抛异常、不是编译失败）。
#
# 【编号说明】圈内数字那一套到 ㊿（50）就到底了，本块第 10 条用 ⓐ 接着数。
#   （2026-10-21 块 24 更正：㊿ 早被 M1-f 的「校验位加进 VIN 门禁」占用，
#     本块的「卸载清单里漏掉开机自启配置」改用 ⑵；ⓐ 原属块 21 的 PDF 版面那一条，
#     那条已改号为 ⑺。圈号是变异项的身份，同一个圈号不许配两条变异。）
#
# 【这一块的一条实测结论，改变了设计】
#   2026-10-21 在本机（macOS 27.2.0）跑受控矩阵实测 launchd 的 KeepAlive 语义：
#     {Crashed: true}  → SIGKILL / exit0 / exit1 一律**不重启**（与手册相反）
#     {SuccessfulExit} → 与手册一致
#     true             → 一律重启
#   → 「崩了要自愈」只能写成**无条件 true**，配套 ThrottleInterval 限速。
#   详见 LaunchAgentPlist 的类注释。
#
# 破坏㊷：plist 的 ProgramArguments 少一个 serve。
#   launchd 起的不是服务，而是「未知子命令」——
#   进程立刻退出，KeepAlive 又只认崩溃，于是**服务永远不在线**，
#   而 install 照样报「已安装开机自启」。
#   → 红灯必须来自 LaunchAgentPlistTest.ProgramArguments 恰好是 [...]
#     （断言失败：期望 [exe, serve, --no-browser, --port, 8123]，实际少了 serve）。
run_case "破坏㊷：plist 的启动参数里没有 serve（服务根本起不来）" "$PLIST" \
  'sb.append("    <string>serve</string>\n");' \
  'sb.append("    <string>--serve</string>\n");' RED

# 破坏㊸：RunAtLoad 改成 false。
#   plist 装了、launchd 也装载了，但**登录时不会自动起**——
#   用户以为装好了，其实每次都得手动敲 serve。
#   → 红灯必须来自 LaunchAgentPlistTest.RunAtLoad 为真。
run_case "破坏㊸：RunAtLoad 改成 false（登录时不会自动起）" "$PLIST" \
  'sb.append("  <key>RunAtLoad</key>\n  <true/>\n");' \
  'sb.append("  <key>RunAtLoad</key>\n  <false/>\n");' RED

# 破坏㊹：KeepAlive 改成 false（崩了不自愈，服务永久下线）。
#   自启服务是没人看着的：崩了没人拉它，工具就静默地不工作了。
#   实测本机 launchd（macOS 27.2.0）：{Crashed:true} 竟然**从不重启**（与手册相反），
#   唯一能让它自愈的写法是无条件 true —— 去掉它，崩溃后服务就永久下线。
#   → 红灯必须来自 LaunchAgentPlistTest.KeepAlive 是无条件 true。
run_case "破坏㊹：KeepAlive 改成 false（崩了不自愈，服务永久下线）" "$PLIST" \
  'sb.append("  <key>KeepAlive</key>\n  <true/>\n");' \
  'sb.append("  <key>KeepAlive</key>\n  <false/>\n");' RED

# 破坏ⓐ：ThrottleInterval 被去掉（重试没有频率上限）。
#   端口被占时服务会正常退出、launchd 会再拉它 —— 这是要的（端口一腾出来就自动就位），
#   但没有 ThrottleInterval 就是「每几秒启一次 JVM」的无限重试：
#   机器上多一个不停重试的服务，日志越滚越大。
#   → 红灯必须来自 LaunchAgentPlistTest.重试有频率上限。
run_case "破坏ⓐ：plist 里没有 ThrottleInterval（重试没有频率上限）" "$PLIST" \
  'sb.append("  <key>ThrottleInterval</key>\n  <integer>10</integer>\n");' \
  'sb.append("  <key>ThrottleInterval</key>\n  <integer>0</integer>\n");' RED

# 破坏㊺：install 不写 plist 文件。
#   命令报「已安装开机自启」，用户重启后什么也没发生——
#   而 launchctl bootstrap 会因为文件不存在而失败，
#   装不上这件事本该当场说出来。
#   → 红灯必须来自 AutostartCommandTest.install 会写 plist 并真的去 launchctl bootstrap
#     与 UninstallCommandTest.开机自启配置属于应用数据。
run_case "破坏㊺：install 不写 plist（装了个寂寞）" "$AUTOSTART" \
  '            Files.writeString(plistPath, xml, StandardCharsets.UTF_8);' \
  '            // 变异体：故意不写 plist' RED

# 破坏㊻：卸载时不从 launchd 里卸服务。
#   launchd 只认「装载过」这件事，**不管文件还在不在**。
#   只删 plist 的话，那个常驻进程会继续跑，开机还会自己起来——
#   程序卸载了却留了个常驻服务，这是卸载最不该有的结局。
#   → 红灯必须来自 UninstallCommandTest.卸载必须先把 launchd 里的常驻服务停掉。
run_case "破坏㊻：卸载时不从 launchd 里卸服务（程序没了但常驻进程还在）" "$UNINSTALL" \
  '            AutostartCommand.stopIfPresent(sys, userHome, LaunchAgentPlist.DEFAULT_LABEL, out);' \
  '            // 变异体：卸载时不从 launchd 里卸服务' RED

# 破坏㊼：--dry-run 也去停服务。
#   --dry-run 的承诺是「**什么都不改**」——停服务也是改。
#   用户只想看看会删什么，结果服务被停了、开机自启没了。
#   → 红灯必须来自 UninstallCommandTest.--dry-run 不删任何东西，也不该改任何状态。
run_case "破坏㊼：--dry-run 也去停开机自启的服务（承诺是「什么都不改」）" "$UNINSTALL" \
  '        } else {
            AutostartCommand.stopIfPresent(sys, userHome, LaunchAgentPlist.DEFAULT_LABEL, out);
        }' \
  '        }
        AutostartCommand.stopIfPresent(sys, userHome, LaunchAgentPlist.DEFAULT_LABEL, out);' RED

# 破坏㊽：非 macOS 不再明确报错。
#   Windows 上跑这一条，命令会**假装成功**——
#   用户以为装上自启了，其实什么都没发生（Windows 侧本来就还没实现）。
#   规矩是 fail-closed：做不到就明说做不到。
#   → 红灯必须来自 AutostartCommandTest.非 macOS 明确报错，不静默假装成功。
run_case "破坏㊽：非 macOS 不再明确报错（假装装上了）" "$AUTOSTART" \
  '        if (!isMac(osName)) {' \
  '        if (false && !isMac(osName)) {' RED

# 破坏㊾：label 的合法性校验被去掉。
#   label 会直接拼进文件路径（~/Library/LaunchAgents/<label>.plist），
#   放开之后就能把文件写到 **LaunchAgents 之外**的地方——
#   这是路径穿越，不是「用户想自定义个名字」。
#   → 红灯必须来自 AutostartCommandTest.label 里带路径分隔符一律拒绝。
run_case "破坏㊾：label 不做合法性校验（能把文件写到 LaunchAgents 之外）" "$AUTOSTART" \
  '        if (!LABEL_OK.matcher(label).matches()) {' \
  '        if (false && !LABEL_OK.matcher(label).matches()) {' RED

# 破坏⑵：卸载清单里漏掉开机自启配置。
#   清单是「本工具在磁盘上留下了什么」的**唯一真源**；
#   漏一项的后果是「卸载说清了，但那个文件还在」——
#   而 launchd 会在下次登录时照旧把它起来。
#   → 红灯必须来自 UninstallCommandTest.清单的完整性 与
#     UninstallCommandTest.开机自启配置属于应用数据。
run_case "破坏⑵：卸载清单里漏掉开机自启配置（文件留着，开机还会自己起来）" "$CLEANER" \
  '        out.add(new Target(LaunchAgentPlist.plistPath(userHome, LaunchAgentPlist.DEFAULT_LABEL),
                Scope.APP, "开机自启配置（launchd 的 LaunchAgent plist）"));' \
  '        // 变异体：清单里漏掉开机自启配置' RED

# ------------------------------------------------------------------ 第 18 块：Windows 开机自启（注册表 Run 键）
#
# 【这一块为什么必须有变异】
#   Windows 侧的失败模式**全是静默的**：注册表值写进去了、命令也报「已安装开机自启」，
#   但登录时 Windows 按那条命令行起不来（引号被吃掉 / 路径被切成两段 / 值名不对）。
#   而这一切**要等到下一次登录**才暴露 —— 那时用户多半已经卸载了，根本不会回来报障。
#   → 本块的判据必须在 macOS 上就把「我们写出去的那一长串字符串」钉死。
#
# 【这一块与第 17 块的结构差别】
#   macOS 侧的内容在「plist 文本」里；Windows 侧的内容在「命令行 + 键 + 值名」里。
#   两边都是「纯逻辑 + 流程」两层，所以变异打在同一类位置：
#   约定层（WindowsRunKey）与流程层（WindowsAutostart / AutostartCommand / UninstallCommand）。
#
# 【一条查证得来的结论，决定了「引号」这一条怎么写】
#   微软 reg add /? 自己写着：要传一个含引号的值，必须在每个引号前加反斜杠（\）作为转义；
#   而 JDK 的 ProcessBuilder 在 Windows 上拼命令行时，也会把裸引号转义成 \"。
#   → 我们传给 argv 的那份数据里放**真的双引号**，两边各转义一次，正好回到原样。
#   这条**在真 Windows 上还没验过**，所以 install 装完必须**读回来逐字核对**（破坏Ⓜ打的就是它）。
#
# 破坏Ⓚ：可执行文件路径不加引号。
#   Windows 解析 Run 键的值时按空白切分：`C:\Program Files\LightMeter\LightMeter.exe serve ...`
#   会被切成 `C:\Program` 与 `Files\LightMeter\...` 两段 →
#   登录时弹一个「找不到文件」，而 install 早就报过「已安装开机自启」。
#   → 红灯必须来自 WindowsRunKeyTest.命令行形态 与 WindowsAutostartTest.install 写出注册表值。
run_case "破坏Ⓚ：可执行文件路径不加引号（登录时被切成两段，起不来）" "$WINKEY" \
  '        return "\"" + s + "\"";' \
  '        return s;' RED

# 破坏Ⓛ：Run 键从 HKCU 改成 HKLM（本机）。
#   写 HKLM 要管理员权限。普通用户跑 install 会看到提权弹窗，
#   或者干脆失败 —— 而失败是「静默」的：值没写进去，命令却报「已安装」。
#   更糟的是它与「HKCU」**只在字符串里差两个字母**，肉眼极难发现。
#   → 红灯必须来自 WindowsRunKeyTest.Run 键是当前用户（HKCU）下的那一个
#     以及 addCommandArguments / deleteCommandArguments / queryCommandArguments。
run_case "破坏Ⓛ：Run 键从 HKCU 改成 HKLM（写本机要提权，普通用户装不上）" "$WINKEY" \
  '    public static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";' \
  '    public static final String RUN_KEY = "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";' RED

# 破坏Ⓜ：平台判定从 contains("windows") 改成 contains("win")。
#   "Darwin" 里含 "win"（d-a-r-**win**）！于是在 macOS 上会走进 Windows 分支：
#   去写注册表（写不进去），而 macOS 侧的 plist 反而没人管。
#   症状是「在 macOS 上装开机自启永远失败」，而且报错信息会指向注册表 —— 极难查。
#   → 红灯必须来自 WindowsAutostartTest.平台判定只认 windows 与
#     WindowsAutostartTest.CLI 在 macOS 上走 plist 分支。
run_case "破坏Ⓜ：平台判定用 contains(\"win\")（Darwin 会被误判成 Windows）" "$WINAUTO" \
  '        return os.contains("windows");' \
  '        return os.contains("win");' RED

# 破坏Ⓝ：装完不读回来核对。
#   reg add 返回 0 只说明「写进去了」，不说明「写进去的是我们想要的那条命令行」。
#   引号被吃掉是真实存在的失败模式：值变成 `C:\Program Files\...\LightMeter.exe serve ...`，
#   登录时 Windows 把命令行切成两段 → 弹「找不到文件」。
#   而 install 照样报「已安装开机自启」—— 这正是本块要防的静默失败。
#   → 红灯必须来自 WindowsAutostartTest.install 之后必须读回来核对。
run_case "破坏Ⓝ：装完不读回来核对（内容被改过也报成功）" "$WINAUTO" \
  '        if (!data.equals(after.stored())) {' \
  '        if (false && !data.equals(after.stored())) {' RED

# 破坏Ⓞ：Windows 上卸载时不删注册表里的那个值。
#   Run 键里的值只要还在，下次登录它就会自己起来 ——
#   程序卸载了却留了个常驻进程，而且是用户看不见的那种。
#   → 红灯必须来自 UninstallCommandTest.Windows 上卸载必须删掉注册表 Run 键里的那个值。
run_case "破坏Ⓞ：Windows 上卸载时不删注册表值（下次登录还会自己起来）" "$UNINSTALL" \
  '                WindowsAutostart.removeIfPresent(sys, out, WindowsRunKey.DEFAULT_VALUE_NAME);' \
  '                // 变异体：Windows 上卸载时不删注册表值' RED

# 破坏Ⓟ：相对路径的 exe 也放行。
#   Run 键的值是一条命令行，Windows 在登录时解析它；
#   相对路径会按「当时的工作目录」解析，而登录时的工作目录是不确定的
#   （可能是 C:\Windows\System32）→ 「自启条目在，但永远起不来」。
#   → 红灯必须来自 WindowsRunKeyTest.可执行文件路径必须绝对 与
#     WindowsAutostartTest.install 相对路径的 exe 一律拒绝。
run_case "破坏Ⓟ：相对路径的 exe 也放行（登录时按不确定的工作目录解析 = 永远起不来）" "$WINKEY" \
  '        if (!isAbsolutePath(exe.toString())) {' \
  '        if (false && !isAbsolutePath(exe.toString())) {' RED

# ------------------------------------------------------------------ 第 20 块：映射件的加密封装
#
# 【这一块为什么必须有变异】
#   映射件是本工具**唯一含原文**的产物。给它的保护有两层：
#     ① 加密封装（口令 → 密钥 → AES-GCM）；
#     ② 落盘权限收紧到 0600。
#   两层都极容易「看着在、其实没生效」：
#     · 认证标签不校验 → 错口令也能解出内容（而用户以为那是一道锁）；
#     · 盐与 nonce 写死 → 同一把密钥、同一个 nonce 反复用，
#       GCM 下这是**灾难性**的（密钥流复用）；
#     · 权限没收紧 → 同一个机器上的任何用户都能读到原值；
#     · 封装被跳过 → 用户以为加密了，落盘的却是明文。
#   而这四种坏了以后，**功能测试全绿**（能读、能还原、格式对）——
#   红灯必须来自专门的判据。
#
# 破坏Ⓠ：盐与 nonce 写死。
#   GCM 的安全性依赖「同一把密钥下 nonce 永不复用」。盐写死 → 密钥每次都一样；
#   nonce 也写死 → 同一个密钥流被用来加密所有文件。
#   → 红灯必须来自 SecretMapTest.同明文两次封装，密文不同。
run_case_multi "破坏Ⓠ：盐与 nonce 写死（同一密钥流反复用 → 机密性直接失效）" \
  "$SECRETMAP" \
  '        RANDOM.nextBytes(salt);' \
  '        java.util.Arrays.fill(salt, (byte) 1);' \
  "$SECRETMAP" \
  '        RANDOM.nextBytes(nonce);' \
  '        java.util.Arrays.fill(nonce, (byte) 2);'

# 破坏Ⓡ：认证失败不当失败处理。
#   「口令不对」与「密文被改过」在 GCM 里是同一个信号（认证标签不匹配）。
#   如果把这个信号吞掉、返回一个空内容，工具就会对着**错的**内容说「还原好了」。
#   → 红灯必须来自 SecretMapTest.口令不对 / 密文被改 一个字节，以及
#     SecretMapTest.错口令与密文被改：失败信息完全一样。
run_case "破坏Ⓡ：认证失败不当失败处理（错口令与被篡改都不报错）" "$SECRETMAP" \
  '            throw new SecretMapException("口令不对，或这个文件已被改动");' \
  '            return new byte[0]; // 变异体：认证失败时不当失败处理' RED

# 破坏Ⓢ：文件权限不收紧。
#   映射件默认 0644 → 同一台机器上的任何用户都能读到原值。
#   这条在**任何功能测试里都看不出来**（读得出来就说明没坏），
#   所以必须有一条专门盯权限的判据。
#   → 红灯必须来自 PrivateFileTest.写出的文件权限是 0600。
run_case "破坏Ⓢ：映射件权限不收紧（默认 0644 → 同机其他用户能读到原值）" "$PRIVATEFILE" \
  '            tighten(tmp, OWNER_ONLY);' \
  '            tighten(tmp, PosixFilePermissions.fromString("rw-r--r--"));' RED

# 破坏Ⓣ：封装被跳过。
#   用户给了口令、命令行也回了「已用口令封装」，
#   而落盘的还是明文 —— 整个加密功能形同虚设，而且**没有任何异常**。
#   → 红灯必须来自 MapSealCliTest.给了口令：映射件里没有原值，且是封装格式。
run_case "破坏Ⓣ：封装被跳过（用户以为加密了，落盘的还是明文）" \
  "$ROOT/src/main/java/com/litemask/cli/Main.java" \
  '            if (passphrase != null) {' \
  '            if (false && passphrase != null) {' RED

# ------------------------------------------------------------------ 第 21 块：界面上的映射件口令
#
# 【这一块为什么必须有变异】
#   界面上填了口令，服务端把映射件封装起来——这条链上有四种坏了以后
#   **功能测试全绿**的坏法（能读、能还原、格式对、界面显示「已加密」）：
#     · 封装被跳过 → 文件是明文，用户却以为加密了；
#     · 口令被回显到响应体 → 映射件确实是密文，可口令就写在同一条响应里；
#     · 口令被缓存进任务对象 → 映射件确实是密文，可它在整个会话期间都躺在内存里；
#     · 封装失败悄悄退回明文 → fail-open：用户以为加密了，文件里却全是原值。
#   这四种坏法在「映射件能被正确还原」面前全是隐形的，
#   所以必须各有判据盯着，否则「界面上的加密」只是一句宣传。
#
# 破坏Ⓤ：界面上给了口令，封装却被跳过。
#   用户填了口令、界面也显示了「映射件已加密」，而落盘的映射件是明文——
#   文件里全是原值，界面却让用户以为可以随手传出去。
#   → 红灯必须来自 MapPassphraseTest.给了口令：落盘的映射件是封装件，文件里找不到原值。
run_case "破坏Ⓤ：界面上给了口令，封装却被跳过（落盘的还是明文）" \
  "$JOBSTORE" \
  '        if (passphrase != null) {' \
  '        if (false && passphrase != null) {' RED

# 破坏Ⓥ：口令被回显到响应体里。
#   这是一种很常见的「顺手加个调试字段」：映射件确实是密文，
#   可同一条 HTTP 响应的 JSON 里就摆着明文口令——
#   而这个响应是要**发到浏览器**的（界面上还能看到它）。
#   → 红灯必须来自 MapPassphraseTest.口令不出现在任何响应体里。
run_case "破坏Ⓥ：口令被回显到响应体里（映射件是密文，可口令就在同一条响应里）" \
  "$APIHANDLERS" \
  '                Http.json(ex, 200, JobStore.describe(job, job.applied, "applied", excluded));' \
  '                java.util.Map<String, Object> leak = new java.util.LinkedHashMap<>(JobStore.describe(job, job.applied, "applied", excluded)); leak.put("passphraseEcho", passphrase == null ? "" : new String(passphrase)); Http.json(ex, 200, leak);' RED

# 破坏Ⓦ：口令被缓存进任务对象。
#   「为了以后能重新下载/重新封装，把口令留在 job 上」是个很自然的想法。
#   后果是：映射件确实是密文，可口令在整个会话期间都躺在内存里，
#   而任务对象活到会话结束（还会被 LRU 保留一段时间）。
#   本设计明确拒绝：口令只需要活到映射件写完那一刻。
#   → 红灯必须来自 MapPassphraseTest.口令不进任务对象。
run_case_multi "破坏Ⓦ：口令被缓存进任务对象（映射件是密文，可口令整个会话都在内存里）" \
  "$JOBSTORE" \
  '        volatile boolean mappingSealed;' \
  '        volatile boolean mappingSealed; volatile String mapPassphrase;' \
  "$JOBSTORE" \
  '        job.mappingSealed = sealed;' \
  '        job.mappingSealed = sealed; job.mapPassphrase = passphrase == null ? null : new String(passphrase);'

# 破坏Ⓧ：封装失败就悄悄退回明文。
#   「封装失败也别让用户白跑一趟，退回明文总比没有好」——这个想法很有人情味，
#   后果却是 fail-open：用户以为加密了，文件里却全是原值，而且**没有任何异常**。
#   宁可这次请求失败。
#   → 红灯必须来自 MapPassphraseTest.库里给了空口令也必须失败，不许悄悄退回明文。
run_case "破坏Ⓧ：封装失败就悄悄退回明文（用户以为加密了，文件里全是原值）" \
  "$JOBSTORE" \
  '                throw new IOException("封装映射件失败：" + e.getMessage(), e);' \
  '                content = json; sealed = false; // 变异体：封装失败就悄悄退回明文' RED

# ------------------------------------------------------------------ 块 23
#
# 【这一块在验什么】
#   `autostart status` 必须区分「装没装」与「服务**真的**在不在对外服务」。
#   2026-10-21 实测复现的缺陷：端口被占、服务每次起来都立刻退出时，
#   launchd 仍按 KeepAlive 反复拉起它（launchctl 报「已装载」），
#   而 /api/health 实际是 timeout —— 老 status 此时报「已装载」、退出码 0，
#   用户据此以为服务在跑，界面却打不开。
#
# 【为什么这 5 条要拆得这么细】
#   「不探活」与「探了但结果被忽略」是**两个不同的缺陷**：
#     前者根本不发请求，后者发了但不看结果。
#   只拆前者，后者还能全绿 —— 那就漏掉了一类「装了探活、但等于没装」的写法。

# 破坏➂：status 不探活（退回「只看 launchd 认不认」）。
# 【块 34 更新过这条的变异体 —— 不是「顺手改的」，是块 34 改了实现】
#   块 34 把 `ServiceProbe.Result` 从「boolean + detail」改成了 **record**：
#       `record Result(State state, String detail)`
#   → 旧的变异体 `new ServiceProbe.Result(true, "假装在线")` **编译不过**
#     → 整条实验作废（变异体编译失败 = 红灯原因不对，不算「测试发现问题」）。
#   → 现在按新的构造器写：`new ServiceProbe.Result(ServiceProbe.State.ONLINE, "假装在线")`。
#   【为什么这类失效必须当场修而不是重跑一次就算】
#     「变异体没写进去 / 写进去编译不过」与「测试没咬住」是**相反的结论**：
#     前者说明**这条判据此刻根本没测过任何东西**，却会被记成「PASS」或「INVALID」混进汇总。
#     → 作废的条目不说明测试咬不咬得住，必须改好变异体后重跑。
run_case "破坏➂：status 不探活（服务起不来也只说已装载）" \
  "$AUTOSTART" \
  '        ServiceProbe.Result r = probe.probe(port);' \
  '        ServiceProbe.Result r = new ServiceProbe.Result(ServiceProbe.State.ONLINE, "假装在线");' RED

# 破坏➃：探活结果被忽略（探了，但结论永远说在线）。
# 【块 34 更新过这条的锚点】
#   块 34 把 status 的渲染从「在线 / 不可用」两档改成**五档**，
#   那行从 `r.online() ? "在线" : "不可用"` 变成了 `r.state().label()`。
#   → 旧锚点不存在 → 变异点未命中 → 实验无效。
#   → 锚点跟着新实现走；变异语义不变（结论永远说在线）。
run_case "破坏➃：探活结果被忽略（探了也永远说在线）" \
  "$AUTOSTART" \
  '        out.println("  服务检查 " + r.state().label());' \
  '        out.println("  服务检查 在线");' RED

# 破坏➄：服务不可用时也返回 0（把故障说成成功）。
run_case "破坏➄：服务不可用也退出 0（故障被说成成功）" \
  "$AUTOSTART" \
  '        err.println("  手工查   curl -s http://127.0.0.1:" + port + "/api/health");
        return 3;' \
  '        err.println("  手工查   curl -s http://127.0.0.1:" + port + "/api/health");
        return 0;' RED

# 破坏➅：端口不读 plist，改用默认端口。
run_case "破坏➅：端口不读 plist（改用默认端口去探）" \
  "$AUTOSTART" \
  '        int port = LaunchAgentPlist.readPort(plistPath);' \
  '        int port = LaunchAgentPlist.DEFAULT_PORT;' RED

# 破坏➆：读不到端口时猜一个默认端口（把「不知道」说成「探了没通」）。
run_case "破坏➆：读不到端口时猜一个默认端口去探（把不知道说成探不通）" \
  "$PLIST" \
  '            int p = Integer.parseInt(v);
            return p > 0 && p <= 65535 ? p : -1;' \
  '            int p = Integer.parseInt(v);
            return p > 0 && p <= 65535 ? p : 8090;' RED

# ------------------------------------------------------------------ 块 24
#
# 【这一块在验什么】
#   Windows 侧的 `autostart status` 也必须区分「装没装」与「服务**真的**在不在」。
#   块 23 只做了 macOS 侧（并在阶段文档 §27.12 明确记下「Windows 侧没做」）。
#   Windows 侧那时候的 status 只报「装没装 / 值里存的是什么」，**退出码恒 0**。
#
# 【Windows 侧比 macOS 侧更严重】
#   macOS 侧有 KeepAlive（服务挂了 launchd 会自动重试）；
#   Windows 的 Run 键**只在登录时触发一次**，而且**没有自愈** ——
#   服务挂了、被关了，**永远不会自己回来**。
#   → 「已安装」在 Windows 上几乎**不代表**「现在能用」。
#
# 【端口的唯一真源】
#   「已写进 Run 键的那条命令行」——与 macOS 侧「已写出的 plist」对称。
#   改用「猜一个默认端口」= 探错地方 = 编造事实。
#
# 【为什么这 5 条要拆得这么细】
#   「端口猜错」「退出码说成功」「结论说反」「告知说反」「不给下一步」
#   是**五个不同的缺陷**：合并成一条的话，
#   只要还有一条闸门站着，其余四个的坏法就都测不出来。

# 破坏⓫：端口读不到时猜一个默认端口去探。
run_case "破坏⓫：读不到端口时猜一个默认端口去探（探错了地方却说探不通）" \
  "$WINKEY" \
  '        Matcher m = PORT_ARG.matcher(args);
        if (!m.find()) {
            return -1;
        }' \
  '        Matcher m = PORT_ARG.matcher(args);
        if (!m.find()) {
            return 8090; // 变异体：读不到就猜一个默认端口
        }' RED

# 破坏⓬：端口读不到时报成功（把「不知道服务在不在」说成「一切正常」）。
run_case "破坏⓬：读不到端口时报成功（把不知道说成一切正常）" \
  "$WINAUTO" \
  '            out.println("  服务检查 跳过（Run 键里没读出 --port，不知道该探哪个端口）");
            out.println("  访问地址 未知（Run 键里没有 --port）");
            return 3;' \
  '            out.println("  服务检查 跳过（Run 键里没读出 --port，不知道该探哪个端口）");
            out.println("  访问地址 未知（Run 键里没有 --port）");
            return 0; // 变异体：不知道也报成功' RED

# 破坏⓭：服务不可用时说成在线（结论与事实相反）。
# 【块 34 更新过这条的锚点 —— 与破坏➃ 同一个家族】
#   块 34 把 Windows 侧 status 的渲染行从
#       `out.println("  服务检查 " + (r.online() ? "在线" : "不可用"));`
#   改成
#       `out.println("  服务检查 " + r.state().label());`
#   → 旧锚点不存在 → 变异点未命中 → 实验无效。
#   → 锚点跟着新实现走；**变异语义保持不变**（结论与事实相反）。
run_case "破坏⓭：服务不可用却说成在线（结论与事实相反）" \
  "$WINAUTO" \
  '        out.println("  服务检查 " + r.state().label());' \
  '        out.println("  服务检查 " + (r.online() ? "不可用" : "在线"));' RED

# 破坏⓮：把「不会自己回来」说反（Windows 的 Run 键确实不会自愈）。
run_case "破坏⓮：把「不会自己回来」说成「会自动回来」（危险的误导）" \
  "$WINAUTO" \
  '                + "服务不在跑不会自己回来（macOS 侧有 KeepAlive 会自动重试）；"' \
  '                + "服务不在跑会自动回来（macOS 侧有 KeepAlive 会自动重试）；"' RED

# 破坏⓯：服务不可用时不给手工启动命令（Run 键不自愈，用户只能干等）。
run_case "破坏⓯：服务不可用却不给手工启动命令（用户只能干等）" \
  "$WINAUTO" \
  '            err.println("  手工启动 \"" + exe + "\" serve --no-browser --port " + port);' \
  '            err.println("  手工启动 （变异体：不给命令）");' RED

# ------------------------------------------------------------------ 块 25
#
# 【这一块在验什么】
#   核心包 ≤ 90 MiB 是本项目**唯一的硬指标**，也是唯一一个「涨了不会有人发现」的东西：
#   功能坏了会红、测试会挂、用户会报错；体积涨了 3 MiB 时一切都正常，只是文档不再说得对。
#
#   块 25 之前，闸门只在 tools/measure-size.sh（bash）里，
#   而且比的是**四舍五入后的 MiB**；Windows 侧的打包脚本**根本没有闸门**
#   （它会打印体积，然后照样打「PASS 打包并验证通过」）。
#   → 现在判定搬进纯 JDK（com.litemask.pack.SizeGate），两端调同一份判定。
#
# 【为什么这 3 条必须存在】
#   「Windows 侧接上了闸门」不等于「闸门会报警」——
#   判定逻辑自己坏掉时，接上的就是一个不会报警的东西。
#   → 这 3 条就是「拔掉闸门，看测试会不会红」。
#
# 【为什么拆成 3 条】
#   ⑻ 永远放行         → 闸门完全失效
#   ⑼ 按舍入后的 MiB   → 放过 0.005 MiB 以内的超额（**旧 bash 就是这样**）
#   ⑽ 不再 fail-closed → 拿少一层的合计去比预算（把「没量到」说成「量过了」）
#   合并成一条的话，只要还有一条闸门站着，其余两个的坏法就都测不出来。

# 破坏⑻：体积闸门永远放行（超预算也 PASS）。
run_case "破坏⑻：体积闸门永远放行（超预算也 PASS，硬指标失效）" "$SIZEGATE" \
  '        boolean within = complete && total <= budgetBytes;' \
  '        boolean within = true; // 变异体：闸门永远放行' RED

# 破坏⑼：体积闸门按舍入后的 MiB 比预算（放过 0.005 MiB 以内的超额）。
run_case "破坏⑼：体积闸门按舍入后的 MiB 比（超 4643 字节仍判通过）" "$SIZEGATE" \
  '        boolean within = complete && total <= budgetBytes;' \
  '        boolean within = complete && Double.parseDouble(mib(total)) <= (double) budgetMib; // 变异体：按舍入后的 MiB 比' RED

# 破坏⑽：必需层缺失不再 fail-closed（拿少了一层的合计去比预算）。
run_case "破坏⑽：必需组件缺失不再 fail-closed（拿没量到的合计比预算）" "$SIZEGATE" \
  '                    complete = false;' \
  '                    // 变异体：不再 fail-closed' RED

# ------------------------------------------------------------------
# 第 36 块：进程外调用这一维度
# ------------------------------------------------------------------
#
# 【为什么这两条必须存在】
#   块 36 往产品侧加了 14 条断言（LocalServerUrlTest / ProcessEgressBoundaryTest），
#   而**新加的断言如果没有对照实验，就等于「从没被验证过会咬人」**——
#   写一条恒真的断言也是绿的，绿得毫无意义。
#   → 这两条各破坏一次实现，对应测试必须变红。
#
# 【为什么这两条的破坏形状选的是「参数/目标」而不是「有没有调用」】
#   「有没有进程外调用」是**静态层**的判据（标尺扫常量池），
#   拿它做对照实验 = 用静态判据证明静态判据。
#   → 这里破坏的恰好是静态层**看不见**的那一面：
#     地址是什么、命令是什么。这才补上了标尺的盲区。

# 破坏➇：把交给外部程序的地址改成 0.0.0.0（不再是回环 → 指向本机所有网卡）。
run_case "破坏➇：本地服务地址改成 0.0.0.0（交给浏览器的地址不再是回环）" "$LOCAL_SERVER" \
  '        return "http://" + BIND_ADDRESS + ":" + policy.port() + "/";' \
  '        return "http://0.0.0.0:" + policy.port() + "/";' RED

# 破坏➈：浏览器命令改走 shell（地址会被当命令重新解析 → 一次参数注入就能变成任意命令）。
run_case "破坏➈：浏览器命令改走 shell（地址会被当命令重新解析）" "$BROWSER" \
  '            return List.of("open", "-u", url);' \
  '            return List.of("sh", "-c", url);' RED

# 破坏➉：顶层 --help 不再**嵌入** model 的命令清单，改回手抄（而且抄漏了 download / trust）。
# 症状：用户照 README 敲 `model download`，`--help` 里查不到这条命令 → 以为工具没这功能。
# 这正是块 37 开工前的真形态：顶层手抄了一份 model 的清单，抄漏了两块。
run_case "破坏➉：顶层 help 不再嵌入 model 的命令清单（改回手抄，抄漏 download/trust）" "$CLI" \
  '        ModelCommand.usageLines(out);' \
  '        out.print("                  model install <模型包.zip> [--to 目录] [--allow-unsigned]  装一个模型包（旧的会保留，可回退）\n");' RED

# 破坏➊：help 的**用法块**里漏掉 --task-timeout（README 教了它，实现里也有）。
# 症状：用户照 README 敲 `serve --task-timeout`，在 `--help` 的**用法块**里查不到这个开关。
#
# 【为什么强调「用法块」—— 块 37 收尾时这条曾报 GREEN（expected RED）】
#   `--task-timeout` 在 help 全文里出现**两次**：用法块的 serve 行 + 下面的说明段。
#   → 只删用法块那一处，说明段那一处还在 → 「help 全文里有这个字符串」照样成立
#     → 断言没咬住，而**症状其实没被制造出来**（用户仍然查得到，只是不在选项表里）。
#   → 现在断言落在**用法块**上（用户查的就是选项表），这条变异才真的制造症状。
#
# 【块 38 改了变异目标的文件 —— 锚点跟着代码走】
#   块 38 把 serve 的命令行搬进了 ServeCommand 自己（唯一真源），
#   Main.java 里不再有那一行 → 锚点必须跟着搬，否则会判 INVALID
#   （INVALID = 变异体压根没写进去，测试一次都没跑 —— 与「跑了没咬住」是两件事）。
run_case "破坏➊：help 的用法块里漏掉 --task-timeout（README 教了，实现里也有）" "$SERVECMD" \
  ' [--max-upload-bytes 字节] [--task-timeout 毫秒]' \
  ' [--max-upload-bytes 字节]' RED

# ---------------------------------------------------------------------------
# 块 38：--help 必须处处成立，且「看帮助」绝不等于「动手干活」。
#
# 【这组在验什么】
#   块 38 开工前的实测（真二进制）：
#     rules --help        静默执行了 rules（退出码 0、stderr 全空，stdout 是规则表）
#     redact 合同.txt --help   **真的把合同脱敏了**，最后才报错
#     redact --ouput 合同.txt   未知开关被当成了**文件名**
#     serve/uninstall/autostart --help  报「错误」并退出码 2
#     顶层 help 顶部的版本写死成阶段标签「· M3-2」
#   → 下面六条各拆掉一处，看测试是不是真的咬得住。
#   → 特别提醒：拆「--help 短路」时**不能**让它真的把服务起起来
#     （serve 起了服务就会 forever.await()，那不是红灯，是挂死）。
#     破坏㋄ 拆的是「认不认 --help」这一支，落到未知开关分支 → 立即退出码 2。
# ---------------------------------------------------------------------------

# 破坏㋀：未知开关被当成文件名（回到块 38 开工前的形态）。
# 症状：`redact --ouput 合同.txt` 报的是「错误: --ouput」——
#   那其实不是「工具认出了我拼错的开关」，而是「文件 --ouput 不存在」。
run_case "破坏㋀：未知开关被当成文件名（不再报成未知开关）" "$CLI" \
  '                    default -> {
                        // 单独的 "-" 是「从标准输入读文档」，不是开关。
                        if (arg.length() > 1 && arg.startsWith("-")) {
                            throw new IllegalArgumentException(
                                    CliHints.unknownSwitchMessage(arg, KNOWN_FLAGS));
                        }
                        o.positional.add(arg);
                    }' \
  '                    default -> {
                        o.positional.add(arg);
                    }' RED

# 破坏㋁：redact 的 --help 不再短路 —— 「看一眼用法」会真的把合同脱敏一遍。
run_case "破坏㋁：redact 的 --help 不再短路（会真的脱敏一遍）" "$CLI" \
  '        if (opt.helpRequested) {
            // 【为什么 --help 必须在任何动作之前短路】
            //   块 38 开工前的实测：`redact 合同.txt --help` **真的把合同脱敏了**
            //   （产出了 .redacted.txt 与映射件），最后才因为找不到文件 `--help` 报错。
            //   → 「看一眼用法」不该变成「脱敏一遍」。
            CliHelp.print(System.out, redactUsageText(), redactUsageNotes());
            return 0;
        }' \
  '' RED

# 破坏㋂：rules 的 --help 不再短路 —— 静默执行 rules，退出码 0、stderr 全空。
# 这一条最隐蔽：**看起来一切正常**，所以判据必须落在「stdout 里有没有规则表」上。
run_case "破坏㋂：rules 的 --help 不再短路（静默执行 rules）" "$CLI" \
  '        if (opt.helpRequested) {
            // 【这一条是块 38 里最隐蔽的一个】
            //   开工前 `rules --help` **静默执行了 rules**：退出码 0、stderr 全空，
            //   stdout 是一张规则表 —— 用户以为自己看到了用法，其实跑了一条命令。
            //   「没有任何异常输出」正是它最危险的地方：看起来一切正常。
            CliHelp.print(System.out, rulesUsageText(), rulesUsageNotes());
            return 0;
        }' \
  '' RED

# 破坏㋃：顶层 help 顶部的版本写死成阶段标签（回到「· M3-2」）。
# 症状：项目走到 M4 之后那行字还是 M3-2，而**没有任何东西会报**
#   （编译不红、测试不红，help 照打不误）。
run_case "破坏㋃：顶层 help 的版本写死成阶段标签「· M3-2」" "$CLI" \
  '        out.print("轻量单机脱敏工具 · " + ProductIdentity.version() + "\n");' \
  '        out.print("轻量单机脱敏工具 · M3-2\n");' RED

# 破坏㋄：serve 不再认 --help。
# 症状：`serve --help` 报「未知开关: --help」，退出码 2 —— 用户看到的是错误，不是用法。
# 【为什么拆的是「认不认」而不是「短路不短路」】
#   拆「短路」会让 serve 真的起一个服务并永远等着 —— 那不是红灯，是挂死。
#   拆「认不认」落到未知开关分支，立即退出码 2，红灯原因也正确。
run_case "破坏㋄：serve 不再认 --help（报成未知开关，退出码 2）" "$SERVECMD" \
  '                case "-h", "--help" -> {
                    // 【为什么 --help 要短路在起服务之前】
                    //   块 38 开工前的实测：`serve --help` 报的是「未知选项: --help」，
                    //   退出码 2 —— 用户看到的是「错误」，不是用法。
                    //   而这一条命令会**真的起一个服务**（起完就 forever.await()），
                    //   所以更不能让「想看帮助」变成「真的起了个服务」。
                    CliHelp.print(System.out, usageText(), usageNotesText());
                    return 0;
                }' \
  '' RED

# 破坏㋅：顶层 help 不再嵌入 serve 的命令行（改回手抄，抄漏了两行）。
# 症状：help 的用法块里 serve 只有一行，后两行（--ner-model / --max-upload-bytes /
#   --task-timeout 那一行与说明行）不见了 —— 用户在 help 里查不到这些开关。
run_case "破坏㋅：顶层 help 不再嵌入 serve 的命令行（改回手抄，抄漏两行）" "$CLI" \
  '        ServeCommand.usageLines(out);' \
  '        out.print("  serve   [--port N] [--workspace 目录] [--no-browser] [--print-token]\n");' RED

# 锚点体检的汇总：放在**所有调用点之后**（放前面一个锚点都数不到）。
#
# 体检模式下到这里就收工：不跑基线复核（那是跑测试，体检不该等它），
# 也不跑下面的总数校验（那是给真正跑完一轮的实验用的）。
if [ -n "${AUDIT:-}" ]; then
  echo "=== 锚点体检：${audit_count} 个锚点，${audit_bad} 处有问题 ==="
  if [ "$audit_bad" -gt 0 ]; then
    echo "!!! 这些实验一旦跑到就会判 INVALID：变异体压根没写进去，测试一次都没跑。"
    echo "!!! INVALID 的条目**不说明测试咬不牙得住**——它咬不咬得住是未知的。"
    echo "!!! 修法：把锚点改成当前源码里真实存在的字面量，然后重跑这一条。"
    exit 1
  fi
  echo "全部命中且唯一。"
  exit 0
fi

assert_baseline

# 总条数自己算，不手写。
#
# 之前文档里写的「99 条」是从 `grep -cE '^run_case'` 得来的，
# 而那个正则把 `run_case() {` 和 `run_case_multi() {` 两个**函数定义行**也数了进去。
# 于是从 M3-2a 起，阶段文档、汇报、路线图里一路写着一个多了 2 的数字，
# 而且每次都是照抄上一次的，没人核过。
#
# 现在收尾时把「脚本里声明的条数」与「实际判过的条数」摆在一起对：
# 对不上就说明有条实验根本没被执行（比如 ONLY 过滤把它跳过了），
# 那时 pass=N 里的 N 就是一个不完整的分子。
#
# 匹配 `^run_case(_multi)? "`（必须跟一个引号）——带引号才是调用点，
# 函数定义的 `run_case() {` 不会匹配到。
declared=$(grep -cE '^run_case(_multi)? "' "$0" | tr -d ' ')
# 判过的条数 = 通过的 + 没通过的。
#
# 【为什么不能是 pass + fail + invalid —— 2026-10-19 实测】
#   每处 invalid 都会同时 `invalid++` **和** `fail++`（invalid 是 fail 的一个子集，
#   两者都要让整场失败）。于是 `pass + fail + invalid` 把 invalid 算了两次，
#   实测一轮 118 条里只有 116 条通过 + 2 条作废，judged 却算出 120，
#   收尾于是报「判过的 120 条 ≠ 声明的 118 条」——
#   而这句话的结论是「**有实验没被执行**」，与实情（全部执行了，2 条作废）相反。
#
#   报一个假的「有条实验没跑」，比不报更坏：读的人会去查 ONLY 过滤、
#   查函数名拼写，而真正的问题在另一个地方。
#   所以这里数的是**互斥**的三类：通过 / 真失败（没咬住）/ 作废——
#   invalid 已被 fail 包含，不能再加一次。
judged=$((pass + fail))

# 汇总里把「作废」单独拎出来，并且**报出真正的失败数**。
#
# 之前 INVALID 与真 FAIL 共用一个计数，收尾只打 pass=N fail=M。
# 于是「4 条里 2 过 2 挂」看起来像是测试有两处咬不住，实际是其中一条**编译失败**——
# 而编译失败根本不算「测试发现了问题」。两种情况混在一个数字里，
# 读日志的人必须逐行翻才能分清，而多数人不会翻，于是就把作废当成了失败。
#
# 实测 2026-10-19：118 条里 116 过、2 条作废，收尾打的是「fail=2 invalid=2」，
# 读起来像「2 条没咬住 + 2 条作废」（4 条出问题），实情是只有 2 条有问题、且都是作废。
# 所以这里报 `fail=<真失败数>`（= fail - invalid），invalid 另列。
# 「真失败」才是「测试没咬住」——那才是需要改测试的信号。
real_fail=$((fail - invalid))
if [ "$invalid" -gt 0 ]; then
  echo "!!! 其中 ${invalid} 条实验**作废**（变异体编译失败 / 抛异常红 / 崩溃不符预期）——"
  echo "!!! 作废的条目不说明测试咬不咬得住，必须改好变异体后重跑。"
fi
if [ -n "${ONLY:-}" ]; then
  echo "=== 子集结束（匹配 '${ONLY}'）：pass=${pass} fail=${real_fail} invalid=${invalid} / 脚本共 ${declared} 条 —— 不是全量结果 ==="
else
  echo "=== control experiment end: pass=${pass} fail=${real_fail} invalid=${invalid} / 脚本共 ${declared} 条 ==="
fi

# 判过的条数必须等于脚本里声明的条数。
# 少判说明有条实验被静默跳过了（ONLY 过滤写错、run_case 写成了别的名字、
# 或者有一处调用在 if 分支里没走到）——那时的 pass=N 是个不完整的分子。
if [ -z "${ONLY:-}" ] && [ "$judged" -ne "$declared" ]; then
  echo "X 判过的 ${judged} 条 ≠ 脚本里声明的 ${declared} 条；有实验没被执行，pass 是个残缺的分子"
  exit 1
fi

# 作废必须让整场失败，不能只打印一行警告。
#
# 「4 条里 2 过 2 作废」和「4 条里 2 过 2 真挂」在 pass/fail 两个数字上一模一样，
# 而它们的结论正相反：前者说明这两条实验根本没提供任何信息（变异体编译不过，
# 测试压根没跑），后者才说明测试咬住了。原来只判 fail，于是 CI 里这一场是绿的，
# 而实际上有一半的证据是空的。现在 invalid 与 fail 同样致命。
#
# 这里判 fail 即可：invalid 是 fail 的子集（每处 invalid 都同时 fail++），
# 写成 `fail + invalid` 不会改变结果，但会让人以为两者互斥。
[ "$fail" -eq 0 ] || exit 1