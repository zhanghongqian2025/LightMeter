#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立标尺：核对「只保留第 N 次出现」在**文件里**真的生效了。

【为什么要有它】
    界面上勾了一个框、报文里带了序号，都不等于文件里真的只留下了那一处。
    这一条是端到端里唯一决定性的判据：
    **文件本身**必须只留下用户指定的那一次出现，其余照常脱敏。

【标尺独立于被测实现】
    只用 Python 标准库（zipfile + re）读 docx：
    不 import 任何项目代码，不调用本工具的任何类。
    「令牌长什么样」也不写死——它从文件里**读出来**：
    同一份文档里同一个值的各次出现，脱敏后应当拿到**同一个**令牌。

【判定】
    · 目标段落（按出现序号数的第 keep-index 段）里应当**还有**原值；
    · 其余各段里应当**没有**原值，且都带着**同一个**令牌；
    · 整个包里原值应当**只出现一次**。

【用法】
    python3 verify_occurrence_keep.py <脱敏件.docx> \\
        --value 13800138000 --keep-index 3 \\
        --paragraph-prefix 联系人电话 --expect-occurrences 5
退出码 0 = 通过；1 = 不通过（并打印原因）。
"""
import argparse
import re
import sys
import zipfile

# <w:p> … </w:p>；段落里的文字在 <w:t>…</w:t>（含 xml:space 属性的那些也一样）
P_RE = re.compile(r"<w:p[ >].*?</w:p>", re.S)
T_RE = re.compile(r"<w:t(?:\s[^>]*)?>(.*?)</w:t>", re.S)
# 令牌：OOXML 侧写成 <手机号1> 这种尖括号形式
TOKEN_RE = re.compile(r"<[^<>]+>")

# XML 实体。
#
# 【为什么必须反转义】OOXML 里尖括号是**转义**着存的：
# 令牌在文件里长的是「尖括号被转义成 lt / gt 的形式」，不是尖括号本身。
# 不反转义就拿原始字节去找尖括号，一个都匹配不到 →
# 每一段都被判成「既没有原值、也没有令牌」→ 标尺报红，
# 而报红的原因是**标尺自己坏了**，不是被测行为坏了。
#
# 顺序：& 必须**最后**替换，否则 "&" + "lt;" 会被解成 "<"（而不是 "<"）。
#
# 【实体名一律用拼接写】文件里**不出现完整的实体字面量**：
# 直接写实体字面量的话，写进文件那一步会被解码成裸字符 →
# 实体表变成空操作，还把 Python 的三引号解析搞坏（实测过一次）。
实体表 = (
    ("&" + "lt;", "<"),
    ("&" + "gt;", ">"),
    ("&" + "quot;", '"'),
    ("&" + "apos;", "'"),
    ("&" + "amp;", "&"),
)


def 反转义(text):
    for a, b in 实体表:
        text = text.replace(a, b)
    return text


def 段落文字(xml_text):
    out = []
    for m in P_RE.finditer(xml_text):
        parts = T_RE.findall(m.group(0))
        out.append(反转义("".join(parts)))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("docx")
    ap.add_argument("--value", required=True, help="原值（期望只留下一次的那个）")
    ap.add_argument("--keep-index", type=int, required=True,
                    help="保留的是第几次出现（0 基，按文件里的段落顺序数）")
    ap.add_argument("--paragraph-prefix", required=True,
                    help="哪些段落是这个值的出现处（按这个前缀认）")
    ap.add_argument("--expect-occurrences", type=int, required=True,
                    help="这个值一共出现多少次")
    args = ap.parse_args()

    problems = []
    try:
        with zipfile.ZipFile(args.docx) as z:
            names = z.namelist()
            blob = "".join(z.read(n).decode("utf-8", "replace")
                            for n in names if n.endswith(".xml"))
            doc = z.read("word/document.xml").decode("utf-8", "replace")
    except (OSError, KeyError, zipfile.BadZipFile) as e:
        print("!!! 读不了这个 docx：%s" % e)
        return 1

    段落 = 段落文字(doc)
    目标段 = [p for p in 段落 if args.paragraph_prefix in p]
    print("== 段落核对 ==")
    print("带前缀的段落数：%d（期望 %d）"
          % (len(目标段), args.expect_occurrences - 1))
    if len(目标段) != args.expect_occurrences - 1:
        problems.append("带前缀的段落数不对：%d（期望 %d）"
                        % (len(目标段), args.expect_occurrences - 1))

    # ---- 保留的那一处：应当还有原值 ----
    if 0 <= args.keep_index < len(目标段):
        保留 = 目标段[args.keep_index]
        if args.value not in 保留:
            problems.append("第 %d 段里没有原值了（保留的应当是它）：%r"
                            % (args.keep_index, 保留))
        else:
            print("第 %d 段保留了原值 ✓（%r）" % (args.keep_index, 保留))
    else:
        problems.append("keep-index %d 越界（只有 %d 段）"
                        % (args.keep_index, len(目标段)))

    # ---- 其余各处：原值不该在，且都带着同一个令牌 ----
    令牌集 = set()
    for i, p in enumerate(目标段):
        if i == args.keep_index:
            continue
        if args.value in p:
            problems.append("第 %d 段里还有原值（它应当照常脱敏）：%r" % (i, p))
            continue
        m = TOKEN_RE.search(p)
        if not m:
            problems.append("第 %d 段里既没有原值、也没有令牌：%r" % (i, p))
            continue
        令牌集.add(m.group(0))
    if len(令牌集) > 1:
        problems.append("同一个值的各次出现拿到了不同的令牌：%s" % sorted(令牌集))
    elif 令牌集:
        print("其余各处都换成了同一个令牌：%s ✓" % sorted(令牌集)[0])
    if 令牌集 and sorted(令牌集)[0] == args.value:
        problems.append("令牌就是原值（等于没脱）")

    # ---- 整个包里，原值只该出现一次 ----
    #
    # 在**解码后**的文本上数：原值若是带 & < > 的字符，
    # 原始字节里它是转义形式，直接 count 会数成 0（假绿）。
    全量次数 = 反转义(blob).count(args.value)
    print("整个包里原值出现 %d 次（期望 1）" % 全量次数)
    if 全量次数 != 1:
        problems.append("整个包里原值出现了 %d 次（期望 1 次）" % 全量次数)

    print()
    if problems:
        print("!!! 标尺报红：")
        for p in problems:
            print("   -", p)
        return 1
    print("标尺通过：只保留了那一次出现，其余照常脱敏 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
