#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 NER 模型目录打成「模型包 zip」+ 同名 .sha256 旁挂文件。

为什么必须打包成一个 zip（而不是让用户自己拷目录）
----------------------------------------------------
方案 B 把模型包从核心包里拆了出去，核心包 ≤90MiB 才是硬指标。
拆出去的代价是：用户手上**没有**模型包是常态，而没有模型包时
中文人名与机构名**不会**被脱敏——那不是「少一个功能」，
是一份没脱干净的文档。所以模型包必须有一条不靠手工复制目录的安装路径。

为什么必须**确定性**
--------------------
模型包要独立分发。分发出去的东西，用户得能核对「我拿到的这份，
和官方公布的摘要一致吗」。而 zip 默认会写入文件 mtime、
条目顺序取决于目录遍历顺序、权限位随 umask 变——
于是同一个模型目录在两台机器上打出两个不同的摘要，
「核对摘要」这件事就无从谈起。

所以这里把 zip 里所有会漂的东西都钉死：
  - 条目顺序：按名排序
  - 时间戳：钉在 zip 格式允许的最小值 (1980,1,1,0,0,0)
  - 权限位：写死 0644
  - create_system：写死 3（Unix），不跟着跑的平台变
  - 压缩方式与级别：写死 deflate level 9

判据独立于被测实现：本文件只用 Python 的 zipfile/hashlib，
**不 import 任何项目代码**——理由同其它 e2e 工具：
用被测代码去验被测代码，只能证明内部自洽。

子命令
------
  pack    校验清单 → 打确定性 zip → 写 .sha256
  check   拿 .sha256 旁挂文件核对一个已有的 zip
  tamper  造一份「坏掉」的 zip（端到端的负控制用）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

# zip 格式允许的最早时间。zip 的 DOS 时间字段最小是 1980-01-01。
ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)
# 写死权限位与平台标记：这两样都会随 umask 与运行平台漂。
FILE_ATTR = 0o644 << 16
CREATE_SYSTEM = 3  # 3 = Unix

# macOS 会在任何被 Finder 碰过的目录里放这个。它不是模型包的一部分，
# 也不该影响摘要——但**别的**隐藏文件要报错，不能悄悄跳过
# （悄悄跳过 = 「打包成功」但包里少东西，而读的人看不出来）。
IGNORED_NAMES = {".DS_Store"}


def die(msg: str) -> "NoReturn":  # type: ignore[name-defined]
    print("FAIL " + msg, file=sys.stderr)
    raise SystemExit(1)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_manifest(src: Path) -> dict:
    """读并**核对**清单。

    这里只做「清单 ↔ 磁盘文件」的一致性核对：清单里说的文件在不在、
    摘要对不对。**模型本身能不能加载**由 Java 侧的
    `litemask model verify` / `model install` 负责——两边各管一半，
    谁也不重复谁。
    """
    mpath = src / "manifest.json"
    if not mpath.is_file():
        die("模型目录里没有 manifest.json: " + str(src))
    try:
        manifest = json.loads(mpath.read_text("utf-8"))
    except Exception as e:  # noqa: BLE001 - 这里要报人话
        die("manifest.json 解析失败: %s" % e)
    if not isinstance(manifest, dict):
        die("manifest.json 不是一个 JSON 对象")

    fmt = manifest.get("formatVersion")
    if fmt != 2:
        die("清单格式版本是 %r，不认识（当前只认 2）" % (fmt,))
    model_id = manifest.get("id")
    if not isinstance(model_id, str) or not model_id.strip():
        die("清单里没有 id")
    # id 会变成**文件名**（<id>.zip），所以它必须是一个安全的文件名。
    #
    # 不查的话，一个 id 写着 "../../../etc/某东西" 的清单能把产物写到输出目录之外去。
    # 这不是理论上的：清单是可以从别处拿来的。
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", model_id):
        die("清单里的 id 不能安全地当文件名用: %r" % (model_id,))
    files = manifest.get("files")
    if not isinstance(files, dict) or not files:
        die("清单里没有 files")
    for role, spec in files.items():
        if not isinstance(spec, dict):
            die("清单里 files.%s 不是一个对象" % role)
        path = spec.get("path")
        digest = spec.get("sha256")
        if not isinstance(path, str) or not path:
            die("清单里 files.%s 没有 path" % role)
        if "/" in path or "\\" in path or path in (".", ".."):
            die("清单里 files.%s 的 path 不是纯文件名: %r" % (role, path))
        if not isinstance(digest, str) or len(digest) != 64:
            die("清单里 files.%s 没有 sha256" % role)
        fp = src / path
        if not fp.is_file():
            die("清单里 files.%s 指的文件不在模型目录里: %s" % (role, path))
        actual = sha256_file(fp)
        if actual.lower() != digest.lower():
            die("文件摘要对不上: %s（清单 %s，实测 %s）" % (path, digest, actual))
    return manifest


def collect_entries(src: Path, manifest: dict,
                    manifest_path: "Path | None" = None) -> list[tuple[str, Path]]:
    """要进包的条目：清单本身 + 清单点名的文件，按名排序。

    **清单说了算**：目录里多余的文件不进包（否则包会随着
    「谁在开发机上留下了什么」而变）。清单点名的文件必须都在。
    """
    entries: dict[str, Path] = {}
    # 每个条目的「所属目录」：清单被改写时它在临时目录里，
    # 其余条目在模型目录里。相对路径要按各自所属目录算，
    # 否则 `p.relative_to(src)` 会直接抛 ValueError。
    bases: dict[str, Path] = {}
    manifest_file = manifest_path or (src / "manifest.json")
    entries["manifest.json"] = manifest_file
    bases["manifest.json"] = manifest_file.parent
    for spec in manifest["files"].values():
        name = spec["path"]
        entries[name] = src / name
        bases[name] = src

    out: list[tuple[str, Path]] = []
    for name in sorted(entries):
        p = entries[name]
        if p.is_symlink():
            die("模型包里不能有符号链接: %s" % p)
        if not p.is_file():
            die("模型包条目不是普通文件: %s" % p)
        rel_parts = p.relative_to(bases[name]).parts
        for part in rel_parts:
            if part in IGNORED_NAMES:
                continue
            if part.startswith("."):
                die("模型目录里有隐藏条目 %s，不打包（只有 %s 被忽略）"
                    % (p, "、".join(sorted(IGNORED_NAMES))))
        out.append((name, p))
    return out


def build_zip(entries: list[tuple[str, Path]], out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_name(out_path.name + ".tmp")
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name, path in entries:
            zi = zipfile.ZipInfo(filename=name, date_time=ZIP_EPOCH)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.create_system = CREATE_SYSTEM
            zi.external_attr = FILE_ATTR
            zi.internal_attr = 0
            zi.extra = b""
            zi.comment = b""
            zf.writestr(zi, path.read_bytes())
    shutil.move(str(tmp), str(out_path))


def cmd_pack(args: argparse.Namespace) -> int:
    src = Path(args.src).expanduser().resolve()
    out_dir = Path(args.out_dir).expanduser()
    if not src.is_dir():
        die("模型目录不存在: " + str(src))

    manifest = read_manifest(src)
    model_id = manifest["id"]
    entries = collect_entries(src, manifest)
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.model_id:
        # 改 id = 造一个「能区分开的另一版」。
        #
        # 【为什么需要它】端到端要验「装第二份 → 旧的保留 → 回退」，
        # 而机器上只有一份真模型包。改清单里的 id 就能造出第二份：
        # 模型还是同一个（所以「回退后还能正常脱敏」这件事仍然测的是真模型），
        # 但两版的版本键不同、能被区分开。
        new_id = args.model_id
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", new_id):
            die("--id 不能安全地当文件名用: %r" % (new_id,))
        manifest = dict(manifest)
        manifest["id"] = new_id
        model_id = new_id
        with tempfile.TemporaryDirectory() as tmpdir:
            rewritten = Path(tmpdir) / "manifest.json"
            rewritten.write_text(
                json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                "utf-8")
            entries = collect_entries(src, manifest, rewritten)
            out_zip = out_dir / ("%s.zip" % model_id)
            build_zip(entries, out_zip)
    else:
        out_zip = out_dir / ("%s.zip" % model_id)
        build_zip(entries, out_zip)
    digest = sha256_file(out_zip)
    sidecar = out_dir / ("%s.zip.sha256" % model_id)
    # sha256sum 兼容格式：<十六进制>  <文件名>（两个空格）
    sidecar.write_text("%s  %s\n" % (digest, out_zip.name), "utf-8")

    print("模型包 id      : %s" % model_id)
    print("模型包文件    : %s" % out_zip)
    print("条目          : %d 个（%s）" % (len(entries), "、".join(n for n, _ in entries)))
    print("大小          : %d 字节" % out_zip.stat().st_size)
    print("sha256        : %s" % digest)
    print("旁挂文件      : %s" % sidecar)
    print("确定性        : 条目按名排序、时间戳 1980-01-01、权限位 0644、deflate level 9")
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    """拿 .sha256 旁挂文件核对一个 zip。"""
    sidecar = Path(args.sidecar)
    if not sidecar.is_file():
        die("旁挂文件不存在: " + str(sidecar))
    lines = [l for l in sidecar.read_text("utf-8").splitlines() if l.strip()]
    if not lines:
        die("旁挂文件是空的: " + str(sidecar))
    parts = lines[0].split(None, 1)
    expected = parts[0].strip().lower()
    # 旁挂文件叫 <包名>.zip.sha256 → 被核对的东西是 <包名>.zip。
    if args.target:
        target = Path(args.target)
    else:
        name = sidecar.name
        if not name.endswith(".sha256"):
            die("旁挂文件名不是 <包名>.sha256 形态: " + name)
        target = sidecar.with_name(name[: -len(".sha256")])
    if not target.is_file():
        die("要核对的文件不存在: " + str(target))
    actual = sha256_file(target)
    if actual.lower() != expected:
        print("FAIL 摘要对不上", file=sys.stderr)
        print("  旁挂文件: %s" % expected, file=sys.stderr)
        print("  实测    : %s" % actual, file=sys.stderr)
        return 1
    print("PASS 摘要一致: %s" % target)
    print("     sha256 = %s" % actual)
    return 0


def cmd_tamper(args: argparse.Namespace) -> int:
    """造一份「坏掉」的 zip：指定条目的内容被改掉一个字节。

    这是端到端的**负控制**：没有它，「安装失败」这条断言
    永远不会被真正触发过——测试就只测了「装得上」这一半。
    """
    src = Path(args.input).resolve()
    out = Path(args.output).resolve()
    if not src.is_file():
        die("输入 zip 不存在: " + str(src))
    if out.exists():
        die("输出已存在，不覆盖: " + str(out))
    out.parent.mkdir(parents=True, exist_ok=True)

    found = False
    with zipfile.ZipFile(src, "r") as zin:
        with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                if info.filename == args.entry and not found:
                    if not data:
                        die("条目是空的，改不了: " + args.entry)
                    # 改中间那个字节：改头尾容易碰巧落进不影响校验的位置
                    pos = len(data) // 2
                    data = data[:pos] + bytes([data[pos] ^ 0xFF]) + data[pos + 1:]
                    found = True
                zi = zipfile.ZipInfo(filename=info.filename, date_time=ZIP_EPOCH)
                zi.compress_type = zipfile.ZIP_DEFLATED
                zi.create_system = CREATE_SYSTEM
                zi.external_attr = FILE_ATTR
                zi.internal_attr = 0
                zi.extra = b""
                zi.comment = b""
                zout.writestr(zi, data)
    if not found:
        die("zip 里没有这个条目: " + args.entry)
    print("已造坏包: %s（改了 %s 的一个字节）" % (out, args.entry))
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="NER 模型包打包工具")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("pack", help="校验清单并打成确定性 zip")
    p.add_argument("--src", required=True, help="模型目录")
    p.add_argument("--out-dir", required=True, help="输出目录")
    p.add_argument("--id", dest="model_id", default=None,
                   help="改写清单里的 id（造出能区分开的另一版用；模型本身不动）")
    p.set_defaults(func=cmd_pack)

    c = sub.add_parser("check", help="拿 .sha256 旁挂文件核对 zip")
    c.add_argument("--sidecar", required=True, help=".sha256 文件")
    c.add_argument("--target", default=None, help="要核对的 zip（默认取旁挂文件同名者）")
    c.set_defaults(func=cmd_check)

    t = sub.add_parser("tamper", help="造一份坏掉的 zip（负控制用）")
    t.add_argument("--input", required=True)
    t.add_argument("--output", required=True)
    t.add_argument("--entry", required=True, help="要改坏的条目名")
    t.set_defaults(func=cmd_tamper)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
