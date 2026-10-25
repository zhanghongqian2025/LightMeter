#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 NER 链路的测试用最小 ONNX 模型（纯标准库，不依赖 onnx 包）。

设计意图
--------
M3-1c 的推理链路是：字符 -> id -> ORT -> [1,T,C] logits -> Viterbi -> 实体。
要用真实训练好的模型来测它，有两个坏处：
  1. 模型权重成了测试的前置条件，权重一改测试就红，红的原因不是代码缺陷；
  2. 期望值只能靠"再跑一遍 PyTorch"得到，而 PyTorch 和 Java 共用一套权重，
     一致时看不出问题，不一致时又说不清谁对。

所以这里生成一个 **只有一层 Gather 的玩具模型**：
  out[0, t, :] = table[ids[0, t], :]
每个字符对应的 7 维 logits 由下面显式给出，期望实体可以直接手算，
不需要任何机器学习框架参与——这满足"测试标尺独立于被测实现"。

> 踩过的坑：ONNX 的 Gather 是 `Gather(data, indices)`，**参数顺序写反成
> `Gather(indices, data)` 时 onnx.checker 查不出来**（它默认不做算子的类型推导），
> 只有 onnxruntime 加载时才报
> `Type 'tensor(float)' of input parameter (table) ... is invalid`——
> 读起来像"权重类型不对"，实际含义是"这个参数被当成 indices 了，而 indices 必须是整型"。
> 所以生成后必须用 `onnx.checker.check_model(m, full_check=True)` **并**真正加载一次。

为什么手写 protobuf
--------------------
仓库要求"训练/导出脚本能在别的机器上复现"。如果夹具依赖 onnx 包，
复现就得先装一个几十 MB 的依赖。用标准库手写 protobuf 后，
`python3 tools/make_ner_fixture.py` 在任何装了 python3 的机器上都能重跑。

字段号取自 onnx.proto（onnx 1.16），与 Java 侧 OnnxSelfContained 使用的同一套：
  ModelProto.ir_version=1  opset_import=8  graph=7
  GraphProto.node=1  name=2  initializer=5  input=11  output=12
  NodeProto.input=1  output=2  name=3  op_type=4  attribute=5
  AttributeProto.name=1  i=3  type=20
  TensorProto.dims=1  data_type=2  name=8  raw_data=9  external_data=13  data_location=14
  ValueInfoProto.name=1  type=2 ; TypeProto.tensor_type=1 ; Tensor.elem_type=1 shape=2
  TensorShapeProto.dim=1 ; Dimension.dim_value=1

用法：
  python3 tools/make_ner_fixture.py <输出.onnx> [--dump]
"""

import struct
import sys

# ---------------------------------------------------------------- protobuf --

def varint(n):
    if n < 0:
        n += 1 << 64
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def tag(field, wire):
    return varint((field << 3) | wire)


def f_varint(field, value):
    return tag(field, 0) + varint(value)


def f_bytes(field, value):
    return tag(field, 2) + varint(len(value)) + value


def f_str(field, value):
    return f_bytes(field, value.encode("utf-8"))


def f_msg(field, payload):
    return f_bytes(field, payload)


def packed_varint(field, values):
    body = b"".join(varint(v) for v in values)
    return f_bytes(field, body)


# ------------------------------------------------------------------- ONNX --

FLOAT = 1
INT64 = 7
ATTR_INT = 2

IR_VERSION = 7
OPSET_VERSION = 13


def tensor_proto(name, dims, raw, data_type=FLOAT):
    b = b""
    b += packed_varint(1, dims)          # dims
    b += f_varint(2, data_type)           # data_type
    b += f_str(8, name)                  # name
    b += f_bytes(9, raw)                 # raw_data (整段权重，OnnxSelfContained 必须跳过它)
    return b


def type_proto(elem_type, shape):
    """shape 里用 None 表示动态维（维名未知）。"""
    dims = b""
    for d in shape:
        if d is None:
            dims += f_msg(1, f_str(2, "N"))      # dim_param
        else:
            dims += f_msg(1, f_varint(1, d))    # dim_value
    tensor = f_varint(1, elem_type) + f_msg(2, dims)
    type_pb = f_msg(1, tensor)                  # TypeProto.tensor_type
    return type_pb


def value_info(name, elem_type, shape):
    return f_str(1, name) + f_msg(2, type_proto(elem_type, shape))


def gather_node(inputs, outputs, axis=0):
    attr = f_str(1, "axis") + f_varint(3, axis) + f_varint(20, ATTR_INT)
    b = b""
    for i in inputs:
        b += f_str(1, i)
    for o in outputs:
        b += f_str(2, o)
    b += f_str(3, "gather0")
    b += f_str(4, "Gather")
    b += f_msg(5, attr)
    return b


def identity_node(inputs, outputs):
    b = b""
    for i in inputs:
        b += f_str(1, i)
    for o in outputs:
        b += f_str(2, o)
    b += f_str(3, "identity0")
    b += f_str(4, "Identity")
    return b


def build_model(table_rows, n_tags, vocab_size, transition=None):
    """table_rows: list of length vocab_size, 每行是长度 n_tags 的 float 列表。

    ``transition`` 不为 None 时额外挂一个 C×C 的常量输出 ``transition``，
    由 Identity 从初始化张量引出——和训练侧导出 CRF 的做法一致，
    这样 Java 侧 ``readConstantOutput`` 那条路径有东西可测。
    """
    raw = b"".join(struct.pack("<%df" % n_tags, *row) for row in table_rows)
    graph = b""
    # Gather(data, indices) —— 顺序是 ONNX 规范定的，写反了只有 onnxruntime 会报错
    graph += f_msg(1, gather_node(["table", "ids"], ["logits"], axis=0))
    if transition is not None:
        traw = b"".join(struct.pack("<%df" % n_tags, *row) for row in transition)
        graph += f_msg(1, identity_node(["crf_transitions"], ["transition"]))
        graph += f_msg(5, tensor_proto("crf_transitions", [n_tags, n_tags], traw))
    graph += f_str(2, "ner_fixture")
    graph += f_msg(5, tensor_proto("table", [vocab_size, n_tags], raw))
    # ids: [batch, T]，batch 与 T 都设为动态维，让同一份权重能吃任意窗口长度
    graph += f_msg(11, value_info("ids", INT64, [1, None]))
    graph += f_msg(12, value_info("logits", FLOAT, [1, None, n_tags]))
    if transition is not None:
        graph += f_msg(12, value_info("transition", FLOAT, [n_tags, n_tags]))
    graph = f_bytes(7, graph)

    model = f_varint(1, IR_VERSION)
    opset = f_str(1, "") + f_varint(2, OPSET_VERSION)
    model += f_msg(8, opset)
    model += f_str(2, "litemask-make-ner-fixture")
    model += graph
    return model


def build_transition(n_tags, masked_value=-1.0e4):
    """C×C 的转移矩阵，结构上非法的位置填 ``masked_value``。

    用导出时的占位值（-1e4）而不是 -inf，正是为了复现训练侧那一手：
    ONNX 里放 -inf 会在部分后端被算成 NaN。Java 侧加载器必须把它还原回
    「不可能」，否则 SequenceDecoder 那条「全路径 -Infinity 就报错」的安全网
    会被绕过。这条路径没有真实模型可测，只能靠夹具。
    """
    rows = [[0.0] * n_tags for _ in range(n_tags)]
    for i in range(n_tags):
        for j in range(n_tags):
            tag_i = TAGS[i] if i < len(TAGS) else "O"
            tag_j = TAGS[j] if j < len(TAGS) else "O"
            if tag_i == "O":
                ok = True
            elif tag_j == "O":
                ok = True
            elif tag_j.startswith("B-"):
                ok = True
            else:
                # I- 只能接同类型
                ok = tag_i.split("-", 1)[1] == tag_j.split("-", 1)[1]
            rows[i][j] = 0.5 if ok else masked_value
    return rows


# ------------------------------------------------------------- 玩具标签表 --

TAGS = ["O", "B-PER", "I-PER", "B-ORG", "I-ORG"]
N_TAGS = len(TAGS)
VOCAB_SIZE = 64

HIGH = 5.0
LOW = -5.0


def blank_row(tags=None):
    """普通字符：明显偏 O。"""
    tags = tags or TAGS
    return [HIGH if t == "O" else LOW for t in tags]


def entity_row(tag, tags=None):
    """实体字符：明显偏某个 B-/I- 标签。"""
    tags = tags or TAGS
    return [HIGH if t == tag else LOW for t in tags]


def build_table(tags=None):
    """
    固定的玩具表，id 与字符的对应关系见 CHARS。
    之所以把字符写死在 CHARS 里，是为了让 Java 测试能拿到同一份映射，
    而期望实体可以在测试里直接手写出来。
    """
    tags = tags or TAGS
    rows = [blank_row(tags) for _ in range(VOCAB_SIZE)]
    for ch, tag in CHARS.items():
        # tag 为 None 表示"这个字符不构成实体"，保持普通行(O)不变。
        # 这里必须显式跳过：entity_row(None) 会让七个标签全部落到 LOW，
        # 相当于把普通字符也变成"没有 O 倾向"，argmax 就会挑到通道 0 但分数是 -5。
        if tag is None:
            continue
        rows[CHARS_ID[ch]] = entity_row(tag, tags)
    return rows


CHARS = {
    # 人名
    "张": "B-PER", "三": "I-PER", "丰": "I-PER",
    # 机构名
    "北": "B-ORG", "京": "I-ORG", "大": "I-ORG", "学": "I-ORG",
    # 只用来验证「不构成实体的字符」
    "。": None,
}
CHARS_ID = {}


def _assign_ids():
    # 0 留给 UNK，其余按插入顺序编号，和 CHARS 一样写死便于测试对拍
    order = ["。", "张", "三", "丰", "北", "京", "大", "学"]
    for i, ch in enumerate(order, start=1):
        CHARS_ID[ch] = i
    CHARS_ID["\x00"] = 0  # UNK


_assign_ids()

# 未在 CHARS_ID 里的字符统统映射到 0（UNK），其 logits 与 "。" 相同。

# 为什么只有 5 个标签：模型只预测**我们要脱敏的类型**。
# 地名不进标签集——地址由正则规则处理，让模型去预测 LOC 只是白占两个通道，
# 而 TagScheme 要求「恰好一个不属于任何实体的标签」，
# 多出来的 B-LOC/I-LOC 会被判成非法标签方案、整个模型包被拒。
# 这不是巧合，是设计：模型和 EntityType 严格对齐，边界清楚。


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    out = args[0] if args else "ner-fixture.onnx"

    tags = list(TAGS)
    if "--channels" in sys.argv:
        n = int(sys.argv[sys.argv.index("--channels") + 1])
        if n < len(TAGS):
            raise SystemExit("通道数不能小于 %d" % len(TAGS))
        # 多出来的通道一律按普通字符（偏 O）处理，
        # 这样它不影响任何实体的判定，只用来测「通道数对不上」那条错误路径。
        tags = tags + ["O"] * (n - len(TAGS))

    rows = build_table(tags)
    transition = build_transition(len(tags)) if "--transition" in sys.argv else None
    model = build_model(rows, len(tags), VOCAB_SIZE, transition)
    with open(out, "wb") as f:
        f.write(model)

    if "--dump" in sys.argv:
        print("写出 %s (%d 字节, %d 通道%s)" % (
            out, len(model), len(tags), ", 含 transition 输出" if transition else ""))
        print("TAGS = %s" % tags)
        for ch in sorted(CHARS_ID, key=lambda c: CHARS_ID[c]):
            i = CHARS_ID[ch]
            top = tags[max(range(len(tags)), key=lambda t: rows[i][t])]
            print("  id=%-3d char=%r  -> %s" % (i, ch, top))


if __name__ == "__main__":
    main()
