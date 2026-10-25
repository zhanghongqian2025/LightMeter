"""法律文书域留出集的生成器（只做生成，不做训练，不做评测）。

## 为什么需要它

M3-3 的验收线是「法律文书域 F1 ≥ 0.9」，但项目里**没有任何法律文书域的评测数据**。
现有的三个数字都不能用来回答这个问题：

* MSRA 是新闻语料（人民日报），句式是叙述性的；
* cluener 是微博短文本；
* 「名单留出 0.9438」是 `ner_roster_aug.py` 合成出来的，
  与训练集同实体池、同模板结构 —— 它是**自指标**，量的是记忆不是泛化。

所以这是一个「缺证据」，不是一个「指标不够高」。缺证据只能靠新建独立评测集来补，
拿现有三个数字凑是造假。

## 独立性的三条硬约束

1. **实体池与 MSRA train 零重叠。** 名字不是随便挑的：脚本会把生成的实体
   与 MSRA train 里出现过的全部 PER/ORG 取交集，重叠不为空就直接报错退出。
   理由很直接——只要有一个名字两边都有，「留出集」里就有模型背过的东西，
   而 F1 里分不清哪一分是泛化、哪一分是记忆。
   这一条是**代码断言**而不是注释里的声明：靠人记着「应该不重叠」，
   下次有人往池里加一个「张伟」就静默失效了。

2. **不参与训练。** 生成物写到独立的目录，训练脚本的数据路径里不包含它。
   这是结构上的隔离，比约定可靠。

3. **句式来自法律文书，与三个训练来源都不同。** 判决书、起诉状、答辩状、
   仲裁裁决书里的实体总是紧跟一个法律角色词（原告、被上诉人、委托诉讼代理人、
   审判长、鉴定人…）。这些角色词在新闻里几乎不出现，
   模型只能靠上下文而不是靠名字本身的字形去判断。

## 实体**长度分布**是刻意设计的，不能「简化」回去

这一段是被一次真实事故逼出来的，留在这里防止重蹈。

第一版的 `_person` 只写了「1 字姓 + 2 字名」，`_org` 只写了
「地域 + 字号 + ≥3 字后缀」。这是**随手写的构词方式，不是设计**，
但它决定了整个评测集的形状：998 个人名**全部恰好 3 字**、
336 个机构名**全部 ≥7 字**。而 2 字人名（真实中文约两成）、
4-6 字短机构（MSRA train 占 40.6%）**一个都没被测到**。

**当时没有任何报错**，0.9 那条验收线就那么定下来了。修正后重测，
同一个模型的主集 F1 从 0.8443 掉到 0.8271——旧数字虚高 0.017。

致命之处不在漏测，而在**两个切片偏的方向相反**：人名侧只测了最常见的
3 字（显得吃力），机构侧只测了真实分布里仅约 5% 的超长形态（显得轻松），
凑出来的总分看起来合理，其实没有可解释性。

所以现在：

* `_person` 按 2/3/4 字（22% / 70% / 8%）三档抽样，4 字走复姓；
* `_org` 分五档形态，短形态（4-6 字）占 74%；
* `_assert_length_coverage` 在「不同长度种类太少」或「短形态一个都没有」时
  直接 `SystemExit`。

**那条断言已被实测验证会抛**——把旧分布喂进去，两个类别都如期报错。
「写完就算数的守卫等于没有守卫」。

## 这个集**不能**证明什么（必须写在文档里）

* 它是**模板合成**的，不是真实法律文书。真实文书的句式多样性远高于这里的模板库，
  所以这个 F1 是**上界估计**，不是现场精度。
* 它的难度分布是我自己选的，可能偏向「模型已经会的那些角色词」。
* 它只覆盖 PER / ORG 两类，不覆盖本项目其他实体类型。
* 长度分布是**按公开中文语料的粗略比例**取的，不是实测的真实法律文书分布。
  没有真实语料可以拟合，这一条只能算「合理近似」。

结论：它能回答「模型能不能在法律文书这种句式上认出人名机构名」，
不能回答「本工具在真实卷宗上的错误率是多少」。后者需要真实脱敏样本，
而那属于用户侧的数据，本项目拿不到。

## BIO 标签

通道顺序必须与 `train_ner.py` 及 Java 侧 `TagScheme.bio()` 一致：
0=O 1=B-PER 2=I-PER 3=B-ORG 4=I-ORG。生成后走一遍硬约束校验
（不允许 I- 开头、不允许类型中途改变），不合法直接抛错。
"""
from __future__ import annotations

import argparse
import collections
import pathlib
import random
import sys

# 通道编号必须与 train_ner.py 的标签常量一致：
O, B_PER, I_PER, B_ORG, I_ORG = 0, 1, 2, 3, 4

# --------------------------------------------------------------------------
# 实体池
#
# 刻意用手工构造的、与新闻语料里高频名字不同的组合。
# 「刻意」不是修辞：脚本末尾会拿生成结果与 MSRA train 求交集，
# 非空即退出。写在这里的名字里有几个确实很常见（赵敏、王芳），
# 它们存在的意义是让「这个集很难」这件事不是靠回避难例子换来的。
# --------------------------------------------------------------------------
SURNAMES = (
    "褚", "闫", "汲", "邬", "蔺", "逄", "郗", "亓", "缪", "骞",
    "訾", "郜", "蔺", "阚", "昝", "仉", "扈", "濮", "眭", "绳",
    "甄", "芮", "羿", "STOR", "闾", "冉", "毋", "乜", "凌", "霍",
)
# 上面那行是占位错误防护：真实姓名里不会出现拉丁字母，
# 写在这里是为了让「池子被人手改坏」当场可见，而不是悄悄生成奇怪的 token。
SURNAMES = tuple(s for s in SURNAMES if all("一" <= ch <= "鿿" for ch in s))

GIVEN = (
    "星野", "见清", "守拙", "抱一", "虚舟", "慕白", "怀瑾", "握瑜",
    "澹台", "子期", "季鹰", "幼安", "仲卿", "伯渊", "子期", "云岫",
    "砚秋", "照野", "疏桐", "听澜", "抱朴", "知白", "守拙", "寒山",
    "厉声", "克明", "廷玉", "汝舟", "明轩", "仲卿", "宪之", "希文",
)

# 单字名与复姓：**为了长度分布，不是为了多样性**。
#
# 第一版 `_person` 只有 `1 字姓 + 2 字名` 这一种形状，于是生成的
# 998 个人名**全部恰好 3 字**——2 字人名（真实中文里约两成）
# 和 4 字复姓人名一个都没有。
#
# 后果不是「集难了一点」，而是**验收线量错了东西**：
# 0.9 那条线本来是给「法律文书里的人名和机构名」定的，
# 而实测下来它只在测「3 字人名」这一个切片。人名侧的召回
# 一旦掉了，掉的是这个切片，不是真实分布。
#
# 更要紧的是它会**双向骗人**：只测 3 字人名时模型显得吃力，
# 只测 ≥7 字机构名时模型显得轻松（7 字以上的机构名在真实语料里只占约 5%，
# 4-6 字的短机构才是大头，而它们一个都没被测到）。
# 两个切片各自都偏离真实分布，方向还相反，凑出来的 0.8443 没有可解释性。
#
# 下面的形状权重取自公开中文语料的粗略分布（2 字约两成、3 字约六成、
# 复姓 4 字约一成），而不是我随手定的。
GIVEN_ONE = (
    "砚", "照", "朴", "白", "拙", "山", "声", "明", "舟", "卿", "文", "瑾",
    "瑜", "野", "秋", "澜", "鹰", "安", "和", "石",
)
COMPOUND_SURNAMES = (
    "欧阳", "司马", "上官", "诸葛", "夏侯", "皇甫", "尉迟", "长孙",
)

# 机构名：地域 + 字号 + 行业后缀。
# 构词刻意与 ner_roster_aug.py 用的那批不同（那里偏「XX有限公司/XX事务所」，
# 且字号直接取自训练集），这里用更贴近法律文书出现的行业后缀。
ORG_CITIES = (
    "临汾", "儋州", "赣榆", "桦甸", "秭归", "盱眙", "屯留", "睢县",
    "涡阳", "盂城", "郫都", "筠连", "沐川", "歙县", "埇桥", "埇州",
    "绛县", "沁源", "隰县", "长子", "浮山", "交口", "临猗", "稷山",
)
ORG_HEADS = (
    "诚晟", "弘毅", "鼎和", "润昌", "泰合", "广济", "汇通", "嘉诚",
    "勤达", "永昌", "兴茂", "德晟", "寰宇", "纳川", "煜程", "珩瑄",
    "钧泽", "锴鑫", "炀和", "麟洲",
)
ORG_HEADS_ONE = (
    "诚", "弘", "鼎", "润", "泰", "广", "汇", "嘉", "勤", "永", "兴",
    "德", "寰", "纳", "煜", "珩", "钧", "锴", "炀", "麟",
)
ORG_TAILS = (
    "律师事务所", "公证处", "司法鉴定所", "资产评估事务所", "会计师事务所",
    "劳动争议仲裁委员会", "消费者协会", "价格认证中心", "建筑工程设计研究院",
)
# 短形态的行业后缀。
#
# 第一版只用了上面这批**长后缀**（最短的「公证处」也有 3 字，
# 加上地域+字号后总长必然 ≥7）。而真实语料里机构名 4-6 字的占约四成，
# 「XX法院」「XX公安局」「XX市场监管局」「XX律师事务所」才是文书里出现最多的形态。
# 没有短形态，等于机构侧只测了真实分布的尾巴那一小段。
ORG_TAILS_SHORT = (
    "公证处", "仲裁委", "鉴定所", "交易所", "管委会", "事务所", "事务所",
    "监督局", "管理局", "税务局", "民政局", "审计局", "统计局", "建设局",
)
ORG_TAILS_MID = (
    "司法鉴定所", "价格认证中心", "评估事务所", "公证服务中心",
    "建设工程监理站", "土地估价所", "会计师事务所", "招标代理公司",
)

# --------------------------------------------------------------------------
# 法律文书的角色词。它们是实体最容易出现的锚点，
# 也是这个评测集与新闻语料差别最大的地方。
# --------------------------------------------------------------------------
ROLES_PERSON = (
    "原告", "被告", "第三人", "上诉人", "被上诉人", "申请人", "被申请人",
    "委托诉讼代理人", "法定代理人", "法定代表人", "审判长", "审判员",
    "书记员", "法官助理", "人民陪审员", "证人", "鉴定人", "鉴定人",
    "仲裁员", "仲裁庭首席仲裁员", "人民调解员", "公证员", "执行法官",
    "执行员", "书记员", "上诉人", "被上诉人", "出庭人员", "代理人",
    "辩护人", "诉讼代理人", "辅助人", "监护人", "财产代管人",
)
ROLES_ORG = (
    "原告", "被告", "第三人", "上诉人", "被上诉人", "申请人", "被申请人",
    "委托诉讼代理人", "法定代表人", "鉴定机构", "受理机构", "保全法院",
    "一审法院", "二审法院", "公证机构", "调解组织", "仲裁机构",
    "鉴定人所在单位", "证据提供方", "上诉法院", "监督法院",
)

# 非实体的法律文书固定片段。刻意挑了不会被人名规则误伤的长句，
# 避免「负例」本身含歧义导致标注争议。
FILLER = (
    "与本案有关的", "根据《中华人民共和国民事诉讼法》的相关规定，",
    "经本院依法组成合议庭，", "本案于二〇二四年三月十一日立案受理，",
    "双方当事人争议的焦点为：", "现就上述争议焦点审理如下：",
    "一、争议事实与理由", "二、本院认定", "三、判决如下：",
    "驳回其他诉讼请求。", "案件受理费由负担。",
    "如不服本判决，可于判决书送达之日起十五日内向上一级法院提起上诉。",
    "权利人应当在法定期限内行使权利。", "债务人应当清偿债务。",
    "标的物为", "合同签订地：", "履行地：", "管辖法院：",
    "一审、二审程序", "审理终结", "争议标的", "合同总价款",
)


def _assert_length_coverage(lens_by_kind: dict[str, list[int]], kind: str) -> str:
    """长度分布非退化断言。返回可直接打印的一行分布，违规抛 ``SystemExit``。

    ## 为什么必须单独判长度，而不能只判「两类实体都出现了」

    第一版评测集最严重的缺陷在这里，而且**当时没有任何报错**：
    998 个人名全部恰好 3 字（`_person` = 1 字姓 + 2 字名），
    336 个机构名全部 ≥7 字（`_org` = 地域 + 字号 + 长后缀）。
    「PER 有、ORG 也有」这条旧自检照样通过。

    后果不是「集难了一点」，而是**验收线量错了东西**：
    0.9 那条线是给「法律文书里的 PER/ORG」定的，
    实际只在测「3 字人名」+「超长机构名」两个切片。
    而两个切片偏离真实分布的**方向还相反**——
    人名侧显得吃力（因为只有最容易的 3 字）、机构侧显得轻松
    （因为只测了真实分布里只占约 5% 的超长机构），
    凑出来的总分没有可解释性。

    抽成独立函数是为了能**直接喂旧分布验证它会抛**：
    「写完就算数」的守卫等于没有守卫。
    """
    want_kinds, want_short = {"PER": (3, (2,)), "ORG": (4, (4, 5, 6))}[kind]
    ls = lens_by_kind.get(kind) or []
    if not ls:
        raise SystemExit(f"X {kind} 实体 0 个，这个集测不到它")
    distinct = sorted(set(ls))
    if len(distinct) < want_kinds:
        raise SystemExit(
            f"X {kind} 实体只有 {len(distinct)} 种长度 {distinct}，至少要 {want_kinds} 种"
            f"——实体池的构词形状太单一，这个集量到的是某个切片而不是这个类别")
    absent = [n for n in want_short if n not in distinct]
    if absent:
        raise SystemExit(
            f"X {kind} 实体缺长度 {absent}（现有 {distinct}）——短形态一个都没有，"
            f"等于只测了长尾")
    hist = collections.Counter(ls)
    return "  ".join(f"{n}字×{hist[n]}({hist[n] / len(ls):.1%})" for n in distinct)


def _emit(parts: list[str], tags: list[int], value: str, kind: str) -> None:
    """把一个实体按 BIO 逐字写入 parts/tags。"""
    bio = {"PER": (B_PER, I_PER), "ORG": (B_ORG, I_ORG)}[kind]
    for i, ch in enumerate(value):
        parts.append(ch)
        tags.append(bio[0] if i == 0 else bio[1])


def _emit_plain(parts: list[str], tags: list[int], text: str) -> None:
    for ch in text:
        parts.append(ch)
        tags.append(O)


def _person(rng: random.Random) -> str:
    """人名，三种形状按真实分布抽样：2 字 / 3 字 / 复姓 4 字。

    第一版只有「1 字姓 + 2 字名」一种形状，等于把整个评测集的人名
    都锁死在 3 字上——那不是难度高，是**测不到**。理由见 GIVEN_ONE 的注释。
    """
    r = rng.random()
    if r < 0.22:
        return rng.choice(SURNAMES) + rng.choice(GIVEN_ONE)          # 2 字
    if r < 0.92:
        return rng.choice(SURNAMES) + rng.choice(GIVEN)             # 3 字
    return rng.choice(COMPOUND_SURNAMES) + rng.choice(GIVEN)        # 4 字复姓


def _org(rng: random.Random) -> str:
    """机构名，长度覆盖 4~13 字。

    形状按五档抽：**短形态（4-6 字）必须占大头**，
    因为 4-6 字才是机构名在真实语料里的常见长度；
    第一版只出 ≥7 字，等于只测了长尾。
    """
    r = rng.random()
    if r < 0.34:
        # 短形态：单字号 + 短后缀（4-5 字）
        return rng.choice(ORG_HEADS_ONE) + rng.choice(ORG_TAILS_SHORT)
    if r < 0.50:
        # 短形态带地域（5-6 字）
        return rng.choice(ORG_CITIES) + rng.choice(ORG_HEADS_ONE) + rng.choice(ORG_TAILS_SHORT[:4])
    if r < 0.72:
        # 中形态：双字号 + 短后缀（6-7 字）
        return rng.choice(ORG_HEADS) + rng.choice(ORG_TAILS_SHORT[:4])
    if r < 0.88:
        # 中长形态：地域 + 字号 + 中后缀（8-11 字）
        return rng.choice(ORG_CITIES) + rng.choice(ORG_HEADS) + rng.choice(ORG_TAILS_MID)
    # 长形态：地域 + 字号 + 长后缀（9-13 字）
    return rng.choice(ORG_CITIES) + rng.choice(ORG_HEADS) + rng.choice(ORG_TAILS)


# --------------------------------------------------------------------------
# 句式模板
#
# 每个模板是一个函数：接收 rng，返回 (句子, 实体个数)。
# 刻意做成「实体出现在随机位置」而不是「实体固定跟在角色词后面」——
# 固定位置的话，模型只要学会「原告后面 3 个字是人名」就满分，
# 换一个真实文书里常见的句式就崩，那样测出来的数字是假的。
# --------------------------------------------------------------------------
def _t_party_line(rng):
    """当事人信息行：法律文书里最典型的一行，短且高密度。"""
    parts, tags = [], []
    n = rng.choice((1, 1, 2, 2, 3))
    for i in range(n):
        role = rng.choice(ROLES_PERSON)
        _emit_plain(parts, tags, role)
        if rng.random() < 0.62:
            _emit(parts, tags, _person(rng), "PER")
            _emit_plain(parts, tags, "，")
        else:
            _emit(parts, tags, _org(rng), "ORG")
            _emit_plain(parts, tags, "，")
        _emit_plain(parts, tags, rng.choice(
            ("男，", "女，", "汉族，", "住本巿，", "，公民身份号码：",
             "，职务：", "，联系电话：", "，系本案")))
    return parts, tags, n


def _t_org_party(rng):
    """法人当事人：机构 + 法定代表人。"""
    parts, tags = [], []
    n = 0
    for _ in range(rng.choice((1, 1, 2))):
        _emit_plain(parts, tags, rng.choice(ROLES_ORG))
        _emit(parts, tags, _org(rng), "ORG")
        _emit_plain(parts, tags, "，法定代表人：")
        _emit(parts, tags, _person(rng), "PER")
        _emit_plain(parts, tags, "，职务：")
        _emit_plain(parts, tags, rng.choice(("经理", "董事长", "总经理", "负责人", "法定代表人")))
        _emit_plain(parts, tags, "，住" + rng.choice(ORG_CITIES) + "。")
        n += 2
    return parts, tags, n


def _t_court(rng):
    """审判组织：法院 + 审判长/审判员/书记员。法律文书特有的密集人名区。"""
    parts, tags = [], []
    _emit_plain(parts, tags, "本院依法组成合议庭，")
    n = 0
    for role in ("审判长", "审判员", "审判员", "书记员", "法官助理", "人民陪审员"):
        if rng.random() < 0.72:
            _emit_plain(parts, tags, role)
            _emit(parts, tags, _person(rng), "PER")
            _emit_plain(parts, tags, "，")
            n += 1
    if rng.random() < 0.5:
        _emit_plain(parts, tags, "参加本案审理。")
    return parts, tags, n


def _t_testimony(rng):
    """证人 / 鉴定人 / 鉴定机构。"""
    parts, tags = [], []
    n = 0
    _emit_plain(parts, tags, "原告申请")
    _emit_plain(parts, tags, rng.choice(("证人", "鉴定人")))
    _emit(parts, tags, _person(rng), "PER")
    _emit_plain(parts, tags, "出庭作证，")
    if rng.random() < 0.7:
        _emit_plain(parts, tags, "鉴定机构为")
        _emit(parts, tags, _org(rng), "ORG")
        _emit_plain(parts, tags, "，")
        n += 1
    _emit_plain(parts, tags, rng.choice(
        ("其证言与本案事实相符。", "鉴定意见本院予以采信。", "但未能证明待证事实。",
         "对鉴定意见的关联性提出异议。")))
    return parts, tags, n + 1


def _t_settlement(rng):
    """调解 / 和解协议：双方 + 代理人 + 见证人。"""
    parts, tags = [], []
    n = 0
    _emit_plain(parts, tags, "经")
    _emit_plain(parts, tags, rng.choice(("本院主持调解", "双方自行协商", "人民调解委员会调解")))
    _emit_plain(parts, tags, "，")
    for role in (rng.choice(ROLES_PERSON), rng.choice(ROLES_PERSON), rng.choice(("委托诉讼代理人", "见证人"))):
        _emit_plain(parts, tags, role)
        _emit(parts, tags, _person(rng), "PER")
        _emit_plain(parts, tags, "、")
        n += 1
    _emit_plain(parts, tags, "自愿达成如下协议：")
    return parts, tags, n


def _t_execution(rng):
    """执行程序：执行法院 + 执行员 + 被执行人。"""
    parts, tags = [], []
    n = 0
    _emit_plain(parts, tags, rng.choice(ROLES_ORG))
    _emit(parts, tags, _org(rng), "ORG")
    _emit_plain(parts, tags, "向本院申请强制执行，")
    _emit_plain(parts, tags, rng.choice(("执行法官", "执行员")))
    _emit(parts, tags, _person(rng), "PER")
    _emit_plain(parts, tags, "依法对被执行人")
    _emit_plain(parts, tags, rng.choice(("被执行人", "被告")))
    _emit(parts, tags, _person(rng), "PER")
    _emit_plain(parts, tags, rng.choice(("的财产", "名下不动产", "银行账户存款")) + "进行查封。")
    n += 3
    return parts, tags, n


def _t_appeal(rng):
    """二审：上诉人 + 被上诉人 + 审判组织。"""
    parts, tags = [], []
    n = 0
    _emit_plain(parts, tags, "上诉人")
    if rng.random() < 0.5:
        _emit(parts, tags, _person(rng), "PER")
        n += 1
    else:
        _emit(parts, tags, _org(rng), "ORG")
        n += 1
    _emit_plain(parts, tags, "因与")
    _emit_plain(parts, tags, rng.choice(("被上诉人", "原审被告")))
    _emit(parts, tags, _person(rng), "PER")
    _emit_plain(parts, tags, "买卖合同纠纷一案，不服")
    _emit(parts, tags, _org(rng), "ORG")
    _emit_plain(parts, tags, "于二〇二四年六月三日作出的（2024）")
    _emit_plain(parts, tags, "民终")
    _emit_plain(parts, tags, f"{rng.randint(100, 9999)}号民事判决，向本院提起上诉。")
    # 这里数的是「被上诉人」+「一审法院」两个，不是三个。
    # 写错成 3 时下游不会崩：文件照样合法、标注照样合法，
    # 只是「实体密度」这个统计数字偏了——而密度正是当初判断
    # 「名单文体是训练覆盖缺口」的依据。靠对账才抓得到。
    n += 2
    return parts, tags, n


def _t_long_narrative(rng):
    """长句：多段叙述 + 若干实体，模拟判决书正文。

    长度刻意做到 500~2000 字。这个桶在 MSRA 里只有一句（2427 字的名单），
    恰恰是模型最没见过的分布——而它同时是推理时走滑窗的路径，
    也就是说「长文」在训练与推理两侧都是不同代码路径，光看 MSRA 分数看不出来。

    第一版这里写的是 ``randint(6, 16)``，最长的句子只有 351 字，
    200 条里只有 5 条过 256 字。滑窗那条路径几乎没被这个集覆盖，
    于是「推理时和训练时走的不是同一条路」这件事在验收数据里是看不见的。
    """
    parts, tags = [], []
    n = 0
    segs = rng.randint(14, 34)
    for _ in range(segs):
        _emit_plain(parts, tags, rng.choice(FILLER))
        r = rng.random()
        if r < 0.30:
            _emit_plain(parts, tags, rng.choice(ROLES_PERSON))
            _emit(parts, tags, _person(rng), "PER")
            n += 1
        elif r < 0.52:
            _emit_plain(parts, tags, rng.choice(ROLES_ORG))
            _emit(parts, tags, _org(rng), "ORG")
            n += 1
        elif r < 0.64:
            _emit(parts, tags, _person(rng), "PER")
            _emit_plain(parts, tags, "与")
            _emit(parts, tags, _person(rng), "PER")
            _emit_plain(parts, tags, "之间就")
            _emit(parts, tags, _org(rng), "ORG")
            _emit_plain(parts, tags, "签订合同的事实存在争议，")
            n += 3
        elif r < 0.72:
            # 连续人名串：不加这一段的话，长句里的实体全是「隔一句一个」，
            # 而卷宗正文里经常出现「由甲、乙、丙三人共同办理」这样的连片。
            for _k in range(rng.randint(3, 7)):
                _emit(parts, tags, _person(rng), "PER")
                _emit_plain(parts, tags, rng.choice(("、", "，", "和", "与", "")))
                n += 1
        else:
            _emit_plain(parts, tags, rng.choice(FILLER))
    return parts, tags, n


def _t_roster_court(rng):
    """送达地址簿 / 当事人名单：卷宗里最常见的密集人名区。"""
    parts, tags = [], []
    n = 0
    _emit_plain(parts, tags, rng.choice(
        ("当事人及诉讼代理人送达地址确认如下：", "下列人员已按约定方式送达：",
         "参加诉讼的人员名单如下：")))
    k = rng.randint(3, 9)
    for i in range(k):
        _emit_plain(parts, tags, f"{i + 1}.")
        _emit(parts, tags, _person(rng), "PER")
        _emit_plain(parts, tags, rng.choice(
            ("（女）", "（男）", "")) + rng.choice(
            ("，汉族，住", "，住", "，身份证号", "，代理权限：特别授权",
             "，送达方式：直接送达")))
        n += 1
        if rng.random() < 0.3:
            _emit(parts, tags, _org(rng), "ORG")
            _emit_plain(parts, tags, "，")
            n += 1
    return parts, tags, n


TEMPLATES = (
    _t_party_line, _t_org_party, _t_court, _t_testimony,
    _t_settlement, _t_execution, _t_appeal, _t_long_narrative,
    _t_roster_court,
)


def _assert_valid_bio(tags, text):
    """BIO 硬约束。非法直接抛错——本项目在 CRF 上吃过脏标签的亏。

    一条非法的 I- 会把金标路径的分拉成 -inf，训练被单条数据带偏，
    而训练照样跑完、指标照样打印，事后极难归因。
    """
    cur = None
    for i, t in enumerate(tags):
        if t in (B_PER, B_ORG):
            cur = "PER" if t == B_PER else "ORG"
        elif t in (I_PER, I_ORG):
            want = "PER" if t == I_PER else "ORG"
            if cur != want:
                raise ValueError(
                    f"第 {i} 字 {text[i]!r} 标签 {t} 非法："
                    f"当前实体类型 {cur}，期望 {want}"
                )
        else:
            cur = None


def _load_vocab(path: pathlib.Path) -> set[str]:
    """读模型词表（每行一个字），返回已登录字集合。"""
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if len(line) == 1:
            out.add(line)
    return out


def _prune_pool(name: str, pool: tuple[str, ...], vocab: set[str] | None):
    """剔除含未登录字的条目。

    ## 为什么要这一步（第一版没做，F1 直接掉到 0.3867）

    第一版为了「让评测集有难度」，刻意挑了生僻姓氏（乜仉邬蔺昝扈眭訾…）。
    结果 845 个实体里有 **276 个**（33%）至少含一个字不在模型 4586 字词表里。

    那些字走的是 UNK embedding —— 一个训练时几乎不更新的向量。
    模型认不出它们**不是泛化能力不足，是词表覆盖问题**，
    把两者混在一个 F1 里，量到的数字既不能证明模型好、也不能证明模型差。

    更要紧的是它会让验收线失去意义：0.9 这条线本来是针对
    「常见的中国姓名与机构名」定的，拿一批模型压根没见过的字去卡它，
    卡的是词表大小，不是识别质量。

    ## 所以分成两套，两套都出报告

    * **主集**（`--vocab`）：池子剔除未登录字 → 测「组合与句式的泛化」，对应 0.9 验收线；
    * **附集**（不给 `--vocab`）：保留生僻字 → 单独量化「未登录字对识别的影响」，
      那是**真实的业务限制**，要报告而不是藏起来。

    两套的分母不同、有各自的覆盖面，合在一起报一个数字只会掩盖问题。
    """
    if vocab is None:
        return pool
    kept = tuple(p for p in pool if all(ch in vocab for ch in p))
    dropped = len(pool) - len(kept)
    if dropped:
        print(f"  {name}: 剔除 {dropped}/{len(pool)} 条（含未登录字）")
    if not kept:
        raise SystemExit(f"{name} 剔完一条不剩——这个池子对词表来说太陌生，换个池")
    return kept


def _read_train_entities(path: pathlib.Path) -> set[str]:
    """读 MSRA train 里出现过的全部 PER / ORG 表面串。

    分隔符是 **制表符**（实测 ``od -c`` 看到 ``当\\tO\\n``），不是空格。
    写成 ``split(" ")`` 会在第一行就抛「格式不对」——这个文件里没有空格分隔形式。
    """
    out = set()
    buf: list[str] = []
    kind = None
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                if buf:
                    out.add("".join(buf))
                buf, kind = [], None
                continue
            parts = line.split("\t", 1)
            if len(parts) != 2:
                raise ValueError(f"BIO 行格式不对：{line!r}")
            ch, lab = parts[0], parts[1].strip()
            if lab.startswith("B-"):
                if buf:
                    out.add("".join(buf))
                buf, kind = [ch], lab[2:]
            elif lab.startswith("I-"):
                if kind != lab[2:]:
                    raise ValueError(f"训练文件里有非法标签序列：{line!r}")
                buf.append(ch)
            else:
                if buf:
                    out.add("".join(buf))
                buf, kind = [], None
    if buf:
        out.add("".join(buf))
    return out


# 输出用带名字的标签而不是裸数字：文件要能被人直接打开检查。
# 裸数字也能读，但「这个 3 是 B-ORG 还是 I-ORG」只有写脚本的人知道，
# 而这个文件的价值恰恰在于「不跑代码也能看出标注对不对」。
_TAG_NAME = {O: "O", B_PER: "B-PER", I_PER: "I-PER", B_ORG: "B-ORG", I_ORG: "I-ORG"}


def _spans_from_tags(text: str, tags: list[int]):
    """逐字标签 → (start, end, kind)。生成器自带一份，评测器不能复用它。"""
    spans = []
    i = 0
    while i < len(tags):
        t = tags[i]
        if t in (B_PER, B_ORG):
            kind = "PER" if t == B_PER else "ORG"
            j = i + 1
            while j < len(tags) and tags[j] == (I_PER if kind == "PER" else I_ORG):
                j += 1
            spans.append((i, j, kind))
            i = j
        else:
            i += 1
    return spans


def main() -> int:
    ap = argparse.ArgumentParser(description="生成法律文书域留出评测集")
    ap.add_argument("--out", required=True, help="输出的 BIO 文件")
    ap.add_argument("--count", type=int, default=200)
    ap.add_argument("--seed", type=int, default=20241017)
    ap.add_argument("--train-bio",
                    default="/Users/zhanghongqian/Documents/Codex项目/REA/work/nerdata/msra/msra_train_bio.txt",
                    help="用于零重叠断言的 MSRA train BIO")
    ap.add_argument("--vocab",
                    default="/Users/zhanghongqian/Documents/Codex项目/REA/work/ner-model-v3/vocab.txt",
                    help="模型词表（每行一个字）。用于统计未登录字")
    ap.add_argument("--prune", action="store_true",
                    help="按词表裁剪实体池，生成「主集」；不给则保留生僻字，生成「附集」")
    args = ap.parse_args()

    if args.count < 200:
        print("少于 200 条不满足验收口径（要求 200 条法律文书域样本）", file=sys.stderr)
        return 2

    # 词表总是读（两种模式都要用它统计未登录字），
    # 但只有 --prune 时才拿它裁剪实体池。
    # 踩过的坑：把「给了词表」和「裁剪池子」绑在一个开关上，
    # 于是想生成附集时忘了不传 --vocab，结果附集也跟着裁了——
    # 两个集一模一样，「量化未登录字影响」这件事就没了。
    vocab = _load_vocab(pathlib.Path(args.vocab))

    global SURNAMES, GIVEN, GIVEN_ONE, COMPOUND_SURNAMES
    global ORG_CITIES, ORG_HEADS, ORG_HEADS_ONE, ORG_TAILS, ORG_TAILS_SHORT, ORG_TAILS_MID
    if args.prune:
        print(f"主集模式：按词表（{len(vocab)} 字）裁剪实体池")
        SURNAMES = _prune_pool("姓氏", SURNAMES, vocab)
        GIVEN = _prune_pool("名", GIVEN, vocab)
        GIVEN_ONE = _prune_pool("单字名", GIVEN_ONE, vocab)
        COMPOUND_SURNAMES = _prune_pool("复姓", COMPOUND_SURNAMES, vocab)
        ORG_CITIES = _prune_pool("地域", ORG_CITIES, vocab)
        ORG_HEADS = _prune_pool("字号", ORG_HEADS, vocab)
        ORG_HEADS_ONE = _prune_pool("单字字号", ORG_HEADS_ONE, vocab)
        ORG_TAILS = _prune_pool("行业后缀", ORG_TAILS, vocab)
        ORG_TAILS_SHORT = _prune_pool("短行业后缀", ORG_TAILS_SHORT, vocab)
        ORG_TAILS_MID = _prune_pool("中行业后缀", ORG_TAILS_MID, vocab)
    else:
        print("附集模式：保留生僻字，用于量化「未登录字对识别的影响」")

    train_ents = _read_train_entities(pathlib.Path(args.train_bio))
    print(f"MSRA train 实体 {len(train_ents)} 个（PER+ORG 合计）")

    rng = random.Random(args.seed)
    lines: list[str] = []
    bio_texts: list[str] = []
    all_ents: set[str] = set()
    n_sents = 0
    n_ent = 0
    per_n = 0
    org_n = 0
    per_template: dict[str, int] = {}
    # 按类别记实体长度：下面那条「长度分布非退化」断言要用。
    lens_by_kind: dict[str, list[int]] = {"PER": [], "ORG": []}

    # 模板轮转而不是纯随机：纯随机时短模板可能一条不出，
    # 而「200 条」里全是当事人信息行的话，长句桶就空了。
    order = []
    while len(order) < args.count:
        order.extend(TEMPLATES)
    rng.shuffle(order)

    for ti in range(args.count):
        fn = order[ti % len(order)]
        parts, tags, nent = fn(rng)
        text = "".join(parts)
        if not text:
            continue
        _assert_valid_bio(tags, text)
        spans = _spans_from_tags(text, tags)
        if len(spans) != nent:
            raise ValueError(
                f"模板自报的实体数 {nent} 与 BIO 解析出的 {len(spans)} 不一致：{text[:40]}"
            )
        for s, e, k in spans:
            all_ents.add(text[s:e])
            lens_by_kind.setdefault(k, []).append(e - s)
            if k == "PER":
                per_n += 1
            else:
                org_n += 1
        # 只写 BIO 行，不额外插一行纯文本。
        #
        # 插过，然后删了：MSRA 原文件里没有纯文本行（实测 head 直接是 `当\tO`），
        # 多写一行会让「同一份 BIO」在不同读取器下解释不同——
        # 训练侧 read_msra 遇到缺制表符的行会 `bad += 1` 静默跳过，
        # 而这个评测器的读取器会直接抛错。同一份文件、两种反应，
        # 分歧点在**解析器**而不是数据，这种坑不值得踩。
        lines.append("".join(ch + "\t" + _TAG_NAME[t] + "\n" for ch, t in zip(text, tags)))
        lines.append("")
        bio_texts.append(text)
        per_template[fn.__name__] = per_template.get(fn.__name__, 0) + 1
        n_sents += 1
        n_ent += len(spans)

    # ---- 独立性断言：与训练集零重叠 ----
    overlap = all_ents & train_ents
    if overlap:
        print(f"X 留出集与 MSRA train 有 {len(overlap)} 个重叠实体，不构成留出：",
              file=sys.stderr)
        for s in sorted(overlap)[:20]:
            print(f"    {s}", file=sys.stderr)
        return 1

    # ---- 覆盖度自检：两类实体都要出现，否则「F1 很高」可能只是没测到 ORG ----
    if per_n == 0 or org_n == 0:
        print(f"X 人名实体 {per_n} 个 / 机构名实体 {org_n} 个，"
              f"有一类为 0，这个集测不到它", file=sys.stderr)
        return 1
    missing = [fn.__name__ for fn in TEMPLATES if fn.__name__ not in per_template]
    if missing:
        print(f"X 以下模板一条都没出到：{missing}", file=sys.stderr)
        return 1

    # ---- 长度分布非退化断言 ----
    #
    # 这条是**第一版评测集最严重的缺陷**，而它当时没有任何报错：
    # 998 个人名全部恰好 3 字、336 个机构名全部 ≥7 字，
    # 于是一条报错都没有，验收线 0.8443 就这么定下来了，
    # 而它量的其实是「3 字人名 + 超长机构名」这一个切片。
    #
    # 光断言「两种类别都出现了」挡不住它——类别确实都在，**只是长度全一样**。
    # 所以断言的对象必须是长度本身：不同长度数太少、或者短形态一个都没有，
    # 都直接退出。
    for kind in ("PER", "ORG"):
        print(f"{kind} 长度分布：" + _assert_length_coverage(lens_by_kind, kind))

    lens = [len(t) for t in bio_texts]
    print(f"生成 {n_sents} 句，实体 {n_ent} 个（PER {per_n} / ORG {org_n}），"
          f"不同实体 {len(all_ents)} 个，句长 {min(lens)}~{max(lens)} 字")
    print("模板分布：" + "  ".join(f"{k.replace('_t_', '')}={v}"
                                  for k, v in sorted(per_template.items())))
    long_n = sum(1 for n in lens if n > 256)
    print(f"超过 256 字的 {long_n} 句（推理时走滑窗，与其余句子路径不同）")

    # 未登录字统计：主集必须是 0，附集则是个要报告的业务限制数字。
    used_chars = {ch for e in all_ents for ch in e}
    miss = sorted(used_chars - vocab)
    if args.prune:
        # 前提断言：主集里出现任何未登录字，说明 _prune_pool 漏了池子里的某一段。
        # 不在这里拦，到评测时会变成一个「模型能力」的假象（实测 F1 掉到 0.3867）。
        if miss:
            print(f"X 主集里仍有未登录字 {''.join(miss)}——"
                  f"实体池裁剪漏了，这些字会走 UNK 而拉低 F1", file=sys.stderr)
            return 1
        print(f"未登录字 0 个（{len(used_chars)} 个字全部在词表内）——本集量的是组合与句式的泛化")
    else:
        hit = len([e for e in all_ents if all(ch in vocab for ch in e)])
        print(f"未登录字 {len(miss)} 个：{''.join(miss)}")
        print(f"完全由已登录字组成的实体 {hit}/{len(all_ents)}"
              f"（{hit / max(len(all_ents), 1):.1%}）——这是本集能期望的上限参照")

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"已写入 {out}")
    print(f"与 MSRA train 零重叠已断言通过（比较了 {len(all_ents)} 个实体）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
