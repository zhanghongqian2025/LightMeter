#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""现造两份浏览器端到端用的 docx 夹具。

为什么不复用被测代码生成样本：校验端到端时要证明的是「界面上真的拿到了结果」。
如果样本也是被测代码生成的，那么被测代码对某种结构的理解一旦有偏差，样本就跟着错，
最后测出来「界面有命中」是因为样本里本来就有——标尺必须独立于被测实现，
这条对**样本生成**同样成立。所以这里只用 zipfile + 手写 XML，不 import 任何项目代码。

产出两份：

  sample.docx  小文档，四个敏感值（身份证/手机号/邮箱/发票号）分散在
               段落、跨 run 的段落、表格、页眉、文档属性里 —— 用来验证
               「界面上的命中清单真的渲染出来了，且原值一字不差」。

  big.docx     大量段落，用来验证「进度会往前爬」与「取消按钮真的接上了事件」。
               段落数越多，浏览器里点取消时任务还在跑的概率越高；
               这不是靠运气，是靠「任务本身够长」。

【为什么不用 txt 代替 big.docx】
  txt 整篇只有 1 个文本单元，引擎要等整篇读完才动一次，
  进度条从头到尾都不动——那样的进度是「假进度」。
  浏览器测试要能看见进度真的在爬，段落型文档才做得到。
"""

import argparse
import os
import zipfile

# 四个值都经过规则校验器（ID_CARD / phone / email 都要过校验位或格式）。
# 不要随手编数字：编错一位校验位，规则不命中，
# 测试会安静地变成「检查一个本来就不敏感的值还在不在」。
VALUES = {
    "id_card": "11010519491231002X",
    "phone": "13800138000",
    "email": "zhang@example.com",
    "invoice": "622202020202020207",
}

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
R = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
CT_HEAD = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
)

# 填充用的普通文字：不含任何敏感样式（纯中文 + 标点），
# 免得正则误命中，把「命中数」搅浑。
FILLER = "本合同由双方在充分协商的基础上签订，条款以正文所述为准。"

DOC_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId10" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
    'relationships/header" Target="header1.xml"/>'
    '</Relationships>'
)

ROOT_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
    'relationships/officeDocument" Target="word/document.xml"/>'
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/'
    'relationships/metadata/core-properties" Target="docProps/core.xml"/>'
    '</Relationships>'
)

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

CONTENT_TYPES = CT_HEAD + (
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-'
    'officedocument.wordprocessingml.document.main+xml"/>'
    '<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-'
    'officedocument.wordprocessingml.header+xml"/>'
    '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-'
    'package.core-properties+xml"/>'
    '</Types>'
)


def _para(text):
    return ('<w:p><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>' % text)


def _docx(body, header):
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document %s %s><w:body>%s'
        '<w:sectPr><w:headerReference w:type="default" r:id="rId10"/></w:sectPr>'
        '</w:body></w:document>'
    ) % (W, R, body)
    hdr = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<w:hdr %s>%s</w:hdr>' % (W, _para(header)))
    return {
        "[Content_Types].xml": CONTENT_TYPES,
        "_rels/.rels": ROOT_RELS,
        "docProps/core.xml": CORE_XML,
        "word/document.xml": document,
        "word/header1.xml": hdr,
        "word/_rels/document.xml.rels": DOC_RELS,
    }


def build_sample():
    v = VALUES
    parts = []
    # 1. 身份证横跨三个 run，但**在同一个段落里**。
    #    这两点都要对：段内的多个 w:t 会被拼成一个文本单元，引擎认得出完整的身份证；
    #    而拆到两个段落去，跨段落的标识符引擎不合并（那是另一个已知缺口），
    #    那样清单里根本不会出现完整的身份证，浏览器断言就成了「测一个不存在的东西」。
    parts.append(
        '<w:p><w:r><w:t xml:space="preserve">身份证</w:t></w:r>'
        '<w:r><w:t xml:space="preserve">%s</w:t></w:r>'
        '<w:r><w:t xml:space="preserve">%s号</w:t></w:r></w:p>'
        % (v["id_card"][:6], v["id_card"][6:])
    )
    # 2. 手机号在正文段落里
    parts.append(_para("联系人电话 %s。" % v["phone"]))
    # 3. 邮箱在表格单元格里
    parts.append(
        '<w:tbl><w:tr><w:tc>%s</w:tc></w:tr></w:tbl>' % _para("邮箱 %s" % v["email"])
    )
    # 4. 一段普通文字：证明清单里不该出现「随便什么文本都算命中」
    parts.append(_para(FILLER))
    return _docx("".join(parts), "页眉 %s" % v["phone"])


def build_big(paragraphs, chars):
    """大量段落：每一段都带一个手机号，后面跟一段填充文字。

    手机号用同一个值：这样命中数是确定的（1 个值 / N 处），
    浏览器测试断言「清单里出现了这个原值」时不会被同类值搅浑。
    """
    pad = FILLER * max(1, chars // len(FILLER))
    one = _para("第 %d 条 联系电话 %s。%s" % (0, VALUES["phone"], pad))
    body = []
    for i in range(paragraphs):
        body.append(one.replace("第 0 条", "第 %d 条" % i))
    return _docx("".join(body), "页眉 %s" % VALUES["phone"])


# 段内编号用汉字：规则里有「20 位数字」那类，句子里放阿拉伯数字会搅浑命中数。
PHONE_PLACES = ["一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                "十一", "十二", "十三", "十四", "十五"]


def build_many(count=5):
    """同一个值出现 N 次：用来测「第 N 处」的逐处排除（块 22）。

    <p>为什么不能靠 sample.docx：那里手机号只出现两次（正文 + 页眉），
    而界面上「出现处」那一格默认只列 3 条位置 —— 两次出现**碰不到**那条限制，
    也就验不出「第 4 次出现选不选得到」。
    """
    v = VALUES
    body = []
    for i in range(count - 1):
        body.append(_para("联系人电话 %s（%s处）。" % (v["phone"], PHONE_PLACES[i])))
    return _docx("".join(body), "页眉 %s" % v["phone"])


def write_docx(path, parts):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, content in parts.items():
            z.writestr(name, content.encode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="输出目录（不存在则创建）")
    ap.add_argument("--big-paragraphs", type=int, default=3000,
                    help="big.docx 的段落数（决定任务跑多久）")
    ap.add_argument("--big-chars", type=int, default=200,
                    help="big.docx 每段的填充字数")
    ap.add_argument("--many-occurrences", type=int, default=5,
                    help="sample-many.docx 里同一个值出现多少次（块 22：验「第 N 处」选不选得到）")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    sample = os.path.join(args.out, "sample.docx")
    big = os.path.join(args.out, "big.docx")
    # 第三个样本：文件名故意很长（"长文件名" × 15 = 60 个汉字）。
    # M2-c 的已知限制里有一条「长文件名在表格里的换行表现」，
    # 那条原本只能靠真人看；有了长名字的文件，浏览器测试就能自己量一遍
    # 「窄屏下有没有横向溢出」。
    long_name = "长文件名" * 15 + ".docx"
    long_path = os.path.join(args.out, long_name)
    # 第五个样本：后缀不在白名单内的文件（.exe）。
    # 服务端按**后缀**拒（不按内容），所以这里放什么都不该被收下——
    # 内容只是几字节的 MZ 头，够让「这是个可执行文件」看起来成立，
    # 但真正的判据是后缀。
    exe_path = os.path.join(args.out, "sample.exe")
    # 第六个样本：同一个值出现多次（块 22）。
    many_path = os.path.join(args.out, "sample-many.docx")
    write_docx(sample, build_sample())
    write_docx(big, build_big(args.big_paragraphs, args.big_chars))
    write_docx(long_path, build_sample())
    write_docx(many_path, build_many(args.many_occurrences))
    with open(exe_path, "wb") as f:
        f.write(b"MZ" + bytes(64))
    print("已生成 %s（%d 字节）" % (sample, os.path.getsize(sample)))
    print("已生成 %s（%d 字节，%d 段）"
          % (big, os.path.getsize(big), args.big_paragraphs))
    print("已生成 %s（%d 字节）" % (long_path, os.path.getsize(long_path)))
    print("已生成 %s（%d 字节，后缀不在白名单内）"
          % (exe_path, os.path.getsize(exe_path)))
    print("已生成 %s（%d 字节，同一个值出现 %d 次）"
          % (many_path, os.path.getsize(many_path), args.many_occurrences))
    print("长文件名：%s" % long_name)
    print("敏感值清单：%s" % VALUES)


if __name__ == "__main__":
    main()
