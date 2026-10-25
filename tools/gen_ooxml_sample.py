#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""现造三份 OOXML 样本，把敏感值放进「容易漏的位置」。

为什么用 Python 而不是 Java、为什么不复用被测代码：
校验端到端时要证明的是「原文没留在包里任何一处」。如果样本也是被测代码
生成的，那么被测代码对某种结构的理解一旦有偏差，样本就跟着错，
最后测出来「全文没有原值」是因为样本里本来就没有——
标尺必须独立于被测实现，这条对**样本生成**同样成立。
所以这里只用 zipfile + 手写 XML，不 import 任何项目代码。

覆盖的「容易漏的位置」清单（缺一项就等于没测那个位置）：

docx
  1. 身份证被**段落边界**切断（前半段与后半段分处两个 w:p）
  2. 身份证被**同一段落内的 run 边界**切断（值本身分处两个 w:t）
  3. 表格单元格
  4. 文本框 w:txbxContent/w:p（嵌在 w:r 里，不是 body 直接子节点）
  5. 修订留痕 w:delText（正文里看不见，Word 里是删除线）
  6. 页眉 word/header1.xml
  7. 页脚 word/footer1.xml
  8. 批注 word/comments.xml
  9. 文档属性 docProps/core.xml（作者）

xlsx
  1. 共享字符串 <si> 之间的**跨单元**切断（第 0 项与第 1 项分处两个 si）
  2. 共享字符串 <si> 的富文本多片段（身份证被拆进同一 si 的多个 r/t）
  3. inlineStr 单元格
  4. 数值单元格（20 位发票号，转成文本才看得见脱敏）
  5. 工作表名（在 xl/workbook.xml 里）
  6. 文档属性 docProps/core.xml

pptx
  1. 幻灯片正文被**段落边界**切断（分处两个 a:p）
  2. 幻灯片正文被**同一段落内的 run 边界**切断（值本身分处两个 a:t）
  3. 图表数据缓存 c:numCache/c:pt/c:v
     （pptx 的图表数据是内嵌的、打开时不重算，这一份就是屏幕上那份）
  4. 备注页 ppt/notesSlides/notesSlide1.xml
  5. 文档属性 docProps/core.xml

关于「切断」为什么必须用**完整值的两半**（M3-4 踩出来的盲区）：
早先这里写的是「身份证 + 前 10 位 + 号」——后半段根本没放进文档。
于是那个值既不匹配任何规则，也不是任何完整标识符的一部分，
**全包扫描查不到它（它不是清单里的完整值），规则也匹配不上它**，
两边都「没事」，这条用例就成了一句空断言。
切开后它就是一段真实的敏感数据，哪怕拼起来不是完整身份证也照样是个人信息。
所以现在两半都在：6 位 + 12 位，拼起来正好是一个完整且过校验位的身份证。

用法：gen_ooxml_sample.py --out <目录>
"""

import argparse
import json
import os
import zipfile

# 四个值，全部经过规则校验器（ID_CARD / phone / email 三个都要过校验位或格式）。
# 不要再往里加「随手编的数字」：编错一位校验位，规则不命中，
# 测试会安静地变成「检查一个本来就不敏感的值还在不在」。
VALUES = {
    "id_card": "11010519491231002X",
    "phone": "13800138000",
    "email": "zhang@example.com",
    "invoice": "622202020202020207",
}

# 被切断的身份证的两半。**由上面的完整值派生，不另立一处**：
# 拆成 6 + 12 是刻意的——14 + 3 那种切法两片都匹配不上任何规则，
# 拼起来又不是完整的 18 位，是最难的一类；6 + 12 两片各自也匹配不上，
# 但拼起来是完整且过校验位的身份证。
ID_HEAD = VALUES["id_card"][:6]        # 110105
ID_TAIL = VALUES["id_card"][6:]         # 19491231002X

# 碎片清单：校验器要**逐条**检查它们在脱敏包里一处都不剩。
#
# 为什么必须单独列：完整值扫描查的是「这 18 位一串还在不在」。
# 碎片散落在包里时，完整值扫描**查不到**（那 18 位从来没连在一起出现过），
# 而碎片本身就是个人信息——这正是 M3-4 记的「不是泄漏」结论的错处。
#
# expect_type 是对**类型**的要求，不只是「有没有被替换掉」：
# 碎片如果被错标成手机号，完整值扫描同样查不出来（碎片不是完整手机号），
# 只有断言「令牌前缀是身份证」才咬得住。
FRAGMENTS = {
    "id_head": {"value": ID_HEAD, "expect_type": "身份证"},
    "id_tail": {"value": ID_TAIL, "expect_type": "身份证"},
}

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
R = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
CT_HEAD = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
)


def _rels(rels):
    body = "".join(
        '<Relationship Id="%s" Type="http://schemas.openxmlformats.org/'
        'officeDocument/2006/relationships/%s" Target="%s"/>' % r
        for r in rels
    )
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/'
            'package/2006/relationships">%s</Relationships>' % body)


ROOT_RELS = _rels([
    ("rId1", "officeDocument", "word/document.xml"),
    ("rId2", "core-properties", "docProps/core.xml"),
])

CORE_XML = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<cp:coreProperties '
    'xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
    'xmlns:dc="http://purl.org/dc/elements/1.1/">'
    '<dc:creator>%(email)s</dc:creator>'
    '<cp:lastModifiedBy>%(email)s</cp:lastModifiedBy>'
    '<dc:title>卷宗 %(invoice)s</dc:title>'
    '</cp:coreProperties>'
) % VALUES


def _docx():
    v = VALUES
    # 1. 身份证被段落边界切断：两半分处两个 w:p —— 逐段匹配必然漏
    para_split = (
        '<w:p><w:r><w:t xml:space="preserve">身份证</w:t></w:r>'
        '<w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>'
    ) % ID_HEAD
    para_split += (
        '<w:p><w:r><w:t>续</w:t></w:r><w:r><w:t>%s</w:t></w:r>'
        '<w:r><w:t>尾</w:t></w:r></w:p>'
    ) % ID_TAIL
    # 2. 身份证被同一段落内的 run 边界切断：值本身分处两个 w:t
    para_runs = (
        '<w:p><w:r><w:t>身份证</w:t></w:r>'
        '<w:r><w:t>%s</w:t></w:r>'
        '<w:r><w:t>%s</w:t></w:r>'
        '<w:r><w:t>号</w:t></w:r></w:p>'
    ) % (ID_HEAD, ID_TAIL)
    # 3. 表格
    table = (
        '<w:tbl><w:tr><w:tc><w:p><w:r><w:t>联系人</w:t></w:r></w:p></w:tc>'
        '<w:tc><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:tc></w:tr></w:tbl>'
    ) % v["phone"]
    # 4. 文本框：w:txbxContent 嵌在 w:p/w:r 里，不是 body 的直接子节点
    textbox = (
        '<w:p><w:r><w:pict><w:txbxContent><w:p><w:r>'
        '<w:t>附注 %s</w:t></w:r></w:p></w:txbxContent></w:pict></w:r></w:p>'
    ) % v["email"]
    # 5. 修订留痕：屏幕上是被划掉的字，朴素抽取抽不到
    deleted = (
        '<w:p><w:del w:id="1" w:author="a"><w:r>'
        '<w:delText>%s</w:delText></w:r></w:del></w:p>'
    ) % v["invoice"]

    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document %s %s><w:body>%s%s%s%s%s'
        '<w:sectPr><w:headerReference w:type="default" r:id="rId10"/>'
        '<w:footerReference w:type="default" r:id="rId11"/></w:sectPr>'
        '</w:body></w:document>'
    ) % (W, R, para_split, para_runs, table, textbox, deleted)

    header = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
              '<w:hdr %s><w:p><w:r><w:t>页眉 %s</w:t></w:r></w:p></w:hdr>'
              % (W, v["phone"]))
    footer = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
              '<w:ftr %s><w:p><w:r><w:t>页脚 %s</w:t></w:r></w:p></w:ftr>'
              % (W, v["id_card"]))
    comments = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<w:comments %s><w:comment w:id="0" w:author="a">'
                '<w:p><w:r><w:t>批注 %s</w:t></w:r></w:p></w:comment></w:comments>'
                % (W, v["email"]))

    ct = CT_HEAD + (
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>'
        '<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>'
        '<Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/>'
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        '</Types>')

    return "case.docx", {
        "[Content_Types].xml": ct,
        "_rels/.rels": ROOT_RELS,
        "docProps/core.xml": CORE_XML,
        "word/document.xml": document,
        "word/header1.xml": header,
        "word/footer1.xml": footer,
        "word/comments.xml": comments,
        "word/_rels/document.xml.rels": _rels([
            ("rId10", "header", "header1.xml"),
            ("rId11", "footer", "footer1.xml"),
            ("rId12", "comments", "comments.xml"),
        ]),
    }


def _xlsx():
    v = VALUES
    # 1. 共享字符串之间的跨单元切断：第 0 项与第 1 项分处两个 si
    sst = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'count="5" uniqueCount="5">'
        '<si><r><t>身份证</t></r><r><t>%s</t></r></si>'
        '<si><r><t>续</t></r><r><t>%s</t></r><r><t>尾</t></r></si>'
        '<si><r><t>身份证</t></r><r><t>%s</t></r><r><t>%s</t></r><r><t>号</t></r></si>'
        '<si><t>邮箱 %s</t></si>'
        '<si><t>页脚 %s</t></si>'
        '</sst>'
    ) % (ID_HEAD, ID_TAIL, ID_HEAD, ID_TAIL, v["email"], v["id_card"])
    # 2. inlineStr 单元格
    sheet = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheetData>'
        '<row r="1"><c r="A1" t="s"><v>0</v></c>'
        '<c r="B1" t="inlineStr"><is><t>联系人 %s</t></is></c>'
        '<c r="C1"><v>%s</v></c></row>'
        '</sheetData></worksheet>'
    ) % (v["phone"], v["invoice"])
    # 3. 工作表名（在 xl/workbook.xml 里）
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets><sheet name="联络%s" sheetId="1" r:id="rId1"/></sheets></workbook>'
    ) % v["phone"]

    ct = CT_HEAD + (
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        '</Types>')
    wb_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
               '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
               '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>'
               '</Relationships>')
    return "case.xlsx", {
        "[Content_Types].xml": ct,
        "_rels/.rels": _rels([
            ("rId1", "officeDocument", "xl/workbook.xml"),
            ("rId2", "core-properties", "docProps/core.xml"),
        ]),
        "docProps/core.xml": CORE_XML,
        "xl/workbook.xml": workbook,
        "xl/_rels/workbook.xml.rels": wb_rels,
        "xl/worksheets/sheet1.xml": sheet,
        "xl/sharedStrings.xml": sst,
    }


def _pptx():
    v = VALUES
    # 1. 幻灯片正文被段落边界切断：两半分处两个 a:p
    # 2. 同一段落内被 run 边界切断：值本身分处两个 a:t
    slide = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
        'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
        '<p:cSld><p:spTree>'
        '<p:sp><p:txBody>'
        '<a:p><a:r><a:t>身份证</a:t></a:r><a:r><a:t>%s</a:t></a:r></a:p>'
        '<a:p><a:r><a:t>续</a:t></a:r><a:r><a:t>%s</a:t></a:r><a:r><a:t>尾</a:t></a:r></a:p>'
        '<a:p><a:r><a:t>身份证</a:t></a:r><a:r><a:t>%s</a:t></a:r>'
        '<a:r><a:t>%s</a:t></a:r><a:r><a:t>号</a:t></a:r></a:p>'
        '</p:txBody></p:sp>'
        '<p:sp><p:txBody><a:p><a:r><a:t>电话 %s</a:t></a:r></a:p></p:txBody></p:sp>'
        '</p:spTree></p:cSld></p:sld>'
    ) % (ID_HEAD, ID_TAIL, ID_HEAD, ID_TAIL, v["phone"])
    # 3. 图表数据缓存：pptx 打开时**不重算**，c:v 就是屏幕上那份
    chart = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart">'
        '<c:chart><c:plotArea><c:barChart><c:ser><c:val><c:numRef>'
        '<c:f>Sheet1!$B$2:$B$3</c:f><c:numCache><c:formatCode>General</c:formatCode>'
        '<c:ptCount val="2"/>'
        '<c:pt idx="0"><c:v>%s</c:v></c:pt>'
        '<c:pt idx="1"><c:v>%s</c:v></c:pt>'
        '</c:numCache></c:numRef></c:val>'
        '<c:cat><c:strRef><c:strCache><c:ptCount val="2"/>'
        '<c:pt idx="0"><c:v>%s</c:v></c:pt>'
        '<c:pt idx="1"><c:v>%s</c:v></c:pt>'
        '</c:strCache></c:strRef></c:cat>'
        '</c:ser></c:barChart></c:plotArea></c:chart></c:chartSpace>'
    ) % (v["phone"], v["invoice"], v["email"], v["id_card"])

    # 4. 备注页
    notes = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
        'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
        '<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r>'
        '<a:t>备注 %s</a:t></a:r></a:p></p:txBody></p:sp>'
        '</p:spTree></p:cSld></p:notes>'
    ) % v["email"]

    presentation = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>'
    )
    ct = CT_HEAD + (
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
        '<Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>'
        '<Override PartName="/ppt/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>'
        '<Override PartName="/ppt/notesSlides/notesSlide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"/>'
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        '</Types>')
    pres_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                 '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>'
                 '</Relationships>')
    return "case.pptx", {
        "[Content_Types].xml": ct,
        "_rels/.rels": _rels([
            ("rId1", "officeDocument", "ppt/presentation.xml"),
            ("rId2", "core-properties", "docProps/core.xml"),
        ]),
        "docProps/core.xml": CORE_XML,
        "ppt/presentation.xml": presentation,
        "ppt/_rels/presentation.xml.rels": pres_rels,
        "ppt/slides/slide1.xml": slide,
        "ppt/charts/chart1.xml": chart,
        "ppt/notesSlides/notesSlide1.xml": notes,
    }


def write_pkg(path, parts):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in parts.items():
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, text.encode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="输出目录（不存在则创建）")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for builder in (_docx, _xlsx, _pptx):
        name, parts = builder()
        p = os.path.join(args.out, name)
        write_pkg(p, parts)
        print("已生成 %s（%d 个部件）" % (p, len(parts)))
    with open(os.path.join(args.out, "values.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps({"values": VALUES, "fragments": FRAGMENTS},
                           ensure_ascii=False, indent=2))
    print("敏感值清单：%s" % VALUES)
    print("碎片清单：%s" % {k: v["value"] for k, v in FRAGMENTS.items()})


if __name__ == "__main__":
    main()
