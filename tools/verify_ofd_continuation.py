#!/usr/bin/env python3
"""OFD 跨对象续接的**独立**校验器。

【为什么校验器要另写一遍】
    被测实现自己验自己，只能证明内部自洽。
    这里用 Python 重新实现了一遍「OFD 里文本在哪、令牌长什么样、版面变没变」，
    **不 import 任何项目代码**——理由同 tools/verify_ofd.py。

【判定口径】
  * 「原文还在不在」按**字节级**扫全包（含 zip 条目名）。
  * 「版面变没变」按**几何**判：脱敏前后的 TextObject (ID, Boundary) 序列必须逐项相同。
    续接只做「归类不合并」，绝不允许把右栏的内容拉到左栏。
  * 「令牌归谁」按**位置**判：前半的令牌写回前半那个对象，后半的写回后半那个。
  * 期望值全部来自**清单**（samples.json），校验器里不写死任何期望。

【INVALID 与 FAIL 分开】
    「命令没跑成」「样本自检没过」一律记成 notes 里的作废项，
    不混进 failures——否则读的人会把「没测成」当成「测出问题」。
"""

import json
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

# 令牌形态：OFD 侧用中文令牌（PDF 侧才用 ASCII）。
TOKEN_RE = re.compile(r"<[^<>]*(?:身份证|手机号|邮箱|银行卡号|信用代码|发票号码)[^<>]*>")

failures = []
notes = []
invalid = []
# 断言计数：每条判据算一条。
# 【为什么要数】文档里要写「端到端 N 条断言全过」——
#   那个数必须**现算**，不能手抄（手抄的计数一定会漂）。
n_checks = 0


def count():
    global n_checks
    n_checks += 1


def fail(msg):
    failures.append(msg)


def invalidate(msg):
    invalid.append(msg)


def notes_add(msg):
    notes.append(msg)


def parts(path):
    with zipfile.ZipFile(path) as zf:
        return {n: zf.read(n) for n in zf.namelist() if not n.endswith("/")}


def blob(path):
    """全包文本：条目名 + 每个部件的内容。条目名最容易被忘。"""
    out = []
    for name, data in parts(path).items():
        out.append(name)
        out.append(data.decode("utf-8", errors="ignore"))
    return "\n".join(out)


def local(tag):
    return tag.split("}")[-1]


def text_objects(path):
    """返回 [(部件名, TextObject 的 ID, Boundary, 该对象下所有 TextCode 文字)]。"""
    out = []
    for name, data in sorted(parts(path).items()):
        if not name.endswith(".xml"):
            continue
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            continue
        for el in root.iter():
            if local(el.tag) != "TextObject":
                continue
            text = "".join((c.text or "") for c in el.iter() if local(c.tag) == "TextCode")
            out.append((name, el.get("ID"), el.get("Boundary"), text))
    return out


def text_codes(path):
    """返回 [(部件名, TextCode 文字)]，按文档顺序。"""
    out = []
    for name, data in sorted(parts(path).items()):
        if not name.endswith(".xml"):
            continue
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            continue
        for el in root.iter():
            if local(el.tag) == "TextCode":
                out.append((name, el.text or ""))
    return out


def geometry(path):
    return [(name, oid, boundary) for name, oid, boundary, _ in text_objects(path)]


def load_changes(map_path):
    with open(map_path, encoding="utf-8") as fh:
        payload = json.load(fh)
    return payload.get("changes", []), payload.get("mapping", {})


def pick_case(manifest, name):
    for c in manifest["cases"]:
        if c["name"] == name:
            return c
    fail("清单里没有名为 %r 的场景" % name)
    return None


def check_case(manifest, case, original, redacted, map_path):
    head = manifest["idHead"]
    tail = manifest["idTail"]
    full = manifest["idFull"]
    expect = case["expectIdCardHits"]

    # 1. 样本自检：脱敏前原文必须在，否则后面的「0 残留」是空转。
    before = blob(original)
    for v in (head, tail):
        count()
        if v not in before:
            invalidate("样本自检失败：%r 本来就不在样本里，后续检查是空转" % v)
            return
    notes_add("样本自检：两半原文确认在脱敏前的包里")

    # 2. 脱敏后原文的残留情况按**清单**判定。
    #    「前半应当残留」是刻意的 fail-open：孤立的 6 位凑不出 18 位，判不出来，
    #    没有续接就没有「这是一张身份证」这个事实可依据。
    #    → 若把它写死成「一律不残留」，就会把**正确**的行为判成失败——
    #      那是标尺自己的错，不是产品的错。
    after = blob(redacted)
    want = {
        head: case.get("expectHeadGone", True),
        tail: case.get("expectTailGone", True),
        full: case.get("expectFullGone", True),
    }
    for v, should_be_gone in want.items():
        count()
        present = v in after
        if should_be_gone and present:
            fail("脱敏后原文 %r 仍残留在包里（清单要求它被脱敏）" % v)
        elif not should_be_gone and not present:
            fail("脱敏后原文 %r 不在了（清单要求它保留：这是 fail-open 的预期）" % v)
    gone = [v for v, g in want.items() if g and v not in after]
    kept = [v for v, g in want.items() if not g]
    notes_add("脱敏后残留核对：应消失且已消失 %d 项；清单要求保留 %s"
              % (len(gone), kept if kept else "无"))

    # 2b. 「没被拼起来」的**直接**证据：任何一个 TextObject 里都不该同时出现两半。
    #     有了这条，「两栏被拼成一条」这种错法就不会只靠「id_card 命中数」来挡。
    for name, oid, boundary, text in text_objects(redacted):
        count()
        if head in text and tail in text:
            fail("对象 %s（部件 %s）里同时出现了前半与后半：两段被拼进同一个对象了" % (oid, name))
    if not any("拼进同一个对象" in f for f in failures):
        notes_add("没有任何一个对象里同时出现前半与后半（没有被拼起来）")

    # 3. 映射里的 id_card 命中数。
    changes, _ = load_changes(map_path)
    id_card = [c for c in changes if c.get("ruleKey") == "id_card"]
    count()
    if len(id_card) != expect:
        fail("id_card 命中 %d 条，期望 %d 条（实际规则：%s）"
             % (len(id_card), expect,
                sorted({c.get("ruleKey") for c in changes})))
    else:
        notes_add("映射：id_card 命中 %d 条，与清单期望一致" % expect)

    if expect > 0:
        originals = {c.get("original") for c in id_card}
        count()
        if originals != {head, tail}:
            fail("id_card 命中的原值集合 %s，期望 {%r, %r}" % (sorted(originals), head, tail))
        tokens = [c.get("token") for c in id_card]
        count()
        if len(set(tokens)) != len(tokens):
            fail("两个令牌相同（%s）：碎片必须各拿各的令牌" % tokens)
        for t in tokens:
            count()
            if "身份证" not in t:
                fail("令牌 %r 不含规则名「身份证」" % t)
        if not any(f for f in failures if "令牌" in f):
            notes_add("两个碎片各拿各的令牌：%s" % "、".join(str(t) for t in tokens))

        # 4. 令牌写回各自的对象（位置对得上，才谈得上「版面没坏」）。
        objs = text_objects(redacted)
        count()
        if len(objs) != 2:
            fail("页面里 TextObject 数是 %d，期望 2" % len(objs))
        else:
            by_original = {c.get("original"): c.get("token") for c in id_card}
            first, second = objs[0][3], objs[1][3]
            count()
            if by_original.get(head) and by_original[head] not in first:
                fail("前半的令牌没写回前半那个对象：对象1 文字=%r" % first)
            count()
            if by_original.get(tail) and by_original[tail] not in second:
                fail("后半的令牌没写回后半那个对象：对象2 文字=%r" % second)
            count()
            if "身份证：" not in first:
                fail("对象1 的前缀「身份证：」不见了：%r" % first)
            if not any(f for f in failures if "对象" in f):
                notes_add("令牌各归各的对象：对象1=%r 对象2=%r" % (first, second))

    # 5. 版面（几何）必须一字不动。
    g0 = geometry(original)
    g1 = geometry(redacted)
    count()
    if g0 != g1:
        fail("版面变了：脱敏前 %s，脱敏后 %s" % (g0, g1))
    else:
        notes_add("版面未变：%d 个 TextObject 的 (ID, Boundary) 逐项相同" % len(g0))


def check_roundtrip(manifest, case, original, restored):
    head = manifest["idHead"]
    tail = manifest["idTail"]

    back = blob(restored)
    for v in (head, tail):
        count()
        if v not in back:
            fail("还原后原文 %r 不在包里" % v)
    count()
    leftover = TOKEN_RE.findall(back)
    if leftover:
        fail("还原后还残留令牌：%s" % sorted(set(leftover)))
    if not any(f for f in failures if "令牌" in f or "不在包里" in f):
        notes_add("还原：两半原文回来了，且没有令牌残留")

    g0 = geometry(original)
    g1 = geometry(restored)
    count()
    if g0 != g1:
        fail("还原后版面变了：%s vs %s" % (g0, g1))
    else:
        notes_add("还原后版面未变")

    t0 = text_codes(original)
    t1 = text_codes(restored)
    count()
    if t0 != t1:
        fail("还原后的文字与原文不一致\n  原文: %s\n  还原: %s" % (t0, t1))
    else:
        notes_add("往返一致：%d 个 TextCode 文字逐条相同" % len(t0))


def main():
    if len(sys.argv) < 4:
        print("用法:\n"
              "  verify_ofd_continuation.py case <清单.json> <场景名> <脱敏前.ofd> <脱敏后.ofd> <映射.json>\n"
              "  verify_ofd_continuation.py roundtrip <清单.json> <场景名> <脱敏前.ofd> <还原后.ofd>",
              file=sys.stderr)
        return 2
    cmd = sys.argv[1]
    with open(sys.argv[2], encoding="utf-8") as fh:
        manifest = json.load(fh)
    case = pick_case(manifest, sys.argv[3])
    if case is None:
        for f in failures:
            print("  ✗ " + f, file=sys.stderr)
        return 1

    if cmd == "case":
        check_case(manifest, case, sys.argv[4], sys.argv[5], sys.argv[6])
    elif cmd == "roundtrip":
        check_roundtrip(manifest, case, sys.argv[4], sys.argv[5])
    else:
        print("未知子命令 %s" % cmd, file=sys.stderr)
        return 2

    for n in notes:
        print("  " + n)
    for f in failures:
        print("  ✗ " + f, file=sys.stderr)
    for i in invalid:
        print("  !! 作废：" + i, file=sys.stderr)
    if invalid:
        print("校验作废 %d 项（这次没测成，不能当证据）" % len(invalid), file=sys.stderr)
        return 1
    if failures:
        print("校验失败 %d 项" % len(failures), file=sys.stderr)
        return 1
    print("  校验通过（%d 条断言）" % n_checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
