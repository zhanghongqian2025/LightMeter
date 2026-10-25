#!/usr/bin/env python3
"""生成「OFD 几何续接」用的样本包：三种切法 + 一份清单。

【为什么样本要单独一个生成器】
    端到端要验的是**命令行 + 打包后的 jar**。
    样本若由测试夹具类生成，等于把「测试代码本身对不对」混进了「成品对不对」；
    所以这里只用 zipfile 造包，**不 import 任何项目代码**。

【为什么用清单（samples.json）而不是把期望值写在校验器里】
    「这个样本长什么样、该被脱成什么样」是**一个事实**，
    只能定义一处。清单是它的唯一落点：
    生成器写期望值，校验器只按清单执行——
    两边各写一份的话，改了生成器忘了改校验器，就会得到「永远绿」的假通过。

【三种切法】
    紧邻：同一行、前者右边缘 == 后者左边缘（x=20+60=80 == 80）→ **必须续接**
    两栏：同一行、x 相距 60mm → **绝不能续接**（OFD 是绝对定位版式，跨栏拼接是版面事故）
    跨页：相邻两页、坐标完全相同 → **绝不能续接**（坐标一样不代表是同一段文字）

【坐标单位】毫米，与 OFD 的 Boundary="x y w h" 一致。
"""

import json
import pathlib
import sys
import zipfile

NS = "http://www.ofdspec.org/2016"

# 切开的那个标识符：身份证（18 位，过校验位）。
# 前 6 位放左边那个对象，后 12 位放右边那个对象。
ID_FULL = "11010519491231002X"
ID_HEAD = "110105"
ID_TAIL = "19491231002X"


def delta_x(count: int) -> str:
    """DeltaX 是每个字符的字宽列表——裁掉就还原不回原字距。"""
    return " ".join(["3.5"] * count)


def text_object(oid: int, x: int, y: int, w: int, text: str) -> str:
    return (f'<ofd:TextObject ID="{oid}" Boundary="{x} {y} {w} 5" Font="3" Size="3.5">'
            f'<ofd:TextCode X="0" Y="3" DeltaX="{delta_x(len(text))}">{text}</ofd:TextCode>'
            f'</ofd:TextObject>')


def xml(root: str, *bodies: str) -> str:
    inner = "".join(bodies)
    return (f'<?xml version="1.0" encoding="UTF-8"?>'
            f'<{root} xmlns:ofd="{NS}">{inner}</{root}>')


def page_xml(layer_body: str) -> str:
    return xml("ofd:Page",
               "<ofd:Content>",
               "<ofd:Layer ID=\"1\">",
               layer_body,
               "</ofd:Layer>",
               "</ofd:Content>")


def build(path: pathlib.Path, pages: list) -> pathlib.Path:
    """pages: [(部件名相对路径, layer_body), ...]"""
    page_refs = "".join(
        f'<ofd:Page ID="{i + 1}" BaseLoc="{rel}"/>' for i, (rel, _) in enumerate(pages))
    parts = {
        "OFD.xml": xml("ofd:OFD",
                        "<ofd:DocBody>",
                        "<ofd:DocInfo>",
                        "<ofd:DocID>0123456789abcdef0123456789abcdef</ofd:DocID>",
                        "<ofd:Author>端到端样本</ofd:Author>",
                        "<ofd:CreationDate>2026-01-01</ofd:CreationDate>",
                        "<ofd:Creator>gen_ofd_geo_sample</ofd:Creator>",
                        "</ofd:DocInfo>",
                        "<ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot>",
                        "</ofd:DocBody>"),
        "Doc_0/Document.xml": xml("ofd:Document",
                                  "<ofd:CommonData>",
                                  "<ofd:PublicRes>PublicRes.xml</ofd:PublicRes>",
                                  "<ofd:PageArea><ofd:PhysicalBox>0 0 210 297</ofd:PhysicalBox></ofd:PageArea>",
                                  "<ofd:MaxUnitID>20</ofd:MaxUnitID>",
                                  "</ofd:CommonData>",
                                  f"<ofd:Pages>{page_refs}</ofd:Pages>"),
        "Doc_0/PublicRes.xml": xml("ofd:Res",
                                    "<ofd:Fonts><ofd:Font FontName=\"宋体\" FamilyName=\"宋体\" ID=\"3\"/></ofd:Fonts>"),
    }
    for rel, layer_body in pages:
        parts["Doc_0/Pages/" + rel] = page_xml(layer_body)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, body in parts.items():
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.external_attr = 0o644 << 16
            z.writestr(zi, body.encode("utf-8"))
    return path


def main() -> int:
    if len(sys.argv) != 2:
        print("用法: gen_ofd_geo_sample.py <输出目录>", file=sys.stderr)
        return 2
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)

    head = "身份证：" + ID_HEAD
    tail = ID_TAIL

    cases = []

    # 1. 紧邻：x=20 w=60 → 右边缘 80；下一个 x=80 → 间隙 0mm（贴在一起）
    build(out / "紧邻.ofd", [("Page_0/Content.xml",
                             text_object(2, 20, 30, 60, head)
                             + text_object(3, 80, 30, 60, tail))])
    cases.append({
        "name": "紧邻",
        "file": "紧邻.ofd",
        "expectIdCardHits": 2,
        "expectHeadGone": True,
        "expectTailGone": True,
        "expectFullGone": True,
        "expectHead": head,
        "expectTail": tail,
        "note": "同行紧邻（间隙 0mm）：两半拼起来是完整身份证，两半必须各自脱敏，"
                "两个令牌各归各的对象",
    })

    # 2. 两栏：x=20 w=40 → 右边缘 60；下一个 x=120 → 间隙 60mm
    build(out / "两栏.ofd", [("Page_0/Content.xml",
                             text_object(2, 20, 30, 40, head)
                             + text_object(3, 120, 30, 40, tail))])
    cases.append({
        "name": "两栏",
        "file": "两栏.ofd",
        "expectIdCardHits": 0,
        # 前半是**孤立的 6 位**，凑不出 18 位 → 判不出来 → 刻意的 fail-open，**应当残留**。
        # 这不是漏脱：没有续接就没有「这是一个身份证」这个事实可依据。
        "expectHeadGone": False,
        # 后半独立成段时命中手机号规则（1[3-9]\\d{9} 吃掉前 11 位），所以它会消失。
        # 注意：这条**不是**续接的正确性判据，它证明的是「后半没被留在原地」。
        "expectTailGone": True,
        "expectFullGone": True,
        "expectHead": head,
        "expectTail": tail,
        "note": "左右两栏（间隙 60mm）：绝不能拼成身份证，否则是版面事故。"
                "前半孤立 6 位保留（fail-open），后半被手机号规则脱敏",
    })

    # 3. 跨页：两页各一个对象，坐标完全相同（x=20 w=60 与 x=80 w=60 → 间隙 0mm）
    build(out / "跨页.ofd", [("Page_0/Content.xml", text_object(2, 20, 30, 60, head)),
                             ("Page_1/Content.xml", text_object(3, 80, 30, 60, tail))])
    cases.append({
        "name": "跨页",
        "file": "跨页.ofd",
        "expectIdCardHits": 0,
        "expectHeadGone": False,
        "expectTailGone": True,
        "expectFullGone": True,
        "expectHead": head,
        "expectTail": tail,
        "note": "相邻两页、坐标相同：坐标一样不代表是同一段文字，组不能跨部件。"
                "前半孤立 6 位保留（fail-open），后半被手机号规则脱敏",
    })

    manifest = {
        "idFull": ID_FULL,
        "idHead": ID_HEAD,
        "idTail": ID_TAIL,
        "cases": cases,
    }
    manifest_path = out / "samples.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("已生成样本与清单: %s" % out)
    for c in cases:
        print("  %-6s %s  期望 id_card 命中 %d 条 —— %s"
              % (c["name"], c["file"], c["expectIdCardHits"], c["note"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
