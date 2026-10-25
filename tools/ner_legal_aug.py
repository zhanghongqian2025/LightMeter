"""法律文书域训练语料合成（M3-3 的 ORG 短板是这么来的）。

## 为什么要有这一段

M3-3 用产品路径在法律文书留出集上实测：**ORG（机构名）F1 只有 0.1571**，
227 个误报、288 个漏检。诊断结论是训练覆盖缺口，不是评测集的错——
留出集里的「桦甸泰合建筑工程设计研究院」这类机构在真实法律文书里完全会出现，
换成训练集那种「XX公司」数字会好看，但那是放松评测而不是改进模型。

查 MSRA train 的 7865 个机构名，后缀高频项是
「公司 706 / 委员会 277 / 大学 175 / 集团 175 / 协会 173 / 中心 170」——
**「律师事务所 / 公证处 / 司法鉴定所 / 资产评估事务所 / 价格认证中心」一个都没有**。
新闻语料里本来就不会高频出现这些，而法律文书里它们是主力。

## 这一段补的是两样东西

1. **法律行业机构名的构词模式**（三段式：地域 + 字号 + 行业后缀）；
2. **法律文书的角色词上下文**（`法定代表人：` 后面跟人名、`鉴定机构为` 后面跟机构、
   `审判长` 后面跟人名……）。

第 2 条同样重要：错误样本里「委托诉讼代理人桦甸泰合资产评估事务所，男，」
一个都没认出来，说明模型**不是靠字形认的，是靠上下文认的**，
而新闻语料里没有这些上下文模式。

## 与评测集的分界（这一段是防线，不是客套）

评测集在 `tools/gen_legal_eval.py`，训练语料在这里。两者的关系：

| | 评测集 | 训练语料 |
|---|---|---|
| 句式骨架 | 9 个模板 | **另外 9 个不同的骨架** |
| 角色词 | 法律角色词表 | **同一张表**（角色词是真实词汇，模型应该认识） |
| 行业后缀 | 法律行业后缀表 | **同一张表** |
| 人名 | 手工构造、与 MSRA train 零重叠 | **从 MSRA train 抽样** |
| 机构名 | 手工构造地域 + 字号 | **从 MSRA train 提取的字号 + 不同的地域配对** |

关键区分：**共享的是「词」，不共享的是「实体」**。
「有限公司」「律师事务所」这类词模型当然要见过——
没见过它就不叫泛化，叫闭眼。真正需要留出的是**完整实体的组合**。

所以任何时候都成立：评测集里任何一个完整实体表面串，
既不在 MSRA train 里，也不在本模块生成的训练语料里。
`--assert-disjoint` 会把这条断言跑一遍。

## 怎么防止「合成痕迹变成捷径」

这是名单式合成（`ner_roster_aug.py`）已经踩过的坑，做法照抄：

* 实体在句中的**位置随机**，不固定跟在角色词后面——
  固定位置的话模型只需学「原告后面 3 个字是人名」，换个句式就崩；
* 每句都掺非实体文本（案号、日期、法条引用、当事人称谓）；
* 长度与实体个数随机，避免固定模式；
* 生成结果过一遍 BIO 硬约束校验，非法直接抛错。

**训练集的比例是刻意压低的**：法律合成句只占训练语料的一小部分。
让它占大头会让模型在新闻语料上崩，而 MSRA test 是公开可比的基准，不能牺牲。
"""
from __future__ import annotations

import random

# 通道编号必须与 train_ner.py 的标签常量一致：
O, B_PER, I_PER, B_ORG, I_ORG = 0, 1, 2, 3, 4

# --------------------------------------------------------------------------
# 法律行业后缀。
#
# 与评测集用**同一张表**是刻意的：这些是词，不是实体。
# 真实法律文书里机构名就长这样，模型没见过「律师事务所」这个词，
# 那不叫泛化，那叫缺数据。
#
# 下面每一项都是评测集里出现过的后缀（见 gen_legal_eval.ORG_TAILS），
# 差别在于**地域与字号**取自 MSRA train，配对方式也完全不同。
# --------------------------------------------------------------------------
LEGAL_ORG_TAILS = (
    "律师事务所", "公证处", "司法鉴定所", "资产评估事务所", "会计师事务所",
    "劳动争议仲裁委员会", "消费者协会", "价格认证中心", "建筑工程设计研究院",
    "房地产估价事务所", "工程监理公司", "人力资源服务机构", "信息咨询有限公司",
    "招标代理有限公司", "律师事务所", "卫生监督所", "质量技术监督局",
    "价格评估中心", "司法鉴定研究院", "公证服务中心",
)

# 法律文书里出现的人名角色词。与评测集共用——真实文书里就是这些词。
ROLES_PERSON = (
    "原告", "被告", "第三人", "上诉人", "被上诉人", "申请人", "被申请人",
    "委托诉讼代理人", "法定代理人", "法定代表人", "审判长", "审判员",
    "书记员", "法官助理", "人民陪审员", "证人", "鉴定人",
    "仲裁员", "仲裁庭首席仲裁员", "人民调解员", "公证员", "执行法官",
    "执行员", "出庭人员", "代理人", "辩护人", "诉讼代理人", "辅助人", "监护人",
)

# 机构名角色词。同一张表，供训练模板使用。
ROLES_ORG = (
    "原告", "被告", "第三人", "上诉人", "被上诉人", "申请人", "被申请人",
    "委托诉讼代理人", "法定代表人", "鉴定机构", "受理机构", "保全法院",
    "一审法院", "二审法院", "公证机构", "调解组织", "仲裁机构",
    "鉴定人所在单位", "证据提供方", "上诉法院", "监督法院",
)

# 非实体的法律文书填充片段。刻意挑了不会被人名/机构规则误伤的。
FILLER = (
    "与本案有关的", "根据《中华人民共和国民事诉讼法》的相关规定，",
    "经本院依法组成合议庭，", "本案于二〇二四年五月十九日立案受理，",
    "双方当事人争议的焦点为：", "现就上述争议焦点审理如下：",
    "一、争议事实与理由", "二、本院认定", "三、判决如下：",
    "驳回其他诉讼请求。", "案件受理费由负担。",
    "如不服本判决，可于判决书送达之日起十五日内向上一级法院提起上诉。",
    "权利人应当在法定期限内行使权利。", "债务人应当清偿债务。",
    "标的物为", "合同签订地：", "履行地：", "管辖法院：",
    "审理终结", "争议标的", "合同总价款", "逾期利息",
    "证据目录", "质证意见", "争议调解", "财产保全",
)


# ==========================================================================
# v6 的负面结果：把上面四张表**扩大**，法律域反而更差
# ==========================================================================
#
# v6 在**不新增句式骨架**的前提下扩了词表（这与 v5 不同，v5 加的是新模板）。
#
# 规模（**逐条数出来的，不是估的**——第一版这里写的是 31/22/21/29 四个估值，
# 实际是 29/21/20/26，与文件里的条目数对不上，已按实际改）：
#
#   人名角色词   29 → 52
#   机构角色词   21 → 40
#   行业后缀     20 → 33
#   填充片段     26 → 56
#
# 另外改了 4 处模板内的连接词与序列。
#
# 实测（修正后的法律域评测集，与 v4 同口径）：
#
#   主集 F1  0.8271 → **0.7787**     附集 F1  0.6606 → **0.6217**
#   公开新闻基准  MSRA 0.8310 → 0.8359     cluener 0.9362 → 0.9370
#
# **扩词表不是补充训练信号，而是稀释它。** 句子总数不变而上下文变多，
# 于是每个上下文的样例数下降。训练曲线早就说明模型靠背上下文工作：
# 同一生成器的法律留出 0.9982，而独立评测只有 0.827——
# 差的那 0.17 就是「我见过这句话」而不是「我认识这个结构」的那部分。
#
# 退化集中在一个切片上，证据很干净：
#
#   2 字人名召回  0.7702 → 0.7702（181/235，**逐个相同**）
#   3 字人名召回  0.7715 → 0.6559（547/709，塌了 82 个）
#
# v4 的 302 个漏检里只有 84 个变成了 v6 多出来的漏检，而 3 字这一档就占了
# 82 个——几乎就是全部增量。候选成因：其中把合议庭职务序列从固定轮转改成
# `rng.sample(..., 2..4)` **减少了人名密度**，而合议庭恰恰是人名最密的上下文。
# 这是推断不是定论；要证它需要把那一处单独改回去、只训一次。
#
# **四处模板内改动也已全部还原**（当事人块的尾随字段、合议庭职务序列、
# 鉴定语境、送达清单分隔符）。因此本模块的默认状态与 v4 的训练配方**逐字一致**，
# v4 的每一个数字都可复现；负结果只以注释与 `OPTIONAL_*` 的形式留存。
#
# 下面这些条目**保留但默认不启用**，与 `OPTIONAL_TEMPLATES` 同一个理由：
# 删掉等于把这次负结果也删掉，下一个人会重踩一遍。
OPTIONAL_ROLES_PERSON_EXTRA = (
    "共同原告", "共同被告", "有独立请求权的第三人", "无独立请求权的第三人",
    "再审申请人", "被再审人", "遗产管理人", "遗嘱执行人",
    "意定监护人", "破产管理人", "清算组", "和解申请方", "调解员",
    "委托鉴定人", "补充鉴定人", "出庭证人", "质证方", "利害关系人",
    "代位继承人", "继承人", "放弃继承权人", "受遗赠人", "债权人",
)

OPTIONAL_ROLES_ORG_EXTRA = (
    "再审法院", "执行法院", "破产清算组", "遗产管理人机构", "遗嘱执行机构",
    "协助执行单位", "财产保全法院", "受理仲裁机构", "调解委员会", "社区矫正机构",
    "法律援助机构", "司法行政机关", "市场监督管理部门", "社会保险经办机构",
    "登记机关", "行业主管部门", "价格认证机构", "保险机构", "担保机构",
)

OPTIONAL_ORG_TAILS_EXTRA = (
    "法律援助中心", "人民调解委员会", "社区矫正机构", "破产管理人事务所",
    "税务师事务所", "土地估价事务所", "测绘院",
    "司法鉴定中心", "仲裁委员会秘书处", "标准化研究院",
    "计量测试检定所", "产品质量监督检验中心", "特种设备检验检测研究院",
)


def _emit(parts: list[str], tags: list[int], value: str, kind: str) -> None:
    bio = {"PER": (B_PER, I_PER), "ORG": (B_ORG, I_ORG)}[kind]
    for i, ch in enumerate(value):
        parts.append(ch)
        tags.append(bio[0] if i == 0 else bio[1])


def _plain(parts: list[str], tags: list[int], text: str) -> None:
    for ch in text:
        parts.append(ch)
        tags.append(O)


def _org(rng, heads: list[str], cities: list[str]) -> str:
    """地域 + 字号 + 法律行业后缀。

    三段式是这个模块存在的核心理由：MSRA train 的机构名几乎都是两段式
    （地名 + 公司/大学），法律文书里的「地名 + 字号 + 行业后缀」它没见过。
    """
    r = rng.random()
    if r < 0.22:
        # 偶尔退回两段式，避免模型学成「三段字的一定是机构」
        return rng.choice(cities) + rng.choice(heads) + rng.choice(LEGAL_ORG_TAILS)
    if r < 0.30:
        # 或者只字号 + 后缀（无地域），同样是真实文书的常见形态
        return rng.choice(heads) + rng.choice(LEGAL_ORG_TAILS)
    return rng.choice(cities) + rng.choice(heads) + rng.choice(LEGAL_ORG_TAILS)


# --------------------------------------------------------------------------
# 训练模板：与评测集的 9 个骨架**不同**。
#
# 「不同」指的是句式骨架与填充语料不同，不是角色词不同。
# 角色词共用是对的——真实文书里就是这些词。
# --------------------------------------------------------------------------
def _t_party_block(rng, P, H, C):
    """当事人基本信息块：多个角色连续出现，密度高。"""
    parts, tags = [], []
    n = 0
    for _ in range(rng.randint(1, 4)):
        role = rng.choice(ROLES_PERSON)
        _plain(parts, tags, role)
        if rng.random() < 0.55:
            _emit(parts, tags, rng.choice(P), "PER")
        else:
            _emit(parts, tags, _org(rng, H, C), "ORG")
        n += 1
        _plain(parts, tags, rng.choice(
            ("，男，", "，女，", "，汉族，", "，公民身份号码：",
             "，联系电话：", "，送达地址：", "，系本案")))
    return parts, tags, n


def _t_legal_rep(rng, P, H, C):
    """法定代表人 + 机构：法律文书里 PERSON/ORG 交替最密的一种。"""
    parts, tags = [], []
    n = 0
    for _ in range(rng.randint(1, 3)):
        _plain(parts, tags, rng.choice(ROLES_ORG))
        _emit(parts, tags, _org(rng, H, C), "ORG")
        _plain(parts, tags, rng.choice(
            ("的法定代表人", "的委托诉讼代理人", "的负责人", "的代理人")))
        _emit(parts, tags, rng.choice(P), "PER")
        _plain(parts, tags, rng.choice(
            ("，职务：", "，住所地：", "，统一社会信用代码：", "，系本案")))
        n += 2
    return parts, tags, n


def _t_bench(rng, P, H, C):
    """审判组织：人名连片但不分隔符。"""
    parts, tags = [], []
    _plain(parts, tags, rng.choice(
        ("本案由", "本院依法指定", "经双方协商确定")))
    n = 0
    for role in ("审判长", "审判员", "审判员", "书记员", "人民陪审员", "法官助理"):
        if rng.random() < 0.7:
            _plain(parts, tags, role)
            _emit(parts, tags, rng.choice(P), "PER")
            n += 1
            if rng.random() < 0.55:
                _plain(parts, tags, rng.choice(("，", "；", " ")))
    _plain(parts, tags, rng.choice(("组成合议庭。", "参与本案审理。", "，公开开庭审理。")))
    return parts, tags, n


def _t_expertise(rng, P, H, C):
    """鉴定 / 公证 / 评估：机构 + 人员成对出现。"""
    parts, tags = [], []
    n = 0
    _plain(parts, tags, rng.choice(("本院委托", "原告申请", "被告申请", "双方共同申请")))
    _plain(parts, tags, rng.choice(("鉴定人", "鉴定机构", "公证机构", "评估机构")))
    if rng.random() < 0.6:
        _emit(parts, tags, _org(rng, H, C), "ORG")
        n += 1
    else:
        _emit(parts, tags, rng.choice(P), "PER")
        n += 1
    _plain(parts, tags, rng.choice(("对涉案财物进行", "就争议事实进行", "对合同标的进行")))
    _plain(parts, tags, rng.choice(("鉴定", "公证", "评估", "审计")) + "。")
    if rng.random() < 0.6:
        n += 1
        _emit(parts, tags, _org(rng, H, C), "ORG")
        _plain(parts, tags, rng.choice(("出具的", "出具的鉴定意见", "出具的评估报告")) + rng.choice(("本院予以采信。", "双方均有异议。", "作为定案依据。")))
    return parts, tags, n


def _t_enforcement(rng, P, H, C):
    """执行程序：法院 + 执行人员 + 被执行人。"""
    parts, tags = [], []
    _plain(parts, tags, rng.choice(ROLES_ORG))
    _emit(parts, tags, _org(rng, H, C), "ORG")
    _plain(parts, tags, rng.choice(
        ("申请强制执行", "提出执行异议", "不予执行", "责令被执行人履行")))
    n = 1
    for _ in range(rng.randint(1, 3)):
        _plain(parts, tags, rng.choice(("执行法官", "执行员", "书记员")))
        _emit(parts, tags, rng.choice(P), "PER")
        _plain(parts, tags, rng.choice(("，", "及")))
        n += 1
    if rng.random() < 0.7:
        _plain(parts, tags, "被执行人")
        _emit(parts, tags, rng.choice(P), "PER")
        _plain(parts, tags, rng.choice(("的", "名下")))
        n += 1
    return parts, tags, n


def _t_mediation(rng, P, H, C):
    """调解 / 和解：多方当事人 + 代理人。"""
    parts, tags = [], []
    _plain(parts, tags, rng.choice(
        ("经本院主持调解", "经人民调解委员会调解", "双方自行协商一致",
         "在本院主持下达成如下调解协议")))
    n = 0
    for _ in range(rng.randint(2, 4)):
        _plain(parts, tags, rng.choice(ROLES_PERSON))
        if rng.random() < 0.75:
            _emit(parts, tags, rng.choice(P), "PER")
        else:
            _emit(parts, tags, _org(rng, H, C), "ORG")
        _plain(parts, tags, rng.choice(("、", "，", "与")))
        n += 1
    _plain(parts, tags, "自愿达成如下协议：")
    return parts, tags, n


def _t_second_instance(rng, P, H, C):
    """二审：上诉人 + 被上诉人 + 一审法院。"""
    parts, tags = [], []
    _plain(parts, tags, "上诉人")
    if rng.random() < 0.5:
        _emit(parts, tags, rng.choice(P), "PER")
        n = 1
    else:
        _emit(parts, tags, _org(rng, H, C), "ORG")
        n = 1
    _plain(parts, tags, rng.choice(
        ("因与", "因不服", "因合同纠纷与", "因侵权责任与")))
    _plain(parts, tags, rng.choice(("被上诉人", "原审被告", "原审原告")))
    if rng.random() < 0.6:
        _emit(parts, tags, rng.choice(P), "PER")
        n += 1
    else:
        _emit(parts, tags, _org(rng, H, C), "ORG")
        n += 1
    _plain(parts, tags, rng.choice(
        ("买卖合同纠纷", "民间借贷纠纷", "劳动争议", "房屋租赁合同纠纷",
         "侵权责任纠纷", "建设工程施工合同纠纷")) + "一案，不服")
    _emit(parts, tags, _org(rng, H, C), "ORG")
    _plain(parts, tags, rng.choice(("作出的", "于二〇二四年四月二日作出的", "于二〇二三年十二月一日作出的")))
    _plain(parts, tags, rng.choice(("（2024）", "（2023）")) + rng.choice(("民终", "民初", "仲案字")) + f"{rng.randint(10, 9999)}号民事判决，向本院提起上诉。")
    n += 1
    return parts, tags, n


def _t_narrative(rng, P, H, C):
    """长叙述：多段事实陈述，实体随机出现（不固定位置）。"""
    parts, tags = [], []
    n = 0
    for _ in range(rng.randint(10, 28)):
        _plain(parts, tags, rng.choice(FILLER))
        r = rng.random()
        if r < 0.26:
            _plain(parts, tags, rng.choice(ROLES_PERSON))
            _emit(parts, tags, rng.choice(P), "PER")
            n += 1
        elif r < 0.48:
            _plain(parts, tags, rng.choice(ROLES_ORG))
            _emit(parts, tags, _org(rng, H, C), "ORG")
            n += 1
        elif r < 0.60:
            _emit(parts, tags, rng.choice(P), "PER")
            _plain(parts, tags, rng.choice(("与", "和", "及", "同")))
            _emit(parts, tags, rng.choice(P), "PER")
            # 这里的机构名**必须**用 _emit，不能塞进 _plain()。
            #
            # 第一版写成 `_plain(..., "之间就" + _org(...) + "存在争议，") + n += 3`，
            # 于是它自报 3 个、实际只标了 2 个——机构名被打成 O 标签。
            # 后果比「计数错」严重得多：那是**一批主动的负样本**，
            # 教模型「这种位置的机构名不是实体」，越合成越多负样本。
            # 「模板自报数 vs BIO 解析数」的对账就是为抓这个才存在的：
            # 标签错误在下游完全看不出来（句子读得通、格式合法、训练照跑完）。
            _plain(parts, tags, rng.choice(("之间就", "共同与", "一同与")))
            _emit(parts, tags, _org(rng, H, C), "ORG")
            _plain(parts, tags, rng.choice(
                ("签订的合同", "发生的买卖关系", "的房屋租赁合同", "的借款合同"))
                + "存在争议，")
            n += 3
        elif r < 0.70:
            # 连续机构串：卷宗正文里常见（多个被告公司并列）
            for _k in range(rng.randint(2, 4)):
                _emit(parts, tags, _org(rng, H, C), "ORG")
                _plain(parts, tags, rng.choice(("、", "，", "与")))
                n += 1
        else:
            _plain(parts, tags, rng.choice(FILLER))
    return parts, tags, n


def _t_service_list(rng, P, H, C):
    """服务/送达清单：编号 + 人名/机构，密度最高的一种。"""
    parts, tags = [], []
    _plain(parts, tags, rng.choice((
        "诉讼文书送达方式确认如下：", "下列人员已按约定方式收到法律文书：",
        "本案参与诉讼人员名单：", "委托诉讼代理人的联系方式如下：")))
    n = 0
    for i in range(rng.randint(4, 10)):
        _plain(parts, tags, f"{i + 1}.")
        if rng.random() < 0.7:
            _emit(parts, tags, rng.choice(P), "PER")
        else:
            _emit(parts, tags, _org(rng, H, C), "ORG")
        n += 1
        _plain(parts, tags, rng.choice(
            ("（女）", "（男）", "")) + rng.choice(
            ("，送达方式：电子送达", "，已确认收到", "，代理权限：一般授权",
             "，送达地址：当事人住所地", "，联系电话：")))
    return parts, tags, n


def _t_service_receipt(rng, P, H, C):
    """送达回证：受送达人身份信息行。

    这是《人民法院民事裁判文书样式》里的**固定格式**，不是某个评测集的句式：
    「受送达人 XXX，性别，男，民族，汉族，住 XXX，职业 XXX」。
    加它的理由是这类行的**分隔符密度**特别高：人名后面紧跟逗号 + 性别 + 民族，
    人名后面还可能紧跟机构名（住址单位）。只训过「角色词 + 人名」的话，
    模型会把 `汉族` 或紧邻的机构名吃进人名里——这就是边界切错的主要来源。
    """
    parts, tags = [], []
    _plain(parts, tags, rng.choice((
        "送达回证：", "文书送达情况如下：", "受送达人信息：", "电子送达确认单：")))
    n = 0
    for i in range(rng.randint(1, 3)):
        _plain(parts, tags, rng.choice(("受送达人：", "受送达人 ", "收件人：", "")))
        _emit(parts, tags, rng.choice(P), "PER")
        n += 1
        _plain(parts, tags, rng.choice((
            "，性别，", "，性别：")) + rng.choice(("男", "女")))
        _plain(parts, tags, "，民族，"
               + rng.choice(("汉族", "回族", "满族", "蒙古族")))
        _plain(parts, tags, "，住")
        if rng.random() < 0.45:
            _emit(parts, tags, _org(rng, H, C), "ORG")
            n += 1
        else:
            _plain(parts, tags, rng.choice(C) + rng.choice(("区", "县", "市"))
                   + rng.choice(("朝阳路", "解放大道", "人民路", "文化路"))
                   + str(rng.randint(1, 199)) + "号")
        _plain(parts, tags, rng.choice(
            ("，职业：个体经营", "，职业：公司职员", "，职业：无业",
             "，工作单位：", "，联系电话：")))
        # 刻意不插换行：build_vocab 会把 \n/\r 排除出字符表（模型包那次 id 错位
        # 就是 \n 进了词表、save 写成空行、load 静默跳过造成的），这里干脆不给它机会。
        _plain(parts, tags, rng.choice(("；", "。", "，")))
    return parts, tags, n


def _t_agent_detail(rng, P, H, C):
    """委托诉讼代理人 / 当事人身份信息段。"""
    parts, tags = [], []
    _plain(parts, tags, rng.choice((
        "委托诉讼代理人：", "法定代理人：", "原告：", "被告：", "第三人：")))
    _emit(parts, tags, rng.choice(P), "PER")
    n = 1
    _plain(parts, tags, rng.choice((
        "，男，", "，女，", "，男，", "，女，"))
        + "%d年%d月%d日出生" % (rng.randint(1955, 2003),
                                rng.randint(1, 12), rng.randint(1, 28))
        + "，民族，" + rng.choice(("汉族", "回族")))
    _plain(parts, tags, "，住")
    if rng.random() < 0.5:
        _emit(parts, tags, _org(rng, H, C), "ORG")
        n += 1
    else:
        _plain(parts, tags, rng.choice(C) + rng.choice(("区", "县"))
               + rng.choice(("府前街", "中山路", "学院路")) + str(rng.randint(1, 88)) + "号")
    _plain(parts, tags, rng.choice(
        ("，职务：经理", "，职务：负责人", "，职务：主任", "，联系电话：")))
    if rng.random() < 0.5:
        _plain(parts, tags, rng.choice(
            ("，系本案原告的法定代理人。", "，与本案有利害关系。",
             "，系被告的委托诉讼代理人。", "，代理权限：特别授权。")))
    if rng.random() < 0.4:
        _plain(parts, tags, rng.choice(("原告", "被告", "第三人")))
        _emit(parts, tags, rng.choice(P), "PER")
        n += 1
    return parts, tags, n


def _t_case_opening(rng, P, H, C):
    """立案受理 + 当事人列举的段落组合。"""
    parts, tags = [], []
    _plain(parts, tags, "本案于%s年%s月%s日在" % (
        rng.choice(("二〇二四", "二〇二三")),
        rng.choice(("三", "六", "十一")),
        rng.choice(("十一", "十九", "二十八"))))
    _emit(parts, tags, rng.choice(C) + rng.choice(("市", "")) + "人民法院",
          "ORG")
    n = 1
    _plain(parts, tags, rng.choice(("立案受理", "依法立案", "受理登记")))
    _plain(parts, tags, "。原告知被告知：")
    _emit(parts, tags, rng.choice(P), "PER")
    n += 1
    _plain(parts, tags, "，被告")
    _emit(parts, tags, _org(rng, H, C), "ORG")
    n += 1
    _plain(parts, tags, "。")
    _plain(parts, tags, rng.choice((
        "原告与被告之间因买卖合同纠纷发生争议，",
        "申请人与被申请人之间因劳动争议发生争议，",
        "上诉人与被上诉人之间因服务合同纠纷发生争议，")))
    _plain(parts, tags, "本院于%s年%s月%s日公开开庭审理了本案。" % (
        rng.choice(("二〇二四", "二〇二三")), rng.randint(1, 12), rng.randint(1, 28)))
    if rng.random() < 0.6:
        _plain(parts, tags, rng.choice(("审判长", "审判员", "书记员")))
        _emit(parts, tags, rng.choice(P), "PER")
        n += 1
        _plain(parts, tags, "主持本案审理。")
    return parts, tags, n


# 训练模板集合。**默认 9 个是实测最优的那一版**，别随手加。
#
# 实测（M3-3，独立法律评测集 main200_bio.txt，走产品路径 LegalEvalProbe）：
#   v3  9 模板 → 主集 F1 0.5376（PER 0.6788 / ORG 0.1571）
#   v4  9 模板 → 主集 F1 **0.8443**（PER 0.8363 / ORG 0.8660）  ← 当前交付模型
#   v5 12 模板 → 主集 F1 **0.8147**（PER 0.7797 / ORG 0.9072）  ← 更差，不交付
#
# v5 把下面 OPTIONAL_TEMPLATES 的三个也放进来了，ORG 确实涨了 0.041，
# 但 PER 掉了 0.057，整体 F1 反而低 0.030；附集也从 0.6279 掉到 0.5902。
# 也就是说**加法律模板不是单调变好的**：那三个模板句式高度固定
# （送达回证、身份信息段），模型学成「逗号分隔字段里少报人名」，
# 连带把清单式句子里的人名召回也拉低了。
#
# 所以默认集合保持 9 个，三个可选的保留在下面——删掉它们等于把这次负结果也删掉，
# 下一个人会重踩一遍。要再试请同时报主集与附集两个数。
TRAIN_TEMPLATES = (
    _t_party_block, _t_legal_rep, _t_bench, _t_expertise, _t_enforcement,
    _t_mediation, _t_second_instance, _t_narrative, _t_service_list,
)

# 实测让整体变差的那三个。见上面的说明，默认不启用。
OPTIONAL_TEMPLATES = (
    _t_service_receipt, _t_agent_detail, _t_case_opening,
)


def _assert_valid_bio(tags, text):
    """BIO 硬约束校验，非法直接抛错。

    一条非法的 I- 会把金标路径分拉成 -1e4，训练被单条数据带偏，
    而训练照样跑完、指标照样打印——这个项目在 CRF 上已经吃过一次。
    """
    cur = None
    for i, t in enumerate(tags):
        if t in (B_PER, B_ORG):
            cur = "PER" if t == B_PER else "ORG"
        elif t in (I_PER, I_ORG):
            want = "PER" if t == I_PER else "ORG"
            if cur != want:
                raise ValueError(
                    f"第 {i} 字 {text[i]!r} 标签 {t} 非法：当前 {cur}，期望 {want}"
                )
        else:
            cur = None


def _spans_from_tags(tags: list[int]):
    spans = []
    i = 0
    while i < len(tags):
        t = tags[i]
        if t in (B_PER, B_ORG):
            kind = "PER" if t == B_PER else "ORG"
            j = i + 1
            cont = I_PER if kind == "PER" else I_ORG
            while j < len(tags) and tags[j] == cont:
                j += 1
            spans.append((i, j, kind))
            i = j
        else:
            i += 1
    return spans


def synth_legal_sentences(per_pool: list[str], heads: list[str],
                          cities: list[str], rng: random.Random,
                          n_sentences: int):
    """生成 ``n_sentences`` 条法律文书训练句，返回 ``[(text, tags)]``。

    与 ``ner_roster_aug.synth_roster_sentences`` 同一个接口，
    直接在 train_ner.py 里并入训练集即可。
    """
    if not (per_pool and heads and cities):
        raise ValueError("实体池为空：训练语料里没有实体等于没生成任何信号")
    out = []
    order = []
    while len(order) < n_sentences:
        order.extend(TRAIN_TEMPLATES)
    rng.shuffle(order)

    for ti in range(n_sentences):
        fn = order[ti % len(order)]
        parts, tags, nent = fn(rng, per_pool, heads, cities)
        text = "".join(parts)
        if not text or nent <= 0:
            # 一条实体都没有的句子对训练没有贡献，但也不是错误——
            # 空跑会让「合成了 N 条」这个数字虚高，所以直接跳过并由调用方看到真实条数。
            continue
        _assert_valid_bio(tags, text)
        got = len(_spans_from_tags(tags))
        if got != nent:
            # 打印实际值而不是只说「不一致」——第一版只说不一致，
            # 于是只能把 9 个模板逐个跑一遍才知道是哪个。实际值直接指向差多少。
            raise ValueError(
                f"模板 {fn.__name__} 自报 {nent} 个、BIO 解析出 {got} 个"
                f"（差 {got - nent}）：{text[:60]}"
            )
        out.append((text, tags))
    return out


def org_head_pool(org_pool: list[str], rng: random.Random, want: int = 400):
    """从 MSRA 的机构名里抽出「字号」候选。

    做法：取机构名去掉常见行业后缀后的前半段，长度 2~3 字的优先——
    那是「地名 + 字号 + 行业」三段式里的中间那一段。

    抽出来的字号**字全部在词表内**（它们本来就出现在 MSRA train 里），
    所以拼出来的法律行业机构名不会引入 UNK。
    """
    tails = ("有限公司", "有限责任公司", "公司", "集团", "大学", "学院",
             "出版社", "医院", "研究所", "委员会", "银行", "协会", "中心")
    heads: list[str] = []
    seen = set()
    for name in org_pool:
        for t in tails:
            if name.endswith(t) and len(name) > len(t):
                cand = name[: -len(t)]
                # 剥掉前面的地名（2~3 字），剩下的当字号
                for cut in (3, 2):
                    if len(cand) > cut:
                        cand = cand[cut:]
                        break
                if 2 <= len(cand) <= 3 and cand not in seen:
                    seen.add(cand)
                    heads.append(cand)
                break
    rng.shuffle(heads)
    return heads[:want]


def org_city_pool(org_pool: list[str], rng: random.Random, want: int = 200):
    """从 MSRA 的机构名里抽出「地域」候选（2~3 字）。"""
    tails = ("有限公司", "有限责任公司", "公司", "集团", "大学", "学院",
             "出版社", "医院", "研究所", "委员会", "银行", "协会", "中心")
    cities: list[str] = []
    seen = set()
    for name in org_pool:
        for t in tails:
            if name.endswith(t) and len(name) > len(t):
                cand = name[: -len(t)]
                if 2 <= len(cand) <= 3 and cand not in seen:
                    seen.add(cand)
                    cities.append(cand)
                break
    rng.shuffle(cities)
    return cities[:want]
