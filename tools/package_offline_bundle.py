#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「模型包 + 它的签名」打成一个**单文件**的离线模型包。

【为什么要有这个】
    界面上的「选择离线模型包」按钮，用户只需要选**一个文件**。
    而模型包的原始分发形态是**两个文件**：`<包名>.zip` 与 `<包名>.zip.sig`
    （签名是按「同名 + .sig」被找到的，见 ModelTrust.signatureFileFor）。

    让普通用户自己去把这两个文件凑在一起下载、再分别指给程序，
    是最典型的「装到一半放弃」场景——而这个工具的产品承诺恰恰是
    「不需要用户自己找 .sha256、.sig 或公钥」。

    → 所以这里把它们（外加一份说明）装进**一个 zip**：
      用户只选这一个文件，程序自己拆开、验签、安装。

【离线包长什么样】
    LightMeter-离线模型包-<模型id>.zip
      ├── <模型id>.zip          ← 模型包本体
      ├── <模型id>.zip.sig      ← 它的签名（发布方私钥签的）
      └── 说明.txt              ← 给用户看的一页说明

【验签仍然照旧】
    本工具**不新增**任何信任关系：它只是把两个文件装进一个 zip。
    真正的验签仍由 ModelTrust 用**内置信任公钥**在安装那一刻做。
    离线包本身**不需要**签名（它没被当成模型包来验），
    但它里面装的那个模型包**必须**验得过 —— 否则装不上。

【用法】
    python3 tools/package_offline_bundle.py --model dist/litemask-ner-zh-char-v3.zip \
        --out dist/LightMeter-离线模型包-litemask-ner-zh-char-v3.zip
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile

README = """LightMeter 离线识别模型包
================================

这个文件里装着「人名与机构名识别模型」以及它的**验证材料**。
用法：打开 LightMeter → 界面顶部的「识别模型」一栏 → 点「选择离线模型包」
→ 选中这个文件 → 等它显示「人名与机构名识别已就绪」。

不需要另外找 .sha256、.sig 或公钥 —— 都在这个文件里了。

里面有什么：
  <模型id>.zip       模型包本体
  <模型id>.zip.sig   它的签名（由发布方的私钥签发）

程序在安装这一刻会：
  1. 验签（确认这个模型确实来自发布方，而不是被人改过的）；
  2. 核对每个文件的摘要（确认文件没有损坏）；
  3. 原子安装（失败时保留你原来那份能用的模型）；
  4. 真的把模型加载一遍（确认推理引擎能用）。

如果安装失败，界面会说明原因并告诉你下一步怎么做。
本程序不会在失败时把原来那份模型弄丢。

模型版本：{model_id}
模型包 SHA-256：{sha256}
"""


def main() -> int:
    ap = argparse.ArgumentParser(description="把模型包与它的签名打成一个单文件的离线包")
    ap.add_argument("--model", required=True, help="模型包 zip 的路径")
    ap.add_argument("--out", required=True, help="产出的离线包路径")
    args = ap.parse_args()

    model = os.path.abspath(args.model)
    out = os.path.abspath(args.out)
    sig = model + ".sig"

    if not os.path.isfile(model):
        print("模型包不存在：%s" % model, file=sys.stderr)
        return 1
    if not os.path.isfile(sig):
        # 签名缺了 → 造出来的离线包一定装不上。
        # **fail-closed**：这里必须停，而不是造一个用户选了会失败的包。
        print("签名文件不存在：%s\n（没有签名的模型包装不上——程序会拒绝它）" % sig, file=sys.stderr)
        return 1

    model_id = ""
    try:
        with zipfile.ZipFile(model) as z:
            with z.open("manifest.json") as f:
                model_id = json.loads(f.read().decode("utf-8")).get("id", "")
    except Exception as e:  # noqa: BLE001 - 只是读 id，读不到不影响产出
        print("读模型清单里的 id 失败（只影响文件名，不影响安装）：%s" % e, file=sys.stderr)

    h = hashlib.sha256()
    with open(model, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    sha = h.hexdigest()

    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    name = os.path.basename(model)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(model, name)
        z.write(sig, name + ".sig")
        z.writestr("说明.txt", README.format(model_id=model_id or "（未知）", sha256=sha))

    size = os.path.getsize(out)
    print("已产出离线模型包：%s" % out)
    print("  里面：%s、%s.sig、说明.txt" % (name, name))
    print("  大小：%d B = %.2f MiB" % (size, size / 1048576))
    print("  模型版本：%s" % (model_id or "（未知）"))
    print("  模型包 SHA-256：%s" % sha)
    return 0


if __name__ == "__main__":
    sys.exit(main())
