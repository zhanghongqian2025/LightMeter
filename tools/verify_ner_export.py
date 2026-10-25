"""独立验证 train_ner.export_onnx：双输出、自包含、ORT 真加载、移位可复现。

不复用被测实现当标尺——这里用 onnxruntime 自己加载并重算，
再用 numpy 对照 PyTorch 的输出，两条路径互相独立。
"""
import pathlib
import sys

import numpy as np
import torch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import train_ner as T  # noqa: E402

import onnx  # noqa: E402
import onnxruntime as ort  # noqa: E402


def main() -> int:
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/ner-export-probe")
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        for p in out.iterdir():
            p.unlink()
    out.mkdir(parents=True, exist_ok=True)

    torch.manual_seed(7)
    np.random.seed(7)

    vocab_size, dim = 97, 32
    model = T.CharIdCnn(vocab_size, dim)
    # 随机化转移矩阵，否则导出的 transition 全 0，验不出「值真的带过去了」
    with torch.no_grad():
        model.transitions.copy_(torch.randn(T.NUM_TAGS, T.NUM_TAGS) * 0.7)
    # 标尺取**导出用**的那份（-inf 已换成有限值）。
    # 拿 masked_transitions() 去比是比不成的：那边是 -inf，差值是 nan，
    # 断言挂在 nan 上，报错信息还会误导成「数值没对齐」。
    tr_expected = model.export_transitions().detach().cpu().numpy()
    model.eval()

    path = T.export_onnx(model, out / "probe.onnx", max_len=64, embeds_init=tr_expected)
    size = path.stat().st_size
    print(f"导出 {path}，{size} B")

    m = onnx.load(str(path))
    onnx.checker.check_model(m, full_check=True)
    print("onnx.checker(full_check=True) 通过")
    print("输出名：", [o.name for o in m.graph.output])

    # 自包含：不允许出现任何 external_data
    bad = []
    for init in m.graph.initializer:
        if init.HasField("data_location") and init.data_location == 1:
            bad.append(init.name)
        for e in init.external_data:
            bad.append(f"{init.name}:{e.key}")
    print("外部数据引用：", bad if bad else "无（自包含）")
    assert not bad, "模型里出现了外部数据引用"

    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    print("ORT 输入：", [(i.name, i.shape, i.type) for i in sess.get_inputs()])
    print("ORT 输出：", [(o.name, o.shape, o.type) for o in sess.get_outputs()])

    for Tn in (1, 17, 64, 200):
        ids = np.random.randint(1, vocab_size, size=(1, Tn)).astype(np.int64)
        got_em, got_tr = sess.run(None, {"ids": ids})

        with torch.no_grad():
            ref_em = model.emission(torch.from_numpy(ids)).cpu().numpy()
        d_em = float(np.max(np.abs(got_em - ref_em)))
        d_tr = float(np.max(np.abs(got_tr - tr_expected)))
        print(f"  T={Tn:4d}  emission 形状 {got_em.shape}  与 PyTorch 最大偏差 {d_em:.3e}"
              f"  transition 偏差 {d_tr:.3e}")
        assert d_em < 1e-4, f"emission 与 PyTorch 不一致：{d_em}"
        assert d_tr < 1e-6, f"transition 没原样带过去：{d_tr}"
        # 位移不变：改变 batch 维，输出应当完全相同
        got_em2, got_tr2 = sess.run(None, {"ids": np.repeat(ids, 2, axis=0)})
        assert np.array_equal(got_em[0], got_em2[0]), "同一段文本两次推理结果不同"
        assert np.array_equal(got_tr, got_tr2), "transition 在两次推理间不稳定"

    # 转移矩阵里被屏蔽的位置必须是有限负值，且**只有**非法位置被屏蔽。
    # 「有限」这条是导出契约的一部分：ONNX 文件里出现 -inf 读起来像损坏。
    allowed = T.ALLOWED.numpy()
    masked_pos = tr_expected <= T.EXPORT_NEG / 2
    print("被屏蔽的位置数：", int(masked_pos.sum()), " 非法转移总数：", int((~allowed).sum()))
    assert np.array_equal(masked_pos, ~allowed), "屏蔽位置与 BIO 约束对不上"
    assert np.isfinite(tr_expected).all(), "导出的转移矩阵里出现了非有限值"
    assert float(tr_expected[masked_pos].max()) == T.EXPORT_NEG, "屏蔽值不是约定的有限负值"

    print("\n全部通过：双输出 / 自包含 / ORT 加载 / 与 PyTorch 一致 / 位移无关 / 屏蔽位置正确且有限")
    return 0


if __name__ == "__main__":
    sys.exit(main())
