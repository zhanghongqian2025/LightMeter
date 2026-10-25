#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守卫：jar 里的界面资源必须与源码**逐字相符**。

【为什么要有这道守卫 —— 2026-10-21 实测踩到的坑】
    对照实验与自证脚本会变异 src/main/resources/web/app.js、app.css，
    跑完再把源码还原。还原用的是 copy2 / mv —— **会把 mtime 还原到变异之前**。
    于是源文件比 target/classes 里的旧拷贝**更旧** →
    Maven 增量判定「这个资源不用重拷贝」→ 跳过 →
    打出来的 jar 里装的是**变异体**，不是源码。
    实测后果：端到端三条断言全红，红在「折叠没生效」「排除项没进报文」上，
    看着像代码缺陷，根因却在构建产物 —— 查源码查半天是对的。

    这道守卫把「前提」显式断言出来：jar 与源码不一致，这一轮的结果一律不作数。

【用法】
    python3 check_jar_web_matches_source.py <jar> <源码目录>
退出码 0 = 逐字相符；1 = 有出入（并打印差在哪）。
"""
import io
import os
import sys
import zipfile


def 读(path):
    with io.open(path, "rb") as f:
        return f.read()


def main(argv):
    if len(argv) != 3:
        sys.stderr.write("用法：check_jar_web_matches_source.py <jar> <源码目录>\n")
        return 2
    jar, src = argv[1], argv[2]
    if not os.path.isfile(jar):
        sys.stderr.write("X jar 不存在：%s\n" % jar)
        return 1
    if not os.path.isdir(src):
        sys.stderr.write("X 源码目录不存在：%s\n" % src)
        return 1

    期望 = {}
    for 根, _目录, 文件 in os.walk(src):
        for 名 in 文件:
            全 = os.path.join(根, 名)
            相对 = os.path.relpath(全, src).replace(os.sep, "/")
            期望["web/" + 相对] = 全

    try:
        z = zipfile.ZipFile(jar)
        名单 = set(z.namelist())
    except (OSError, zipfile.BadZipFile) as e:
        sys.stderr.write("X 读不了这个 jar：%s\n" % e)
        return 1

    问题 = []
    for 条目, 路径 in sorted(期望.items()):
        if 条目 not in 名单:
            问题.append("jar 里没有 %s（源码有）" % 条目)
            continue
        a = 读(路径)
        b = z.read(条目)
        if a != b:
            问题.append("%s 与源码不一致：源码 %d 字节 / jar %d 字节"
                        % (条目, len(a), len(b)))
    for 条目 in sorted(名单):
        # 目录条目（以 / 结尾）不是文件，跳过 ——
        # jar 里必然有 web/ 这个目录条目，它不是「多出来的文件」。
        if 条目.endswith("/"):
            continue
        if 条目.startswith("web/") and 条目 not in 期望:
            问题.append("jar 里有源码没有的 %s" % 条目)

    if 问题:
        print("!!! jar 与源码不一致：")
        for p in 问题:
            print("   -", p)
        print("   （多半是构建产物脏了：target/classes 里留着上一轮的变异体，")
        print("    Maven 按 mtime 判定「资源无需重拷贝」就跳过了。先 clean 再构建。）")
        return 1

    print("jar 里的界面资源与源码逐字相符（%d 个文件）" % len(期望))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
