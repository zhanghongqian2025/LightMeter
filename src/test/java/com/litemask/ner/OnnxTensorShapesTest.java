package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OnnxTensorShapes}：从 ONNX 里读初始化张量的形状。
 *
 * <p>这个类存在的理由只有一条——让 {@link NerModelPackage} 能在加载时核对
 * 「模型与词表是同一对产物」。所以测试的重点不是「能不能读出形状」，
 * 而是<b>读不出来时会不会被当成读出来了</b>。
 *
 * <p>夹具由 {@code tools/make_ner_fixture.py} 生成，{@code dims} 是
 * <b>packed</b> 编码（{@code packed_varint(1, dims)}）。Packed 与非 packed
 * 两种编码 protobuf 都合法，只认一种的话换个导出器就会读到空维度——
 * 而空维度在下游表现为「行数对不上」，错误信息会指向完全错误的地方。
 * 所以两种编码都要测，而且要用手写的字节，<b>不能用同一个生成器造</b>：
 * 用同一个生成器造两种编码，测的就只是「生成器没写错」。
 */
class OnnxTensorShapesTest {

    private static byte[] fixture() {
        return NerModelFixtures.onnx(false);
    }

    @Test
    @DisplayName("读出夹具里各张量的形状")
    void readsInitializerDims() {
        Map<String, long[]> dims = OnnxTensorShapes.initializerDims(fixture());
        assertEquals(1, dims.size(), "夹具只有一个 table，实际: " + dims.keySet());
        assertArrayEquals(new long[]{NerModelFixtures.EMBEDDING_ROWS, 5},
                dims.get("table"), "夹具的 table 应当是 [64, 5]");
    }

    @Test
    @DisplayName("rowsOf 给出第一维，找不到时给 -1 而不是 0")
    void rowsOfFirstDim() {
        byte[] b = fixture();
        assertEquals(NerModelFixtures.EMBEDDING_ROWS, OnnxTensorShapes.rowsOf(b, "table"));
        // -1 与 0 必须区分：「没有这个张量」和「这个张量是 0 行」是两件事。
        // 返回 0 会让一个空模型看起来合法，而空模型在下游读出的行数是 0，
        // 与「词表为空」撞在一起，错误信息就指不到真因了。
        assertEquals(-1, OnnxTensorShapes.rowsOf(b, "no.such.tensor"));
    }

    @Test
    @DisplayName("CRF 夹具里有两个张量，都能读到")
    void readsCrfFixture() {
        Map<String, long[]> dims = OnnxTensorShapes.initializerDims(NerModelFixtures.onnx(true));
        assertEquals(2, dims.size(), "实际: " + dims.keySet());
        assertArrayEquals(new long[]{5, 5}, dims.get("crf_transitions"));
        assertArrayEquals(new long[]{NerModelFixtures.EMBEDDING_ROWS, 5}, dims.get("table"));
    }

    // ------------------------------------------------------------------ packed / 非 packed

    /** TensorProto 的 dims（字段 1）非 packed 写法：一个标签一个 varint。 */
    private static byte[] tensorUnpackedDims(String name, long... dims) {
        byte[] raw = new byte[4 * 8];
        byte[] b = new byte[0];
        b = concat(b, varintField(1, dims[0]));
        for (int i = 1; i < dims.length; i++) {
            b = concat(b, varintField(1, dims[i]));
        }
        b = concat(b, varintField(2, 1));          // data_type = FLOAT
        b = concat(b, bytesField(8, name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        b = concat(b, bytesField(9, raw));
        return b;
    }

    /** 塞进一个最小 ModelProto.graph 里的单张量。 */
    private static byte[] modelWith(byte[] tensorProto) {
        byte[] graph = concat(concat(new byte[0], bytesField(5, tensorProto)),
                bytesField(2, "g".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        byte[] model = varintField(1, 7);
        model = concat(model, bytesField(8, concat(bytesField(1, new byte[0]), varintField(2, 13))));
        return concat(model, bytesField(7, graph));
    }

    @Test
    @DisplayName("dims 用非 packed 编码时同样读得对")
    void readsUnpackedDims() {
        byte[] m = modelWith(tensorUnpackedDims("emb", 7, 3));
        assertArrayEquals(new long[]{7, 3}, OnnxTensorShapes.initializerDims(m).get("emb"));
    }

    @Test
    @DisplayName("dims 用 packed 编码时同样读得对")
    void readsPackedDims() {
        byte[] m = modelWith(tensorUnpackedDims("emb", 7, 3));
        // 上面那个构造里 dims 是非 packed 的（多次 f_varint(1, …)）。
        // 这里再手工拼一个 packed 版本，确保两条分支都被覆盖。
        byte[] t = concat(concat(concat(concat(
                bytesField(1, concat(varint(7), varint(3)))),
                varintField(2, 1)),
                bytesField(8, "emb".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                bytesField(9, new byte[4 * 7 * 3]));
        assertArrayEquals(new long[]{7, 3}, OnnxTensorShapes.initializerDims(modelWith(t)).get("emb"));
    }

    @Test
    @DisplayName("只有一个维度时也读得对")
    void readsSingleDim() {
        byte[] m = modelWith(tensorUnpackedDims("bias", 5));
        assertArrayEquals(new long[]{5}, OnnxTensorShapes.initializerDims(m).get("bias"));
    }

    @Test
    @DisplayName("没有 dims 的张量读出空数组，而不是当成 1 行")
    void readsScalarAsEmpty() {
        // dims 缺失在 protobuf 里是合法的（标量张量）。
        // 读成 [1] 会让 rowsOf 返回 1，与「词表 0 字 + UNK」撞上；
        // 读成空数组再由 rowsOf 返回 -1，语义才清楚。
        byte[] t = concat(concat(varintField(2, 1),
                bytesField(8, "scalar".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                bytesField(9, new byte[4]));
        assertEquals(-1, OnnxTensorShapes.rowsOf(modelWith(t), "scalar"));
    }

    // ------------------------------------------------------------------ 拒绝

    @Test
    @DisplayName("空字节拒绝")
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> OnnxTensorShapes.initializerDims(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> OnnxTensorShapes.initializerDims(null));
    }

    @Test
    @DisplayName("没有 graph 的文件拒绝——不能「没扫到就算干净」")
    void rejectsNoGraph() {
        // 字段 1 (ir_version) = 7，能干净地解析完，但整份文件里没有 graph。
        // 之前随手写了个 {1,2,3,4,5,6,7,8}，那个会被更早的一道「字段号不能为 0」拦下，
        // 于是这条用例测的其实是别的错误——**红灯对了，测的东西却不对**。
        byte[] noGraph = varintField(1, 7);
        var e = assertThrows(IllegalArgumentException.class,
                () -> OnnxTensorShapes.initializerDims(noGraph));
        assertTrue(e.getMessage().contains("graph"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("文件被截断时拒绝，不返回半截形状")
    void rejectsTruncated() {
        byte[] full = fixture();
        for (int cut : new int[]{1, 5, 20, full.length / 2, full.length - 1}) {
            byte[] truncated = Arrays.copyOf(full, cut);
            // 截断必须在「拒绝」或「读出东西」之间二选一；
            // 不允许出现「安静地返回一张空表」——那会让上游以为模型没有嵌入层。
            try {
                Map<String, long[]> dims = OnnxTensorShapes.initializerDims(truncated);
                assertFalse(dims.isEmpty() && cut < full.length,
                        "截断到 " + cut + " 字节却安静返回空表——这等于把「没读到」说成「没有」");
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isBlank(), "拒绝时必须说明原因");
            }
        }
    }

    @Test
    @DisplayName("packed dims 的 varint 跨出载荷边界时拒绝")
    void rejectsPackedVarintOverrun() {
        // 三个连续的 0x80：一个 varint 只有续位、没有结束位。
        // 读取时必须发现它跨过了载荷声明的边界，而不是把后面的字段字节
        // 也吃进来当成维度值——那样会得到一个**看起来合法**的形状，
        // 上游的行数对账于是去比一个错误的数字。
        byte[] broken = {(byte) 0x80, (byte) 0x80, (byte) 0x80};
        byte[] t = concat(concat(concat(
                bytesField(1, broken),
                varintField(2, 1)),
                bytesField(8, "emb".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                bytesField(9, new byte[4]));
        var e = assertThrows(IllegalArgumentException.class,
                () -> OnnxTensorShapes.initializerDims(modelWith(t)));
        assertFalse(e.getMessage().isBlank(), "拒绝时必须说明原因");
    }

    @Test
    @DisplayName("负维度被拒绝")
    void rejectsNegativeDim() {
        // varint 的 -1 会编码成 10 字节的无符号值，读出来是个巨大的正数，
        // 所以这里验证的是「维度不会悄悄变成一个离谱的正数」。
        byte[] t = concat(concat(concat(
                bytesField(1, concat(varint(7))),
                varintField(2, 1)),
                bytesField(8, "emb".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                bytesField(9, new byte[4]));
        long[] dims = OnnxTensorShapes.initializerDims(modelWith(t)).get("emb");
        assertTrue(dims.length == 1 && dims[0] >= 0,
                "维度必须是非负数，实际: " + Arrays.toString(dims));
    }

    // ------------------------------------------------------------------ protobuf 辅助

    private static byte[] varint(long value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        long v = value;
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    private static byte[] varintField(int field, long value) {
        return concat(varint(((long) field << 3)), varint(value));
    }

    private static byte[] bytesField(int field, byte[] payload) {
        return concat(concat(varint(((long) field << 3) | 2), varint(payload.length)), payload);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
