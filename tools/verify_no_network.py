#!/usr/bin/env python3
"""独立标尺：本项目自己的代码里，**谁会真的发起出站网络 I/O**。

【为什么要有这个标尺 —— 它防的是哪一类洞】
动态那面（封网沙箱里跑完整管线）只能证明**被跑到的那些路径**不需要网络。
它证明不了「某个没被 e2e 走到的分支里偷偷加了一次 HTTP 请求」。
所以要再有一面静态的：任何类想**发起出站网络 I/O**，都必须在白名单里，
且白名单必须逐条写清理由。新加一处 → 这里立刻红 → 逼着人显式决定。

【判据为什么是「调了哪个方法」，不是「引用了哪个类型」 —— 这是本标尺的第一版真缺陷】
第一版按「常量池里出现 java/net/Xxx」判能力，结果是**误报一大片**：
  - LocalServer 命中 java/net/InetAddress —— 真实原因只是
    `InetSocketAddress.getAddress()` 的**返回类型**是 InetAddress，它不做 DNS；
  - ApiHandlers / JobStore 命中 java/net/URLDecoder —— 那是**纯解码**；
  - AutostartCommand / NerModelRegistry 命中 java/net/URL —— 真实来源是
    `CodeSource.getLocation()` → toURI() → Path.of(URI)，**拿的是本地路径**。
按类型判，会把这 8 个类全塞进白名单 → 白名单等于「现状清单」→ 守卫退化成一个摆设。
改成**按实际调用的方法**判之后，白名单收敛到 2 条，判据才立得住。

【标尺必须独立于被测实现 —— 理由同其它 e2e】
被测代码自己写、自己读、自己验，只能证明内部自洽。
所以这个标尺**纯 Python**，只读 jar 的字节、**不 import 任何项目代码**，
class 文件的常量池也是自己解析的（不靠 javap，不靠 JDK）。

【覆盖边界 —— 必须写清楚，不能让人误以为它是「无网络」的证明】
  ✅ 覆盖：**jar 内每一个 class**（自家 + 第三方依赖）里直接调用 JDK 网络 API 的出站 I/O。
     块 35 之前只扫 `com/litemask/**`（228 / 2355 = 9.7%），
     jar 里 2127 个第三方 class 全在视野外 —— 那是**覆盖面的谎报**，不是覆盖。
  ❌ 不覆盖：反射、动态代理 —— 这两处的兜底是**封网沙箱里的动态端到端**
     （tools/e2e-zero-egress.sh），两面是互补的，不是二选一。
  ❌ 不覆盖：入站（本地监听）。本标尺只管「把数据发出去」；
     `serve` 绑 127.0.0.1 这件事由 LocalServer/SecurityPolicy 的既有判据负责。

【三层语义 —— 这块最容易说错，务必分清】
  ① **能力**：jar 里这个 class **含有**发起出站网络 I/O 的调用点。
  ② **被调用**：本项目的代码**实际调用**了那个重载。
  ③ **会联网**：这条数据流**真的**把数据发出去了。
  本标尺判的是 ①；② 由登记册逐条论证（谁在哪个包里、我们调的是哪个重载）；
  ③ 由**封网沙箱里的动态端到端**证明（那一遍跑下来零出站）。
  → 「jar 里有联网能力」**不等于**「本工具会联网」，
    但反过来「本工具会联网」**必然**落在 ① 里面 → 所以 ① 是必要的下限，不是充分的证明。

用法：
  python3 tools/verify_no_network.py --jar target/desens-core.jar
  python3 tools/verify_no_network.py --selftest
"""
import argparse
import io
import os
import re
import shutil
import struct
import sys
import tempfile
import zipfile

# ---------------------------------------------------------------------------
# 「会真的发起出站网络 I/O」的 JDK 方法。
#
# 收录标准：这个方法**自己去拿数据**或**自己去建连接**。
# 不收：纯值对象（URI/URLDecoder）、只读本地对象的方法
#       （InetAddress.getHostAddress / isLoopbackAddress / InetSocketAddress.getPort）。
# 也不收 java/net/URI.toURL —— 转成 URL 不等于连网，连网的是 URL.openConnection。
# ---------------------------------------------------------------------------
NETWORK_METHODS = {
    ("java/net/URL", "openConnection"),
    ("java/net/URL", "openStream"),
    ("java/net/URL", "getContent"),
    ("java/net/URLConnection", "connect"),
    ("java/net/URLConnection", "getInputStream"),
    ("java/net/URLConnection", "getOutputStream"),
    ("java/net/HttpURLConnection", "connect"),
    ("java/net/HttpURLConnection", "getInputStream"),
    ("java/net/HttpURLConnection", "getOutputStream"),
    ("java/net/InetAddress", "getByName"),
    ("java/net/InetAddress", "getAllByName"),
    ("java/net/InetAddress", "getCanonicalHostName"),
    ("java/net/Socket", "<init>"),
    ("java/net/Socket", "connect"),
    ("java/net/ServerSocket", "<init>"),
    ("java/net/ServerSocket", "bind"),
    ("java/net/DatagramSocket", "<init>"),
    ("java/net/DatagramSocket", "connect"),
    ("java/net/ProxySelector", "select"),
    ("java/net/Authenticator", "requestPasswordAuthentication"),
    ("java/nio/channels/SocketChannel", "open"),
    ("java/nio/channels/SocketChannel", "connect"),
    ("java/nio/channels/DatagramChannel", "open"),
    ("java/nio/channels/DatagramChannel", "connect"),
}

# 整个 owner 都是网络 API 的（JDK 11+ 的新 HTTP 客户端）。
NETWORK_OWNER_PREFIXES = ("java/net/http/",)

# 「把 URI 交给文件 API」也会真的开连接（Files.readAllBytes(URI) 会去下载）。
# 这条是特判，因为它不像网络类名那样显眼。
URI_INTO_FILES = ("java/nio/file/Files",)

# 本项目自己的类在 jar 里的包前缀。**只用于分类，不用于筛选**——
# 块 35 之前这里被当成筛选器，jar 里 2127 个第三方 class 全在视野外。
OWN_PREFIX = "com/litemask/"

# 白名单：本项目里**允许**发起出站网络 I/O 的类，逐条写理由。
#
# 判据：每一项都必须是「按设计就要联网」的入口，
# 且**不参与用户文档的脱敏与还原数据流**。
#
# 【块 35 新增：calls 必须逐条写出来】
#   原来只登记「哪个类」，不登记「它调了哪些联网方法」。
#   于是「有人往已登记的类里再加一次联网调用」这条路径**没人管**——
#   类还在白名单里，标尺照绿。
#   → 现在登记**精确的调用集合**：实际调用集合与登记不一致就报红
#     （多了 = 有人加了新调用；少了 = 依赖升级后能力消失，登记过期）。
ALLOWED = {
    "com/litemask/cli/ModelFetcher": {
        "calls": [
            "java/net/HttpURLConnection.getInputStream()Ljava/io/InputStream;",
            "java/net/URL.openConnection()Ljava/net/URLConnection;",
        ],
        "reason": (
            "「取模型包」的下载器：全项目**唯一**按设计要访问外网的地方。"
            "它下载的是发布方托管的**模型包**，不是用户文档；"
            "下载后强制 Ed25519 验签，没信任锚就装不上。"
        ),
        # 它下载的地址由用户给出（发布方托管地址），所以不能要求「只连回环」。
        "loopback_only": False,
    },
    "com/litemask/cli/ServiceProbe": {
        "calls": [
            "java/net/HttpURLConnection.getInputStream()Ljava/io/InputStream;",
            "java/net/URL.openConnection()Ljava/net/URLConnection;",
        ],
        "reason": (
            "本机服务探针：只连 **127.0.0.1**，探的是**自己这个工具**起没起来。"
            "用的是 JDK 自带的 HttpURLConnection，没有第三方网络库。"
        ),
        "loopback_only": True,
    },
}

# 第三方依赖的联网能力登记册（块 35 新增）。
#
# 【为什么必须有这一本，而不能装作没有】
#   块 35 实测：交付用的 fat jar 里 2355 个 class，只有 228 个是自家的；
#   剩下 2127 个第三方 class 里，**有 4 个带着发起出站网络 I/O 的调用点**：
#     Jackson ×3、commons-logging ×1。
#   之前标尺只扫自家那 228 个 → 这 4 个**从来没被任何静态判据看过**。
#   → 现在全 jar 一起扫：这 4 个必须**逐条登记**，登记与实际不一致就报红。
#
# 【这一本登记册与自家那本不是一回事】
#   自家的类：能力 == 意图（是我们自己写的，按设计要不要联网我们自己说了算）。
#   第三方类：**能力 ≠ 意图**。依赖作者写了什么重载，不是我们的选择；
#   我们能负责的是「**本项目的数据流不经过它**」——这靠两条：
#     ① not_reached_because：本项目的 classpath / 调用面为什么碰不到它；
#     ② backed_by：**封网沙箱里的动态端到端**真跑过全流程、零出站。
#   → 「jar 里有联网能力」**不等于**「本工具会联网」，两句话不能混成一句。
#
# 【URL 字面量为什么不据此判红】
#   这些第三方类里的 URL 字面量是**标识符与文档链接**（XML 命名空间、报错文案里的
#   官网地址），**不是连接目标**。静态层分不出「字面量被当连接目标用」与
#   「字面量只是文本」——判红就是误报，误报会把判据变成摆设。
#   → 字面量**照打**（让人看得见），判红交给「这条数据流会不会联网」那一条，
#     而那一条由封网端到端背书。
ALLOWED_THIRD_PARTY = {
    "com/fasterxml/jackson/core/TokenStreamFactory": {
        "calls": [
            "java/net/URL.openStream()Ljava/io/InputStream;",
        ],
        "reason": (
            "Jackson 的 JSON 工厂提供了 `createParser(URL)` 这个**按 URL 读**的重载。"
            "它在本项目里**只是 jar 里的一段字节**，不构成出站行为。"
        ),
        "not_reached_because": (
            "本项目用 Jackson 只做一件事：把**字节 / 字符串**解析成对象"
            "（`RuleLoader` 读规则文件、`NerModelPackage` 读模型清单、`Json` 读映射文件），"
            "调用的是 `readValue(byte[]|String, Class)`。"
            "**没有任何一处**把 URL 交给 Jackson —— "
            "`grep -rn 'createParser\\|readValue' src/main/java` 的实参全是字节/字符串。"
        ),
        "backed_by": "封网端到端（e2e-zero-egress 的 docx/xlsx/pptx/pdf/ofd/txt 六段全跑）",
    },
    "com/fasterxml/jackson/databind/ObjectReader": {
        "calls": [
            "java/net/URL.openStream()Ljava/io/InputStream;",
        ],
        "reason": (
            "Jackson databind 的 `ObjectReader.readValue(URL)` 重载，"
            "同样只是「按 URL 读」的能力，不是出站行为。"
        ),
        "not_reached_because": (
            "与 TokenStreamFactory 同源：本项目只调 `readValue(byte[]|String, Class)`，"
            "**不把 URL 交给 Jackson**。"
        ),
        "backed_by": "封网端到端（e2e-zero-egress 的六段全跑）",
    },
    "com/fasterxml/jackson/databind/deser/std/FromStringDeserializer$Std": {
        "calls": [
            "java/net/InetAddress.getByName(Ljava/lang/String;)Ljava/net/InetAddress;",
        ],
        "reason": (
            "Jackson databind 内置的「从字符串反序列化」通用实现，"
            "其中对 `java.net.InetAddress` 有一个特判：走 `InetAddress.getByName()`。"
            "**这是 DNS 解析**——是这条登记册里唯一一条「可能真的会出网」的能力。"
        ),
        "not_reached_because": (
            "该特判只在**反序列化的目标类型是 InetAddress** 时才会被选中。"
            "本项目反序列化的目标类型只有三个：`RulesFile`、模型包的 `Manifest`、"
            "以及映射文件用的 `Map` —— **没有一个字段是 InetAddress**"
            "（`Map` 的值走 `UntypedObjectDeserializer`，不会按 InetAddress 反序列化）。"
        ),
        "backed_by": "封网端到端（e2e-zero-egress 的六段全跑）+ 目标类型清单（无 InetAddress）",
    },
    "org/apache/commons/logging/LogFactory": {
        "calls": [
            "java/net/URL.openConnection()Ljava/net/URLConnection;",
            "java/net/URLConnection.getInputStream()Ljava/io/InputStream;",
        ],
        "reason": (
            "commons-logging 1.3.3（**由 pdfbox 3.0.3 引入**，compile scope）"
            "在读取 classpath 上的 `commons-logging.properties` 配置时会 `URL.openConnection()`。"
            "本项目的 PDF 流程**确实会初始化**它"
            "（PDFBox 有 149 个类引用 commons-logging，核心类 COSDocument 等都在内），"
            "所以这一条**不能写成「我们从不用它」**。"
        ),
        "not_reached_because": (
            "触发那次 openConnection 的前提是 **classpath 上存在 `commons-logging.properties`**。"
            "实测交付用的 jar 里**没有这个资源**（也没有 "
            "`META-INF/services/org.apache.commons.logging.LogFactory`）→ "
            "那次取配置直接返回 null，**openConnection 根本不会被执行**。"
            "另外它取的是 `ClassLoader.getResource(...)` 的结果，"
            "协议必为 `file:` / `jar:`（本项目 classpath 只有本地目录与 jar），不可能是 `http:`。"
        ),
        "backed_by": (
            "封网端到端（e2e-zero-egress 的 PDF 段真跑过全流程）+ "
            "jar 内资源清单实测（无 commons-logging.properties）"
        ),
    },
}

# ServiceProbe 的内部类（$Http）单独实现 HTTP 部分，算同一处用途。
ALLOWED_PREFIXES = {
    "com/litemask/cli/ServiceProbe$": "com/litemask/cli/ServiceProbe",
}

# ---------------------------------------------------------------------------
# 块 36 新增：另外两个维度。
#
# 【为什么必须补这两个维度】
#   块 35 之后，标尺管的是「这个 class **直接调用**了 JDK 的出站网络 API」。
#   但文档内容外发还有两条路，静态层当时完全看不见：
#     ① **进程外调用**：拉起一个外部程序（curl / nc / python / 浏览器），
#        由**那个程序**去联网。常量池里长的是 `ProcessBuilder.start` / `Runtime.exec`，
#        不长得像任何网络 API —— 静态判据一眼扫过去，什么都没看见。
#     ② **反射**：用 `Class.getMethod("openConnection")` + `Method.invoke`
#        绕开「直接调用」这条判据。常量池里长的是 `java/lang/reflect/Method.invoke`。
#   → 这两条路一旦存在，「零外发」的静态结论就有洞，而且**洞是静默的**。
#   → 块 36 把这两条路也纳入标尺：看得见、登记在册、与事实对不上就报红。
#
# 【收进哪个维度 = 一条明确的判据，不是「看着像就收」】
#   进程外：这些方法**自己会创建另一个操作系统进程**。
#   反射：**按名字取到可执行的成员**（类/方法/构造器/字段）或**按名字调用**。
#     刻意**不收** `Class.getResource` / `getResourceAsStream`：
#     取资源只读 classpath，既不执行任何方法、也不按名字取可调用成员，
#     单独构不成外发路径；收进来只会把二十几个「读个资源」的 class 混进来，
#     把登记册变成现状清单 —— 现状清单看着热闹，判据却不咬。
# ---------------------------------------------------------------------------
PROCESS_METHODS = {
    ("java/lang/ProcessBuilder", "<init>"),
    ("java/lang/ProcessBuilder", "start"),
    ("java/lang/Runtime", "exec"),
}

REFLECT_METHODS = {
    # 按名字取类
    ("java/lang/Class", "forName"),
    ("java/lang/Class", "loadClass"),
    # 按名字取方法
    ("java/lang/Class", "getMethod"),
    ("java/lang/Class", "getDeclaredMethod"),
    ("java/lang/Class", "getMethods"),
    ("java/lang/Class", "getDeclaredMethods"),
    # 按名字取构造器
    ("java/lang/Class", "getConstructor"),
    ("java/lang/Class", "getDeclaredConstructor"),
    ("java/lang/Class", "getConstructors"),
    ("java/lang/Class", "getDeclaredConstructors"),
    # 按名字取字段
    ("java/lang/Class", "getField"),
    ("java/lang/Class", "getDeclaredField"),
    ("java/lang/Class", "getFields"),
    ("java/lang/Class", "getDeclaredFields"),
    # 真正执行
    ("java/lang/reflect/Method", "invoke"),
    ("java/lang/reflect/Constructor", "newInstance"),
    ("java/lang/reflect/Proxy", "newProxyInstance"),
    # 方法句柄（反射的等价物）
    ("java/lang/invoke/MethodHandles", "lookup"),
    ("java/lang/invoke/MethodHandles$Lookup", "findVirtual"),
    ("java/lang/invoke/MethodHandles$Lookup", "findStatic"),
    ("java/lang/invoke/MethodHandles$Lookup", "findSpecial"),
    ("java/lang/invoke/MethodHandles$Lookup", "findConstructor"),
    ("java/lang/invoke/MethodHandles$Lookup", "findGetter"),
    ("java/lang/invoke/MethodHandles$Lookup", "findSetter"),
    ("java/lang/invoke/MethodHandles$Lookup", "findStaticGetter"),
    ("java/lang/invoke/MethodHandles$Lookup", "findStaticSetter"),
    ("java/lang/invoke/MethodHandles$Lookup", "unreflect"),
}


def _network_method_names():
    """「按名字调一个网络方法」在常量池里的样子：网络方法名作为一个**独立**的 UTF8 常量。

    这里按**全等**判，不按「包含」判 —— 按包含会把 Jackson 的注解方法名
    `getContentType` 误判成网络方法 `getContent`，那是假红，而假红比漏报更费时间。
    `<init>` 必须排除：它是构造器名，与 `Socket.<init>` 这种网络调用不是一回事。
    """
    names = {n for (_owner, n) in NETWORK_METHODS if n != "<init>"}
    # java/net/http/* 整个 owner 都是网络 API，但它的方法名不在 NETWORK_METHODS 里
    names |= {"newHttpClient", "send", "sendAsync"}
    return frozenset(names)


NETWORK_METHOD_NAMES = _network_method_names()

# ---------------------------------------------------------------------------
# 进程外调用登记册（块 36 新增）。
#
# 【与网络登记册不是一回事】
#   网络登记册管的是「**本进程**自己发起出站 I/O」。
#   这一本管的是「本进程**把事情交给了另一个进程**」——
#   那个进程自带网络能力，于是「本进程零外发」不再自动成立。
#   → 所以每一条都必须说清：交给**谁**、参数里**装的是什么**。
#   → `why_no_egress` 是必填项：不能只写「这个类没问题」。
# ---------------------------------------------------------------------------
ALLOWED_PROCESS = {
    "com/litemask/cli/BrowserLauncher": {
        "calls": [
            "java/lang/ProcessBuilder.<init>(Ljava/util/List;)V",
            "java/lang/ProcessBuilder.start()Ljava/lang/Process;",
        ],
        "reason": (
            "用系统默认浏览器打开**本机服务地址**。"
            "命令由纯函数 `commandFor(osName, url)` 产出，三选一："
            "`open -u <url>`（macOS）/ `rundll32 url.dll,FileProtocolHandler <url>`（Windows）"
            "/ `xdg-open <url>`（Linux）；不认识的系统返回空命令，不启动任何东西。"
        ),
        "why_no_egress": (
            "交给外部程序的**地址恒为本机回环**：唯一外部调用方 `ServeCommand` 传的是 "
            "`LocalServer.url()`，而 `LocalServer` 的绑定地址常量写死 `127.0.0.1`，"
            "`url()` 返回 `http://127.0.0.1:<port>/`。"
            "命令**不经 shell 解析**（`ProcessBuilder` 直接收参数表），"
            "参数里只有这个地址，**不含任何用户文档内容**。"
        ),
        "backed_by": (
            "单测：`LocalServer.url()` 恒回环 + `commandFor` 三平台命令表固定；"
            "jar 静态事实：`BrowserLauncher.open` 的外部调用方只有 `ServeCommand`"
        ),
    },
    "com/litemask/cli/AutostartCommand$RealLauncher": {
        "calls": [
            "java/lang/ProcessBuilder.<init>(Ljava/util/List;)V",
            "java/lang/ProcessBuilder.start()Ljava/lang/Process;",
        ],
        "reason": (
            "开机自启的安装 / 卸载 / 状态查询要执行**系统命令**："
            "macOS 是 `launchctl`（bootstrap / print / bootout）与 `id -u`，"
            "Windows 是 `reg`（add / delete / query）。"
            "这是「让本工具在登录后自己起来」这条产品功能的实现方式。"
        ),
        "why_no_egress": (
            "执行的命令**首元素是写死的本机命令**，不经 shell；"
            "参数里只有本机路径、端口号与服务标签名，**没有用户文档内容**，"
            "也不带任何地址。这条路径与文档数据流完全不相交。"
        ),
        "backed_by": (
            "单测：自启全流程用记录式假件跑一遍，断言执行过的命令首元素集合"
            "（launchctl / reg / id）与源文件里写死的一致"
        ),
    },
    "org/apache/fontbox/util/autodetect/WindowsFontDirFinder": {
        "calls": [
            "java/lang/Runtime.exec([Ljava/lang/String;)Ljava/lang/Process;",
        ],
        "reason": (
            "fontbox（由 pdfbox 3.0.3 引入）在 **Windows** 上找系统字体目录时，"
            "执行 `cmd.exe /c echo %windir%`（老系统是 `command.com`）。"
            "命令与参数全是常量，回显的是环境变量 `%windir%`。"
        ),
        "why_no_egress": (
            "**这条路径是可达的**（PDF 遇到未内嵌字体时 PDFBox 会去找系统字体），"
            "所以不能写成「碰不到它」；但它**碰得到也不会外发**："
            "命令与参数全是常量，回显的是环境变量，"
            "既不含文档内容也不联网。"
            "另外它只在 `os.name` 以 `Windows` 开头时才走 —— macOS/Linux 上根本不进这个分支。"
        ),
        "backed_by": (
            "常量池实测（命令与参数是常量）+ macOS 端到端（该分支在 macOS 上不进）"
            "—— **Windows 真机端到端未跑过**（本机没有 Windows 环境），"
            "这一条在 Windows 侧仍属未验证"
        ),
    },
    "com/litemask/cli/ConsoleEncoding": {
        "calls": [
            "java/lang/ProcessBuilder.<init>([Ljava/lang/String;)V",
            "java/lang/ProcessBuilder.start()Ljava/lang/Process;",
        ],
        "reason": (
            "把**我们自己拉起的那个控制台**的输出代码页切到 UTF-8（65001）。"
            "产品统一用 UTF-8 输出（jvm.args 里 stdout/stderr.encoding=UTF-8），"
            "而 Windows 控制台按**自己的输出代码页**解释那些字节："
            "中文系统默认 936(GBK) → UTF-8 字节显示成乱码；"
            "非中文 locale 默认 437/1252 → 那套代码页根本没有「用法:」这几个字，"
            "Java 只能替换成 '?'。"
            "而「端口冲突」「启动失败」这些提示恰好走这条双击路径 —— 乱码等于用户不知道出了什么事。"
        ),
        "why_no_egress": (
            "交给外部程序的是 **`chcp.com`**（Windows 自带的控制台代码页工具，"
            "在 `%SystemRoot%\\System32` 下），参数表里**只有一个字面量 `65001`**，"
            "**不含任何用户数据、不含任何地址**。"
            "chcp 只改当前控制台的输出代码页，**不发起任何网络连接**。"
            "而且只在「我们自己拉起控制台」的那条路上执行"
            "（`DesktopLauncher` 的无参分支）；"
            "用户在已有终端里跑 CLI 时**不执行** —— 那是用户的终端，不该替他改设置。"
        ),
        "backed_by": (
            "常量池实测（命令与参数都是常量）+ 本轮 Windows 真机端到端"
            "（tools\\e2e-windows-product.ps1 起服务、走 HTTP 读到的中文提示正常）"
        ),
    },
}

# ---------------------------------------------------------------------------
# 反射入口登记册（块 36 新增）。
#
# 【为什么按家族登记，而不是逐 class 登记】
#   反射入口有 36 个 class（全是第三方）。逐 class 各写一段论证，
#   就会退化成「现状清单」—— 36 段几乎一样的话，看着热闹，判据却不咬。
#   → 按**家族**论证（4 个）：Jackson / commons-logging / PDFBox / onnxruntime。
#     论证的是「这个库的反射用来干什么」，那一句话才真正有信息量。
#   → 但**每个 class 的精确调用集合仍然逐条登记**：
#     依赖升级带来新的反射入口时，靠这一条报红，而不是靠人眼扫。
#
# 【families 里每个 class 都必须逐个判定】
#   「家族覆盖」不等于「家族里随便什么 class 都行」：
#   登记里没有的 class 一旦出现反射入口，直接判红。
#
# 【network_name_notes 是干什么的】
#   反射能不能变成外发，取决于「它按名字取的那个方法是不是网络方法」。
#   一个 class 的常量池里如果出现了**网络方法名**的独立字面量，
#   就必须逐个说明—— 否则「反射只是反射」这句话就没被验证过。
#   实测只有一个 class 需要说明：commons-logging 的 LogFactory
#   （它**直接**调 openConnection，方法名因此出现在常量池里；
#     它的反射入口指向的是日志实现类，不是网络方法）。
# ---------------------------------------------------------------------------
ALLOWED_REFLECT = {
    "jackson": {
        "reason": (
            "Jackson（databind）用反射读 POJO：构造器、字段、getter/setter，"
            "以及 `Class.forName` 按名字取目标类型。"
            "这是 JSON 反序列化的固有机制，绕不开。"
        ),
        "why_no_egress": (
            "反射调用的目标**全部是本进程内的取值/赋值与构造**"
            "（setter、构造器、字段读取），没有一条是「按名字取一个网络方法再调它」。"
            "本项目反序列化的目标类型只有三个（规则文件、模型包清单、映射表），"
            "没有一个是网络类型。"
            "**更硬的证据**：这些 class 的常量池里除了 `<init>` 之外，"
            "**没有出现过任何网络方法名**（按全等判）——"
            "反射这条路在本 jar 里没有被指向网络方法。"
        ),
        "backed_by": (
            "封网端到端（e2e-zero-egress 六段全跑）+ "
            "常量池网络方法名检查（标尺自动判，见 network_name_notes）"
        ),
        "classes": {
            "com/fasterxml/jackson/core/util/VersionUtil": [
                "java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                "java/lang/Class.getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/deser/BeanDeserializerBase": [
                "java/lang/Class.getConstructors()[Ljava/lang/reflect/Constructor;",
            ],
            "com/fasterxml/jackson/databind/deser/BuilderBasedDeserializer": [
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/deser/impl/BeanAsArrayBuilderDeserializer": [
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/deser/impl/InnerClassProperty": [
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/deser/impl/MethodProperty": [
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/deser/impl/SetterlessProperty": [
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/deser/std/DateDeserializers$CalendarDeserializer": [
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/deser/std/StdKeyDeserializer$StringCtorKeyDeserializer": [
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/deser/std/StdKeyDeserializer$StringFactoryKeyDeserializer": [
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/ext/Java7Support": [
                "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
            ],
            "com/fasterxml/jackson/databind/ext/OptionalHandlerFactory": [
                "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
            ],
            "com/fasterxml/jackson/databind/introspect/AnnotatedConstructor": [
                "java/lang/Class.getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/introspect/AnnotatedCreatorCollector": [
                "java/lang/Class.getDeclaredMethods()[Ljava/lang/reflect/Method;",
            ],
            "com/fasterxml/jackson/databind/introspect/AnnotatedField": [
                "java/lang/Class.getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
            ],
            "com/fasterxml/jackson/databind/introspect/AnnotatedFieldCollector": [
                "java/lang/Class.getDeclaredFields()[Ljava/lang/reflect/Field;",
            ],
            "com/fasterxml/jackson/databind/introspect/AnnotatedMethod": [
                "java/lang/Class.getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/introspect/AnnotatedMethodCollector": [
                "java/lang/Class.getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                "java/lang/Class.getDeclaredMethods()[Ljava/lang/reflect/Method;",
            ],
            "com/fasterxml/jackson/databind/introspect/JacksonAnnotationIntrospector": [
                "java/lang/Class.getDeclaredFields()[Ljava/lang/reflect/Field;",
                "java/lang/Class.getField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
            ],
            "com/fasterxml/jackson/databind/jdk14/JDK14Util$RecordAccessor": [
                "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
                "java/lang/Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/ser/BeanPropertyWriter": [
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/type/TypeFactory": [
                "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
                "java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
            ],
            "com/fasterxml/jackson/databind/util/ClassUtil": [
                "java/lang/Class.getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                "java/lang/Class.getDeclaredConstructors()[Ljava/lang/reflect/Constructor;",
                "java/lang/Class.getDeclaredFields()[Ljava/lang/reflect/Field;",
                "java/lang/Class.getDeclaredMethods()[Ljava/lang/reflect/Method;",
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "com/fasterxml/jackson/databind/util/ClassUtil$EnumTypeLocator": [
                "java/lang/Class.getDeclaredFields()[Ljava/lang/reflect/Field;",
            ],
            "com/fasterxml/jackson/databind/util/NativeImageUtil": [
                "java/lang/Class.getDeclaredConstructors()[Ljava/lang/reflect/Constructor;",
                "java/lang/Class.getDeclaredFields()[Ljava/lang/reflect/Field;",
                "java/lang/Class.getDeclaredMethods()[Ljava/lang/reflect/Method;",
            ],

        },
    },
    "commons-logging": {
        "reason": (
            "commons-logging 1.3.3（由 pdfbox 3.0.3 引入，compile scope）"
            "用 `Class.forName` + `Constructor.newInstance` 发现日志实现；"
            "`LogFactoryImpl` / `Slf4jLogFactory` 用 `getMethod` + `invoke` 调 setter。"
        ),
        "why_no_egress": (
            "反射目标是**日志实现类**（`org.apache.commons.logging.impl.*`），"
            "不是网络方法。ServiceLoader 那条动态发现路径在本 jar 里**根本没有候选**："
            "实测 jar 内没有 `META-INF/services/org.apache.commons.logging.LogFactory`，"
            "也没有 `commons-logging.properties` → `forName` 那条路拿到的是 null，直接返回。"
        ),
        "backed_by": (
            "jar 内资源清单实测 + 封网端到端（PDF 段真跑过全流程）"
        ),
        "classes": {
            "org/apache/commons/logging/LogFactory": [
                "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
                "java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                "java/lang/Class.getConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "org/apache/commons/logging/LogSource": [
                "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
                "java/lang/Class.getConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "org/apache/commons/logging/impl/Log4JLogger": [
                "java/lang/Class.getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
            ],
            "org/apache/commons/logging/impl/LogFactoryImpl": [
                "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
                "java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                "java/lang/Class.getConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                "java/lang/Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "org/apache/commons/logging/impl/ServletContextCleaner": [
                "java/lang/Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "org/apache/commons/logging/impl/Slf4jLogFactory": [
                "java/lang/Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                "java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            ],

        },
        "network_name_notes": {
            "org/apache/commons/logging/LogFactory": {
                "openConnection": (
                    "这个 `openConnection` 是它**直接调用** `URL.openConnection` 时的方法名"
                    "（块 35 已把它登记成第三方联网能力），**不是**按名字反射出来的 —— "
                    "它没有 `getMethod(\"openConnection\")` 这样的调用。"
                    "它的反射入口指向的是日志实现类。"
                ),
                "getInputStream": (
                    "同上：这是直接调用 `URLConnection.getInputStream` 的方法名，"
                    "不是反射目标。"
                ),
            },
        },
    },
    "pdfbox": {
        "reason": (
            "PDFBox 的 `IOUtils` 用反射 + MethodHandles 做平台相关的 IO 优化；"
            "`SecurityHandlerFactory` / `PDNumberTreeNode` 用 `Constructor.newInstance` 建对象。"
        ),
        "why_no_egress": (
            "反射目标是**本进程内的 IO 工具与加密处理器**，"
            "构造出来的对象立刻被 PDFBox 自己使用；"
            "没有任何一条按名字取的是网络方法。"
        ),
        "backed_by": (
            "封网端到端（PDF 段真跑过全流程）+ "
            "常量池网络方法名检查（标尺自动判）"
        ),
        "classes": {
            "org/apache/pdfbox/io/IOUtils": [
                "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
                "java/lang/Class.getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                "java/lang/Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                "java/lang/invoke/MethodHandles$Lookup.findStatic(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                "java/lang/invoke/MethodHandles$Lookup.findVirtual(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                "java/lang/invoke/MethodHandles$Lookup.unreflect(Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;",
                "java/lang/invoke/MethodHandles.lookup()Ljava/lang/invoke/MethodHandles$Lookup;",
            ],
            "org/apache/pdfbox/pdmodel/common/PDNumberTreeNode": [
                "java/lang/Class.getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],
            "org/apache/pdfbox/pdmodel/documentinterchange/taggedpdf/StandardStructureTypes": [
                "java/lang/Class.getFields()[Ljava/lang/reflect/Field;",
            ],
            "org/apache/pdfbox/pdmodel/encryption/SecurityHandlerFactory": [
                "java/lang/Class.getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                "java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;",
            ],

        },
    },
    "onnxruntime": {
        "reason": (
            "onnxruntime 的 `Fp16Conversions` 用 `MethodHandles.Lookup.findStatic` "
            "找同包内的 float16 转换方法（Java 没有原生 half 精度）。"
        ),
        "why_no_egress": (
            "目标是**同包内的数值转换方法**（`ai.onnxruntime.platform` 里的 half/float 互转），"
            "取法写死在代码里，不按输入决定，"
            "更不可能取到一个网络方法。"
        ),
        "backed_by": (
            "封网端到端（NER 模型段真跑过全流程）+ "
            "常量池网络方法名检查（标尺自动判）"
        ),
        "classes": {
            "ai/onnxruntime/platform/Fp16Conversions": [
                "java/lang/invoke/MethodHandles$Lookup.findStatic(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                "java/lang/invoke/MethodHandles.lookup()Ljava/lang/invoke/MethodHandles$Lookup;",
            ],

        },
    },
}

URL_LITERAL = re.compile(r"\b(?:https?|ftp)://([^\s\"'<>\\]+)", re.IGNORECASE)
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", "[::1]"}


def url_host(url):
    """从 URL 里取主机名。

    必须自己拆，不能拿正则一刀切：
      - 「host:port」要拆掉端口；
      - 「[::1]:8080」这种 IPv6 回环，方括号是主机的一部分；
      - 「user@host」要去掉 userinfo。
    判错主机名 = 把「连外网」误判成「连回环」，方向完全反了。
    """
    rest = url.split("://", 1)[1] if "://" in url else url
    rest = rest.split("/", 1)[0].split("?", 1)[0].split("#", 1)[0]
    if "@" in rest:
        rest = rest.rsplit("@", 1)[1]
    if rest.startswith("["):
        end = rest.find("]")
        return rest[: end + 1] if end >= 0 else rest
    return rest.split(":", 1)[0]


# ---------------------------------------------------------------------------
# class 文件解析
# ---------------------------------------------------------------------------

def parse_pool(data):
    """解析常量池。返回 (pool, offset)。

    pool[i] = (tag, value)，下标与 class 文件里的常量池下标一一对应。
    Long/Double 占两个槽位，所以 pool 里那个槽位是 None。
    """
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("不是 class 文件（magic 不对）")
    (count,) = struct.unpack_from(">H", data, 8)
    pool = [None] * count
    off = 10
    i = 1
    while i < count:
        if off >= len(data):
            raise ValueError("常量池被截断")
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8
            (ln,) = struct.unpack_from(">H", data, off)
            off += 2
            pool[i] = (tag, data[off:off + ln])
            off += ln
        elif tag in (7, 8, 16, 19, 20):  # Class/String/MethodType/Module/Package
            pool[i] = (tag, struct.unpack_from(">H", data, off)[0])
            off += 2
        elif tag == 15:  # MethodHandle
            pool[i] = (tag, struct.unpack_from(">H", data, off)[0])
            off += 3
        elif tag in (3, 4):  # Integer/Float
            pool[i] = (tag, struct.unpack_from(">I", data, off)[0])
            off += 4
        elif tag in (5, 6):  # Long/Double —— 占两个槽
            pool[i] = (tag, struct.unpack_from(">Q", data, off)[0])
            off += 8
            i += 1
        elif tag in (9, 10, 11, 12, 17, 18):  # ref / NameAndType / Dynamic
            pool[i] = (tag, struct.unpack_from(">HH", data, off))
            off += 4
        else:
            raise ValueError("未知常量池 tag=%d（偏移 %d）" % (tag, off - 1))
        i += 1
    return pool, off


def utf8(pool, idx):
    item = pool[idx] if idx < len(pool) else None
    if not item or item[0] != 1:
        return "<bad-utf8:%s>" % idx
    return item[1].decode("utf-8", "replace")


def method_calls(pool):
    """取出这个 class 里调用到的全部方法：(owner, name, descriptor)。"""
    out = set()
    for item in pool:
        if not item:
            continue
        tag, val = item
        if tag in (10, 11):  # Methodref / InterfaceMethodref
            cls_idx, nat_idx = val
            cls_item = pool[cls_idx]
            nat_item = pool[nat_idx]
            if not cls_item or not nat_item:
                continue
            out.add((
                utf8(pool, cls_item[1]),
                utf8(pool, nat_item[1][0]),
                utf8(pool, nat_item[1][1]),
            ))
    return out


def is_network_call(owner, name, desc):
    """这个调用会不会真的发起出站网络 I/O？"""
    if any(owner.startswith(p) for p in NETWORK_OWNER_PREFIXES):
        return True
    if (owner, name) in NETWORK_METHODS:
        return True
    if owner in URI_INTO_FILES and "Ljava/net/URI;" in desc:
        return True
    return False


def is_process_call(owner, name, desc):
    """这个调用会不会**拉起另一个操作系统进程**？

    ProcessBuilder.start / Runtime.exec 本身**不联网**，
    它们只是把「联网能力」交给了外部程序 —— 这正是静态层的盲区。
    """
    return (owner, name) in PROCESS_METHODS


def is_reflect_call(owner, name, desc):
    """这个调用是不是「按名字取成员」或「按名字调用」？

    反射能绕开「直接调用」这条判据：`Class.getMethod("openConnection")`
    + `Method.invoke` 在常量池里长得跟网络 API 一点都不像。
    """
    return (owner, name) in REFLECT_METHODS


def all_utf8(pool):
    return [utf8(pool, i) for i, item in enumerate(pool) if item and item[0] == 1]


# ---------------------------------------------------------------------------
# 扫 jar
# ---------------------------------------------------------------------------

def make_dims(network=None, process=None, reflect=None):
    """造一份维度字典。

    【为什么放在模块里】
        自证与守卫都要造「只含某个维度」的样子来判红。
        各写一遍的话，「维度字典长什么样」就有两个定义，
        迟早一边加了字段另一边没加 → 假红。
    """
    return {
        "network": dict(network or {}),
        "process": dict(process or {}),
        "reflect": dict(reflect or {}),
    }


def scan_jar(jar_path):
    """返回 (维度字典, 统计字典)。

    维度字典：{"network": {...}, "process": {...}, "reflect": {...}}
      每个维度里：类名 → {"calls": [...], "urls": [...], "own": bool}
      其中 calls 是该维度命中的调用，urls 是该类里出现的 URL 字面量。
      reflect 维度额外带一个 "net_names"：
        该类常量池里出现的**网络方法名**（全等判）—— 用来判「反射有没有被指向网络方法」。

    【块 35：扫全 jar，不再只扫 com/litemask/**】
    原来这里有一行 `if not name.startswith("com/litemask/"): continue` ——
    2127 个第三方 class 因此从未被任何静态判据看过。
    现在**每一个 class 都扫**，自家与第三方只用于**分类**与**分别登记**。

    【块 36：一次扫描，三个维度】
    三个维度都从**同一份常量池**里取，所以只解析一遍 class 文件。
    分成两次扫 = 「扫 jar」这件事有两个定义，迟早两边口径不一致。
    """
    dims = {"network": {}, "process": {}, "reflect": {}}
    total = 0
    own_total = 0
    with zipfile.ZipFile(jar_path) as zf:
        for name in sorted(zf.namelist()):
            if not name.endswith(".class"):
                continue
            total += 1
            own = name.startswith(OWN_PREFIX)
            if own:
                own_total += 1
            pool, _ = parse_pool(zf.read(name))
            calls = method_calls(pool)
            per_dim = {
                "network": sorted(c for c in calls if is_network_call(*c)),
                "process": sorted(c for c in calls if is_process_call(*c)),
                "reflect": sorted(c for c in calls if is_reflect_call(*c)),
            }
            if not any(per_dim.values()):
                continue
            cls = name[: -len(".class")]
            strings = all_utf8(pool)
            urls = []
            for s in strings:
                for m in URL_LITERAL.finditer(s):
                    urls.append(m.group(0))
            urls = sorted(set(urls))
            net_names = sorted({s for s in strings if s in NETWORK_METHOD_NAMES})
            for dim, dcalls in per_dim.items():
                if not dcalls:
                    continue
                entry = {
                    "calls": ["%s.%s%s" % c for c in dcalls],
                    "urls": urls,
                    "own": own,
                }
                if dim == "reflect":
                    entry["net_names"] = net_names
                dims[dim][cls] = entry
    stats = {
        "total": total,
        "own": own_total,
        "third_party": total - own_total,
    }
    return dims, stats


def effective_allowed(name):
    """把内部类折叠到它的外部类上（ServiceProbe$Http → ServiceProbe）。"""
    for prefix, owner in ALLOWED_PREFIXES.items():
        if name.startswith(prefix):
            return owner
    return name


def _declared_mismatch(declared, actual):
    """比较「登记的调用集合」与「实际调用集合」。

    返回 (多出来的, 少登记的) 两个列表。
    多出来的 = 有人往这个类里加了新的联网调用（**这是要抓的**）。
    少登记的 = 依赖升级后那个能力没了，登记过期（**也是要抓的**：登记与事实不符）。
    """
    d = set(declared or ())
    a = set(actual or ())
    return sorted(a - d), sorted(d - a)


def judge(dims):
    """判定：三个维度的命中集合与三本登记册是否一致。

    返回问题列表（空 = 通过）。
    【为什么把它从 check() 里抽出来】自证要能**安静地**判定合成出来的 jar
    ——直接调 check() 会把一大段输出打进自证日志，淹没真正的断言。
    """
    problems = []
    problems.extend(judge_network(dims.get("network", {})))
    problems.extend(judge_process(dims.get("process", {})))
    problems.extend(judge_reflect(dims.get("reflect", {})))
    return problems


def judge_network(hits):
    """网络维度：自家白名单 + 第三方登记册，与块 35 口径一致。"""
    own_hits = sorted(n for n in hits if hits[n]["own"])
    third_hits = sorted(n for n in hits if not hits[n]["own"])
    problems = []

    # ---- 自家的类：白名单逐条有理由 + 精确调用集合 ----
    for name in own_hits:
        owner = effective_allowed(name)
        if owner not in ALLOWED:
            problems.append("未登记的联网类：%s（调用 %s）" % (name, "；".join(hits[name]["calls"])))
            continue
        rule = ALLOWED[owner]
        if rule.get("loopback_only"):
            for url in hits[name]["urls"]:
                host = url_host(url)
                if host not in LOOPBACK_HOSTS:
                    problems.append(
                        "%s 只允许连回环，但类里有非回环 URL 字面量：%s（主机 %s）"
                        % (name, url, host)
                    )
    # 调用集合核对：把同一 owner 名下的类（ServiceProbe + ServiceProbe$Http）的调用并起来比。
    own_by_owner = {}
    for name in own_hits:
        own_by_owner.setdefault(effective_allowed(name), set()).update(hits[name]["calls"])
    for owner, actual in sorted(own_by_owner.items()):
        extra, missing = _declared_mismatch(ALLOWED[owner].get("calls"), actual)
        if extra:
            problems.append(
                "%s 出现了登记之外的网络调用：%s（登记的调用集合必须与实际一致）" % (owner, "；".join(extra))
            )
        if missing:
            problems.append(
                "%s 的登记里列了实际已不存在的调用：%s（登记过期？）" % (owner, "；".join(missing))
            )
    for owner in sorted(set(ALLOWED) - set(own_by_owner)):
        problems.append("白名单里的 %s 实际没有联网调用（白名单过期？）" % owner)

    # ---- 第三方依赖的类：登记册逐条有理由 + 精确调用集合 ----
    for name in third_hits:
        if name not in ALLOWED_THIRD_PARTY:
            problems.append(
                "第三方依赖里有未登记的联网类：%s（调用 %s）"
                "——依赖升级带来新的联网能力，必须显式登记并说明为什么不影响本项目"
                % (name, "；".join(hits[name]["calls"]))
            )
            continue
        rule = ALLOWED_THIRD_PARTY[name]
        extra, missing = _declared_mismatch(rule.get("calls"), hits[name]["calls"])
        if extra:
            problems.append(
                "第三方类 %s 出现了登记之外的网络调用：%s（登记的调用集合必须与实际一致）"
                % (name, "；".join(extra))
            )
        if missing:
            problems.append(
                "第三方类 %s 的登记里列了实际已不存在的调用：%s（登记过期？）"
                % (name, "；".join(missing))
            )
    for name in sorted(set(ALLOWED_THIRD_PARTY) - set(third_hits)):
        problems.append(
            "第三方登记册里的 %s 在 jar 里已经没有联网调用了（登记过期？）" % name
        )
    return problems


def judge_process(hits):
    """进程外维度：拉起外部程序 = 把联网能力交出去了。

    登记册逐条有理由，且登记的**精确调用集合**必须与实际一致
    （多了 = 有人又加了一次进程外调用；少了 = 登记过期）。
    """
    problems = []
    for name in sorted(hits):
        rule = ALLOWED_PROCESS.get(name)
        if rule is None:
            problems.append(
                "未登记的进程外调用类：%s（调用 %s）"
                "——拉起外部程序等于把联网能力交出去了，必须显式登记并说明交给谁、参数里装什么"
                % (name, "；".join(hits[name]["calls"]))
            )
            continue
        extra, missing = _declared_mismatch(rule.get("calls"), hits[name]["calls"])
        if extra:
            problems.append(
                "进程外调用类 %s 出现了登记之外的调用：%s（登记的调用集合必须与实际一致）"
                % (name, "；".join(extra))
            )
        if missing:
            problems.append(
                "进程外调用类 %s 的登记里列了实际已不存在的调用：%s（登记过期？）"
                % (name, "；".join(missing))
            )
        for field in ("reason", "why_no_egress", "backed_by"):
            if not str(rule.get(field) or "").strip():
                problems.append(
                    "进程外调用类 %s 的 %s 是空的（登记退化成只列类名，论证没了）" % (name, field)
                )
    for name in sorted(set(ALLOWED_PROCESS) - set(hits)):
        problems.append(
            "进程外登记册里的 %s 在 jar 里已经没有进程外调用了（登记过期？）" % name
        )
    return problems


def judge_reflect(hits):
    """反射维度：按名字取成员/调用成员。

    三条判据：
      ① **自家类不允许有反射入口**。自家代码不反射 → 静态标尺对自家才完备。
         真要用反射，必须先改这条约束并在文档里说明 —— 那是自觉的决定，不是默认。
      ② 每个第三方反射 class 必须**属于某个已登记家族**，且精确调用集合与登记一致。
      ③ 反射 class 的常量池里若出现**网络方法名**（全等判），
         必须逐个说明 —— 否则「反射只是反射」这句话就没被验证过。
    """
    problems = []
    owner_of = {}
    for fam, rule in ALLOWED_REFLECT.items():
        for cls in rule.get("classes", {}):
            owner_of[cls] = fam

    for name in sorted(hits):
        # ③ 网络方法名逐个说明。
        #    这一条对**每一个**反射 class 都查，不分它有没有被登记 ——
        #    「这个 class 的反射有没有被指向网络方法」是个独立的问题，
        #    不能因为它已经因为「没登记」被判红就不查了。
        fam = owner_of.get(name)
        rule = ALLOWED_REFLECT.get(fam) if fam else None
        notes = (rule or {}).get("network_name_notes", {}).get(name, {})
        for net_name in hits[name].get("net_names", []):
            if not str(notes.get(net_name) or "").strip():
                problems.append(
                    "反射类 %s 的常量池里出现了网络方法名 %s，但没有说明"
                    "（反射有没有被指向网络方法，必须逐个回答）" % (name, net_name)
                )
        if hits[name]["own"]:
            problems.append(
                "自家类出现了反射入口：%s（调用 %s）"
                "——自家代码不使用反射是本项目的硬约束，静态标尺对自家才完备；"
                "真要用必须先显式改这条约束并在文档里说明"
                % (name, "；".join(hits[name]["calls"]))
            )
            continue
        if fam is None:
            problems.append(
                "第三方依赖里有未登记的反射类：%s（调用 %s）"
                "——依赖升级带来新的反射入口，必须显式登记并说明为什么不影响本项目"
                % (name, "；".join(hits[name]["calls"]))
            )
            continue
        extra, missing = _declared_mismatch(rule["classes"][name], hits[name]["calls"])
        if extra:
            problems.append(
                "反射类 %s 出现了登记之外的反射调用：%s（登记的调用集合必须与实际一致）"
                % (name, "；".join(extra))
            )
        if missing:
            problems.append(
                "反射类 %s 的登记里列了实际已不存在的反射调用：%s（登记过期？）"
                % (name, "；".join(missing))
            )
        for field in ("reason", "why_no_egress", "backed_by"):
            if not str(rule.get(field) or "").strip():
                problems.append(
                    "反射家族 %s 的 %s 是空的（论证退化成只列类名）" % (fam, field)
                )

    for fam in sorted(ALLOWED_REFLECT):
        rule = ALLOWED_REFLECT[fam]
        for cls in sorted(rule.get("classes", {})):
            if cls not in hits:
                problems.append(
                    "反射登记册家族 %s 里的 %s 在 jar 里已经没有反射入口了（登记过期？）"
                    % (fam, cls)
                )
        if not rule.get("classes"):
            problems.append("反射登记册家族 %s 一个 class 都没登记（家族过期？）" % fam)
    return problems


def check(jar_path):
    dims, stats = scan_jar(jar_path)
    hits = dims["network"]
    own_hits = sorted(n for n in hits if hits[n]["own"])
    third_hits = sorted(n for n in hits if not hits[n]["own"])
    proc_hits = dims["process"]
    refl_hits = dims["reflect"]
    proc_own = sorted(n for n in proc_hits if proc_hits[n]["own"])
    proc_third = sorted(n for n in proc_hits if not proc_hits[n]["own"])
    refl_own = sorted(n for n in refl_hits if refl_hits[n]["own"])
    refl_third = sorted(n for n in refl_hits if not refl_hits[n]["own"])
    problems = judge(dims)

    print("扫描 jar 内全部 class 共 %d 个（自家 %d + 第三方 %d）"
          % (stats["total"], stats["own"], stats["third_party"]))
    print("具备出站网络调用点的类共 %d 个：自家 %d 个 + 第三方 %d 个"
          % (len(hits), len(own_hits), len(third_hits)))
    print("具备进程外调用点的类共 %d 个：自家 %d 个 + 第三方 %d 个"
          % (len(proc_hits), len(proc_own), len(proc_third)))
    print("具备反射调用点的类共 %d 个：自家 %d 个 + 第三方 %d 个"
          % (len(refl_hits), len(refl_own), len(refl_third)))
    if own_hits:
        print("")
        print("【自家类】实际发起出站网络 I/O 的类：%d 个" % len(own_hits))
        for name in own_hits:
            print("  - %s" % name)
            for c in hits[name]["calls"]:
                print("      %s" % c)
            for u in hits[name]["urls"]:
                print("      URL 字面量 %s" % u)
    if third_hits:
        print("")
        print("【第三方依赖】具备出站网络调用点的类：%d 个" % len(third_hits))
        print("  （有能力 ≠ 会联网。本项目的数据流碰不到它们，理由见登记册；")
        print("    「这条数据流真的不联网」由封网端到端背书。）")
        for name in third_hits:
            rule = ALLOWED_THIRD_PARTY.get(name)
            print("  - %s%s" % (name, "" if rule else "   ← 未登记"))
            for c in hits[name]["calls"]:
                print("      %s" % c)
            for u in hits[name]["urls"]:
                print("      URL 字面量 %s（第三方类里的字面量是标识符/文档链接，不是连接目标）" % u)
            if rule:
                print("      理由：%s" % rule.get("reason", ""))
                print("      为什么碰不到：%s" % rule.get("not_reached_because", ""))
                print("      背书：%s" % rule.get("backed_by", ""))
    if proc_hits:
        print("")
        print("【进程外调用】拉起外部程序的类：%d 个" % len(proc_hits))
        print("  （拉起外部程序 = 把联网能力交给那个程序了，所以这一类必须逐条登记。）")
        for name in sorted(proc_hits):
            rule = ALLOWED_PROCESS.get(name)
            print("  - %s%s" % (name, "" if rule else "   ← 未登记"))
            for c in proc_hits[name]["calls"]:
                print("      %s" % c)
            if rule:
                print("      理由：%s" % rule.get("reason", ""))
                print("      为什么不会外发：%s" % rule.get("why_no_egress", ""))
                print("      背书：%s" % rule.get("backed_by", ""))
    if refl_hits:
        print("")
        print("【反射入口】按名字取成员/调用成员的类：%d 个" % len(refl_hits))
        print("  （反射能绕开「直接调用」这条判据，所以这一类也必须逐条登记。）")
        for name in sorted(refl_hits):
            rule = None
            for fam, frule in ALLOWED_REFLECT.items():
                if name in frule.get("classes", {}):
                    rule = frule
                    break
            print("  - %s%s" % (name, "" if rule else "   ← 未登记"))
            for c in refl_hits[name]["calls"]:
                print("      %s" % c)
            for nn in refl_hits[name].get("net_names", []):
                print("      常量池里出现网络方法名 %s" % nn)
            if rule:
                print("      理由：%s" % rule.get("reason", ""))
                print("      为什么不会外发：%s" % rule.get("why_no_egress", ""))
                print("      背书：%s" % rule.get("backed_by", ""))
    if problems:
        print("")
        for p in problems:
            print("FAIL %s" % p)
        return 1
    print("")
    print("PASS 联网类的集合与登记一致（自家 %d 个 + 第三方 %d 个 = %d 个，逐条有理由）"
          % (len(own_hits), len(third_hits), len(hits)))
    print("PASS 进程外调用类 %d 个、反射入口类 %d 个，集合与登记一致"
          % (len(proc_hits), len(refl_hits)))
    return 0


# ---------------------------------------------------------------------------
# 自证：合成 class 文件（纯 Python 造常量池，不依赖 javac）
# ---------------------------------------------------------------------------

def _utf8_item(s):
    raw = s.encode("utf-8")
    return b"\x01" + struct.pack(">H", len(raw)) + raw


def synth_class(calls, this_class="com/litemask/core/Foo"):
    """造一个只含若干方法调用的最小 class 文件。

    calls: [(owner, name, desc), ...]
    this_class: 这个 class 自己的名字。
      【块 35】必须能造出**非 com/litemask 包名**的 class ——
      否则「扫全 jar」这件事**没被验过**：自证里的 class 全是自家的，
      标尺就算其实只扫自家，这些用例照样全过 → 自证是空的。
    """
    out = io.BytesIO()
    out.write(b"\xca\xfe\xba\xbe")
    out.write(struct.pack(">HH", 0, 52))

    # 先把要用到的字符串收集起来
    utf8s = []
    seen = set()

    def uid(s):
        if s not in seen:
            seen.add(s)
            utf8s.append(s)
        return utf8s.index(s) + 1

    this_name = uid(this_class)
    entries = []   # (owner_idx, name_idx, desc_idx)
    for owner, name, desc in calls:
        entries.append((uid(owner), uid(name), uid(desc)))

    # 常量池布局：1..N 是 Utf8，之后依次是 Class / NameAndType / Methodref
    n_utf8 = len(utf8s)
    pool_size = n_utf8 + 1 + len(entries) * 3
    out.write(struct.pack(">H", pool_size))

    idx = 1
    for s in utf8s:
        out.write(_utf8_item(s))
        idx += 1

    # Class 项：owner 的名字
    class_idx = []
    for owner_idx, _, _ in entries:
        out.write(b"\x07" + struct.pack(">H", owner_idx))
        class_idx.append(idx)
        idx += 1

    # NameAndType 项：名字 + 描述符
    nat_idx = []
    for _, name_idx, desc_idx in entries:
        out.write(b"\x12" + struct.pack(">HH", name_idx, desc_idx))
        nat_idx.append(idx)
        idx += 1

    # Methodref 项：owner + NameAndType
    for i in range(len(entries)):
        out.write(b"\x0a" + struct.pack(">HH", class_idx[i], nat_idx[i]))
        idx += 1

    # access_flags / this_class / super_class / interfaces_count
    out.write(struct.pack(">HHHH", 0x0021, this_name, 0, 0))
    # fields_count / methods_count / attributes_count
    out.write(struct.pack(">HHH", 0, 0, 0))
    return out.getvalue()


def _jar_with(entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return buf.getvalue()


def _write_jar(path, entries):
    with open(path, "wb") as fh:
        fh.write(_jar_with(entries))


def selftest():
    ok = 0
    bad = 0

    def case(desc, cond):
        nonlocal ok, bad
        if cond:
            print("  PASS %s" % desc)
            ok += 1
        else:
            print("  FAIL %s" % desc)
            bad += 1

    print("== verify_no_network 自证 ==")
    tmpdir = tempfile.mkdtemp(prefix="vnn-selftest-")
    try:
        p_clean = os.path.join(tmpdir, "clean.jar")
        _write_jar(p_clean, {
            "com/litemask/core/Foo.class": synth_class([
                ("java/nio/file/Files", "readAllBytes", "([B)[B"),
            ]),
        })
        dims, total = scan_jar(p_clean)
        hits = dims["network"]
        case("不联网的类 → 扫出 0 个", hits == {})

        p_dirty = os.path.join(tmpdir, "dirty.jar")
        _write_jar(p_dirty, {
            "com/litemask/core/Foo.class": synth_class([
                ("java/net/URL", "openConnection", "()Ljava/net/URLConnection;"),
            ]),
        })
        hits2 = scan_jar(p_dirty)[0]["network"]
        case(
            "调用 URL.openConnection → 被抓出来",
            "com/litemask/core/Foo" in hits2
            and any("openConnection" in c for c in hits2["com/litemask/core/Foo"]["calls"]),
        )

        p_innocent = os.path.join(tmpdir, "innocent.jar")
        _write_jar(p_innocent, {
            "com/litemask/core/Foo.class": synth_class([
                ("java/net/URLDecoder", "decode", "(Ljava/lang/String;)Ljava/lang/String;"),
                ("java/net/InetSocketAddress", "getAddress", "()Ljava/net/InetAddress;"),
                ("java/net/URL", "toURI", "()Ljava/net/URI;"),
            ]),
        })
        hits3 = scan_jar(p_innocent)[0]["network"]
        case(
            "URLDecoder / getAddress / toURI 这类**不联网**的调用不误报",
            hits3 == {},
        )

        p_uri_files = os.path.join(tmpdir, "urifiles.jar")
        _write_jar(p_uri_files, {
            "com/litemask/core/Foo.class": synth_class([
                ("java/nio/file/Files", "readAllBytes", "(Ljava/net/URI;)[B"),
            ]),
        })
        hits4 = scan_jar(p_uri_files)[0]["network"]
        case("Files.readAllBytes(URI) → 被抓出来（它会去下载）", len(hits4) == 1)

        p_http = os.path.join(tmpdir, "http.jar")
        _write_jar(p_http, {
            "com/litemask/core/Foo.class": synth_class([
                ("java/net/http/HttpClient", "newHttpClient", "()Ljava/net/http/HttpClient;"),
            ]),
        })
        hits5 = scan_jar(p_http)[0]["network"]
        case("java.net.http.* → 被抓出来", len(hits5) == 1)

        # 白名单判定
        p_allowed = os.path.join(tmpdir, "allowed.jar")
        _write_jar(p_allowed, {
            "com/litemask/cli/ServiceProbe.class": synth_class([
                ("java/net/URL", "openConnection", "()Ljava/net/URLConnection;"),
            ]),
        })
        hits6 = scan_jar(p_allowed)[0]["network"]
        owner6 = effective_allowed("com/litemask/cli/ServiceProbe")
        case("白名单类被认出来", owner6 in ALLOWED)

        p_inner = os.path.join(tmpdir, "inner.jar")
        _write_jar(p_inner, {
            "com/litemask/cli/ServiceProbe$Http.class": synth_class([
                ("java/net/URL", "openConnection", "()Ljava/net/URLConnection;"),
            ]),
        })
        hits7 = scan_jar(p_inner)[0]["network"]
        case(
            "内部类 ServiceProbe$Http 折叠到 ServiceProbe",
            effective_allowed("com/litemask/cli/ServiceProbe$Http") == "com/litemask/cli/ServiceProbe",
        )

        p_unregistered = os.path.join(tmpdir, "unreg.jar")
        _write_jar(p_unregistered, {
            "com/litemask/core/Sneaky.class": synth_class([
                ("java/net/URL", "openConnection", "()Ljava/net/URLConnection;"),
            ]),
        })
        hits8 = scan_jar(p_unregistered)[0]["network"]
        case(
            "未登记的联网类不在白名单里（会判失败）",
            effective_allowed("com/litemask/core/Sneaky") not in ALLOWED,
        )

        # loopback 字面量规则
        def loopback_ok(name, urls):
            rule = ALLOWED.get(effective_allowed(name))
            if not rule or not rule.get("loopback_only"):
                return True
            for url in urls:
                if url_host(url) not in LOOPBACK_HOSTS:
                    return False
            return True

        case(
            "回环 URL 字面量通过",
            loopback_ok("com/litemask/cli/ServiceProbe", ["http://127.0.0.1:8931/api/health"]),
        )
        case(
            "非回环 URL 字面量被挡",
            not loopback_ok("com/litemask/cli/ServiceProbe", ["http://evil.example.com/x"]),
        )
        case(
            "ModelFetcher 不受回环限制（它按设计要下外网）",
            loopback_ok("com/litemask/cli/ModelFetcher", ["http://cdn.example.com/m.zip"]),
        )

        # ---- 块 35：覆盖范围这一维的自证 ----
        # 【为什么必须单独验这一维】
        #   上面所有自证用的 class 全是 `com/litemask/**` 的。
        #   → 标尺就算其实**只扫自家**（块 35 之前就是那样），
        #     这些用例照样全过 → **自证是空的**，覆盖范围这件事从来没被验过。
        #   → 必须造一个**非 com/litemask 包名**的联网 class，
        #     断言它**也会被抓到**、且被**归类为第三方**。
        p_third = os.path.join(tmpdir, "third.jar")
        _write_jar(p_third, {
            "com/example/evil/Third.class": synth_class([
                ("java/net/URL", "openConnection", "()Ljava/net/URLConnection;"),
            ], this_class="com/example/evil/Third"),
            "com/litemask/core/Mine.class": synth_class([
                ("java/nio/file/Files", "readAllBytes", "([B)[B"),
            ], this_class="com/litemask/core/Mine"),
        })
        dimsT, statsT = scan_jar(p_third)
        hitsT = dimsT["network"]
        case(
            "第三方包名（非 com/litemask）的联网 class **也**被抓到"
            "（证明扫的是全 jar，不是只扫自家）",
            "com/example/evil/Third" in hitsT,
        )
        case(
            "它被归类为第三方（own=False）",
            hitsT.get("com/example/evil/Third", {}).get("own") is False,
        )
        case(
            "统计分得清：自家 1 + 第三方 1，共 2 个 class",
            statsT["total"] == 2 and statsT["own"] == 1 and statsT["third_party"] == 1,
        )
        p_judge_third = judge(dimsT)
        case(
            "未登记的第三方联网类 → 判红",
            any("第三方依赖里有未登记的联网类" in p for p in p_judge_third),
        )

        # 登记册过期（登记里多了一个 jar 里已经没有的类）
        stale = {k: {"calls": list(v["calls"]), "urls": [], "own": False}
                 for k, v in ALLOWED_THIRD_PARTY.items()}
        stale.pop(sorted(stale)[0])
        p_stale = judge(make_dims(network=stale))
        case(
            "第三方登记册里多出来的类 → 判红（登记与事实不符）",
            any("登记过期" in p for p in p_stale),
        )

        # 第三方类多出登记之外的网络调用
        k1 = "com/fasterxml/jackson/core/TokenStreamFactory"
        mismatch = {k1: {
            "calls": list(ALLOWED_THIRD_PARTY[k1]["calls"])
                      + ["java/net/Socket.connect(Ljava/net/SocketAddress;)V"],
            "urls": [], "own": False,
        }}
        p_mm = judge(make_dims(network=mismatch))
        case(
            "第三方类多出登记之外的网络调用 → 判红（依赖升级带来新能力时必须被抓）",
            any("登记之外的网络调用" in p for p in p_mm),
        )

        # 自家已登记的类多出联网调用（原来这条路径没人管）
        own_mm = {"com/litemask/cli/ServiceProbe": {
            "calls": list(ALLOWED["com/litemask/cli/ServiceProbe"]["calls"])
                      + ["java/net/URL.openStream()Ljava/io/InputStream;"],
            "urls": [], "own": True,
        }}
        p_own_mm = judge(make_dims(network=own_mm))
        case(
            "自家已登记的类多出联网调用 → 判红（类在白名单里不等于随便加调用）",
            any("登记之外的网络调用" in p for p in p_own_mm),
        )

        # 自家白名单过期
        p_own_stale = judge(make_dims())
        case(
            "自家白名单里的类实际没有联网调用 → 判红（白名单过期）",
            any("白名单过期" in p for p in p_own_stale),
        )

        # 登记册里每一条都必须有理由（空理由 = 一句没人负责的话）
        case(
            "第三方登记册每一条都有 reason / not_reached_because / backed_by",
            all(
                v.get("reason") and v.get("not_reached_because") and v.get("backed_by")
                for v in ALLOWED_THIRD_PARTY.values()
            ),
        )

        # ==================================================================
        # 块 36：进程外调用 + 反射入口 这两个维度的自证
        # ==================================================================

        # ---- 维度一：进程外调用 ----
        p_proc = os.path.join(tmpdir, "proc.jar")
        _write_jar(p_proc, {
            "com/example/evil/Evil.class": synth_class([
                ("java/lang/ProcessBuilder", "<init>", "(Ljava/util/List;)V"),
                ("java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;"),
            ], this_class="com/example/evil/Evil"),
            "com/litemask/cli/BrowserLauncher.class": synth_class([
                ("java/lang/ProcessBuilder", "<init>", "(Ljava/util/List;)V"),
                ("java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;"),
            ], this_class="com/litemask/cli/BrowserLauncher"),
        })
        dimsP, _ = scan_jar(p_proc)
        case(
            "进程外调用被**进程维度**抓到（不是混进网络维度）",
            set(dimsP["process"]) == {"com/example/evil/Evil", "com/litemask/cli/BrowserLauncher"}
            and dimsP["network"] == {},
        )
        case(
            "第三方包名的进程外 class 也被抓到（覆盖面同样是行为证明）",
            "com/example/evil/Evil" in dimsP["process"]
            and dimsP["process"]["com/example/evil/Evil"]["own"] is False,
        )
        p_unreg_proc = judge(make_dims(process=dimsP["process"]))
        case(
            "未登记的进程外调用类 → 判红",
            any("未登记的进程外调用类" in p for p in p_unreg_proc),
        )

        # 登记册过期
        proc_stale = {k: {"calls": list(v["calls"]), "urls": [], "own": False}
                      for k, v in ALLOWED_PROCESS.items()}
        proc_stale.pop(sorted(proc_stale)[0])
        p_proc_stale = judge(make_dims(process=proc_stale))
        case(
            "进程外登记册里多出来的类 → 判红（登记过期）",
            any("进程外登记册里的" in p and "登记过期" in p for p in p_proc_stale),
        )

        # 登记里少一个论证字段
        # 【为什么必须临时改模块里的登记册】
        #   judge 读的是模块级的登记册常量，传进去的只是「命中集合」。
        #   所以要验「登记里少一个字段会被判红」，只能临时改那份常量，
        #   验完**必须原样还原**（finally 里做）——
        #   否则后面所有用例都跑在一份被改坏的登记册上，结论全是假的。
        proc_first = sorted(ALLOWED_PROCESS)[0]
        proc_blank_all = {k: {"calls": list(v["calls"]), "urls": [], "own": False}
                          for k, v in ALLOWED_PROCESS.items()}
        saved_proc = dict(ALLOWED_PROCESS[proc_first])
        try:
            ALLOWED_PROCESS[proc_first] = dict(saved_proc)
            ALLOWED_PROCESS[proc_first]["why_no_egress"] = ""
            p_proc_blank = judge(make_dims(process=proc_blank_all))
        finally:
            ALLOWED_PROCESS[proc_first] = saved_proc
        case(
            "进程外登记的 why_no_egress 为空 → 判红（论证退化成只列类名）",
            any("why_no_egress 是空的" in p for p in p_proc_blank),
        )

        # 登记的调用集合与实际不一致
        proc_mm = {proc_first: {
            "calls": list(ALLOWED_PROCESS[proc_first]["calls"])
                      + ["java/lang/Runtime.exec([Ljava/lang/String;)Ljava/lang/Process;"],
            "urls": [], "own": False,
        }}
        p_proc_mm = judge(make_dims(process=proc_mm))
        case(
            "进程外类多出登记之外的调用 → 判红（有人又加了一次进程外调用）",
            any("进程外调用类" in p and "登记之外的调用" in p for p in p_proc_mm),
        )

        # ---- 维度二：反射入口 ----
        p_refl = os.path.join(tmpdir, "refl.jar")
        _write_jar(p_refl, {
            "com/example/evil/Reflector.class": synth_class([
                ("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"),
                ("java/lang/reflect/Method", "invoke",
                 "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
            ], this_class="com/example/evil/Reflector"),
            "com/litemask/core/Own.class": synth_class([
                ("java/lang/Class", "getMethod",
                 "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"),
            ], this_class="com/litemask/core/Own"),
        })
        dimsR, _ = scan_jar(p_refl)
        case(
            "反射入口被**反射维度**抓到（不是混进网络维度）",
            set(dimsR["reflect"]) == {"com/example/evil/Reflector", "com/litemask/core/Own"}
            and dimsR["network"] == {} and dimsR["process"] == {},
        )
        case(
            "第三方包名的反射 class 也被抓到",
            "com/example/evil/Reflector" in dimsR["reflect"]
            and dimsR["reflect"]["com/example/evil/Reflector"]["own"] is False,
        )
        p_unreg_refl = judge(make_dims(reflect=dimsR["reflect"]))
        case(
            "未登记的第三方反射类 → 判红",
            any("未登记的反射类" in p for p in p_unreg_refl),
        )
        case(
            "**自家类**出现反射入口 → 判红（自家不反射是硬约束）",
            any("自家类出现了反射入口" in p for p in p_unreg_refl),
        )

        # 反射家族登记过期
        refl_stale = {fam: {"classes": dict(r["classes"]),
                            "reason": r["reason"],
                            "why_no_egress": r["why_no_egress"],
                            "backed_by": r["backed_by"]}
                      for fam, r in ALLOWED_REFLECT.items()}
        fam0 = sorted(refl_stale)[0]
        cls0 = sorted(refl_stale[fam0]["classes"])[0]
        del refl_stale[fam0]["classes"][cls0]
        p_refl_stale = judge(make_dims(reflect={
            cls0: {"calls": list(ALLOWED_REFLECT[fam0]["classes"][cls0]),
                   "urls": [], "own": False, "net_names": []}}))
        case(
            "反射登记册里已不存在的 class → 判红（登记过期）",
            any("已经没有反射入口了" in p for p in p_refl_stale),
        )

        # 反射类常量池里出现网络方法名：没说明 → 判红；说明了 → 不红
        p_netname = os.path.join(tmpdir, "netname.jar")
        _write_jar(p_netname, {
            "com/example/evil/Sneaky.class": synth_class([
                ("java/lang/Class", "getMethod",
                 "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"),
                ("com/example/evil/Target", "openConnection", "()V"),
            ], this_class="com/example/evil/Sneaky"),
        })
        dimsN, _ = scan_jar(p_netname)
        case(
            "反射 class 常量池里的网络方法名被抓出来（按全等判）",
            dimsN["reflect"].get("com/example/evil/Sneaky", {}).get("net_names") == ["openConnection"],
        )
        p_netname_bad = judge(make_dims(reflect=dimsN["reflect"]))
        case(
            "反射 class 出现网络方法名却没有说明 → 判红",
            any("出现了网络方法名" in p and "没有说明" in p for p in p_netname_bad),
        )
        # 同一个**已登记**的 class：登记里写好的说明应当豁免
        lf = "org/apache/commons/logging/LogFactory"
        lf_calls = list(ALLOWED_REFLECT["commons-logging"]["classes"][lf])
        p_netname_ok = judge(make_dims(reflect={lf: {
            "calls": lf_calls, "urls": [], "own": False,
            "net_names": ["openConnection", "getInputStream"]}}))
        case(
            "登记里已逐个说明的网络方法名 → 不判红（说明是唯一豁免口）",
            not any("出现了网络方法名" in p and "没有说明" in p for p in p_netname_ok),
        )
        p_netname_extra = judge(make_dims(reflect={lf: {
            "calls": lf_calls, "urls": [], "own": False,
            "net_names": ["openConnection", "send"]}}))
        case(
            "已说明的那几个之外又多出一个网络方法名 → 仍然判红（豁免不能扩大）",
            any("出现了网络方法名" in p and "没有说明" in p for p in p_netname_extra),
        )

        # 反射 class 登记的调用集合与实际不一致
        k2 = sorted(ALLOWED_REFLECT["jackson"]["classes"])[0]
        refl_mm = {k2: {
            "calls": list(ALLOWED_REFLECT["jackson"]["classes"][k2])
                      + ["java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"],
            "urls": [], "own": False, "net_names": [],
        }}
        p_refl_mm = judge(make_dims(reflect=refl_mm))
        case(
            "反射类多出登记之外的反射调用 → 判红（依赖升级带来新反射入口时必须被抓）",
            any("登记之外的反射调用" in p for p in p_refl_mm),
        )

        # 三个维度互不串：一个类同时有网络调用与进程外调用
        p_mix = os.path.join(tmpdir, "mix.jar")
        _write_jar(p_mix, {
            "com/litemask/cli/ServiceProbe.class": synth_class([
                ("java/net/URL", "openConnection", "()Ljava/net/URLConnection;"),
                ("java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;"),
            ], this_class="com/litemask/cli/ServiceProbe"),
        })
        dimsM, _ = scan_jar(p_mix)
        case(
            "同一个类的网络调用与进程外调用分别落在两个维度",
            set(dimsM["network"]) == {"com/litemask/cli/ServiceProbe"}
            and set(dimsM["process"]) == {"com/litemask/cli/ServiceProbe"},
        )

        # 登记册自身的完整性
        case(
            "进程外登记册每一条都有 reason / why_no_egress / backed_by",
            all(
                v.get("reason") and v.get("why_no_egress") and v.get("backed_by")
                for v in ALLOWED_PROCESS.values()
            ),
        )
        case(
            "反射登记册每个家族都有 reason / why_no_egress / backed_by，且至少一个 class",
            all(
                v.get("reason") and v.get("why_no_egress") and v.get("backed_by") and v.get("classes")
                for v in ALLOWED_REFLECT.values()
            ),
        )
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    print("== 自证：%d 条通过，%d 条失败 ==" % (ok, bad))
    return 0 if bad == 0 else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        sys.exit(selftest())
    if not args.jar:
        ap.error("需要 --jar")
    sys.exit(check(args.jar))


if __name__ == "__main__":
    main()
