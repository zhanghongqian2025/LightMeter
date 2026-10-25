"""名单式（roster）语料合成。

## 为什么要有这一段

MSRA test 里有一句 2427 字的长文，是「邓小平同志治丧委员会名单」：
617 个人名 + 12 个机构名连续罗列，实体密度 0.259/字，是其余 3441 句的 16.5 倍。
单独把这句拿出来算，v2 的 F1 只有 0.5664，而 ≤256 字的句子是 0.8498。

已经排除的两个解释（`work/probe_longdoc.py`）：
* **不是长度效应**——同一句内部按 256 字分段，召回在 0.45~0.80 之间震荡，
  开头 [0,256) 是 0.68、结尾 [2048,2304) 是 0.50，没有随位置下降的趋势。
  而卷积感受野只有 1+2*(1+2+4)=15 字，模型本来就不该对"第 300 字"有意见。
* **不是 max_len 截断**——截断只影响句尾，摘掉截断后长文内部召回依然低。

真实原因是**训练集里没有这类文体**：MSRA train 最长 746 字、实体密度 0.0157/字，
全是带谓语和上下文线索的正常句子；而名单里每个名字的上下文是「另一个名字」。

## 为什么这不是「测试集离群点，忽略即可」

脱敏工具最典型的业务输入恰恰就是名单：
卷宗封面的当事人、证人名单、律师与助理名单、送达地址簿、债权人清单。
这一类文本比普通叙述性正文更**必须**脱干净——一个漏掉的名字会直接导致
文书公开时侵犯当事人权益。

所以它是训练覆盖缺口，不是可以剔除的噪声。

## 合成的边界

合成分只在**训练集自己的实体池**上采样，不碰 test，结构上不可能泄漏。
刻意做了几件防止「合成痕迹变成捷径」的事：

* 分隔符多样化（顿号/逗号/空格/换行/编号/制表），不让模型学成
  「顿号之间 2~3 个字就是人名」这种捷径；
* 每个样本都掺入非实体文本（称谓、日期、住址、案号），避免「整句全是实体」；
* 长度、实体个数随机，避免固定模式；
* 括注（女）/（男）/（新华社北京…）这类后缀随机出现，因为真实名单里就有。

生成结果一律过一遍 BIO 硬约束校验（`_assert_valid_bio`），
不合法直接抛错——**宁可训练中止，也不能让一段脏标签静默混进去**。
本项目在 CRF 上已经吃过一次脏标签的亏：一条非法 I- 会把金标路径分
拉成 -inf，训练被单条数据带偏，而训练照样跑完、指标照样打印。
"""
from __future__ import annotations

import random

# 通道编号必须与 train_ner.py 的标签常量一致：
# 0=O 1=B-PER 2=I-PER 3=B-ORG 4=I-ORG
O, B_PER, I_PER, B_ORG, I_ORG = 0, 1, 2, 3, 4

PERSONALITY = ("（女）", "（男）", "")
AGENCY_SUFFIX = ("（新华社北京２月１日电）", "（新华社北京２月１９日电）", "")

# 不含实体的固定片段。刻意挑了法律文书里高频、且不会被误认成实体的词。
FILLERS_NOISE = (
    "，男，", "，女，", "，汉族，", "，住北京市朝阳区", "号。",
    "，委托代理人为", "。", "，已送达。", "，联系电话", "。",
    "，身份证号", "，出生于", "年", "月", "日。",
)
CASE_HEADS = (
    "原告",
    "被告",
    "第三人",
    "委托诉讼代理人",
    "审判长",
    "审判员",
    "书记员",
    "证人",
    "申请执行人",
    "被执行人",
    "债权人",
    "上诉人",
    "被上诉人",
)
ORG_TAILS = ("律师事务所", "律师事务所", "律师事务所", "法律服务所", "司法鉴定所", "公证处")
# 判断「机构名是否已带同类后缀」时要比的词表，比 ORG_TAILS 更宽：
# 它要覆盖 MSRA/cluener 机构名里真实出现过的所有后缀，
# 而不只是我们打算拼上去的那几个。
_ORG_TAIL_SET = (
    "事务所", "法律服务所", "司法鉴定所", "公证处", "基金会", "协会", "学会",
    "研究院", "研究所", "学院", "大学", "公司", "集团", "银行", "医院", "中心",
    "委员会", "政府", "法院", "检察院", "编辑部", "出版社", "俱乐部", "协会",
)
# 末尾**不带**「市」：拼接处会补，词表里就带就会拼出「南京市市」。
PLACE_HEADS = ("北京", "上海", "广州", "深圳", "杭州", "成都", "南京", "武汉")


def _emit(text_parts, tags, value, kind):
    """把一个实体（或普通文本）接进正在拼的句子，返回新增的字符数。"""
    n = len(value)
    if kind == "PER":
        tags.append(B_PER)
        tags.extend([I_PER] * (n - 1))
    elif kind == "ORG":
        tags.append(B_ORG)
        tags.extend([I_ORG] * (n - 1))
    else:
        tags.extend([O] * n)
    text_parts.append(value)
    return n


_PUNCT = "，。、；：\n"


def _emit_plain(text_parts, tags, value):
    """接一段**非实体**文本，并抑制叠标点。

    不能在拼完之后再做「合并重复标点」的清洗：清洗会改变字符串长度，
    而 tags 是按字符逐位记的——一改就对不齐了，而 _emit 只 assert 长度相等，
    一个整体错位两字的语料照样训练得下去、指标照样打印。所以必须在**生成时**决定接什么。

    触发这个坑的实际输出是「，，联系电话」「。。」这类东西。
    它不只是难看：叠标点在中文语料里的联合概率极低，
    模型会把它当成「这段是合成的」的可识别标记，于是学到一套只在合成数据上
    成立的模式——训练指标好看，真实输入上不成立。
    """
    if text_parts and value:
        last = text_parts[-1][-1]
        lead = 0
        while lead < len(value) and value[lead] in _PUNCT and last in _PUNCT:
            lead += 1
            if lead < len(value):
                last = value[lead]
        value = value[lead:]
    if value:
        _emit(text_parts, tags, value, None)
    return value


def _roster_line(pool, kind, rng, n_items):
    """一行名单：分隔符随机，实体随机。（保留给将来做多行版式时复用）"""
    parts, tags = [], []
    sep_pool = ("、", "，", " ", "　", "\t", "")
    for k in range(n_items):
        if k:
            _emit_plain(parts, tags, rng.choice(sep_pool))
        _emit(parts, tags, rng.choice(pool), kind)
        if rng.random() < 0.25:
            _emit_plain(parts, tags, rng.choice(PERSONALITY))
    return parts, tags


def synth_roster_sentences(per_pool, org_pool, rng: random.Random, n_sentences: int):
    """合成 n_sentences 条名单式句子，返回 [(text, tags)]。

    per_pool / org_pool 必须来自**训练集**。这里不做去重也不做质量过滤：
    训练语料里的标注本身就是金标，抽出来的实体天然带正确标签。
    """
    if not per_pool or not org_pool:
        raise ValueError("实体池为空：合成名单需要至少一个 PER 和一个 ORG")
    out = []
    for _ in range(n_sentences):
        parts, tags = [], []

        # 开头：有时带案件抬头 / 地名，有时直接进名单
        if rng.random() < 0.35:
            _emit_plain(parts, tags, rng.choice(CASE_HEADS) + "：")
        if rng.random() < 0.20:
            _emit_plain(parts, tags, rng.choice(PLACE_HEADS) + "市")

        n_items = rng.randint(2, 14)
        # 一部分名单里掺机构（真实名单常常是「人 + 事务所」混排）。
        # org_ratio 是**逐个实体**的概率而不是整句开关，这样名单里机构和人名是交错的，
        # 不会出现「前半句全是人、后半句全是机构」这种模型一学就会的段落结构。
        org_ratio = 0.25 if rng.random() < 0.5 else 0.0
        for k in range(n_items):
            if k:
                # 分隔符里刻意保留空串：真实名单里确有「李铁王宏宇」这样直接相接的写法。
                # 但不能太常见，否则模型学成「连续汉字块 = 多个实体」。
                # 1/6 的出现频率既让模型见过这种形态，又不至于让它成为主线索。
                _emit_plain(parts, tags, rng.choice(("、", "，", " ", "\n", "；", "")))
            if rng.random() < org_ratio:
                org = rng.choice(org_pool)
                _emit(parts, tags, org, "ORG")
                # 只在机构名**本身没有**同类后缀时才补。
                # MSRA 的机构字面量大量已经以「事务所」「基金会」结尾，
                # 无条件拼上去就会造出「天地法律事务所律师事务所」——
                # 模型会据此学到「后缀可以重复」，而真实文本里从不这样写。
                if rng.random() < 0.5 and not any(org.endswith(t) for t in _ORG_TAIL_SET):
                    _emit_plain(parts, tags, rng.choice(ORG_TAILS))
            else:
                _emit(parts, tags, rng.choice(per_pool), "PER")

        # 掺入非实体尾巴，避免「整句全是实体」这种训练不出来的形态
        for _ in range(rng.randint(1, 3)):
            _emit_plain(parts, tags, rng.choice(FILLERS_NOISE))
        if rng.random() < 0.12:
            _emit_plain(parts, tags, rng.choice(AGENCY_SUFFIX))

        text = "".join(parts)
        if len(text) != len(tags):
            raise AssertionError(
                f"合成句子长度与标签数不符：text {len(text)} vs tags {len(tags)}。"
                "这是 _emit 的记账 bug，不是数据问题——文本和标签必须逐字对齐。"
            )
        _assert_valid_bio(tags)
        out.append((text, tags))
    return out


def _assert_valid_bio(tags):
    """BIO 硬约束校验。

    0=O 1=B-PER 2=I-PER 3=B-ORG 4=I-ORG；ALLOWED[from][to] 的语义与
    train_ner.ALLOWED 一致：I- 的前驱只能是同类型的 B- 或 I-。
    """
    prev = O
    for i, t in enumerate(tags):
        if t == I_PER:
            ok = prev in (B_PER, I_PER)
        elif t == I_ORG:
            ok = prev in (B_ORG, I_ORG)
        else:
            ok = True
        if not ok:
            raise AssertionError(
                f"第 {i} 位出现结构非法的 I-（前驱通道 {prev}）：这是脏标签。"
                "\n脏标签会让金标路径在 CRF 里被 -inf 屏蔽，NLL 变成 inf 或 NaN，"
                "而训练循环只会在 loss 非有限时才中止——所以这里必须提前拦。"
            )
        prev = t


def entity_pool(sents, want_org=False):
    """从 [(text, tags)] 里抽出实体字面量，去重后返回。"""
    seen, pool = set(), []
    for _text, tags in sents:
        i = 0
        while i < len(tags):
            t = tags[i]
            if t in (B_PER, B_ORG):
                j = i + 1
                while j < len(tags) and tags[j] == (I_PER if t == B_PER else I_ORG):
                    j += 1
                if (t == B_ORG) == want_org:
                    lit = _text[i:j]
                    if lit not in seen:
                        seen.add(lit)
                        pool.append(lit)
                i = j
            else:
                i += 1
    return pool
