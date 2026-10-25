#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""块 15 专项：OOXML 侧「跨 zip 部件不得续接」的真实文档验证。

【为什么单测之外还要这一份】
  单测用的是**夹具**造的小包；单测绿只证明「夹具那种结构下不续接」。
  这一份用**手写的真实包结构**（[Content_Types].xml / _rels / 各部件），
  且**只依赖 zipfile + 纯字符串处理**造样本、校验结果 ——
  **不 import 任何项目代码**：标尺必须独立于被测实现
  （理由同 tools/gen_ooxml_sample.py：样本若是被测代码生成的，
    被测代码对结构的理解一旦有偏差，样本就跟着错，
    最后「全文没有原值」是因为样本里本来就没有）。

【这一份要证明的是什么】
  块 13/14 的 OOXML 侧分组是「整篇一组」→ 跨 zip 部件的文本会被**拼在一起**。
  具体形状：页眉末尾一个 16 位数字 + 正文开头一个字母
  → 拼成 17 位、含字母、字符集合法的**形态合法 VIN**（车架号）
  → 两段都被按车架号脱敏：**误判 + 标签错**（原文里根本没有车架号）。

  → 块 15 改成「一个 zip 部件 = 一组」后，
    **跨部件的这两段必须各自保持原样**（都不该被当 VIN 脱掉）：
      - 页眉里的 16 位数字单独不匹配任何规则（16 位数字 → LUHN 不通过；
        VIN 要 17 位且须含字母；身份证要 18 位）→ 保持原样；
      - 正文里的单个字母不匹配任何规则 → 保持原样；
    而**同一部件内部**的切断仍然必须续接（否则就把续接整体关掉了）。

【三条腿各自造什么】
  docx：页眉 word/header1.xml 放数字；正文 word/document.xml 放字母。
         同时正文**内部**再放一对可拼成身份证的两半 → 证明「同部件内仍续接」。
  xlsx：共享字符串 xl/sharedStrings.xml 放数字；工作表 xl/worksheets/sheet1.xml 放字母。
         工作表**内部**再放一对可拼成身份证的两半。
  pptx：备注页 ppt/notesSlides/notesSlide1.xml 放数字；
         幻灯片 ppt/slides/slide1.xml 放字母。
         幻灯片**内部**再放一对可拼成身份证的两半。

【用法】
  python3 tools/verify_ooxml_parts.py --src <样本目录>      # 只生成样本并打印清单
  （实际脱敏由调用方跑 java -jar，本脚本负责造样本 + 独立校验）
"""
import argparse
import os
import zipfile

# 跨部件的两半：单独都匹配不上任何规则，拼起来却是形态合法的 VIN。
# 16 位数字：LUHN 不通过（实测），且不是 18 位身份证、不是 17 位 VIN。
VIN_HEAD = "6222021234567890"
VIN_TAIL = "X"
# 同部件内部的一对：拼起来是完整且过校验位的身份证。
ID_HEAD = "110105"
ID_TAIL = "19491231002X"


def _xml(s):
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' + s).encode("utf-8")


CONTENT_TYPES = _xml(
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    '<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>'
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
    '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
    '<Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>'
    '<Override PartName="/ppt/notesSlides/notesSlide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"/>'
    '</Types>')

RELS = _xml(
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
    '</Relationships>')


def _docx_part(tag, text):
    return _xml(
        '<w:%s xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:p><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>'
        '</w:%s>' % (tag, text, tag))


def build_docx(order="A"):
    """页眉（部件 A）放数字；正文（部件 B）放字母 + 同部件内可续接的身份证两半。

    【为什么两种顺序都要造】
      摊平成一组时续接是**相邻单元**拼接，部件序决定谁是邻居。
      两种顺序各造一份 → 无论实现怎么排，都能被这份标尺抓到。
    """
    header = _docx_part("hdr", "编号" + VIN_HEAD)
    # 身份证两半单独放第三个部件：既保证「同部件内仍续接」，
    # 又不会插在数字与字母**中间**挡住 VIN 那对的相邻关系。
    footer = _xml(
        '<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:p><w:r><w:t xml:space="preserve">案号%s</w:t></w:r></w:p>'
        '<w:p><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>'
        '</w:ftr>' % (ID_HEAD, ID_TAIL))
    # 同一部件内部：身份证被段落边界切成两半 → 必须续接（这一条不许被关掉）
    # 【为什么字母也在正文里】跨部件的那一半（字母）必须在**正文**这个部件里，
    #   否则「页眉数字 + 正文字母」根本不在同一个包里，测的就不是跨部件续接。
    body = _xml(
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:body>'
        '<w:p><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>'
        '</w:body></w:document>' % VIN_TAIL)
    parts = {
        "[Content_Types].xml": CONTENT_TYPES,
        "_rels/.rels": RELS,
    }
    # 顺序只有「数字部件 ↔ 字母部件」两种，二者**始终相邻**：
    # 第三个部件（身份证那对）放在它们**后面**，不挡在中间。
    if order == "A":     # 页眉（数字）在前
        parts["word/header1.xml"] = header
        parts["word/document.xml"] = body
    else:                # 正文（字母）在前
        parts["word/document.xml"] = body
        parts["word/header1.xml"] = header
    parts["word/footer1.xml"] = footer
    return parts


def build_xlsx(order="A"):
    """共享字符串（部件 A）放数字；工作表（部件 B）放字母 + 同部件内可续接的两半。"""
    shared = _xml(
        '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="1" uniqueCount="1">'
        '<si><t xml:space="preserve">编号%s</t></si>'
        '</sst>' % VIN_HEAD)
    sheet = _xml(
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheetData>'
        '<row r="1"><c r="A1" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c></row>'
        '</sheetData></worksheet>' % VIN_TAIL)
    sheet2 = _xml(
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheetData>'
        '<row r="1"><c r="A1" t="inlineStr"><is><t xml:space="preserve">案号%s</t></is></c></row>'
        '<row r="2"><c r="A2" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c></row>'
        '</sheetData></worksheet>' % (ID_HEAD, ID_TAIL))
    workbook = _xml(
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets><sheet name="表一" sheetId="1" r:id="rId1"/></sheets></workbook>')
    parts = {
        "[Content_Types].xml": CONTENT_TYPES,
        "_rels/.rels": RELS,
        "xl/workbook.xml": workbook,
    }
    if order == "A":     # 共享字符串（数字）在前
        parts["xl/sharedStrings.xml"] = shared
        parts["xl/worksheets/sheet1.xml"] = sheet
    else:                # 工作表（字母）在前
        parts["xl/worksheets/sheet1.xml"] = sheet
        parts["xl/sharedStrings.xml"] = shared
    parts["xl/worksheets/sheet2.xml"] = sheet2
    return parts


def build_pptx(order="A"):
    """备注页（部件 A）放数字；幻灯片（部件 B）放字母 + 同部件内可续接的两半。

    【为什么要 order 两种】
      摊平成一组时，续接是**相邻单元**拼接。
      部件序决定谁是「邻居」：
        - 字母部件在前 → 相邻的是 字母 ↔ 数字 → 拼成合法 VIN（咬得住）
        - 数字部件在前 → 相邻的是 数字 ↔ 字母 → 同样拼成合法 VIN
      但**中间不能有别的文本**隔着，否则续接够不着。
      → 所以这里把「字母」和「同部件的身份证两半」分开放：
        字母单独成一个段落，紧邻部件边界，身份证两半另起段落。
    """
    notes = _xml(
        '<p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
        '<p:cSld><p:spTree><p:sp><p:txBody>'
        '<a:p xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
        '<a:r><a:t>编号%s</a:t></a:r></a:p>'
        '</p:txBody></p:sp></p:spTree></p:cSld></p:notes>' % VIN_HEAD)
    # 字母**单独**成一个段落，且这个段落是幻灯片里的**第一个**单元 →
    # 摊平时它紧邻备注页的数字（若备注页在后）或紧邻前一个部件（若在前）。
    slide = _xml(
        '<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
        '<p:cSld><p:spTree><p:sp><p:txBody>'
        '<a:p xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
        '<a:r><a:t>%s</a:t></a:r></a:p>'
        '</p:txBody></p:sp></p:spTree></p:cSld></p:sld>' % VIN_TAIL)
    slide2 = _xml(
        '<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">'
        '<p:cSld><p:spTree><p:sp><p:txBody>'
        '<a:p xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
        '<a:r><a:t>案号%s</a:t></a:r></a:p>'
        '<a:p xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
        '<a:r><a:t>%s</a:t></a:r></a:p>'
        '</p:txBody></p:sp></p:spTree></p:cSld></p:sld>' % (ID_HEAD, ID_TAIL))
    parts = {
        "[Content_Types].xml": CONTENT_TYPES,
        "_rels/.rels": RELS,
    }
    if order == "A":     # 幻灯片（字母）在前
        parts["ppt/slides/slide1.xml"] = slide
        parts["ppt/notesSlides/notesSlide1.xml"] = notes
    else:                # 备注页（数字）在前
        parts["ppt/notesSlides/notesSlide1.xml"] = notes
        parts["ppt/slides/slide1.xml"] = slide
    parts["ppt/slides/slide2.xml"] = slide2
    return parts


# ---- 独立校验：扫脱敏后的包，检查该留的留住了、该脱的脱掉了 ----

def part_texts(zf):
    """每个部件的原始文本，按部件名分开。

    【为什么必须按部件分开查】
      判据要证明的是「**跨部件**不得续接」——
      如果把所有部件的文本拼成一大坨再查，
      就无法区分「数字和字母分处两个部件（没被拼）」与
      「数字和字母本来就在同一处（被拼了）」——
      拼起来的文本里两者的样子**完全一样**。
      → 必须**逐部件**看：数字所在的部件里不得出现车架号令牌，
        字母所在的部件里也不得出现。
    """
    out = {}
    for name in zf.namelist():
        if not name.endswith(".xml"):
            continue
        try:
            out[name] = zf.read(name).decode("utf-8", errors="replace")
        except Exception:
            continue
    return out


def all_text(zf):
    """把包里所有 XML 部件的文本抽出来（只做字面扫描，不解析结构）。

    刻意**不**解析 XML：标尺越简单越不容易与被测实现共享同一个理解偏差。
    """
    import re
    out = []
    for data in part_texts(zf).values():
        for m in re.finditer(r">([^<]+)<", data):
            out.append(m.group(1))
        out.append(data)  # 原始串也留着（令牌可能落在属性里）
    return "\n".join(out)


# 每条腿 = (格式, 部件顺序)。部件顺序决定摊平时「谁是邻居」，
# 两种顺序各算一条腿 —— 少一条就会漏掉一种拼接方向（这正是本脚本第一版的 bug：
# pptx 只造了一种顺序，变异体下探针空转、全绿）。
#
# 值为：数字所在部件、字母所在部件、同部件内那对身份证所在部件
PARTS = {
    ("docx", "A"): ("word/header1.xml", "word/document.xml", "word/footer1.xml"),
    ("docx", "B"): ("word/header1.xml", "word/document.xml", "word/footer1.xml"),
    ("xlsx", "A"): ("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"),
    ("xlsx", "B"): ("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"),
    ("pptx", "A"): ("ppt/notesSlides/notesSlide1.xml", "ppt/slides/slide1.xml", "ppt/slides/slide2.xml"),
    ("pptx", "B"): ("ppt/notesSlides/notesSlide1.xml", "ppt/slides/slide1.xml", "ppt/slides/slide2.xml"),
}

LEGS = list(PARTS.keys())


def leg_name(fmt, order):
    return "%s-%s" % (fmt, order)


def check_redacted(path, fmt, order):
    """返回 (通过?, 说明列表)。腿 = (格式, 部件顺序)。"""
    problems = []
    label = leg_name(fmt, order)
    with zipfile.ZipFile(path) as zf:
        text = all_text(zf)
        parts = part_texts(zf)
    head_part, tail_part, id_part = PARTS[(fmt, order)]

    hp = parts.get(head_part, "")
    tp = parts.get(tail_part, "")
    ip = parts.get(id_part, "")

    # ① 数字部件里：16 位数字必须**原样还在**（没被误当成 VIN 脱掉）
    if VIN_HEAD not in hp:
        problems.append("[%s] 数字部件 %s 里 16 位数字不见了 → 被误当成车架号脱敏了"
                        % (label, head_part))
    # ② 数字部件里：不得出现「车架号」标签（那说明真被当 VIN 脱了）
    if "车架号" in hp:
        problems.append("[%s] 数字部件 %s 里出现了「车架号」→ 跨部件被误判成车架号"
                        % (label, head_part))
    # ③ 字母部件里：那个字母必须**原样还在**
    if VIN_TAIL not in tp:
        problems.append("[%s] 字母部件 %s 里那个字母不见了 → 被误当成车架号碎片脱敏了"
                        % (label, tail_part))
    # ④ 字母部件里：也不得出现「车架号」
    if "车架号" in tp:
        problems.append("[%s] 字母部件 %s 里出现了「车架号」→ 跨部件被误判成车架号"
                        % (label, tail_part))
    # ⑤ 同一部件内部被切断的身份证必须**已经续接并脱敏**（两半都不该还在）
    if ID_HEAD in ip:
        problems.append("[%s] 同部件 %s 内身份证前半段还在 → 续接被关掉了"
                        % (label, id_part))
    if ID_TAIL in ip:
        problems.append("[%s] 同部件 %s 内身份证后半段还在 → 续接被关掉了"
                        % (label, id_part))
    # ⑥ 全包任何一处都不该有「车架号」（跨部件误判的直接证据）
    if "车架号" in text:
        problems.append("[%s] 脱敏包里出现了「车架号」标签 → 跨部件被误判成车架号（标签错）" % label)
    return (not problems), problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", help="样本输出目录（造样本模式）")
    ap.add_argument("--check", help="校验一个已脱敏的包（校验模式）")
    ap.add_argument("--leg", help="校验模式下的腿名，形如 docx-A / xlsx-B / pptx-A")
    args = ap.parse_args()

    # ---- 校验模式 ----
    if args.check:
        if not args.leg:
            print("!! --check 必须配 --leg")
            return 2
        fmt, _, order = args.leg.partition("-")
        if (fmt, order) not in PARTS:
            print("!! 未知腿：%s（可选：%s）" % (args.leg, "、".join(leg_name(*l) for l in LEGS)))
            return 2
        ok, probs = check_redacted(args.check, fmt, order)
        if ok:
            print("%s：全部检查通过" % args.leg)
            return 0
        print("%s：%d 项检查不通过" % (args.leg, len(probs)))
        for p in probs:
            print("  - %s" % p)
        return 1

    # ---- 造样本模式 ----
    if not args.out:
        print("用法：--out <目录>（造样本）或 --check <包> --leg <腿名>（校验）")
        return 2
    os.makedirs(args.out, exist_ok=True)
    for fmt, builder in (("docx", build_docx), ("xlsx", build_xlsx), ("pptx", build_pptx)):
        for order in ("A", "B"):
            path = os.path.join(args.out, "case-%s-%s.%s" % (fmt, order, fmt))
            parts = builder(order)
            with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
                for name, data in parts.items():
                    zf.writestr(name, data)
            print("造好 %s（%d 个部件）：%s" % (leg_name(fmt, order), len(parts), path))
    print()
    print("跨部件两半：%s + %s  → 拼起来是形态合法的 VIN" % (VIN_HEAD, VIN_TAIL))
    print("同部件两半：%s + %s  → 拼起来是过校验位的身份证" % (ID_HEAD, ID_TAIL))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
