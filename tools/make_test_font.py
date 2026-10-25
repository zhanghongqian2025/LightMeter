#!/usr/bin/env python3
"""生成测试专用的中文字体子集，消掉「依赖本机系统字体」这个跨平台隐患。

为什么需要它
------------
中文 PDF 的单测与端到端都需要一个**可嵌入**的中文字体。原来的做法是从系统里找一个：

    /System/Library/Fonts/Supplemental/Arial Unicode.ttf   (macOS)
    C:/Windows/Fonts/msyh.ttc                              (Windows)

这条路的三个问题，都是实测出来的，不是推理出来的：

1. **Windows 候选是字体集（.ttc），而 PDFBox 的普通加载路径读不了它。**
   `PDType0Font.load(doc, InputStream, true)` 对 .ttc 直接抛
   `java.io.IOException: 'head' table is mandatory`（fontbox 3.0.3 实测）。
   要读 .ttc 必须走 `TrueTypeCollection.getFontByName(...)` 单独取出一个字体。
   原来的 `cjkFont()` 找到文件就返回，于是 Windows 上这条测试是**报错**而不是跳过。
2. **「本机没字体就跳过」是个长期静默的覆盖洞。** 中文 PDF 这条路径曾经
   在很多机器上根本没跑过，而全量单测对此完全无感。
3. **不同机器的字体度量不同**，版面断言的数值随机器变，测试不可复现。

所以改成：把一份 **OFL 授权**（可再分发）的中文字体裁成子集，随仓库提供。
子集放在 `src/test/resources/`，**不进生产 jar**，因此不影响 90 MiB 体积预算。

产物
----
    src/test/resources/fonts/litemask-cjk-test.ttf   子集（单字体，主用）
    src/test/resources/fonts/litemask-cjk-test.ttc   同一子集装进字体集（守卫测试用）
    src/test/resources/fonts/OFL.txt                 授权原文

用法
----
    python3 tools/make_test_font.py --source <NotoSansSC-VF.ttf>

源字体：Google Fonts 的 `ofl/notosanssc/NotoSansSC[wght].ttf`（SIL OFL 1.1）。
本脚本只做「实例化 → 子集化 → 写出」，不改字形，也不改授权文件。
"""

import argparse
import hashlib
import pathlib
import shutil
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = REPO / "src" / "test" / "resources" / "fonts"

ASCII = "".join(chr(c) for c in range(0x20, 0x7F))


def chars_from_sources(repo: pathlib.Path) -> set:
    """扫描测试与端到端源码里出现过的所有非 ASCII 字符。

    **只扫文本**。字体文件是二进制，按 UTF-8 硬解会读出成千上万个无意义的字符，
    把它们塞进字符集会让子集凭空多出几千个 .notdef 字形——
    而且下次再跑一次脚本时，扫到的又是上一轮自己生成的字体，字符集会自己长，
    变成一个没人能预测的过程。所以这里只认源码与 .txt。
    """
    found = set()
    patterns = ["src/test/**/*.java", "tools/*.java", "src/test/resources/**/*.txt"]
    fonts_dir = (repo / "src" / "test" / "resources" / "fonts").resolve()
    for pattern in patterns:
        for path in repo.glob(pattern):
            if not path.is_file():
                continue
            # 产物目录整个跳过：那里放的是本脚本的输出（字体与授权文件），
            # 扫它等于让脚本读自己的上一次的产物，字符集会自己长。
            try:
                if fonts_dir in path.resolve().parents:
                    continue
            except OSError:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            for ch in text:
                if ord(ch) > 0x7F:
                    found.add(ch)
    return found


def chars_gb2312_level1() -> set:
    """GB2312 一级汉字（3755 个，覆盖现代汉语用字的 99.7% 以上）。

    直接按 GB2312 编码区间枚举，不需要外部字频表。
    """
    out = set()
    for hi in range(0xB0, 0xD8):
        for lo in range(0xA1, 0xFF):
            try:
                out.add(bytes([hi, lo]).decode("gb2312"))
            except UnicodeDecodeError:
                continue
    return out


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", required=True, help="源字体（可变 TTF，如 NotoSansSC[wght].ttf）")
    ap.add_argument("--license", default=None, help="授权文件，默认与源字体同目录的 OFL.txt")
    ap.add_argument("--charset", choices=["sources", "gb2312"], default="gb2312",
                    help="sources=只用源码里出现过的字；gb2312=再加上 GB2312 一级字库（默认）")
    ap.add_argument("--weight", type=int, default=400, help="可变字重的实例化目标（默认 400）")
    args = ap.parse_args()

    source = pathlib.Path(args.source).expanduser()
    if not source.is_file():
        print(f"源字体不存在: {source}", file=sys.stderr)
        return 1

    try:
        from fontTools import subset
        from fontTools.ttLib import TTFont
        from fontTools.ttLib.ttCollection import TTCollection
        from fontTools.varLib import instancer
    except ImportError as exc:
        print(f"需要 fonttools：{exc}", file=sys.stderr)
        return 1

    charset = set(ASCII) | chars_from_sources(REPO)
    if args.charset == "gb2312":
        charset |= chars_gb2312_level1()
    # 补上 PDFBox/内容流会用到的少量 ASCII 变体与全角标点
    charset |= set(" ")

    print(f"字符集：{len(charset)} 个（含 ASCII）")

    font = TTFont(str(source))
    if "fvar" in font:
        axes = {a.axisTag: a for a in font["fvar"].axes}
        if "wght" not in axes:
            print("源字体没有 wght 轴，无法按字重实例化", file=sys.stderr)
            return 1
        font = instancer.instantiateVariableFont(font, {"wght": args.weight}, updateFontNames=False)
        print(f"已按 wght={args.weight} 实例化可变字体")

    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_legacy = True
    options.name_languages = ["*"]
    options.glyph_names = True
    options.legacy_cmap = True
    options.symbol_cmap = True
    options.recommended_glyphs = True
    options.notdef_outline = True
    options.notdef_glyph = True
    options.drop_tables += ["DSIG"]
    options.hinting = True
    options.desubroutinize = False

    subsetter = subset.Subsetter(options=options)
    subsetter.populate(text="".join(sorted(charset)))
    subsetter.subset(font)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ttf_path = OUT_DIR / "litemask-cjk-test.ttf"
    font.save(str(ttf_path))
    font.close()

    # 再做一个**极小**的字体集（.ttc），只有守卫测试需要它：
    # Windows 上 msyh.ttc 就是这种东西，而 PDFBox 的普通加载路径读不了它，
    # 所以保留一份小 .ttc 来证明「遇到字体集必须给出明确错误，而不是底层异常」。
    # 它不需要覆盖测试用字，所以单独裁一份小得多的。
    tiny_font = TTFont(str(source))
    if "fvar" in tiny_font:
        tiny_font = instancer.instantiateVariableFont(tiny_font, {"wght": args.weight},
                                                      updateFontNames=False)
    tiny_options = subset.Options()
    tiny_options.layout_features = ["*"]
    tiny_options.name_IDs = ["*"]
    tiny_options.legacy_cmap = True
    tiny_options.recommended_glyphs = True
    tiny_options.notdef_outline = True
    tiny_subsetter = subset.Subsetter(options=tiny_options)
    tiny_subsetter.populate(text=ASCII + "中文测试")
    tiny_subsetter.subset(tiny_font)

    ttc_path = OUT_DIR / "litemask-cjk-test.ttc"
    with tempfile.TemporaryDirectory() as tmp_dir:
        # 先落盘再重载：直接拿内存里的子集装进集合，表偏移仍指着源文件，
        # 写出来的 .ttc 会带着越界的表目录。重载一次让偏移指向真实文件。
        staged = pathlib.Path(tmp_dir) / "staged.ttf"
        tiny_font.save(str(staged))
        tiny_font.close()
        reloaded = TTFont(str(staged))
        collection = TTCollection()
        collection.fonts.append(reloaded)
        collection.save(str(ttc_path))
        reloaded.close()

    license_src = pathlib.Path(args.license) if args.license else source.parent / "OFL.txt"
    if license_src.is_file():
        shutil.copyfile(license_src, OUT_DIR / "OFL.txt")
    else:
        print(f"警告：没找到授权文件 {license_src}，OFL.txt 未更新", file=sys.stderr)

    print()
    for path in (ttf_path, ttc_path, OUT_DIR / "OFL.txt"):
        if path.is_file():
            print(f"{path.relative_to(REPO)}  {path.stat().st_size} B  sha256={sha256(path)[:16]}…")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
