#!/usr/bin/env python3
"""把已有模型包的清单升级到当前格式（version 2），不重训。

为什么需要它：清单格式从 v1（只有训练元信息）升到 v2（张量名 / 滑窗 / 摘要 /
嵌入张量名）之后，已经训好的模型不必重训一遍才能被新的加载器读取——
重训一次 40 分钟，而升级清单只花一秒，且**不会改动模型与词表**。

注意它**不覆盖**既有字段里自己不知道的部分：metrics 与 provenance 原样搬运，
而 files 的 sha256 是现算的——所以跑完之后的包，摘要与盘上的字节一定对得上。

用法：
    python tools/manifest_upgrade.py <模型目录> [--max-len 256] [--overlap 64]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def embedding_rows(onnx_path: pathlib.Path, name: str) -> int | None:
    import onnx

    m = onnx.load(str(onnx_path))
    for init in m.graph.initializer:
        if init.name == name:
            return int(init.dims[0])
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("model_dir")
    ap.add_argument("--max-len", type=int, default=256)
    ap.add_argument("--overlap", type=int, default=64)
    ap.add_argument("--embedding-tensor", default="emb.weight")
    ap.add_argument("--id", default="litemask-ner-zh-char-v3")
    args = ap.parse_args()

    d = pathlib.Path(args.model_dir)
    manifest_path = d / "manifest.json"
    if not manifest_path.is_file():
        print(f"找不到清单: {manifest_path}")
        return 1
    old = json.loads(manifest_path.read_text(encoding="utf-8"))

    # 幂等：清单可能已经是 v2（files.model 是个 dict），也可能是 v1（一个字符串）。
    # 只认一种会让「重复跑一次升级」直接崩掉，而升级理应是可反复执行的。
    def file_name(section: str, default: str) -> str:
        v = old.get("files", {}).get(section, default)
        return v["path"] if isinstance(v, dict) else v

    model_path = d / file_name("model", "ner.onnx")
    vocab_path = d / file_name("vocab", "vocab.txt")
    for p in (model_path, vocab_path):
        if not p.is_file():
            print(f"找不到 {p}")
            return 1

    rows = embedding_rows(model_path, args.embedding_tensor)
    if rows is None:
        print(f"模型里找不到初始化张量 {args.embedding_tensor}，无法核对词表")
        return 1

    vocab_chars = len(
        [ln for ln in vocab_path.read_text(encoding="utf-8").split("\n") if ln != ""]
    )
    if rows != vocab_chars + 1:
        print(
            f"拒绝升级：嵌入 {rows} 行 != 词表 {vocab_chars} 字 + 1 UNK = {vocab_chars + 1}。\n"
            "这正是「模型与词表不是同一对」的形态——写进清单只会把错误固化下来。"
        )
        return 1

    max_ent = old.get("maxEntityChars")
    if not isinstance(max_ent, int) or max_ent < 1:
        print("旧清单里没有可用的 maxEntityChars，不猜。")
        return 1
    if args.overlap < max_ent:
        print(
            f"拒绝：overlap={args.overlap} < maxEntityChars={max_ent}。"
            "滑窗契约要求 overlap ≥ 最长实体，否则跨窗口的实体会静默消失。"
        )
        return 1

    new = {
        "formatVersion": 2,
        "id": args.id,
        "arch": old.get("arch", "idcnn-crf"),
        "tags": old["tags"],
        # 标签后缀 → 脱敏规则码。旧清单没有这一项，只能按数据集惯例给；
        # 加载侧只接受 EntityType 白名单里的码，所以这里写错也会被拒绝而不是静默生效。
        "tagTypes": {"PER": "person", "ORG": "organization"},
        "entityTypes": old.get("entityTypes", ["person", "organization"]),
        "maxEntityChars": max_ent,
        "scoreSpace": old.get("scoreSpace", "LOGIT"),
        "window": {"chars": args.max_len, "overlap": args.overlap},
        "io": {"input": "ids", "emission": "emission", "transition": "transition"},
        "embeddingTensor": args.embedding_tensor,
        "files": {
            "model": {"path": model_path.name, "sha256": sha256_file(model_path)},
            "vocab": {"path": vocab_path.name, "sha256": sha256_file(vocab_path)},
        },
        "metrics": old.get("metrics", {}),
        "provenance": {
            **{
                k: old[k]
                for k in ("embeddingDim", "dropout", "seed", "epochs", "trainedOn")
                if k in old
            },
            "manifestUpgradedFrom": old.get("formatVersion"),
        },
    }
    manifest_path.write_text(
        json.dumps(new, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"清单已升级到 formatVersion 2: {manifest_path}")
    print(f"  嵌入 {rows} 行 == 词表 {vocab_chars} 字 + 1 UNK")
    print(f"  window: chars={args.max_len} overlap={args.overlap}（maxEntityChars={max_ent}）")
    print(f"  model sha256 {new['files']['model']['sha256'][:16]}…")
    print(f"  vocab sha256 {new['files']['vocab']['sha256'][:16]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())
