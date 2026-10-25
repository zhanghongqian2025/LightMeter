#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立校验 OOXML 脱敏结果。

判据只有一条核心的，而且刻意**不按格式枚举**：

    任何一个原值都不得出现在脱敏包里**任何一处**——任何部件、任何字节。

为什么这样写：如果校验器写成「检查 word/document.xml 里没有身份证」，
那么清单本身就成了新的「容易漏的位置」——它漏掉一个部件，
测试就跟着漏掉，而报告会说「校验通过」。把判据定成「全包扫描」之后，
新增一个部件、一个新格式、一个新位置都不需要改校验器，
漏了就会红。

逐条目扫描还顺带覆盖了两类校验器自己看不见的问题：
  - 条目名（文件名、工作表名）里的敏感值——不读条目名就查不到
  - 二进制部件（图片、嵌入对象）里的敏感值——按字节查

**碎片也要查**（块 13 加的）：标识符被段落/单元格边界切断之后，
包里剩下的不再是「18 位一串」，而是两段碎片。查完整值查不到它们——
那 18 位从来没连在一起出现过——可碎片本身就是个人信息。
所以 values.json 里除了完整值还带一份碎片清单，逐条查「一处都不剩」。

用法：
  verify_ooxml.py check  --pkg 脱敏包 --map 映射.json --values values.json
  verify_ooxml.py roundtrip --orig 原包 --redacted 脱敏包 --restored 还原包
"""

import argparse
import html
import json
import re
import sys
import zipfile

# 去掉 XML 标签：用于「碎片被拆在两个节点里」的那一类残留。
# 原始字节搜索看不见它（"110" 和 "105" 中间隔着 </w:t><w:t>），
# 但人眼在 Word 里看到的就是连续的 110105。
_TAG = re.compile(r"<[^>]*>")


def _entries(path):
    """返回 [(条目名, 原始字节)]，顺序稳定。"""
    with zipfile.ZipFile(path) as z:
        return [(i.filename, z.read(i.filename)) for i in z.infolist()]


def _views(path):
    """把包里每一处变成多个「字节视图」，返回 {视图名: 大字符串}。

    一共四个视图，每个都覆盖全部条目（含条目名）：

    raw
        原始解码文本。令牌在 OOXML 里必然存成 ``&lt;手机号1&gt;``（合法 XML，
        不转义 Word 直接解析失败），所以单看 raw 会把每个令牌都误报成
        「没写进去」——第一版校验器对 docx 报了 5 条 ng，而文件其实是对的。
    unescaped
        XML 反转义后的文本。用于「原值残留」与「令牌存在性」。
    stripped
        **先去标签再反转义**。碎片被拆在两个文本节点里时，raw 里是
        "110</w:t><w:t>105"，按字节搜 "110105" 搜不到；去掉标签后
        它就是连续的 110105，正是人眼看到的东西。
        顺序不能反：先反转义的话，令牌 ``<身份证1>`` 会被当成标签删掉。
    latin1
        保证字节级搜索覆盖非 utf-8 的部件。

    条目名也进每个视图：文件名/工作表名泄露原值是真实发生过的问题，
    而它不在任何部件的内容里，只看内容查不到。
    """
    views = {"raw": [], "unescaped": [], "stripped": [], "latin1": []}
    for name, data in _entries(path):
        raw = data.decode("utf-8", errors="replace")
        stripped = html.unescape(_TAG.sub("", raw))
        views["raw"].append(name)
        views["raw"].append(raw)
        views["unescaped"].append(name)
        views["unescaped"].append(html.unescape(raw))
        views["stripped"].append(name)
        views["stripped"].append(stripped)
        views["latin1"].append(name)
        views["latin1"].append(data.decode("latin-1"))
    return {k: "\n".join(v) for k, v in views.items()}


def _hits(views, needle):
    """返回 [(视图名, 命中次数)]，只列命中的视图。"""
    out = []
    for name in sorted(views):
        n = views[name].count(needle)
        if n:
            out.append((name, n))
    return out


def _doc_text(path):
    """抽出包里所有 w:t / a:t / t / v 的文本，用于往返逐字比对。"""
    pat = re.compile(r"<(?:\w+:)?(t|delText|v)(?:\s[^>]*)?>(.*?)</(?:\w+:)?\1>",
                     re.S)
    out = []
    for _name, data in _entries(path):
        # 反转义：原件与还原件可能一边转义一边没转义（比如还原后值里带 & <），
        # 不统一就会把「编码差异」报成「往返不一致」。
        s = html.unescape(data.decode("utf-8", errors="replace"))
        for m in pat.finditer(s):
            out.append(m.group(2))
    return "".join(out)


def _load_values(path):
    """读 values.json。

    结构：{"values": {名: 完整值}, "fragments": {名: {"value":…, "expect_type":…}}}。

    兼容只有 {"values": …} 的旧格式（旧格式没有碎片清单）。
    """
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if "values" in data and isinstance(data["values"], dict):
        return data["values"], data.get("fragments") or {}
    # 旧格式：整份就是完整值清单
    return data, {}


def cmd_check(args):
    values, fragments = _load_values(args.values)
    views = _views(args.pkg)
    ok = True

    for key, val in sorted(values.items()):
        hits = _hits(views, val)
        if hits:
            ok = False
            where = "；".join("%s 视图命中 %d 次" % (v, n) for v, n in hits)
            print("  ng  %-8s %s 在脱敏包里出现（%s）" % (key, val, where))
        else:
            print("  ok  %-8s %s 全包无残留" % (key, val))

    # 映射文件必须记全每个原值，而且原值得是精确匹配而不是子串命中
    with open(args.map, encoding="utf-8") as f:
        mp = json.load(f)
    token_map = mp.get("mapping", mp)
    if not isinstance(token_map, dict):
        print("  ng  映射文件里没有 mapping 对象")
        return False
    original_values = list(token_map.values())

    for key, val in sorted(values.items()):
        state = _mapping_records(original_values, val)
        if state == "whole":
            print("  ok  %-8s 映射里记着原值" % key)
        elif state == "fragments":
            print("  ok  %-8s 映射里记着它的片段（NER 与规则重叠时被切成了多段，见 M3-3 §1.19.8）" % key)
        else:
            ok = False
            print("  ng  %-8s 映射里找不到原值 %s（还原会失败）" % (key, val))

    # 碎片：既不能残留在包里，也必须真的被替换掉（映射里记着），
    # 而且**类型要对**——碎片被错标成手机号时，前两条都发现不了。
    for key in sorted(fragments):
        spec = fragments[key]
        frag = spec["value"] if isinstance(spec, dict) else spec
        expect_type = spec.get("expect_type") if isinstance(spec, dict) else None
        hits = _hits(views, frag)
        if hits:
            ok = False
            where = "；".join("%s 视图命中 %d 次" % (v, n) for v, n in hits)
            print("  ng  %-8s 碎片 %s 仍残留在脱敏包里（%s）" % (key, frag, where))
        else:
            print("  ok  %-8s 碎片 %s 全包无残留" % (key, frag))

        tokens = sorted(t for t, o in token_map.items() if o == frag)
        if not tokens:
            ok = False
            print("  ng  %-8s 映射里找不到碎片 %s（碎片没被脱敏）" % (key, frag))
            continue
        if len(tokens) > 1:
            ok = False
            print("  ng  %-8s 碎片 %s 对应了 %d 个令牌 %s（同值同符被破坏）"
                  % (key, frag, len(tokens), tokens))
            continue
        token = tokens[0]
        if expect_type and not token.startswith("<" + expect_type):
            ok = False
            print("  ng  %-8s 碎片 %s 的令牌是 %s，类型应为 %s"
                  % (key, frag, token, expect_type))
        else:
            print("  ok  %-8s 碎片 %s → 令牌 %s（类型正确）" % (key, frag, token))
        if token not in views["unescaped"]:
            ok = False
            print("  ng  %-8s 碎片 %s 的令牌 %s 没出现在脱敏包里" % (key, frag, token))

    # 令牌本身也必须在包里（证明确实脱敏了，而不是「原文根本没被读进来」）
    for token in sorted(token_map.keys()):
        if token not in views["unescaped"]:
            ok = False
            print("  ng  令牌 %s 没出现在脱敏包里——可能根本没处理这段" % token)
        else:
            print("  ok  令牌 %s 已写入" % token)
    return ok


def cmd_roundtrip(args):
    a = _doc_text(args.orig)
    b = _doc_text(args.restored)
    if a == b:
        print("  ok  还原后全包文本与原件逐字一致（%d 字符）" % len(a))
        return True
    print("  ng  还原后与原件不一致：原件 %d 字符，还原 %d 字符" % (len(a), len(b)))
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            print("      第一个差异在第 %d 字：原件 %r 还原 %r" % (i, a[max(0, i - 12):i + 12], b[max(0, i - 12):i + 12]))
            break
    return False


def _mapping_records(original_values, value):
    """映射里记着这个值吗？返回 "whole" / "fragments" / "missing"。

    【为什么不能只查「完整原值」】
    带模型形态下，NER 与规则重叠时，重叠消解会把一个标识符**切成多段**
    （docs/M3-3-阶段状态.md §1.19.8 的定论：维持拆分、不合并映射——
    合并会造出源文件里根本不存在的原值，拿它当审计证据更危险）。
    于是映射里记的是**片段**：「zhang@example」+「.com」，
    而不是完整的「zhang@example.com」。
    只查完整原值的话，带模型形态下这一条**恒红**——
    而那个红不是产品的问题，是判据实现错了自己的本意：
    它自己写的失败理由是「还原会失败」，可拆分之后还原照样逐字成功。

    【判据不能因此放松成「什么都行」】
    这里判的是**能不能还原**：这个值必须能被映射里记着的东西**拼**回来。
    拼不出来 = 还原不了 = 原判据本来要抓的那个问题，必须照样抓。
    「拼」的定义：把映射里记着的原值按出现顺序连成一条，
    这个值必须是这条的子串（切分是连续的，所以连起来就是原值）。
    少一段就拼不出来 —— 那正是「映射漏记了」的样子，必须红。
    """
    if not value:
        return "missing"
    if value in original_values:
        return "whole"
    joined = "".join(original_values)
    if value in joined:
        return "fragments"
    return "missing"


def selftest():
    """判据的判别力自证：这条检查必须会咬，也必须不误报。"""
    ok = 0
    bad = 0

    def case(desc, got, want):
        nonlocal ok, bad
        if got == want:
            print("  PASS %s" % desc)
            ok += 1
        else:
            print("  FAIL %s（期望 %s，实际 %s）" % (desc, want, got))
            bad += 1

    print("== verify_ooxml 映射判据自证 ==")

    # 正控制 1：映射里有完整原值
    case("完整原值 → whole",
         _mapping_records(["11010519491231002X"], "11010519491231002X"), "whole")
    # 正控制 2：映射里**没有**完整原值、只有能拼成它的两段 → fragments
    # （期望值一开始写成了 whole，那是**我写错了**：映射里确实没有完整原值，
    #   身份证被切成了两段，说它 whole 就是在撒谎）
    case("只有能拼成它的两段 → fragments",
         _mapping_records(["110105", "19491231002X", "13800138000"], "11010519491231002X"),
         "fragments")

    # 带模型形态的真实形态：标识符被切成两段
    frag = ["110105", "19491231002X", "13800138000", "zhang@example", ".com"]
    case("被切成两段 → fragments",
         _mapping_records(frag, "zhang@example.com"), "fragments")
    case("被切成两段时，完整原值仍算 whole",
         _mapping_records(["zhang@example.com", "zhang@example"], "zhang@example.com"),
         "whole")

    # 负控制 1：映射里**完全没有**这个值 → 必须 missing
    case("映射里没有这个值 → missing",
         _mapping_records(["110105", "13800138000"], "zhang@example.com"), "missing")
    # 负控制 2：映射里只有**一部分**（少了 @example）→ 必须 missing
    case("映射里少了一段 → missing",
         _mapping_records(["zhang", ".com"], "zhang@example.com"), "missing")
    # 负控制 3：映射里全是无关的值 → 必须 missing
    case("映射里全是无关值 → missing",
         _mapping_records(["abc", "def"], "zhang@example.com"), "missing")
    # 负控制 4：空映射 → 必须 missing
    case("空映射 → missing", _mapping_records([], "zhang@example.com"), "missing")

    # 差分自证：退回**老判据**（只查完整原值），「切成两段」那条必须失败。
    # 证明新判据不是恒真——它与老判据在片段这一族上**判得不一样**。
    def old_rule(original_values, value):
        return "whole" if value in original_values else "missing"

    case("差分：老判据在「切成两段」上判 missing，与新判据不同",
         old_rule(frag, "zhang@example.com"), "missing")
    # 差分自证：老判据在「完整原值」上与新判据一致（说明新判据没有把真问题放过）
    case("差分：老判据在「完整原值」上同样判 whole",
         old_rule(frag, "13800138000"), "whole")

    print("== 自证：%d 条通过，%d 条失败 ==" % (ok, bad))
    return 0 if bad == 0 else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true",
                    help="只跑映射判据的自证（不碰任何文件）")
    sub = ap.add_subparsers(dest="cmd", required=False)

    c = sub.add_parser("check")
    c.add_argument("--pkg", required=True)
    c.add_argument("--map", required=True)
    c.add_argument("--values", required=True)
    c.set_defaults(fn=cmd_check)

    r = sub.add_parser("roundtrip")
    r.add_argument("--orig", required=True)
    r.add_argument("--redacted", required=True)
    r.add_argument("--restored", required=True)
    r.set_defaults(fn=cmd_roundtrip)

    args = ap.parse_args()
    if args.selftest:
        sys.exit(selftest())
    if not args.cmd:
        ap.error("需要子命令 check / roundtrip（或用 --selftest）")
    sys.exit(0 if args.fn(args) else 1)


if __name__ == "__main__":
    main()
