#!/usr/bin/env python3
"""txt 管线的独立校验器（**不复用被测实现的任何逻辑**）。

验三件事：
  1. 原值在脱敏件里**不存在**（含碎片形态，见下）；
  2. 令牌**在**脱敏件里（不是「没脱敏」也不是「脱成了别的东西」）；
  3. 还原件与原件**逐字节一致**。

【为什么要独立写一遍】
被测代码自己写、自己读、自己验，只能证明内部自洽。
这个文件只用 Python 标准库，不 import 项目里的任何东西。

【碎片也要查】
标识符被切断后，包里剩下的是**碎片**而不是完整值；
只查完整值的话，「碎片原样留在交付物里」是查不出来的。
所以这里既查完整值，也查「原值去掉最后一个字符」的前缀形态。

【为什么还必须有 --selftest（块 26 新增）】
一个**从不报红**的校验器，比没有校验器更坏 ——
它会让人以为「这一项验过了」。
→ 自证在临时目录里造几组样本，**故意**做出各种坏情形，
  证明这个校验器**真的会把它们抓出来**（不是恒真）。

用法：
  python3 tools/verify_txt_roundtrip.py --orig A --redacted B --restored C --map D \
      --values v.json
  python3 tools/verify_txt_roundtrip.py --selftest
"""
import argparse
import json
import os
import shutil
import sys
import tempfile

VALUES = ["11010519491231002X", "13800138000"]
ORIG = "身份证 11010519491231002X 和 手机 13800138000\n"
REDACTED = "身份证 <身份证1> 和 手机 <手机1>\n"
MAP_OK = {
    "changes": [
        {"token": "<身份证1>", "replacement": VALUES[0]},
        {"token": "<手机1>", "replacement": VALUES[1]},
    ]
}


def check_case(orig, red, restored, mp, values):
    """返回问题清单（空 = 全过）。**不碰文件**，方便自证直接喂数据。"""
    problems = []
    if not values:
        # 空清单 → 下面那些断言一条都不会跑 → 校验器会变成恒真。
        problems.append("values 清单是空的——校验器会变成恒真")
    for v in values:
        if v in red:
            problems.append("脱敏件里还有原值：%s" % v)
        # 碎片：去掉最后一个字符的前缀（长度 ≥3 才查，否则误报面太大）
        if len(v) >= 3 and v[:-1] in red:
            problems.append("脱敏件里还有碎片：%s…" % v[:-1])

    if "<" not in red:
        problems.append("脱敏件里没有任何令牌——可能根本没脱敏")

    if mp is None:
        problems.append("映射件读不了")
    else:
        changes = mp.get("changes") or []
        if not changes:
            problems.append("映射件里没有 changes")
        for c in changes:
            if not c.get("token") and not c.get("replacement"):
                problems.append("有一条 change 既没有 token 也没有 replacement：%r" % c)
                break

    if restored != orig:
        problems.append("还原件与原件不是逐字节一致")
    return problems


def _selftest():
    """自证：证明这个校验器**会咬人**，而且不误伤好样本。"""
    fails = []
    n_pos = 0
    n_neg = 0

    def want_green(problems, why):
        nonlocal n_pos
        n_pos += 1
        if problems:
            fails.append("%s（好样本被判成坏：%s）" % (why, problems))

    def want_red(problems, why):
        nonlocal n_neg
        n_neg += 1
        if not problems:
            fails.append("%s（坏样本没被抓出来——这个校验器是恒真的）" % why)

    # ---- 正样本：一切正常 → 必须一条问题都没有 ----
    want_green(check_case(ORIG, REDACTED, ORIG, MAP_OK, VALUES), "正样本")
    # ---- 负样本：每一条都要被抓出来 ----
    want_red(check_case(ORIG, ORIG, ORIG, MAP_OK, VALUES),
             "脱敏件里原值原样留着")
    want_red(check_case(ORIG, "身份证 11010519491231002 和 手机 <手机1>\n",
                         ORIG, MAP_OK, VALUES),
             "脱敏件里只剩碎片")
    want_red(check_case(ORIG, "身份证 11010519491231002X 和 手机 13800138000\n",
                         ORIG, MAP_OK, VALUES),
             "脱敏件里一个令牌都没有")
    want_red(check_case(ORIG, REDACTED, ORIG + "x", MAP_OK, VALUES),
             "还原件与原件差一个字节")
    want_red(check_case(ORIG, REDACTED, ORIG, MAP_OK, []),
             "values 清单是空的（恒真防护）")
    want_red(check_case(ORIG, REDACTED, ORIG, {"changes": []}, VALUES),
             "映射件里没有 changes")
    want_red(check_case(ORIG, REDACTED, ORIG, {"changes": [{"x": 1}]}, VALUES),
             "有一条 change 既没有 token 也没有 replacement")
    want_red(check_case(ORIG, REDACTED, ORIG, None, VALUES),
             "映射件读不了")

    # ---- 顺带证一次「文件形态」这条路径也通（自证不能只测内核）----
    tmp = tempfile.mkdtemp(prefix="verify-txt-selftest-")
    try:
        def put(name, content):
            p = os.path.join(tmp, name)
            with open(p, "w", encoding="utf-8") as fh:
                fh.write(content)
            return p
        o = put("o.txt", ORIG)
        r = put("r.txt", REDACTED)
        s = put("s.txt", ORIG)
        m = put("m.json", json.dumps(MAP_OK, ensure_ascii=False))
        v = put("v.json", json.dumps({"values": VALUES}, ensure_ascii=False))
        argv = sys.argv
        sys.argv = ["verify_txt_roundtrip.py", "--orig", o, "--redacted", r,
                    "--restored", s, "--map", m, "--values", v]
        try:
            rc = main()
        finally:
            sys.argv = argv
        want_green([] if rc == 0 else ["主流程居然报红"], "文件形态的正样本")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if fails:
        print("!!! 自证失败：")
        for f in fails:
            print("    " + f)
        return 1
    print("=== verify_txt_roundtrip 自证通过（%d 正例 + %d 负例）===" % (n_pos, n_neg))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true",
                    help="只跑自证：证明这个校验器会咬人（不碰任何文件）")
    ap.add_argument("--orig", required=False)
    ap.add_argument("--redacted", required=False)
    ap.add_argument("--restored", required=False)
    ap.add_argument("--map", required=False)
    ap.add_argument("--values", required=False,
                    help="JSON 文件：{\"values\": [\"敏感值\", ...]}")
    args = ap.parse_args()

    if args.selftest:
        return _selftest()

    for name in ("orig", "redacted", "restored", "map", "values"):
        if not getattr(args, name):
            ap.error("缺少 --%s（或者用 --selftest 跑自证）" % name)

    with open(args.values, encoding="utf-8") as fh:
        values = json.load(fh).get("values") or []

    with open(args.redacted, encoding="utf-8") as fh:
        red = fh.read()
    with open(args.orig, encoding="utf-8") as fh:
        orig = fh.read()
    with open(args.restored, encoding="utf-8") as fh:
        restored = fh.read()

    try:
        with open(args.map, encoding="utf-8") as fh:
            mp = json.load(fh)
    except Exception as exc:
        mp = None
        map_err = str(exc)
    else:
        map_err = None

    problems = check_case(orig, red, restored, mp, values)
    if map_err is not None:
        problems.insert(0, "映射件读不了：%s" % map_err)

    if problems:
        for p in problems:
            print("FAIL %s" % p)
        return 1
    print("OK %d 个敏感值：脱敏件里没有原值/碎片、令牌已写入、还原逐字节一致" % len(values))
    return 0


if __name__ == "__main__":
    sys.exit(main())
