#!/usr/bin/env python3
"""映射件封装格式的**独立标尺**。

【为什么标尺要独立】
    被测对象是「映射件有没有被加密」。如果用产品自己的代码去读这个文件，
    判据就变成「产品读得懂自己写的东西」——产品不加密时它照样读得懂
    （读成空映射），于是这条判据**恒真**。
    → 本脚本只用 Python 标准库（json / base64 / hashlib），
      **不 import 任何产品代码**，也不依赖任何第三方包。

【它查什么（一条都不许靠产品代码）】
    1. 文件是合法 JSON，且是一个对象；
    2. format == "litemask-sealed-map"、version == 1；
    3. kdf：算法名、轮数（不低于下限）、盐是 16 字节的合法 Base64；
    4. cipher：算法名、nonce 是 12 字节的合法 Base64；
    5. ciphertext 是合法 Base64，且长度不短于 GCM 的认证标签（16 字节）；
    6. **文件里不出现给定的任何一个原值**——原样、Base64、hex 三种形式都不许出现；
    7. **文件里不出现明文映射的特征键**（mapping / changes / original）：
       「只是换了个编码」也算没加密。

【为什么第 6、7 条是关键】
    第 1..5 条只证明「格式长得像」；真正要证明的是「原值不在文件里」。
    只查格式的话，把映射件原样抄一遍再套个壳，1..5 条照样全过。

【退出码】
    0 = 全部通过；1 = 有断言失败；2 = 用法错
"""
import argparse
import base64
import json
import re
import sys

FORMAT = "litemask-sealed-map"
VERSION = 1
MIN_ITERATIONS = 10000
SALT_BYTES = 16
NONCE_BYTES = 12
TAG_BYTES = 16

# 明文映射件的特征键。封装件里一个都不该有。
PLAINTEXT_KEYS = ("mapping", "changes", "original")

failures = []
checks = 0


def ok(msg):
    global checks
    checks += 1
    print("  PASS %s" % msg)


def bad(msg):
    global checks
    checks += 1
    failures.append(msg)
    print("  FAIL %s" % msg)


def check(cond, good, badmsg):
    if cond:
        ok(good)
    else:
        bad(badmsg)


def decode_b64(value, name, expect_len=None):
    if not isinstance(value, str) or not value:
        bad("%s 不是非空字符串" % name)
        return None
    try:
        raw = base64.b64decode(value, validate=True)
    except Exception as exc:  # noqa: BLE001
        bad("%s 不是合法 Base64（%s）" % (name, exc))
        return None
    if expect_len is not None and len(raw) != expect_len:
        bad("%s 长度应为 %d 字节，实际 %d" % (name, expect_len, len(raw)))
        return None
    return raw


def verify(path, forbidden):
    with open(path, "rb") as fh:
        raw = fh.read()
    text = raw.decode("utf-8", errors="replace")

    try:
        doc = json.loads(text)
    except Exception as exc:  # noqa: BLE001
        bad("文件不是合法 JSON（%s）" % exc)
        return
    check(isinstance(doc, dict), "文件是 JSON 对象", "文件不是 JSON 对象")

    check(doc.get("format") == FORMAT,
          "format 是 %s" % FORMAT,
          "format 应为 %s，实际 %r" % (FORMAT, doc.get("format")))
    check(doc.get("version") == VERSION,
          "version 是 %d" % VERSION,
          "version 应为 %d，实际 %r" % (VERSION, doc.get("version")))

    kdf = doc.get("kdf")
    check(isinstance(kdf, dict), "kdf 段在", "kdf 段缺失或不是对象")
    if isinstance(kdf, dict):
        check(kdf.get("algo") == "PBKDF2-HMAC-SHA256",
              "kdf 算法是 PBKDF2-HMAC-SHA256",
              "kdf 算法不对：%r" % (kdf.get("algo"),))
        it = kdf.get("iterations")
        check(isinstance(it, int) and it >= MIN_ITERATIONS,
              "kdf 轮数 %s 不低于下限 %d" % (it, MIN_ITERATIONS),
              "kdf 轮数不对：%r（下限 %d）" % (it, MIN_ITERATIONS))
        decode_b64(kdf.get("salt"), "盐", SALT_BYTES)

    cipher = doc.get("cipher")
    check(isinstance(cipher, dict), "cipher 段在", "cipher 段缺失或不是对象")
    if isinstance(cipher, dict):
        check(cipher.get("algo") == "AES-256-GCM",
              "加密算法是 AES-256-GCM",
              "加密算法不对：%r" % (cipher.get("algo"),))
        decode_b64(cipher.get("nonce"), "nonce", NONCE_BYTES)

    ct = decode_b64(doc.get("ciphertext"), "密文")
    if ct is not None:
        check(len(ct) >= TAG_BYTES,
              "密文 %d 字节（不短于认证标签）" % len(ct),
              "密文只有 %d 字节，短于 GCM 认证标签 %d 字节" % (len(ct), TAG_BYTES))

    # ---- 关键：原值不许出现在文件里（原文 / base64 / hex 三种形式）----
    for value in forbidden:
        v = value if isinstance(value, str) else str(value)
        variants = {
            "原文": v,
            "base64": base64.b64encode(v.encode("utf-8")).decode("ascii"),
            "base64(无补位)": base64.b64encode(v.encode("utf-8")).decode("ascii").rstrip("="),
            "hex": v.encode("utf-8").hex(),
        }
        for kind, needle in variants.items():
            if not needle:
                continue
            check(needle not in text,
                  "文件里没有原值的%s形式" % kind,
                  "文件里出现了原值的%s形式" % kind)

    # ---- 「只是换了个编码」也不算加密 ----
    for key in PLAINTEXT_KEYS:
        check(not re.search(r'"%s"\s*:' % re.escape(key), text),
              "文件里没有明文特征键 %r" % key,
              "文件里出现了明文特征键 %r → 里面装的是明文映射" % key)

    # ---- 密文**解出来**之后也不许有原值 ----
    #
    # 【为什么上面那些检查还不够】
    #   最省事的「假加密」是：把整张映射表 base64 一遍塞进 ciphertext 字段。
    #   这样文件里**看不到**原值（base64 之后是乱码），
    #   上面第 6 条的「原样 / base64 / hex 三种形式」也都查不到
    #   （因为原值在整张表的 base64 里，不是在文件里单独出现的那段 base64）。
    #   → 必须把 ciphertext 按 base64 解开再查一遍：
    #     真加密解出来是随机字节，假加密解出来就是明文。
    if ct is not None:
        ct_text = ct.decode("utf-8", errors="replace")
        for value in forbidden:
            v = value if isinstance(value, str) else str(value)
            check(v not in ct_text,
                  "密文解出来后没有原值",
                  "密文解出来之后出现了原值 → 这是「只做了 base64」的假加密")
        for key in PLAINTEXT_KEYS:
            check(not re.search(r'"%s"\s*:' % re.escape(key), ct_text),
                  "密文解出来后没有明文特征键 %r" % key,
                  "密文解出来之后出现了明文特征键 %r → 这是「只做了 base64」的假加密" % key)


def main():
    ap = argparse.ArgumentParser(description="映射件封装格式的独立标尺")
    ap.add_argument("path", help="映射件路径")
    ap.add_argument("--forbid", action="append", default=[],
                    help="不许出现在文件里的原值（可重复）")
    args = ap.parse_args()

    verify(args.path, args.forbid)
    print("  ---- 标尺结论：%d 条检查，%d 条失败 ----" % (checks, len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
