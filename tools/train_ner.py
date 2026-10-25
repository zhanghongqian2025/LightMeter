#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""训练字符级 IDCNN + CRF 中文 NER，导出单文件自包含 ONNX。

与 Java 侧的硬契约（改任何一处都必须同步另一处，否则症状是「模型能跑但结果错」）：

  * 通道顺序 = ``com.litemask.ner.TagScheme.bio()``
    ``0=O  1=B-PER  2=I-PER  3=B-ORG  4=I-ORG``
  * BIO 结构约束 = ``TagScheme.allowsByBIO`` / ``TagScheme.allowsStart``，
    本脚本里的 ``ALLOWED`` / ``ALLOW_START`` 必须与它逐条对应
  * **不训练 start/end 转移**。``SequenceDecoder`` 不读它们，
    多训这两项只会让训练口径与推理口径不一致，而这种不一致不会报错，
    只会表现为推理 F1 比训练 F1 低一截且查不出原因。
  * 非法转移先屏蔽（-1e4）再学，使导出的 ``transition`` 与 ``TagScheme``
    的硬约束**幂等**：Java 再叠一层约束不会改变结果。
  * 导出为**单文件自包含** ONNX（``OnnxSelfContained`` 会扫它），
    两个输出：``emission [1,T,C]`` 与 ``transition [C,C]``。
    转移矩阵放进 ONNX 而不是旁边再放一个文件，是为了杜绝
    「换了模型忘了换转移矩阵」——那种错的现场是全部实体类型判错、报告却正常。

用法::

    python tools/train_ner.py --out ../work/ner-model          # 完整训练
    python tools/train_ner.py --smoke --out /tmp/ner-smoke     # 冒烟（几秒，只验证通路）
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import random
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

# ---------------------------------------------------------------------------
# 标签方案：与 Java TagScheme.bio() 逐条对应
# ---------------------------------------------------------------------------

TAGS = ["O", "B-PER", "I-PER", "B-ORG", "I-ORG"]
O, B_PER, I_PER, B_ORG, I_ORG = range(5)
NUM_TAGS = len(TAGS)

# TagScheme.allowsByBIO：to 是 O 或 B- 时放行；to 是 I-X 时要求 from 也是 X
_ENTITY_OF = {O: None, B_PER: "PER", I_PER: "PER", B_ORG: "ORG", I_ORG: "ORG"}
_IS_BEGIN = {O: False, B_PER: True, I_PER: False, B_ORG: True, I_ORG: False}


def _build_allowed() -> torch.Tensor:
    """[C, C] 布尔矩阵，与 TagScheme.allowsByBIO 逐条对应。"""
    m = np.zeros((NUM_TAGS, NUM_TAGS), dtype=bool)
    for frm in range(NUM_TAGS):
        for to in range(NUM_TAGS):
            target = _ENTITY_OF[to]
            if target is None:
                m[frm, to] = True
            elif _IS_BEGIN[to]:
                m[frm, to] = True
            else:
                m[frm, to] = _ENTITY_OF[frm] == target
    return torch.from_numpy(m)


def _build_allow_start() -> torch.Tensor:
    """[C] 布尔向量：O 或 B- 才能出现在序列开头（TagScheme.allowsStart）。"""
    return torch.tensor(
        [(_ENTITY_OF[t] is None) or _IS_BEGIN[t] for t in range(NUM_TAGS)], dtype=torch.bool
    )


ALLOWED = _build_allowed()
ALLOW_START = _build_allow_start()

NEG_INF = float("-inf")
"""非法转移的屏蔽值。

**必须是 -inf，不能用 -1e4。** 这不是精度洁癖，是正确性问题：
CRF 的 logZ 是「所有路径」的 log-sum-exp，非法路径也参与求和。
用 -1e4 时，屏蔽量与 emission 的实际量级可比，于是非法路径在 logZ 里
被**系统性低估**——一旦模型把 emission 压到同一量级，logZ 就会低于金标路径，
NLL 出现负数（实测：epoch 1 平均 loss = -691660），再往后 logZ 与金标双双溢出成 inf，
相减得 NaN，训练从此静默地跑完 10 个 epoch。

实测代价：12 轮训练全废，MSRA test F1 = 0.0000。

导出到 ONNX 时会把 -inf 换成 -1e4 有限值（见 export_onnx）：
Java 的 SequenceDecoder 本来就用 TagScheme 把非法转移整个跳过，
根本读不到这些位置，有限值只是为了不让 ONNX 文件里出现非有限字面量。
"""

EXPORT_NEG = -1.0e4
"""导出用的屏蔽值。见上面关于 -inf 的说明。"""

# ---------------------------------------------------------------------------
# 数据集读取
# ---------------------------------------------------------------------------

# 数据集在 REA/work/nerdata（desens-desktop 的兄弟目录），可用 --data-dir 覆盖
_DATA_ROOT = pathlib.Path(__file__).resolve().parents[2] / "work" / "nerdata"
DATA_DIR = _DATA_ROOT

# cluener2020 的 ClassLabel 名字（从 parquet 的 huggingface 元数据读出并核对过）
# 只把人名与机构名映射进模型；地名由正则规则处理，不进模型标签集。
CLUENER_MAP = {
    "name": "PER",
    "company": "ORG",
    "organization": "ORG",
    "government": "ORG",
}
# 其余（address/book/game/movie/position/scene）一律当 O

# MSRA 三类：PER / ORG 进模型，LOC 走正则
MSRA_MAP = {"PER": "PER", "ORG": "ORG"}


def _sanitize(tags: list[int]) -> list[int]:
    """把不满足 BIO 约束的标签改写成合法标签。

    MSRA/cluener 是人标的，理论上合法，但**不能假设**——一条 ``I-PER`` 紧跟在 ``O``
    后面会让它的金标路径分被 -1e4 惩罚，训练直接被一条脏标签带偏。
    这里按「I- 无合法前驱就当作新实体开头」改写，
    与 ``SequenceDecoder.toEntities`` 对异常路径的处理一致。
    """
    out = []
    for t in tags:
        if t in (I_PER, I_ORG) and not ALLOWED[out[-1] if out else 0, t]:
            # 序列开头（用 O 当作虚拟前驱）也不允许 I-
            t = B_PER if t == I_PER else B_ORG
        out.append(t)
    return out


def read_msra(path: pathlib.Path) -> list[tuple[str, list[int]]]:
    """``字\\t标签`` 每行、空行分句。"""
    bio = {"PER": (B_PER, I_PER), "ORG": (B_ORG, I_ORG)}
    sents: list[tuple[str, list[int]]] = []
    chars: list[str] = []
    tags: list[int] = []
    bad = 0
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line.strip():
                if chars:
                    sents.append(("".join(chars), _sanitize(tags)))
                    chars, tags = [], []
                continue
            if "\t" not in line:
                bad += 1
                continue
            ch, lab = line.split("\t", 1)
            lab = lab.strip()
            if lab == "" or lab == "O":
                t = O
            elif lab.startswith("B-"):
                ent = MSRA_MAP.get(lab[2:])
                t = bio[ent][0] if ent else O
            elif lab.startswith("I-"):
                ent = MSRA_MAP.get(lab[2:])
                t = bio[ent][1] if ent else O
            else:
                bad += 1
                t = O
            chars.append(ch)
            tags.append(t)
    if chars:
        sents.append(("".join(chars), _sanitize(tags)))
    if bad:
        print(f"  [warn] {path.name}: 跳过 {bad} 行格式异常数据", file=sys.stderr)
    return sents


def read_cluener(path: pathlib.Path) -> list[tuple[str, list[int]]]:
    import json as _json

    import pyarrow.parquet as pq

    md = pq.ParquetFile(path).schema_arrow.metadata or {}
    names = _json.loads(md[b"huggingface"].decode())["info"]["features"]["ner_tags"]["feature"]["names"]
    bio = {"PER": (B_PER, I_PER), "ORG": (B_ORG, I_ORG)}
    sents = []
    for row in pq.read_table(path).to_pylist():
        toks, tag_ids = row["tokens"] or [], row["ner_tags"] or []
        if len(toks) != len(tag_ids) or not toks:
            continue
        raw_tags = [names[i] for i in tag_ids]
        tags = []
        for lab in raw_tags:
            if lab == "O":
                tags.append(O)
                continue
            ent = CLUENER_MAP.get(lab[2:]) if lab[1] == "-" else None
            if ent is None:
                tags.append(O)
            else:
                tags.append(bio[ent][0 if lab[0] == "B" else 1])
        sents.append(("".join(toks), _sanitize(tags)))
    return sents


# ---------------------------------------------------------------------------
# 字符表
# ---------------------------------------------------------------------------


def build_vocab(sents: list[tuple[str, list[int]]], min_freq: int = 2):
    """按「出现次数降序、码点升序」排序，保证同样的数据得到同样的字符表。

    排序里加码点这一级不是洁癖：只按频次排时，同频字符的相对顺序取决于
    哈希遍历顺序，换台机器训练出来的模型字符表 id 就对不上，
    而症状是「模型加载正常但全是 UNK」。

    **换行符必须排除**（见 ``CharVocab.load`` 的说明）：名单式合成会把 ``\\n``
    当分隔符塞进句子里，于是 ``\\n`` 进了字符表；``save`` 写出来就是一个空行，
    而 ``load`` 会跳过空行——**词表从此错位一格**，模型加载正常、推理正常、
    但每个字符都读到了别人的向量。实测症状是训练日志打印 F1 0.8397，
    拿导出的 .onnx 重打分只有 0.1209。
    """
    freq = collections.Counter()
    for text, _ in sents:
        freq.update(text)
    freq.pop("\n", None)
    freq.pop("\r", None)
    kept = [(c, n) for c, n in freq.items() if n >= min_freq]
    kept.sort(key=lambda x: (-x[1], ord(x[0])))
    chars = [c for c, _ in kept]
    oov_rate = sum(n for c, n in freq.items() if n < min_freq) / max(1, sum(freq.values()))
    return chars, oov_rate, len(freq)


class CharVocab:
    def __init__(self, chars: list[str]):
        self.chars = chars
        self.unk = 0
        self.stoi = {c: i + 1 for i, c in enumerate(chars)}

    def __len__(self):
        return len(self.chars) + 1

    def encode(self, text: str) -> list[int]:
        return [self.stoi.get(c, self.unk) for c in text]

    def save(self, path: pathlib.Path):
        """与 Java ``CharVocab.ofListedChars(List<String>)`` 对齐：
        文件里第 i 行（0 基）对应 id i+1，第 0 个 id 是 UNK 占位。

        **表里不允许有换行符。** 一个 ``"\\n"`` 写成文件就是一个空行，
        而 ``load`` 过去会跳过空行，于是**词表错位一格**：模型加载正常、
        推理正常、每个字符却读到了别人的向量。这种故障没有任何报错，
        只表现为「精度莫名其妙地差」，而训练日志里的分数是好的——
        因为训练用的是内存里的表，不是这张文件。
        """
        bad = [c for c in self.chars if "\n" in c or "\r" in c or c == ""]
        if bad:
            raise ValueError(
                f"字符表里混进了换行符/空串（{len(bad)} 个，例如 {bad[:3]!r}），"
                "写出去就是空行，读回来会错位。这不是能靠 load 兜住的问题——"
                "错位之后每一步都正常执行，只是全都算错了。"
            )
        with path.open("w", encoding="utf-8") as fh:
            for c in self.chars:
                fh.write(c + "\n")

    @staticmethod
    def load(path: pathlib.Path) -> "CharVocab":
        """从文件读回。

        **不做静默跳过。** 过去这里有一句 ``if ln != "\\n"`` 跳过空行，
        而空行唯一可能的来源就是 save 时混进了换行符——跳过它等于把
        「词表已经错位」这件事藏起来，让整条推理链正常跑完、结果全错。
        宁可在这里炸掉：那说明模型包的字符表与训练时不是同一张。
        """
        chars = [ln.rstrip("\n") for ln in path.read_text(encoding="utf-8").split("\n")[:-1]]
        empty = [i for i, c in enumerate(chars) if c == ""]
        if empty:
            raise ValueError(
                f"{path} 第 {empty[:5]} 行是空行。字符表文件出现空行只有一个原因："
                "生成它的词表里混进了换行符。读回来会整体错位一格，"
                "模型加载正常、推理正常、每个字符却读到了别人的向量。"
                "重新训练导出，不要在这里手工删行——删掉只是把错位藏起来。"
            )
        return CharVocab(chars)


# ---------------------------------------------------------------------------
# 模型
# ---------------------------------------------------------------------------


class CharIdCnn(nn.Module):
    """字符嵌入 + 空洞卷积 + 线性投影到标签通道。

    刻意保持小：字符表约 6000 字、嵌入 192 维时总参数 ≈1.9M，fp32 约 7.5MB。
    这不是「先做大再压」——一旦要靠剪枝压到预算内，精度损失不可预测，
    而不可预测的精度在验收线上等于没有验收线。

    ## 感受野是被算过的，不是随手定的

    kernel=3 的空洞卷积，感受野 = 1 + 2 × Σdilation。三层 1/2/4 时
    **只有 15 字**（左 7 右 7）。

    对靠上下文判断的 NER，15 字意味着「委托诉讼代理人」这类 7 字角色词
    刚好塞得下、再多就看不全。而法律域的实测短板恰恰是**人名「完全没认出来」**
    （漏检 253 个里占 154 个），机构名则几乎全是边界问题——
    前者要的是上下文，后者的字形特征就在附近。

    加第四层 dilation=8 → 感受野 1 + 2×(1+2+4+8) = **31 字**，
    参数量只多 embed_dim² × 3（embed_dim=256 时约 0.75MB）。
    **先用这一条而不是直接加宽**：加宽是纯赌容量，而感受野不足是可以算出来的。
    """

    def __init__(self, vocab_size: int, embed_dim: int, num_tags: int = NUM_TAGS,
                 dropout: float = 0.3,
                 dilations: tuple[int, ...] = (1, 2, 4)):
        super().__init__()
        # **不要**设 padding_idx=0：id 0 是 UNK，不是 padding。
        # 设了之后 UNK 那一行永远拿不到梯度，于是「表外字符」在模型眼里
        # 始终是随机初始化的向量——而 UNK 恰恰是所有表外字符的入口。
        # 补齐位不进损失（crf_log_partition 与 gold_path_score 都按 mask 累加），
        # 所以不需要靠 padding_idx 来屏蔽梯度。
        self.emb = nn.Embedding(vocab_size, embed_dim)
        self.convs = nn.ModuleList(
            [
                nn.Conv1d(embed_dim, embed_dim, 3, padding=d, dilation=d)
                for d in dilations
            ]
        )
        # dropout 放在每层卷积之后、进入下一层之前。
        # 第一版没有它：训练 loss 一路降到 0.023，而留出集 F1 在 ep10 就见顶
        # （0.8079）之后反而回落（ep19 0.8039）——典型的过拟合。
        # 收紧模型之前先把正则加上，否则加宽加深只会让过拟合更严重。
        self.dropout = nn.Dropout(dropout)
        self.out = nn.Linear(embed_dim, num_tags)
        # CRF 转移矩阵。注册成 buffer 还是 Parameter 取决于有没有梯度，
        # 这里用 Parameter，并在 forward 里叠硬屏蔽。
        self.transitions = nn.Parameter(torch.zeros(num_tags, num_tags))
        # BIO 约束矩阵注册成 buffer：随 .to(device) 一起搬家，
        # 免得每次前向都 .to() 一次（那正是这次冒烟挂掉的原因）。
        self.register_buffer("allowed", ALLOWED.clone(), persistent=False)
        self.register_buffer("allow_start", ALLOW_START.clone(), persistent=False)

    def emission(self, ids: torch.Tensor) -> torch.Tensor:
        """[B, T] int64 -> [B, T, C] 原始 logit。

        卷积核固定 padding，序列长度不变；感受野 = 1 + 2*(1+2+4) = 15 个字符。
        """
        x = self.emb(ids).transpose(1, 2)  # [B, D, T]
        for conv in self.convs:
            x = self.dropout(F.relu(conv(x)))
        return self.out(x.transpose(1, 2))

    def masked_transitions(self) -> torch.Tensor:
        """把非法转移压成 -inf，使它们在 logZ 里的概率恰好为 0。"""
        return self.transitions.masked_fill(~self.allowed, NEG_INF)

    def export_transitions(self) -> torch.Tensor:
        """导出用：-inf 换成有限的 -1e4。理由见 ``EXPORT_NEG``。"""
        return self.masked_transitions().masked_fill(
            torch.isinf(self.masked_transitions()), EXPORT_NEG
        )

    def forward(self, ids: torch.Tensor):
        """只返回 emission。转移矩阵**不进这张图**——它由 export_onnx 手工挂上去。

        早先这里返回 ``(em, self.transitions + em.sum() * 0.0)``，
        结果导出后图里出现了三个输出：emission、一个被自动命名成 "26" 的中间结果、
        以及后来手工追加的 transition。前两个是同一个张量，
        而 ``sess.run(None, ...)` 收到三个返回值——Java 侧按名取就还能凑合，
        任何按位置解包的调用方直接炸。
        顺带一提，``* 0.0`` 那个挂法在 logit 出现 inf 时会产出 nan。
        """
        return self.emission(ids)


# ---------------------------------------------------------------------------
# CRF：前向算法求 logZ，金标路径得分做 NLL
# ---------------------------------------------------------------------------


def crf_log_partition(emissions: torch.Tensor, trans: torch.Tensor, mask: torch.Tensor,
                      allow_start: torch.Tensor) -> torch.Tensor:
    """[B,T,C] logit、**已屏蔽**的 [C,C] 转移、[B,T] bool 有效位、开始约束 -> [B] logZ。

    广播方向要非常小心，这里踩过：

        alpha.unsqueeze(1) + trans          # 错：得到 [B,C,C]，但 alpha 被按 **to** 索引
        alpha.unsqueeze(2) + trans          # 对：alpha 落在 [B,C,1]，广播 over to

    写错的后果不是形状不对（形状照样是 [B,C,C]，一切检查都过），
    而是 logZ 变成一个**不是配分函数**的量：alpha 的贡献在同一列里对所有 from 取同一个值，
    路径分数被彻底写错。现场是 loss 从 1073 掉到 -691660 再变 NaN，
    而「logZ ≥ 金标」这条数学不变量也不成立。形状对 ≠ 算得对。
    """
    alpha = emissions[:, 0, :].masked_fill(~allow_start, NEG_INF)  # [B, C]
    for t in range(1, emissions.size(1)):
        # [B,C,1] + [1,C,C] + [B,1,C] -> [B, C_from, C_to]
        scores = alpha.unsqueeze(2) + trans + emissions[:, t, :].unsqueeze(1)
        nxt = torch.logsumexp(scores, dim=1)  # over C_from -> [B, C_to]
        keep = mask[:, t].unsqueeze(1)
        alpha = torch.where(keep, nxt, alpha)
    return torch.logsumexp(alpha, dim=1)


def gold_path_score(emissions, trans, tags, mask):
    """金标路径总分。trans 需已屏蔽；tags 已 sanitize，不会吃到 -1e4。"""
    m = mask.float()
    score = (emissions.gather(2, tags.unsqueeze(2)).squeeze(2) * m).sum(dim=1)
    if tags.size(1) > 1:
        t_from = tags[:, :-1]
        t_to = tags[:, 1:]
        tr = trans[t_from, t_to]  # [B, T-1]
        score = score + (tr * m[:, 1:]).sum(dim=1)
    return score


def viterbi(emissions: torch.Tensor, trans: torch.Tensor, allow_start: torch.Tensor) -> torch.Tensor:
    """受 BIO 硬约束的 Viterbi。规则与 SequenceDecoder.decode 完全一致。

    单独写一份而不是调 Java，是为了在训练阶段就能独立算 F1；
    两份实现在导出后的对账测试里用同一组随机输入逐个比较。
    """
    tr = trans
    b, t, c = emissions.shape
    dp = emissions[:, 0, :].masked_fill(~allow_start, NEG_INF)
    back = torch.zeros(b, t, c, dtype=torch.long, device=emissions.device)
    allowed = ALLOWED.to(emissions.device)
    for i in range(1, t):
        scores = dp.unsqueeze(2) + tr.unsqueeze(0) + emissions[:, i, :].unsqueeze(1)  # [B,Cf,Ct]
        scores = scores.masked_fill(~allowed, NEG_INF)
        best, arg = scores.max(dim=1)  # [B, Ct]
        # 该通道没有任何合法前驱（best 是 -inf）：退回「任意前驱」，
        # 与 Java SequenceDecoder 的 bestFrom<0 分支保持一致。
        # 少了这一步，argmax 会挑一个来自非法转移的下标，解出结构非法的路径。
        dead = torch.isinf(best)
        if bool(dead.any()):
            loose = dp.unsqueeze(2) + tr.unsqueeze(0) + emissions[:, i, :].unsqueeze(1)
            lbest, larg = loose.max(dim=1)
            best = torch.where(dead, lbest, best)
            arg = torch.where(dead, larg, arg)
        back[:, i, :] = arg
        dp = best
    path = torch.zeros(b, t, dtype=torch.long, device=emissions.device)
    path[:, t - 1] = dp.argmax(dim=1)
    for i in range(t - 1, 0, -1):
        path[:, i - 1] = back[:, i, :].gather(1, path[:, i].unsqueeze(1)).squeeze(1)
    return path


# ---------------------------------------------------------------------------
# 实体抽取与 F1
# ---------------------------------------------------------------------------


def spans_from_tags(tags: list[int]) -> set[tuple[int, int, str]]:
    out = set()
    start = -1
    cur = None
    for i, t in enumerate(tags):
        ent = _ENTITY_OF[t]
        if ent is None:
            if cur is not None:
                out.add((start, i, cur))
                cur, start = None, -1
            continue
        if _IS_BEGIN[t] or cur != ent:
            if cur is not None:
                out.add((start, i, cur))
            start, cur = i, ent
    if cur is not None:
        out.add((start, len(tags), cur))
    return out


def prf(gold_all, pred_all):
    tp = fp = fn = 0
    for g, p in zip(gold_all, pred_all):
        tp += len(g & p)
        fp += len(p - g)
        fn += len(g - p)
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    return prec, rec, f1, tp, fp, fn


# ---------------------------------------------------------------------------
# 批处理
# ---------------------------------------------------------------------------


def make_batches(items, batch_tokens, rng):
    """按长度排序后组 batch，再打乱 batch 顺序。

    排序是必须的：句子长度从 5 到 100+ 都存在，随机组 batch 会让绝大多数算力
    花在 padding 上，而 padding 位置的 logit 会通过卷积的边界看到真实 token。
    """
    order = sorted(range(len(items)), key=lambda i: len(items[i][1]))
    batches, cur, cur_max = [], [], 0
    for i in order:
        ln = len(items[i][1])
        new_max = max(cur_max, ln)
        if cur and new_max * (len(cur) + 1) > batch_tokens:
            batches.append(cur)
            cur, cur_max = [i], ln
        else:
            cur.append(i)
            cur_max = new_max
    if cur:
        batches.append(cur)
    rng.shuffle(batches)
    return batches


def collate(items, idxs, vocab, device):
    xs = [vocab.encode(items[i][0]) for i in idxs]
    ys = [items[i][1] for i in idxs]
    t = max(len(x) for x in xs)
    ids = torch.zeros(len(xs), t, dtype=torch.long)
    tags = torch.zeros(len(xs), t, dtype=torch.long)
    mask = torch.zeros(len(xs), t, dtype=torch.bool)
    for r, (x, y) in enumerate(zip(xs, ys)):
        ids[r, : len(x)] = torch.tensor(x, dtype=torch.long)
        tags[r, : len(y)] = torch.tensor(y, dtype=torch.long)
        mask[r, : len(x)] = True
    return ids.to(device), tags.to(device), mask.to(device)


# ---------------------------------------------------------------------------
# 导出 ONNX
# ---------------------------------------------------------------------------


def export_onnx(model, out_path, max_len, embeds_init):
    """导出单文件自包含 ONNX，两个输出：emission[1,T,C] 与 transition[C,C]。

    转移矩阵通过「追加 Identity(constant)」的方式挂到图里，
    而不是靠 ``emission.sum() * 0`` 那种技巧把它挂在数据流上——
    乘 0 的写法在 logit 出现 inf 时会产出 nan，而模型包里出现一个 nan
    转移矩阵的表现是「所有实体类型乱判」，排查成本极高。
    """
    import onnx

    # 导出必须在 CPU 上做：dummy 输入是 CPU 张量，而模型可能还留在 MPS。
    # 忘了这一句的现场是 "RuntimeError: Passed CPU tensor to MPS op"——
    # 训练全部跑完、指标都打出来了，最后一步炸掉。
    model = model.cpu()
    model.eval()
    dummy = torch.zeros(1, max_len, dtype=torch.long)

    tmp = out_path.with_suffix(".tmp.onnx")
    # dynamo=False：走 TorchScript 旧导出器。torch 2.14 的新导出器要额外装 onnxscript，
    # 而它对本模型没有任何增益——一个 Embedding + 三层 Conv1d + Linear，
    # 旧导出器产出的图更小也更稳定，不值得为它多一个构建期依赖。
    torch.onnx.export(
        model,
        dummy,
        str(tmp),
        input_names=["ids"],
        output_names=["emission"],
        dynamic_axes={"ids": {0: "batch", 1: "time"}, "emission": {0: "batch", 1: "time"}},
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )

    m = onnx.load(str(tmp))
    graph = m.graph
    c = NUM_TAGS
    trans = embeds_init  # [C, C] float32

    const_name = "crf_transitions"
    graph.initializer.append(
        onnx.numpy_helper.from_array(np.asarray(trans, dtype=np.float32), const_name)
    )
    ident = graph.node.add()
    ident.op_type = "Identity"
    ident.name = "crf_transition_out"
    ident.input.append(const_name)
    ident.output.append("transition")

    for o in graph.output:
        if o.name == "emission":
            o.type.tensor_type.shape.dim[0].ClearField("dim_value")
            o.type.tensor_type.shape.dim[0].dim_param = "batch"
            o.type.tensor_type.shape.dim[1].ClearField("dim_value")
            o.type.tensor_type.shape.dim[1].dim_param = "time"

    out = graph.output.add()
    out.name = "transition"
    tt = out.type.tensor_type
    tt.elem_type = onnx.TensorProto.FLOAT
    sh = tt.shape.dim.add()
    sh.dim_value = c
    sh2 = tt.shape.dim.add()
    sh2.dim_value = c

    onnx.checker.check_model(m, full_check=True)
    onnx.save(m, str(out_path))
    tmp.unlink()
    return out_path


class ExportedScorer:
    """用**导出产物本身**算 emission，Viterbi 仍走训练侧那一份。

    为什么不在这里把参数读回 torch 模型重跑：第一版那么写了，结果 F1 掉到 0.0020
    （fp=89768）。原因是 torch.onnx.export 的常量折叠把 ``out.weight``
    改名成了 ``onnx::MatMul_23`` 并预先转置，名字对不上、形状还差一次转置，
    剩下两层卷积和输出层就一直是随机初始化——**模型加载"成功"，分数烂掉，没有任何报错**。
    按名字匹配、按形状匹配都会再踩一次。

    改用 onnxruntime 直接前向之后：不需要认任何名字，且被测对象从
    「我重建出来的 torch 模型」变成「真正要发给用户的那个 .onnx 文件」——
    导出环节的任何问题（常量被转置、图被折坏、opset 不对）都会直接反映在分数上。

    尺子（``viterbi`` + ``ALLOWED``）仍然是训练侧那一份，没有为了迁就被测而重写，
    这条不能松：尺子和被测共用同一份 BIO 规格，但实现必须是两份。
    """

    def __init__(self, model_dir: pathlib.Path):
        import onnxruntime as ort

        self.sess = ort.InferenceSession(
            str(model_dir / "ner.onnx"), providers=["CPUExecutionProvider"]
        )
        names = [o.name for o in self.sess.get_outputs()]
        if "emission" not in names or "transition" not in names:
            raise SystemExit(
                f"{model_dir/'ner.onnx'} 的输出是 {names}，"
                "本项目要求同时有 emission 与 transition（单文件自包含）。"
            )
        self.tr = torch.from_numpy(np.asarray(self.sess.run(None, {"ids": np.zeros((1, 2), np.int64)})[1],
                                              dtype=np.float32))

    def emission(self, ids: torch.Tensor) -> torch.Tensor:
        arr = ids.detach().cpu().numpy().astype(np.int64)
        out = self.sess.run(["emission"], {"ids": arr})[0]
        return torch.from_numpy(np.asarray(out, dtype=np.float32)).to(ids.device)

    def masked_transitions(self) -> torch.Tensor:
        # 图里是导出用的 -1e4 而不是训练用的 -inf。没关系：
        # viterbi 另外用 ALLOWED 做了一次结构屏蔽，非法转移本来就不可能被选中。
        # 这一点在 verify_ner_export.py 的「与 PyTorch 一致」里也验过。
        return self.tr

    @property
    def allow_start(self) -> torch.Tensor:
        return ALLOW_START

    def eval(self):
        return self

    def to(self, device):
        return self  # ORT 自己管设备


def _sha256_file(path: pathlib.Path) -> str:
    """文件的 SHA-256（小写十六进制）。

    写进清单是为了让加载侧能区分三件在现象上很像的事：
    「模型本来就长这样」「下载到一半断了」「包被人换过」。
    没有摘要时这三种都表现为「加载成功但结果离谱」，而这正是本项目
    已经踩过的那一类最安静的故障。
    """
    import hashlib

    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def _assert_export_consistent(out_dir: pathlib.Path, vocab_size: int):
    """导出后当场核对三个文件是否互相吻合，不吻合就中止、**不留下产物**。

    对的是「ONNX 嵌入行数 == vocab.txt 字数 + 1 == 训练期词表大小」。
    这一对不上，整条推理链仍然会正常跑完——只是每个字符都读到了别人的向量，
    没有任何异常抛出。所以只能在写盘这一刻查，且查不过就不该把产物留在盘上。
    """
    import onnx

    m = onnx.load(str(out_dir / "ner.onnx"))
    rows = None
    for init in m.graph.initializer:
        if init.name == "emb.weight":
            rows = init.dims[0]
    reloaded = CharVocab.load(out_dir / "vocab.txt")
    if rows is None:
        raise SystemExit("导出的 ONNX 里找不到 emb.weight，产物不可用")
    if rows != vocab_size:
        raise SystemExit(
            f"产物不一致：ONNX 嵌入 {rows} 行，训练期词表 {vocab_size} 项。"
            "\n已中止。差值就是错位的字符数——每个 id 都读到了别人的向量，"
            "但推理全程无异常。"
        )
    if len(reloaded) != vocab_size:
        raise SystemExit(
            f"产物不一致：vocab.txt 读回 {len(reloaded)} 项，训练期词表 {vocab_size} 项。"
            "\n已中止。最常见的原因是字符表里混进了换行符，写出去变成空行、"
            "读回来被跳过。"
        )
    print(f"产物自检通过：ONNX 嵌入 {rows} 行 == 词表 {len(reloaded)} 项（含 UNK 占位）")


def main() -> int:
    global DATA_DIR
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DATA_DIR.parent / "ner-model"))
    ap.add_argument("--embed-dim", type=int, default=256)
    ap.add_argument("--dropout", type=float, default=0.3)
    ap.add_argument("--dilations", default="1,2,4",
                    help="空洞卷积的 dilation 序列（kernel 固定 3）。"
                         "默认 1,2,4 = 感受野 15 字，与 v4 一致；"
                         "1,2,4,8 = 感受野 31 字")
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--batch-tokens", type=int, default=16384)
    ap.add_argument("--lr", type=float, default=1e-3,
                    help="CRF 的转移矩阵无约束时很容易发散，默认取 1e-3 而不是 3e-3")
    ap.add_argument("--clip", type=float, default=5.0)
    ap.add_argument("--seed", type=int, default=20261016)
    ap.add_argument("--max-len", type=int, default=256)
    ap.add_argument("--min-freq", type=int, default=2)
    ap.add_argument("--smoke", action="store_true", help="只跑几个 batch 验证通路，不训练")
    ap.add_argument("--data-dir", default=None, help="覆盖数据集目录（默认 REA/work/nerdata）")
    ap.add_argument("--eval-only", default=None,
                    help="不训练：直接给已导出的模型目录用当前口径重新打分。"
                         "用来把不同版本放到同一把尺子上比。")
    ap.add_argument("--roster-train", type=int, default=3000,
                    help="合成多少条名单式句子进训练集（0 = 不合成）")
    ap.add_argument("--roster-eval", type=int, default=200,
                    help="合成多少条名单式句子做留出评估（不进训练）")
    ap.add_argument("--legal-train", type=int, default=0,
                    help="合成多少条法律文书句子进训练集（0 = 不合成）。"
                         "动机见 tools/ner_legal_aug.py 的模块注释："
                         "法律域留出集上 ORG F1 仅 0.1571。")
    ap.add_argument("--legal-eval", type=int, default=0,
                    help="合成多少条法律文书句子做留出评估（不进训练）")
    args = ap.parse_args()

    if args.data_dir:
        DATA_DIR = pathlib.Path(args.data_dir)

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    random.seed(args.seed)
    rng = random.Random(args.seed)
    device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    print(f"device = {device}, torch = {torch.__version__}")

    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    t0 = time.time()
    msra_train = read_msra(DATA_DIR / "msra" / "msra_train_bio.txt")
    msra_test = read_msra(DATA_DIR / "msra" / "msra_test_bio.txt")
    cluener = read_cluener(DATA_DIR / "cluener2020.parquet")
    print(
        f"载入耗时 {time.time() - t0:.1f}s：MSRA train {len(msra_train)} 句 / "
        f"MSRA test {len(msra_test)} 句 / cluener {len(cluener)} 句"
    )

    train = msra_train + cluener

    # ---- 名单式语料合成（见 tools/ner_roster_aug.py 的模块注释）----
    # 实测动机：MSRA test 里那句 2427 字的治丧委员会名单，v2 在其上 F1 仅 0.5664，
    # 而 ≤256 字的句子是 0.8498。已排除长度效应（句内分段召回无位置趋势）与
    # max_len 截断（摘掉截断后仍然低），原因是训练集完全没有这类文体。
    # 而名单恰恰是脱敏工具最典型的输入之一：卷宗当事人、律师、证人、债权人清单。
    roster_train, roster_eval = [], []
    if args.roster_train > 0 or args.roster_eval > 0:
        import ner_roster_aug as RA

        per_pool = RA.entity_pool(msra_train, want_org=False) + RA.entity_pool(cluener, want_org=False)
        org_pool = RA.entity_pool(msra_train, want_org=True) + RA.entity_pool(cluener, want_org=True)
        if not per_pool or not org_pool:
            raise SystemExit(
                f"名单合成需要实体池，但训练语料给出 PER {len(per_pool)} / ORG {len(org_pool)}。"
                "池为空说明数据集没读到，不能当成「跳过合成继续训练」——"
                "那样会安静地退回 v2 那个已知有缺口的模型。"
            )
        # 两份用不同种子：留出集**不进**训练，否则量出来的是记忆不是泛化。
        roster_train = RA.synth_roster_sentences(per_pool, org_pool, rng, args.roster_train)
        roster_eval = RA.synth_roster_sentences(per_pool, org_pool, random.Random(args.seed + 1), args.roster_eval)
        n_tr_ent = sum(len(spans_from_tags(t)) for _x, t in roster_train)
        print(
            f"名单式合成：训练 {len(roster_train)} 句 / {n_tr_ent} 实体，"
            f"留出 {len(roster_eval)} 句（不进训练）"
            f"；真实语料 {len(train)} 句，合成占比 {len(roster_train) / (len(train) + len(roster_train)) * 100:.1f}%"
        )
        train = train + roster_train

    # ---- 法律文书语料合成（见 tools/ner_legal_aug.py 的模块注释）----
    #
    # 实测动机：M3-3 用产品路径在法律域留出集上量到 **ORG F1 只有 0.1571**
    # （227 误报 / 288 漏检），而 PER 是 0.6788。查 MSRA train 的 7865 个机构名，
    # 高频后缀是「公司 706 / 委员会 277 / 大学 175 / 集团 175 / 协会 173 / 中心 170」，
    # **「律师事务所 / 公证处 / 司法鉴定所 / 价格认证中心」一个都没有**。
    # 句式上更缺：法律文书的「法定代表人：」「鉴定机构为」「审判长」这些上下文，
    # 新闻语料里根本不会出现。
    #
    # 合成占比刻意压低——让法律合成句占大头会让 MSRA test 崩，
    # 而 MSRA 是公开可比的基准，不能为了修一个域牺牲另一个域。
    legal_train, legal_eval = [], []
    if args.legal_train > 0 or args.legal_eval > 0:
        import ner_legal_aug as LG

        legal_per = per_pool if per_pool else (RA.entity_pool(msra_train, want_org=False)
                                               + RA.entity_pool(cluener, want_org=False))
        legal_org = org_pool if org_pool else (RA.entity_pool(msra_train, want_org=True)
                                               + RA.entity_pool(cluener, want_org=True))
        heads = LG.org_head_pool(legal_org, rng)
        cities = LG.org_city_pool(legal_org, rng)
        if not (legal_per and heads and cities):
            raise SystemExit(
                f"法律文书合成需要实体池，但给出 PER {len(legal_per)} / "
                f"字号 {len(heads)} / 地域 {len(cities)}。"
                "池为空说明数据集没读到，不能当成「跳过合成继续训练」——"
                "那样会安静地退回 ORG 0.1571 那个已知有缺口的模型。"
            )
        legal_train = LG.synth_legal_sentences(legal_per, heads, cities, rng, args.legal_train)
        legal_eval = LG.synth_legal_sentences(legal_per, heads, cities,
                                              random.Random(args.seed + 2), args.legal_eval)
        n_legal_ent = sum(len(spans_from_tags(t)) for _x, t in legal_train)
        print(
            f"法律文书合成：训练 {len(legal_train)} 句 / {n_legal_ent} 实体，"
            f"留出 {len(legal_eval)} 句（不进训练）"
            f"；合成后总语料 {len(train) + len(legal_train)} 句，"
            f"法律合成占 {len(legal_train) / (len(train) + len(legal_train)) * 100:.1f}%"
        )
        train = train + legal_train

    vocab_chars, oov_rate, n_unique = build_vocab(train, args.min_freq)
    vocab = CharVocab(vocab_chars)
    print(
        f"字符表：训练语料共 {n_unique} 个不同字符，"
        f"保留出现 ≥{args.min_freq} 次的 {len(vocab_chars)} 个，"
        f"被归入 UNK 的字符占比 {oov_rate * 100:.4f}%"
    )

    dilations = tuple(int(x) for x in args.dilations.split(",") if x.strip())
    if not dilations or any(d < 1 for d in dilations):
        raise SystemExit(f"--dilations 全是负数或空：{args.dilations!r}")
    rf = 1 + 2 * sum(dilations)
    model = CharIdCnn(len(vocab), args.embed_dim, dropout=args.dropout,
                       dilations=dilations).to(device)
    print(f"dilation={dilations} → 感受野 {rf} 字（kernel=3）", flush=True)
    n_params = sum(p.numel() for p in model.parameters())
    print(f"参数量 {n_params:,}，fp32 约 {n_params * 4 / 1024 / 1024:.2f} MB")

    if args.eval_only:
        # 词表必须跟着**被评估的那个模型包**走，不能拿当前语料重建：
        # 重建出的 id 若和导出时不一致，模型读到的就是另一张表的向量，
        # 分数会低得莫名其妙，而且没有任何报错。
        pkg = pathlib.Path(args.eval_only)
        pkg_vocab = CharVocab.load(pkg / "vocab.txt")
        print(f"重打分：{pkg}，词表 {len(pkg_vocab)} 项")
        # 全程 CPU：ORT 的 CPUExecutionProvider 出的是 CPU 张量，
        # 而 evaluate 会把 ids 建在 device 上。混着放会在
        # masked_fill(mask, -inf) 处报 mask 和 self 不同设备——
        # 重打分是离线核对，不值得为它再引入一条 MPS 路径。
        device = torch.device("cpu")
        model = ExportedScorer(pkg)
        notes = []
        p, r, f1, tp, fp, fn = evaluate(model, msra_test, pkg_vocab, device, args.max_len, notes)
        print("\n".join(notes))
        print(f"MSRA test 实体级 P {p:.4f} R {r:.4f} F1 {f1:.4f} (tp={tp} fp={fp} fn={fn})")
        return 0

    if args.smoke:
        items = train[:200]
        ids, tags, mask = collate(items, list(range(len(items))), vocab, device)
        em = model.emission(ids)
        tr = model.masked_transitions()
        logz = crf_log_partition(em, tr, mask, model.allow_start)
        gold = gold_path_score(em, tr, tags, mask)
        # logZ 是「所有合法路径」的 log-sum-exp，金标路径也在其中，
        # 所以 **logZ 必须 ≥ 金标**，NLL 必须 ≥ 0。
        # 这条不变量一破，就是屏蔽值用错了（-1e4 那版的现场：
        # 非法路径在 logZ 里被低估，logZ 掉到金标之下，NLL 变负）。
        # 把不变量摆进冒烟里，比等训练跑完再看 loss 有没有变成负数快得多。
        slack = (logz - gold)
        if bool((slack < -1e-3).any()):
            raise SystemExit(
                f"CRF 不变量被破坏：logZ < 金标，最小差 {float(slack.min()):.6f}。"
                "检查屏蔽值是不是用了有限数（必须用 -inf）。"
            )
        loss = slack.mean()
        loss.backward()
        path = viterbi(em, tr, model.allow_start)
        print(
            f"[smoke] emission {tuple(em.shape)} loss {loss.item():.4f}"
            f"（≥0 不变量通过） path {tuple(path.shape)}"
        )
        return 0

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-5)
    batches = make_batches(train, args.batch_tokens, rng)
    total_steps = args.epochs * len(batches)
    sched = torch.optim.lr_scheduler.OneCycleLR(
        opt, max_lr=args.lr, total_steps=total_steps, pct_start=0.1, anneal_strategy="linear"
    )

    step = 0
    for ep in range(args.epochs):
        model.train()
        rng.shuffle(batches)
        tot, nb = 0.0, 0
        t0 = time.time()
        for idxs in batches:
            ids, tags, mask = collate(train, idxs, vocab, device)
            # 截到 max_len：超出部分的标签置 0（O），不参与损失
            if ids.size(1) > args.max_len:
                ids, tags, mask = ids[:, : args.max_len], tags[:, : args.max_len], mask[:, : args.max_len]
            em = model.emission(ids)
            tr = model.masked_transitions()
            loss = (
                crf_log_partition(em, tr, mask, model.allow_start)
                - gold_path_score(em, tr, tags, mask)
            ).mean()
            # **非有限就立刻停**。第一版没有这道闸，loss 从 1073 掉到 -691660
            # 再变成 NaN，训练照跑完剩下 10 轮，末尾还打印了「最终 F1 0.0000」——
            # 一次发散被包装成了一次正常实验。要报告的东西，必须先证明它是正常的。
            if not torch.isfinite(loss):
                ep_loss = float(loss.item())
                raise SystemExit(
                    f"\n训练发散：第 {ep} 轮第 {nb} 步的 loss = {ep_loss}（非有限）。"
                    "\n已中止，**没有**导出模型。"
                    "\n排查顺序：①屏蔽值是不是用了有限数（本项目踩过：-1e4 会让 logZ 低于金标）"
                    "②学习率是否过大 ③emission 的量级是否已经和屏蔽值可比。"
                )
            opt.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), args.clip)
            opt.step()
            if step < total_steps - 1:
                sched.step()
            step += 1
            tot += float(loss.item())
            nb += 1
            if nb % 200 == 0:
                print(f"  ep{ep} step{nb}/{len(batches)} loss {tot / nb:.4f} {time.time() - t0:.0f}s", flush=True)
        print(f"[ep {ep}] 平均 loss {tot / max(1, nb):.4f}，{time.time() - t0:.0f}s", flush=True)

        p, r, f1, tp, fp, fn = evaluate(model, msra_test, vocab, device, args.max_len)
        print(f"[ep {ep}] MSRA test 实体级 P {p:.4f} R {r:.4f} F1 {f1:.4f} (tp={tp} fp={fp} fn={fn})", flush=True)
        if roster_eval:
            pr, rr, fr, *_ = evaluate(model, roster_eval, vocab, device, args.max_len)
            print(f"[ep {ep}] 名单留出    P {pr:.4f} R {rr:.4f} F1 {fr:.4f}", flush=True)
        if legal_eval:
            pr, rr, fr, *_ = evaluate(model, legal_eval, vocab, device, args.max_len)
            print(f"[ep {ep}] 法律留出    P {pr:.4f} R {rr:.4f} F1 {fr:.4f}", flush=True)
        elif args.legal_eval > 0:
            # 声明了留出集却没合出来，必须喊出来。
            # 静默跳过在这里是双重失败：既没有数字，又让人以为「这一项没涨是因为模型不行」。
            print(f"[ep {ep}] !! 要求了 {args.legal_eval} 句法律留出，实际 0 句——本项没跑", flush=True)

    # ---- 最终评估（训练集切出的 held-out 之外另报 cluener）----
    final_tr = model.export_transitions().detach()
    p, r, f1, tp, fp, fn = evaluate(model, msra_test, vocab, device, args.max_len)
    print(f"\n最终 MSRA test：P {p:.4f} R {r:.4f} F1 {f1:.4f} (tp={tp} fp={fp} fn={fn})")
    p2, r2, f12, *_ = evaluate(model, cluener, vocab, device, args.max_len)
    print(f"最终 cluener：  P {p2:.4f} R {r2:.4f} F1 {f12:.4f}")
    if roster_eval:
        pr, rr, fr, tpr, fpr, fnr = evaluate(model, roster_eval, vocab, device, args.max_len)
        print(f"最终 名单留出：P {pr:.4f} R {rr:.4f} F1 {fr:.4f} (tp={tpr} fp={fpr} fn={fnr})")
    if legal_eval:
        pr, rr, fr, tpr, fpr, fnr = evaluate(model, legal_eval, vocab, device, args.max_len)
        print(f"最终 法律留出：P {pr:.4f} R {rr:.4f} F1 {fr:.4f} (tp={tpr} fp={fpr} fn={fnr})")
    if args.legal_train > 0 and args.legal_eval <= 0:
        print("注意：加了法律语料但没给 --legal-eval，"
              "训练期看不到它的曲线；结束后只能用 tools/LegalEvalProbe 在真实评测集上量")

    # 最长实体：NerRecognizer 的窗口契约要靠它
    max_ent = 0
    for _, tags in train:
        for _, e, _k in spans_from_tags(tags):
            max_ent = max(max_ent, e - _)
    print(f"训练语料最长实体 {max_ent} 字（Java 侧 overlap 必须 ≥ 该值）")

    onnx_path = out_dir / "ner.onnx"
    export_onnx(model, onnx_path, args.max_len, final_tr.cpu().numpy())
    vocab.save(out_dir / "vocab.txt")

    # 产物自检：嵌入矩阵的行数必须等于「词表字数 + 1（UNK 占位）」。
    #
    # 这一条不是形式主义。v3 之前，名单式合成把换行符带进了字符表，
    # save 写出一个空行、load 跳过空行，于是 4586 个字只加载回 4585 个——
    # **每个字符的 id 从那行起整体错位一格**。症状极其安静：
    # 训练日志打印 F1 0.8397（用的是内存里的词表），
    # 而拿导出的 .onnx + vocab.txt 重新打分只有 0.1209。
    # 没有报错、没有异常、没有形状检查能拦——两个文件各自都"合法"，
    # 合在一起才不合法。所以只能在导出这一刻当场对一次。
    _assert_export_consistent(out_dir, len(vocab))

    manifest = {
        # 版本 2 = 运行时可直接加载。版本 1 只有训练元信息，
        # 缺张量名 / 滑窗参数 / 摘要，Java 侧无法安全加载
        # （张量名不能猜，摘要不能省——省了就分不清「下载坏了」与「模型本来就长这样」）。
        # 加载侧对版本号是 fail-closed 的：不认的版本直接拒绝，不做「尽力解析」。
        "formatVersion": 2,
        "id": "litemask-ner-zh-char-v3",
        "arch": "idcnn-crf",
        "tags": TAGS,
        # 标签后缀（数据集惯例 PER/ORG）→ 脱敏规则码（稳定码 person/organization）。
        # 必须显式声明：加载侧要靠它把模型输出翻译成引擎认识的规则键，
        # 而「模型包里出现的东西能不能变成一条脱敏规则」只能由代码里的白名单决定。
        # 早先没写这一项，于是加载器拿 PER 去查 EntityType 直接报「不认识」——
        # 那是模型包与代码各说各话，不能靠猜后缀来和稀泥。
        "tagTypes": {"PER": "person", "ORG": "organization"},
        "entityTypes": ["person", "organization"],
        "maxEntityChars": max_ent,
        "scoreSpace": "LOGIT",
        # 滑窗参数随模型一起走，不写死在 Java 侧：窗口契约
        # （overlap ≥ maxEntityChars）是这个模型自己的性质，不是所有模型的性质。
        # 这里给 64 而不是贴着 35，是为了留余量——换一个训练语料后最长实体变长时，
        # 不必立刻改 Java 代码就能重新导出。
        "window": {"chars": args.max_len, "overlap": 64},
        "io": {
            "input": "ids",
            "emission": "emission",
            "transition": "transition",
        },
        # 嵌入张量名由加载侧用来核对「模型与词表是同一对产物」。
        # 换导出器时这个名字可能变，所以它属于清单而不是代码常量。
        "embeddingTensor": "emb.weight",
        "files": {
            "model": {
                "path": "ner.onnx",
                "sha256": _sha256_file(onnx_path),
            },
            "vocab": {
                "path": "vocab.txt",
                "sha256": _sha256_file(out_dir / "vocab.txt"),
            },
        },
        "metrics": {
            "msraTestEntityF1": round(f1, 6),
            "cluenerEntityF1": round(f12, 6),
        },
        "provenance": {
            "embeddingDim": args.embed_dim,
            "dropout": args.dropout,
            "seed": args.seed,
            "epochs": args.epochs,
            "trainedOn": ["msra", "cluener2020", "roster-synth"],
            "rosterTrainSentences": args.roster_train,
        },
    }
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"\n导出到 {out_dir}")
    return 0


@torch.no_grad()
def eval_batches(order, sents, max_len, batch_size=64):
    """把句子按长度分桶切成 (idxs, truncate) 批。

    第一版对**所有**句子一刀切 ``[:max_len]``，后果是 MSRA test 里
    12 句超长句（最长 2427 字）被整体截断，它们贡献的 594 个实体
    ——占全部 3304 个的 18%——连同金标一起消失，于是 F1 的分母是 2710 而不是 3304。

    两个数字同时说明这不是「无关紧要的细节」：

    * 训练集同口径只丢 0.3%（最长 746 字），测试集丢 18%。**分子分母的训练/评估口径不对称**，
      报出来的 F1 是乐观的；
    * 丢掉的恰好是长段落，而推理时 ``SlidingWindowNer`` 会把长文按窗口扫完，
      这些实体在真实使用里是会被预测、也会被计数的。

    现在超长句单独成批且**不截断**。批内本来就按 ``t = max(len(x))`` 补齐，
    所以不截断并不会把整批撑到最长句——只有那一句自己长而已。
    训练仍然可以按 max_len 截断（定长批才需要），但**评估不截**：
    评估要回答的是「这份语料上认得准不准」，不是「定长批能装下多少」。
    """
    batches = []
    short, long_ = [], []
    for i in order:
        (long_ if len(sents[i][1]) > max_len else short).append(i)
    for start in range(0, len(short), batch_size):
        batches.append((short[start : start + batch_size], False))
    for i in long_:
        batches.append(([i], False))
    return batches


def evaluate(model, sents, vocab, device, max_len, report=None):
    """按批评估实体级 P/R/F1（严格匹配：起止与类型全中才算命中）。

    必须补齐后再喂给模型：句子长度天然不齐，
    直接 ``torch.tensor([xs])`` 会在长度不同时抛
    "expected sequence of length 3 at dim 2"——第一版就这么写的，
    结果第一个 epoch 一结束就崩，训练白跑。
    补齐位用 0（UNK / O 标签），并且只按各句自己的真实长度取回结果。
    """
    model.eval()
    gold, pred = [], []
    order = sorted(range(len(sents)), key=lambda i: len(sents[i][1]))
    for idxs, _trunc in eval_batches(order, sents, max_len):
        xs = [vocab.encode(sents[i][0]) for i in idxs]
        ys = [sents[i][1] for i in idxs]
        t = max(len(x) for x in xs)
        ids = torch.zeros(len(xs), t, dtype=torch.long, device=device)
        for r, x in enumerate(xs):
            ids[r, : len(x)] = torch.tensor(x, dtype=torch.long, device=device)
        em = model.emission(ids)
        tr = model.masked_transitions()
        path = viterbi(em, tr, model.allow_start).cpu().tolist()
        for k, i in enumerate(idxs):
            gold.append(spans_from_tags(ys[k]))
            pred.append(spans_from_tags(path[k][: len(ys[k])]))
    if report is not None:
        n_over = sum(1 for i in order if len(sents[i][1]) > max_len)
        report.append(
            f"评估口径：金标实体 {sum(len(g) for g in gold)} 个，"
            f"其中 {n_over} 句超长（>{max_len}）单独成批、不截断"
        )
    return prf(gold, pred)


if __name__ == "__main__":
    sys.exit(main())
