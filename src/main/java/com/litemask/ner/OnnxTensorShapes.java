package com.litemask.ner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 ONNX 图里读出各初始化张量的<b>形状</b>（不含权重内容）。
 *
 * <p><b>存在的唯一理由</b>：交叉核对模型与词表是不是<b>同一对</b>产物。
 *
 * <p>本项目踩过这一类坑，而且是最安静的一种：名单式语料合成把换行符带进了字符表，
 * {@code vocab.txt} 写出一个空行，读取端跳过空行，于是 4586 个字只加载回 4585 个——
 * <b>每个字符的 id 从那行起整体错位一格</b>。现场是：
 *
 * <ul>
 *   <li>训练日志打印 F1 0.8397（用的是内存里那份正确的词表）；</li>
 *   <li>拿导出的 {@code .onnx} + {@code vocab.txt} 重新打分只有 0.1209；</li>
 *   <li>两个文件<b>各自都合法</b>，没有异常、没有报错、形状检查全过。</li>
 * </ul>
 *
 * <p>只有「嵌入矩阵的行数 == 词表字数 + 1（UNK 占位）」这条等式能抓住它：
 * 训练侧有这道闸还不够，因为真正消费产物的是加载侧，
 * 必须有<b>一条只读产物的独立路径</b>在运行时再验一次。
 *
 * <p><b>为什么不能靠推理时的越界报错代替</b>：错位让 id 变小，所以最大的 id
 * 仍然落在嵌入矩阵范围内，Gather 不会报错——「能跑」恰恰是这类 bug 的伪装。
 *
 * <p><b>为什么不能靠字节搜索</b>：和 {@link OnnxSelfContained} 同理，
 * 权重就是一大片裸浮点字节，里面出现形似维度的数值几乎是必然的。
 * 所以老实按 protobuf 线格式走 {@code ModelProto.graph(7) → GraphProto.initializer(5) → TensorProto.dims(1)}。
 */
public final class OnnxTensorShapes {

    /** ModelProto.graph */
    private static final int MODEL_GRAPH = 7;
    /** GraphProto.initializer */
    private static final int GRAPH_INITIALIZER = 5;
    /** TensorProto.dims */
    private static final int TENSOR_DIMS = 1;

    private OnnxTensorShapes() {
    }

    /**
     * 读出所有初始化张量的名称与形状。
     *
     * @return 名称 → 维度数组；保持模型里的出现顺序，便于报错时给出可核对的内容
     * @throws IllegalArgumentException 文件不是合法的 ONNX，或结构被截断
     */
    public static Map<String, long[]> initializerDims(byte[] onnx) {
        if (onnx == null || onnx.length == 0) {
            throw new IllegalArgumentException("模型文件为空");
        }
        ProtoWire.Cursor c = new ProtoWire.Cursor(onnx, 0, onnx.length);
        Map<String, long[]> out = new LinkedHashMap<>();
        boolean sawGraph = false;
        while (c.hasMore()) {
            ProtoWire.Field f = c.next();
            if (f.number == MODEL_GRAPH && f.wireType == ProtoWire.WireType.LENGTH_DELIMITED) {
                sawGraph = true;
                readGraph(onnx, f.dataStart, f.dataStart + f.length, out);
            }
        }
        if (!sawGraph) {
            throw new IllegalArgumentException(
                    "模型文件里找不到 ModelProto.graph，不是一个合法的 ONNX 模型");
        }
        return out;
    }

    /**
     * 读出某个初始化张量的第一维（行数）。
     *
     * @return 行数；名字找不到返回 {@code -1}。<b>不返回 0</b>——「没有这个张量」
     *         与「这个张量是 0 行」是两件事，后者会让一个空模型看起来合法。
     */
    public static long rowsOf(byte[] onnx, String initializerName) {
        long[] dims = initializerDims(onnx).get(initializerName);
        if (dims == null || dims.length == 0) {
            return -1;
        }
        return dims[0];
    }

    private static void readGraph(byte[] onnx, int start, int end, Map<String, long[]> out) {
        ProtoWire.Cursor c = new ProtoWire.Cursor(onnx, start, end);
        while (c.hasMore()) {
            ProtoWire.Field f = c.next();
            if (f.number == GRAPH_INITIALIZER
                    && f.wireType == ProtoWire.WireType.LENGTH_DELIMITED) {
                readTensor(onnx, f.dataStart, f.dataStart + f.length, out);
            }
        }
    }

    private static void readTensor(byte[] onnx, int start, int end, Map<String, long[]> out) {
        ProtoWire.Cursor c = new ProtoWire.Cursor(onnx, start, end);
        String name = null;
        long[] dims = new long[0];
        while (c.hasMore()) {
            ProtoWire.Field f = c.next();
            if (f.number == 8 && f.wireType == ProtoWire.WireType.LENGTH_DELIMITED) {
                // TensorProto.name
                name = new String(onnx, f.dataStart, f.length,
                        java.nio.charset.StandardCharsets.UTF_8);
            } else if (f.number == TENSOR_DIMS) {
                if (f.wireType == ProtoWire.WireType.VARINT) {
                    dims = append(dims, f.varintValue);
                } else if (f.wireType == ProtoWire.WireType.LENGTH_DELIMITED) {
                    dims = readPackedDims(onnx, c, f.dataStart, f.length, dims);
                }
                // 其它线类型（fixed32/fixed64）不是合法的 dims 编码，不静默接受
            }
        }
        if (name != null) {
            out.put(name, dims);
        }
    }

    /**
     * 读 packed 编码的 repeated int64。
     *
     * <p><b>packed 与非 packed 都必须支持</b>：protobuf 对 repeated 标量两种编码都合法，
     * 不同导出器给的不一样（本项目导出的 PyTorch 模型是 packed）。
     * 只认一种的话，换个导出器就会读到「空维度」——
     * 而空维度会让后面那条行数对账变成 {@code -1 != N}，报错信息完全指不到真因。
     */
    private static long[] readPackedDims(byte[] onnx, ProtoWire.Cursor c,
                                         int dataStart, int length, long[] acc) {
        int p = dataStart;
        int end = dataStart + length;
        while (p < end) {
            long v = c.readVarintAt(p);
            acc = append(acc, v);
            // readVarintAt 不移动游标，这里必须自己推进一步的 varint 长度
            p = advanceVarint(onnx, p, end);
        }
        if (p != end) {
            throw new IllegalArgumentException(
                    "packed dims 的 varint 长度算出来对不上载荷边界");
        }
        return acc;
    }

    private static int advanceVarint(byte[] buf, int start, int end) {
        int p = start;
        while (p < end) {
            if ((buf[p++] & 0x80) == 0) {
                return p;
            }
        }
        throw new IllegalArgumentException("packed dims 在 varint 中途被截断");
    }

    private static long[] append(long[] acc, long v) {
        if (v < 0) {
            throw new IllegalArgumentException("张量维度不能为负: " + v);
        }
        long[] out = new long[acc.length + 1];
        System.arraycopy(acc, 0, out, 0, acc.length);
        out[acc.length] = v;
        return out;
    }
}
