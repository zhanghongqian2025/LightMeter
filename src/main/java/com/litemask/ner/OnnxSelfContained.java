package com.litemask.ner;

/**
 * ONNX 模型文件的结构扫描：**只回答一个问题——这个模型是否引用了外部数据文件。**
 *
 * <p><b>为什么必须查</b>：ONNX 的「外部数据」允许把权重存在 {@code .onnx} 之外的
 * {@code .data} 文件里。加载这类模型时，运行时会去<b>按路径打开那些文件</b>。
 * 历史上这一族正是路径穿越 / 符号链接遍历漏洞的入口——
 * 一个看起来无害的模型包，能让工具去读用户目录里的任意文件。
 *
 * <p>本工具的应对不是等厂商发版，而是<b>从设计上让它无从发生</b>：
 * 模型一律是自包含单文件，且这一层在交给推理运行时就先查一遍。
 * 理由与「不跨 TextObject 合并 OFD 文本」同源——一个我们用不到的机制，
 * 不给它存在的余地。
 *
 * <p><b>为什么不能靠字节搜索关键字</b>：权重数据就是一大堆裸字节，
 * 里面出现形似字段号（13/14）的字节几乎是必然的。
 * 用「扫一遍有没有关键字」去判，必然误报，误报的代价是所有合法模型都加载不了。
 * 所以这里老老实实按 protobuf 线格式<b>按路径</b>走：
 * {@code ModelProto.graph(7) → GraphProto.initializer(5) / sparse_initializer(15) → TensorProto}
 * 只在 TensorProto 这一层看 {@code external_data(13)} 与 {@code data_location(14)}，
 * 权重 {@code raw_data(9)} 当成字节整段跳过。
 *
 * <p><b>解析不了就报错</b>。这是本工具一贯的方向：宁可拒绝一个模型，
 * 也不能「扫描没发现问题」就当它干净——后者是把「没检查」说成「没问题」。
 */
public final class OnnxSelfContained {

    // ---- ONNX proto 字段号（只列这里用到的） ----
    /** ModelProto.graph */
    private static final int MODEL_GRAPH = 7;
    /** GraphProto.initializer */
    private static final int GRAPH_INITIALIZER = 5;
    /** GraphProto.sparse_initializer */
    private static final int GRAPH_SPARSE_INITIALIZER = 15;
    /** TensorProto.external_data（StringStringEntryProto 列表） */
    private static final int TENSOR_EXTERNAL_DATA = 13;
    /** TensorProto.data_location */
    private static final int TENSOR_DATA_LOCATION = 14;
    /** DataLocation.EXTERNAL */
    private static final long DATA_LOCATION_EXTERNAL = 1L;

    private OnnxSelfContained() {
    }

    /**
     * 断言模型自包含。
     *
     * @param onnx 整个 .onnx 文件的字节
     * @throws ExternalDataRejected 模型引用了外部数据文件
     * @throws IllegalArgumentException 文件结构解析不了（同样必须拒绝）
     */
    public static void assertSelfContained(byte[] onnx) {
        if (onnx == null || onnx.length == 0) {
            throw new IllegalArgumentException("模型文件为空");
        }
        ProtoWire.Cursor c = new ProtoWire.Cursor(onnx, 0, onnx.length);
        boolean sawGraph = false;
        while (c.hasMore()) {
            ProtoWire.Field f = c.next();
            if (f.number == MODEL_GRAPH && f.wireType == ProtoWire.WireType.LENGTH_DELIMITED) {
                sawGraph = true;
                checkGraph(onnx, f.dataStart, f.dataStart + f.length);
            }
        }
        if (!sawGraph) {
            // 一个连 graph 都没有的 ModelProto 不是合法模型。这里显式拒绝，
            // 而不是「没扫到外部数据就算过」——那正是把「没检查」当成「干净」。
            throw new IllegalArgumentException(
                    "模型文件里找不到 ModelProto.graph，不是一个合法的 ONNX 模型");
        }
    }

    private static void checkGraph(byte[] onnx, int start, int end) {
        ProtoWire.Cursor c = new ProtoWire.Cursor(onnx, start, end);
        while (c.hasMore()) {
            ProtoWire.Field f = c.next();
            if (f.wireType != ProtoWire.WireType.LENGTH_DELIMITED) {
                continue;
            }
            if (f.number == GRAPH_INITIALIZER || f.number == GRAPH_SPARSE_INITIALIZER) {
                checkTensor(onnx, f.dataStart, f.dataStart + f.length);
            }
        }
    }

    private static void checkTensor(byte[] onnx, int start, int end) {
        ProtoWire.Cursor c = new ProtoWire.Cursor(onnx, start, end);
        while (c.hasMore()) {
            ProtoWire.Field f = c.next();
            if (f.number == TENSOR_EXTERNAL_DATA
                    && f.wireType == ProtoWire.WireType.LENGTH_DELIMITED) {
                throw new ExternalDataRejected(
                        "模型引用了外部数据文件（TensorProto.external_data）。"
                                + "本工具只接受自包含单文件模型——这是防止模型包借加载之名读取本机文件。");
            }
            if (f.number == TENSOR_DATA_LOCATION && f.wireType == ProtoWire.WireType.VARINT
                    && f.varintValue == DATA_LOCATION_EXTERNAL) {
                throw new ExternalDataRejected(
                        "模型的 data_location=EXTERNAL，权重存在 .onnx 之外。"
                                + "本工具只接受自包含单文件模型。");
            }
        }
    }

    /** 模型引用了外部数据文件。这不是「模型坏了」，是**我们不接受这种模型**。 */
    public static final class ExternalDataRejected extends IllegalArgumentException {
        public ExternalDataRejected(String message) {
            super(message);
        }
    }
}
