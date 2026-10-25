#!/usr/bin/env python3
"""把四个 redactor 从 ZipXmlPackage 静态方法迁移到 Session 会话。

纯机械替换，改完靠 mvn 编译验证——编译失败的红不算数，必须先编译过。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1] / "src/main/java/com/litemask"
FILES = [
    ROOT / "docx/DocxRedactor.java",
    ROOT / "xlsx/XlsxRedactor.java",
    ROOT / "pptx/PptxRedactor.java",
    ROOT / "ofd/OfdRedactor.java",
]

# 顺序有意义：先改调用点，再改 try-with-resources，最后改私有方法签名
SUBS = [
    (r"ZipXmlPackage\.readEntry\(zip, ([A-Za-z0-9_]+)\)", r"pkg.read(\1)"),
    (r"ZipXmlPackage\.entries\(zip\)", "pkg.entries()"),
    (r"ZipXmlPackage\.parse\(", "pkg.parse("),
    (r"ZipXmlPackage\.serialize\(", "pkg.serialize("),
    (r"ZipXmlPackage\.writeZip\(zip, ", "pkg.write("),
    (r"try \(ZipFile zip = ZipXmlPackage\.open\(([A-Za-z0-9_]+)\)\)",
     r"try (ZipXmlPackage.Session pkg = ZipXmlPackage.open(\1))"),
    # 私有辅助方法：形参 ZipFile zip -> ZipXmlPackage.Session pkg
    (r"\(ZipFile zip, ", "(ZipXmlPackage.Session pkg, "),
    (r"\(ZipFile zip\)", "(ZipXmlPackage.Session pkg)"),
    (r"throws IOException \{", "throws IOException {"),
]


def main() -> int:
    for path in FILES:
        if not path.exists():
            print(f"缺少文件: {path}")
            return 1
        text = original = path.read_text(encoding="utf-8")
        for pattern, repl in SUBS:
            text = re.sub(pattern, repl, text)
        if text != original:
            path.write_text(text, encoding="utf-8")
            print(f"已迁移 {path.relative_to(ROOT.parents[1])}")
        else:
            print(f"无变化 {path.name}")

    leftovers = []
    for path in FILES:
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if re.search(r"ZipXmlPackage\.(readEntry|entries|parse|serialize|writeZip)\(", line):
                leftovers.append(f"{path.name}:{n}: {line.strip()}")
            if re.search(r"\bZipFile zip\b", line):
                leftovers.append(f"{path.name}:{n}: {line.strip()}")
    if leftovers:
        print("\n残留未迁移：")
        for item in leftovers:
            print("  " + item)
        return 2
    print("\n迁移完整：无残留静态调用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
