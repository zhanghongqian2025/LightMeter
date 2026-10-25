"""定位 CRF 训练第一步的 NaN 到底出在哪一步。

逐步检查：emission / masked transitions / logZ / 金标分 / Viterbi，
并对比 CPU 与 MPS 两种设备——若只有 MPS 出问题，那就是后端的 inf 处理。
"""
import pathlib
import sys

import torch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import train_ner as T  # noqa: E402


def report(tag, x):
    x = x.detach()
    n_nan = int(torch.isnan(x).sum())
    n_inf = int(torch.isinf(x).sum())
    fin = x[torch.isfinite(x)]
    rng = f"[{fin.min():.4f}, {fin.max():.4f}]" if fin.numel() else "空"
    flag = "  <== 有问题" if (n_nan or n_inf) else ""
    print(f"  {tag:<26} nan={n_nan:<5} inf={n_inf:<5} 有限值范围 {rng}{flag}")
    return n_nan + n_inf


def run(device, T_len=24, B=6):
    print(f"\n=== device = {device} ===")
    torch.manual_seed(11)
    model = T.CharIdCnn(53, 16).to(device)
    tr = model.masked_transitions()
    ids = torch.randint(1, 53, (B, T_len), device=device)
    mask = torch.ones(B, T_len, dtype=torch.bool, device=device)
    mask[:, -4:] = False  # 造一点补齐
    tags = torch.randint(0, T.NUM_TAGS, (B, T_len), device=device)
    tags = tags.masked_fill(~mask, T.O)

    report("transitions(参数原值)", model.transitions)
    report("masked_transitions", tr)
    report("emission", model.emission(ids))

    em = model.emission(ids)
    # 逐步拆 logZ
    allow_start = model.allow_start
    allowed = model.allowed
    trm = tr.masked_fill(~allowed, T.NEG_INF)
    alpha = em[:, 0, :].masked_fill(~allow_start, T.NEG_INF)
    bad = report("logZ 起点 alpha", alpha)
    for t in range(1, em.size(1)):
        prev = alpha.detach().clone()
        scores = alpha.unsqueeze(1) + trm
        scores = scores + em[:, t, :].unsqueeze(1)
        if t == 1:
            print("     --- scores[0] 的原始值（行=from，列=to）---")
            with torch.no_grad():
                for f in range(T.NUM_TAGS):
                    row = " ".join(
                        f"{float(scores[0, f, to]):>9.3f}"
                        + ("(inf)" if torch.isinf(scores[0, f, to]) else "     ")
                        for to in range(T.NUM_TAGS)
                    )
                    print(f"       from{f}({T.TAGS[f]:<5}) {row}")
                print("     --- 允许矩阵 allowed（1=允许）---")
                for f in range(T.NUM_TAGS):
                    print(f"       from{f}({T.TAGS[f]:<5}) "
                          + " ".join(f"{int(allowed[f, to].item()):>5}" for to in range(T.NUM_TAGS)))
                print(f"     --- t=0 时的 alpha[0] = "
                      + " ".join(f"{float(prev[0, f]):.3f}" for f in range(T.NUM_TAGS)))
        nxt = torch.logsumexp(scores, dim=1)
        alpha = torch.where(mask[:, t].unsqueeze(1), nxt, alpha)
        n = report(f"logZ 第 {t} 步 alpha", alpha) if t in (1, 2, 3) or t >= T_len - 2 else 0
        bad += n
        if n:
            mx = scores.max(dim=1).values  # [B, C_to]
            dead = torch.isinf(mx)
            cols = sorted(set(dead[0].nonzero().flatten().tolist()))
            print(f"     全灭的 to 通道 = {cols} -> {T.TAGS}")
            print(f"     scores.max() 里 inf 个数 = {int(torch.isinf(mx).sum())}")
            print(f"     scores 里 nan 个数 = {int(torch.isnan(scores).sum())}")
            for b in range(min(2, B)):
                for to in cols:
                    legal_from = [f for f in range(T.NUM_TAGS) if allowed[f, to].item()]
                    terms = [
                        f"from{f}({T.TAGS[f]}) alpha={float(alpha[b, f]):.3f} "
                        f"tr={float(trm[f, to]):.3f}"
                        for f in range(T.NUM_TAGS)
                    ]
                    print(f"     b={b} to={to}({T.TAGS[to]}) 合法前驱={legal_from}")
                    print(f"        {' | '.join(terms)}")
            break
    if not bad:
        report("logZ", T.crf_log_partition(em, tr, mask, allow_start))
        report("金标路径分", T.gold_path_score(em, tr, tags, mask))
        nll = T.crf_log_partition(em, tr, mask, allow_start) - T.gold_path_score(em, tr, tags, mask)
        report("NLL", nll)
        report("Viterbi 标签", T.viterbi(em, tr, allow_start).float())


def main():
    for dev in ["cpu"] + (["mps"] if torch.backends.mps.is_available() else []):
        run(torch.device(dev))


if __name__ == "__main__":
    sys.exit(main())
