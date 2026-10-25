#!/usr/bin/env python3
"""OFD 端到端的**独立**校验器。

刻意用 Python 重写一遍「OFD 里文本在哪」，不复用被测的 Java 代码：
用被测实现验证被测实现，只能证明内部自洽，证明不了「文件没坏」。

判定口径：
  * 「文件里还有没有那个值」按**字节级**扫全包——包括 zip 条目名。
    条目名最容易被忘：XML 里的引用改了、文件名还留着，一样是泄露。
  * 「文件还能不能读」按每个 XML 部件都能被独立解析器解析来判。
  * 往返按「抽出的 TextCode 文字（去空白后）一致」判。
    跨 TextCode 的切分方式还原不回来（见 OfdRedactor 类注释），所以不比行结构。
"""

import sys
import zipfile
import xml.etree.ElementTree as ET

failures = []
notes = []


def fail(msg):
    failures.append(msg)


def entries(zf):
    return [n for n in zf.namelist() if not n.endswith('/')]


def decode(b):
    return b.decode('utf-8', errors='ignore')


def scan_values(path, values):
    """全包字节级扫描：条目名 + 每个部件的解码内容。"""
    leaked = []
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            hay = name + "\n" + decode(zf.read(name))
            for v in values:
                if v in hay:
                    leaked.append((v, name))
    for v, name in leaked:
        fail(f"原文值 {v!r} 仍出现在 {name}")
    if not leaked:
        notes.append(f"全包扫描：{len(values)} 个原文值 0 残留")


def xml_wellformed(path):
    with zipfile.ZipFile(path) as zf:
        for name in entries(zf):
            if not name.endswith('.xml'):
                continue
            try:
                ET.fromstring(zf.read(name))
            except ET.ParseError as e:
                fail(f"{name} 不是合法 XML：{e}")
    notes.append("每个 XML 部件都能被独立解析")


def text_codes(path):
    out = []
    with zipfile.ZipFile(path) as zf:
        for name in entries(zf):
            if not name.endswith('.xml'):
                continue
            try:
                root = ET.fromstring(zf.read(name))
            except ET.ParseError:
                continue
            for el in root.iter():
                if el.tag.split('}')[-1] == 'TextCode':
                    out.append(el.text or '')
    return out


def squeeze(texts):
    return ''.join(''.join(texts).split())


def check_redacted(path, expect_tokens):
    xml_wellformed(path)
    with zipfile.ZipFile(path) as zf:
        names = entries(zf)
        blob = "\n".join(decode(zf.read(n)) for n in names)
    for t in expect_tokens:
        if t not in blob:
            fail(f"没有找到令牌 {t}")
    for name in names:
        if name.startswith('Doc_0/Signs/'):
            fail(f"签章部件还在：{name}")
    if '<ofd:Signatures' in blob:
        fail("ofd:Signatures 引用还在")
    atts = [n for n in names if n.startswith('Doc_0/Attachs/')]
    if len(atts) != 1:
        fail(f"附件条目数不对：{atts}")
    else:
        with zipfile.ZipFile(path) as zf:
            if decode(zf.read(atts[0])) != 'fake-pdf-bytes':
                fail("改名后附件内容变了")
        notes.append(f"附件条目改名为 {atts[0]}，内容保留")
    notes.append(f"签章部件与引用均已移除；找到令牌 {'/'.join(expect_tokens)}")


def check_roundtrip(original, restored):
    xml_wellformed(restored)
    a = squeeze(text_codes(original))
    b = squeeze(text_codes(restored))
    if a != b:
        fail(f"还原后的文字与原文不一致\n  原文: {a}\n  还原: {b}")
    else:
        notes.append(f"往返一致（{len(a)} 个字符，去空白后逐字相同）")
    if '<' in b and any(x in b for x in ('手机号', '身份证', '邮箱', '银行卡号', '信用代码')):
        fail("还原后还残留令牌")


def require_values(path, values):
    """反向断言：这些值**必须**在文件里。

    脱敏前的「0 残留」只有在原文确实存在时才有意义。
    没有这条，样本生成器哪天少写了一段，后面的校验会全绿——
    而且是那种最典型的空转：什么都没检查，却报告「校验通过」。
    """
    with zipfile.ZipFile(path) as zf:
        blob = "\n".join(n + "\n" + decode(zf.read(n)) for n in zf.namelist())
    missing = [v for v in values if v not in blob]
    for v in missing:
        fail(f"样本自检失败：{v!r} 本来就不在样本里，后续「0 残留」是空转")
    if not missing:
        notes.append(f"样本自检：{len(values)} 个原文值确认存在")


def main():
    cmd = sys.argv[1]
    if cmd == 'scan':
        scan_values(sys.argv[2], sys.argv[3:])
    elif cmd == 'require':
        require_values(sys.argv[2], sys.argv[3:])
    elif cmd == 'check-redacted':
        check_redacted(sys.argv[2], sys.argv[3].split(','))
    elif cmd == 'check-roundtrip':
        check_roundtrip(sys.argv[2], sys.argv[3])
    else:
        print(f"未知子命令 {cmd}", file=sys.stderr)
        return 2
    for n in notes:
        print("  " + n)
    for f in failures:
        print("  ✗ " + f, file=sys.stderr)
    if failures:
        print(f"校验失败 {len(failures)} 项", file=sys.stderr)
        return 1
    print("  校验通过")
    return 0


if __name__ == '__main__':
    sys.exit(main())
