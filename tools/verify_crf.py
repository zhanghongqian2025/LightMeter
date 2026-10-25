"""用暴力枚举给 CRF 的 logZ 与 Viterbi 做独立对账。

**为什么必须有这个文件**：CRF 前向算法里 `alpha.unsqueeze(1)` 和
`alpha.unsqueeze(2)` 只差一个数字，形状**完全一样**（都是 [B,C,C]），
一眼看不出、任何形状断言也拦不住。写错的后果是 logZ 根本不是配分函数，
而 loss 仍然是个有限的数，于是训练「正常」跑完，最后 F1 是 0.0000。

所以标尺不能是「再写一遍 DP」，必须是**完全不同的一种算法**：
把 T × C 的所有合法标签路径全枚举出来，直接求 log-sum-exp。
两者在小规模上逐位对上，才能说明 DP 那一版是对的。

同时和 Java 的 SequenceDecoder 口径一致：
  * 序列开头不允许 I-（TagScheme.allowsStart）
  * 非法转移直接排除（TagScheme.allows）
  * 平分取通道号小的（Java 里用 `>` 而不是 `>=`）

**已实测的盲区**：把 ``alpha.unsqueeze(2)`` 故意写回 ``unsqueeze(1)``，
T=1,3,5,7,9 五组里抓到 4 组，**T=1 漏过**——
单点序列没有转移，广播方向错了也看不出来。
所以真正把关的是 T≥3 的几组；只留 T=1 等于没留。
"""

import pathlib
import sys

import torch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import train_ner as T  # noqa: E402


def legal(t_len, c_len, allow_start, allowed):
    """枚举全部合法标签路径（**增量剪枝的 DFS**，不是全笛卡尔积）。

    先用 itertools.product 生成再过滤的话，T=11 就要造 5^11 ≈ 4.9 亿个元组，
    光生成就要几分钟。剪枝版只走合法分支，合法路径数是 C^2 量级，毫秒级完成。
    标尺本身太慢就等于没有标尺。
    """
    out = []
    path = [0] * t_len

    def dfs(i, prev):
        if i == t_len:
            out.append(tuple(path))
            return
        for c in range(c_len):
            if i == 0:
                if not allow_start[c]:
                    continue
            elif not allowed[prev][c]:
                continue
            path[i] = c
            dfs(i + 1, c)

    dfs(0, -1)
    return out


def brute_logz(emission, trans, allow_start, allowed):
    """emission: [T,C]；trans: [C,C]（已屏蔽）-> log-sum-exp(所有合法路径得分)"""
    t_len, c_len = emission.shape
    terms = []
    for path in legal(t_len, c_len, allow_start, allowed):
        s = float(emission[0][path[0]])
        for i in range(1, t_len):
            s += float(trans[path[i - 1]][path[i]]) + float(emission[i][path[i]])
        terms.append(s)
    mx = max(terms)
    return mx + torch.log(torch.tensor(sum(pow(2.718281828459045, x - mx) for x in terms))).item()


def brute_viterbi(emission, trans, allow_start, allowed):
    """暴力取总分最高的路径；同分取通道号小的（与 Java 的 argmax 顺序一致）。"""
    t_len, c_len = emission.shape
    best = None
    for path in legal(t_len, c_len, allow_start, allowed):
        s = float(emission[0][path[0]])
        for i in range(1, t_len):
            s += float(trans[path[i - 1]][path[i]]) + float(emission[i][path[i]])
        if best is None or s > best[0] + 1e-12:
            best = (s, path)
    return list(best[1]), best[0]


def main():
    torch.manual_seed(3)
    allow_start = [bool(x) for x in T.ALLOW_START]
    allowed = [[bool(x) for x in row] for row in T.ALLOWED]
    c_len = T.NUM_TAGS
    bad = 0

    print("=== logZ 对账：DP vs 暴力枚举 ===")
    for trial in range(5):
        t_len = 1 + trial * 2  # 1,3,5,7,9
        em = torch.randn(t_len, c_len) * 1.7
        tr = torch.randn(c_len, c_len)
        tr = tr.masked_fill(~T.ALLOWED, T.NEG_INF)
        mask = torch.ones(1, t_len, dtype=torch.bool)

        dp = float(T.crf_log_partition(em.unsqueeze(0), tr, mask, T.ALLOW_START)[0])
        bf = brute_logz(em, tr, allow_start, allowed)
        d = abs(dp - bf)
        flag = "" if d < 1e-3 else "   <== 不一致"
        print(f"  T={t_len:2d}  DP={dp:12.6f}  暴力={bf:12.6f}  差={d:.2e}{flag}")
        if d >= 1e-3:
            bad += 1

    print("\n=== Viterbi 对账：DP vs 暴力枚举 ===")
    for trial in range(5):
        t_len = 2 + trial * 2  # 2,4,6,8,10
        em = torch.randn(t_len, c_len) * 1.7
        tr = torch.randn(c_len, c_len)
        tr = tr.masked_fill(~T.ALLOWED, T.NEG_INF)
        got = T.viterbi(em.unsqueeze(0), tr, T.ALLOW_START)[0].tolist()
        want, score = brute_viterbi(em, tr, allow_start, allowed)
        same = got == want
        print(f"  T={t_len:2d}  DP={T.TAGS and [T.TAGS[t] for t in got]}")
        print(f"        暴力={[T.TAGS[t] for t in want]}  分数 {score:.4f}  "
              f"{'一致' if same else '<== 不一致'}")
        if not same:
            bad += 1

    print("\n=== logZ ≥ 金标（NLL 非负）===")
    em = torch.randn(6, 9, c_len) * 3.0
    tr = (torch.randn(c_len, c_len)).masked_fill(~T.ALLOWED, T.NEG_INF)
    mask = torch.ones(6, 9, dtype=torch.bool)
    tags = torch.zeros(6, 9, dtype=torch.long)
    for b in range(6):  # 造合法金标
        prev = T.O
        for t in range(9):
            cand = [x for x in range(c_len) if T.ALLOWED[prev, x] and T.ALLOW_START[x]]
            cur = cand[torch.randint(len(cand), (1,)).item()]
            tags[b, t] = cur
            prev = cur
    nll = T.crf_log_partition(em, tr, mask, T.ALLOW_START) - T.gold_path_score(em, tr, tags, mask)
    print(f"  NLL 最小 {float(nll.min()):.6f}（必须 ≥ 0）")
    if float(nll.min()) < -1e-3:
        print("   <== 出现负 NLL：logZ 低于金标，配分函数算错了")
        bad += 1

    if bad:
        print(f"\n对账失败 {bad} 处")
        return 1
    print("\n全部对上：logZ 与 Viterbi 都与暴力枚举一致，且 NLL 非负")
    return 0


if __name__ == "__main__":
    sys.exit(main())
