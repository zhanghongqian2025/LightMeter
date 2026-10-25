#!/usr/bin/env python3
"""把一个 zip 形态的文档（docx/xlsx/pptx/ofd）里的**可见文本**抽出来。

【为什么需要它 —— 2026-10-25 的真 Windows 验收里加的】
  「脱敏件里搜不到原值」这条判据，**不能靠搜压缩包的原始字节**：
  docx/xlsx/pptx 的正文在 zip 里的 XML 里，OFD 在 pkg 的 XML 里，
  而这些 XML 是**压缩**的 —— 直接在 .docx 的字节里搜「11010519491231002X」，
  永远搜不到，于是那条判据会**恒真**（绿灯），而它压根没验过任何东西。
  → 必须**解包**再搜。

【为什么用「独立读取器」而不是产品自己的代码】
  判据如果用被测实现自己写、自己读，等于只证明内部自洽。
  这个脚本**只用 Python 标准库**（zipfile + 正则），
  不 import 任何项目代码、不认识产品的内部结构 ——
  它只做「把包里所有 XML 部件的文本节点连起来」这一件事。

【用法】
  python3 tools/e2e_pkg_text.py [--content-only] <文件> [<文件> ...]
  每个文件打印一行：<路径>\\t<抽出来的文本>

【--content-only：只抽「内容部件」，跳过元数据部件】
  docx/xlsx/pptx 的 {@code docProps/}（作者、公司、修改时间…）
  与 ofd 的 {@code OFD.xml}（{@code DocInfo} 里的 Author/Creator）
  是产品**清空**掉的位置 —— 那是不可逆处理，原值从来没被保存过，
  还原也带不回来。所以拿「还原件 vs 原件」比文本时，
  这些部件必然不等（清空后的那份少掉作者名）。
  → 比「可见文本往返」时用 --content-only；
    「脱敏件里有没有残留原值」则**要**带上它们（元数据被清空正是预期结果）。
"""
import re
import sys
import zipfile

# XML 里所有「文本节点」与「属性值」里的可见字符。
# 属性值也要抽：批注作者、文档属性、关系 URL 这些敏感信息在属性里。
_TAG = re.compile(r"<[^>]*>")
_ATTR = re.compile(r"""=\s*(?:"([^"]*)"|'([^']*)')""")
_ENT = re.compile(r"&(?:amp|lt|gt|quot|apos|#\d+|#x[0-9A-Fa-f]+);")

# 元数据部件：被**清空**、不可逆，还原带不回来。
_META_PREFIX = ("docProps/",)
_META_EXACT = ("OFD.xml",)


def _is_meta(name: str) -> bool:
    n = name.replace("\\", "/")
    return n.startswith(_META_PREFIX) or n in _META_EXACT


def _unescape(s: str) -> str:
    return _ENT.sub(lambda m: {"&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&apos;": "'"}.get(
        m.group(0), m.group(0)), s)


def text_of_package(path: str, content_only: bool = False) -> str:
    out = []
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.endswith("/"):
                continue
            if content_only and _is_meta(name):
                continue
            try:
                raw = z.read(name)
            except Exception:
                continue
            try:
                s = raw.decode("utf-8")
            except UnicodeDecodeError:
                try:
                    s = raw.decode("gb18030")
                except UnicodeDecodeError:
                    continue
            out.append(s)
    joined = "\n".join(out)
    # 属性值也要抽：批注作者、文档属性、关系 URL 这些敏感信息在属性里。
    #   先把 `="值"` 换成 ` 值 `，再去掉标签 —— 顺序不能反。
    joined = _ATTR.sub(lambda m: " " + (m.group(1) if m.group(1) is not None else m.group(2)) + " ", joined)
    joined = _TAG.sub(" ", joined)
    return _unescape(joined)


def main() -> int:
    args = sys.argv[1:]
    content_only = False
    if "--content-only" in args:
        content_only = True
        args.remove("--content-only")
    if not args:
        print(__doc__)
        return 2
    for p in args:
        try:
            print(p + "\t" + text_of_package(p, content_only))
        except Exception as e:
            print(p + "\t<读不出来: %s>" % e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
