package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OnnxRunner} 的测试，跑在真实 ONNX Runtime 上。
 *
 * <p>夹具是 {@code tools/make_ner_fixture.py} 生成的一层 Gather 模型：
 * {@code out[0,t,:] = table[ids[0,t],:]}。下面 {@link #expectedRow(int)} 里的数值
 * 是<b>在测试里独立写死的</b>，没有调用生成器的任何代码——
 * 否则就变成「用被测代码验被测代码」。
 */
class OnnxRunnerTest {

    private static final String INPUT = "ids";
    private static final String OUTPUT = "logits";
    private static final float HI = 5.0f;
    private static final float LO = -5.0f;

    /** 标签顺序：O / B-PER / I-PER / B-ORG / I-ORG。 */
    private static float[] expectedRow(int id) {
        float[] row = {LO, LO, LO, LO, LO};
        if (id == 0 || id == 1) {
            row[0] = HI;            // 普通字符
        } else if (id == 2) {
            row[1] = HI;            // B-PER
        } else if (id == 3 || id == 4) {
            row[2] = HI;            // I-PER
        } else if (id == 5) {
            row[3] = HI;            // B-ORG
        } else if (id == 6 || id == 7 || id == 8) {
            row[4] = HI;            // I-ORG
        } else {
            throw new IllegalArgumentException("夹具里没有 id=" + id);
        }
        return row;
    }

    private static byte[] fixture() {
        try (InputStream in = OnnxRunnerTest.class.getResourceAsStream("/ner-fixture.onnx")) {
            if (in == null) {
                throw new IllegalStateException("找不到测试夹具 /ner-fixture.onnx");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("夹具能加载，逐位置分数与写死的表逐元素一致")
    void logitsMatchExpectedTable() throws Exception {
        try (OnnxRunner runner = OnnxRunner.open(fixture(), INPUT, OUTPUT)) {
            long[] ids = {1, 2, 3, 4, 0, 5, 6, 7, 8, 0, 0, 1};
            float[][] got = runner.run(ids);

            assertEquals(ids.length, got.length, "输出行数必须等于输入长度");
            for (int t = 0; t < ids.length; t++) {
                assertArrayEquals(expectedRow((int) ids[t]), got[t],
                        "第 " + t + " 行（id=" + ids[t] + "）与夹具表不符");
            }
        }
    }

    @Test
    @DisplayName("同一个 id 序列可以重复跑，结果一致（会话无隐藏状态）")
    void repeatable() throws Exception {
        try (OnnxRunner runner = OnnxRunner.open(fixture(), INPUT, OUTPUT)) {
            long[] ids = {2, 3, 4};
            float[][] first = runner.run(ids);
            float[][] second = runner.run(ids);
            for (int t = 0; t < ids.length; t++) {
                assertArrayEquals(first[t], second[t], "第 " + t + " 行两次结果不一致");
            }
        }
    }

    @Test
    @DisplayName("长度 1 的窗口也要能跑（滑窗末尾会切出很短的块）")
    void singleTokenWindow() throws Exception {
        try (OnnxRunner runner = OnnxRunner.open(fixture(), INPUT, OUTPUT)) {
            float[][] got = runner.run(new long[] {2});
            assertEquals(1, got.length);
            assertArrayEquals(expectedRow(2), got[0]);
        }
    }

    @Test
    @DisplayName("空 id 序列返回空结果，而不是抛异常")
    void emptyInput() throws Exception {
        try (OnnxRunner runner = OnnxRunner.open(fixture(), INPUT, OUTPUT)) {
            assertEquals(0, runner.run(new long[0]).length);
        }
    }

    @Test
    @DisplayName("通道数在首次推理后才可知")
    void channelsKnownAfterRun() throws Exception {
        try (OnnxRunner runner = OnnxRunner.open(fixture(), INPUT, OUTPUT)) {
            assertEquals(-1, runner.numChannels(), "还没推理过就应该说不知道，而不是假装是 0");
            runner.run(new long[] {1});
            assertEquals(5, runner.numChannels());
        }
    }

    @Test
    @DisplayName("引用外部权重的模型被自包含闸门拦下")
    void externalDataRejected() throws Exception {
        byte[] bytes = modelWithExternalData();
        var e = assertThrows(OnnxSelfContained.ExternalDataRejected.class,
                () -> OnnxRunner.open(bytes, INPUT, OUTPUT));
        assertTrue(e.getMessage().contains("external") || e.getMessage().contains("外部"),
                "错误信息要说清是外部权重，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("输出名不存在时报错，并带上那个名字")
    void unknownOutputName() throws Exception {
        try (OnnxRunner runner = OnnxRunner.open(fixture(), INPUT, "没有这个")) {
            OrtException e = assertThrows(OrtException.class, () -> runner.run(new long[] {1}));
            assertTrue(e.getMessage().contains("没有这个"), "实际：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("输入名不存在时 ORT 报错（不是我们的空指针）")
    void unknownInputName() throws Exception {
        try (OnnxRunner runner = OnnxRunner.open(fixture(), "没有这个", OUTPUT)) {
            assertThrows(OrtException.class, () -> runner.run(new long[] {1}));
        }
    }

    @Test
    @DisplayName("空字节、空名字在进 ORT 之前就被拒")
    void badArgumentsRejectedEarly() {
        assertThrows(IllegalArgumentException.class, () -> OnnxRunner.open(new byte[0], INPUT, OUTPUT));
        assertThrows(IllegalArgumentException.class, () -> OnnxRunner.open(fixture(), "", OUTPUT));
        assertThrows(IllegalArgumentException.class, () -> OnnxRunner.open(fixture(), INPUT, ""));
        assertThrows(NullPointerException.class, () -> OnnxRunner.open(null, INPUT, OUTPUT));
    }

    @Test
    @DisplayName("关闭后再推理报 IllegalStateException")
    void useAfterClose() throws Exception {
        OnnxRunner runner = OnnxRunner.open(fixture(), INPUT, OUTPUT);
        runner.close();
        runner.close();   // 幂等
        assertThrows(IllegalStateException.class, () -> runner.run(new long[] {1}));
    }

    @Test
    @DisplayName("不是 ONNX 的字节被判为「文件损坏」，而不是「不接受这种模型」")
    void garbageRejectedAsCorrupt() {
        // 两种失败必须可分辨（M3-1b 的设计）：
        //   ExternalDataRejected = 模型是我们不接受的那一类（可以引导用户换包）
        //   IllegalArgumentException = 文件本身损坏（是数据问题，重下也没用）
        // 垃圾字节连 protobuf 都解析不了，属于后者——在进 ORT 之前就挡住了。
        byte[] junk = "这不是一个 ONNX 模型".getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(IllegalArgumentException.class, () -> OnnxRunner.open(junk, INPUT, OUTPUT));
        assertTrue(!(e instanceof OnnxSelfContained.ExternalDataRejected),
                "损坏文件不该被当成「不接受这种模型」");
        assertTrue(e.getMessage().contains("损坏"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("格式合法但内容不是模型：交给 ORT 报错")
    void validProtobufButNotAModel() {
        // 与上一条的区别：这一份是「结构完整、只是没有模型该有的内容」，
        // 自包含扫描器放行（它只管外部数据），由 ORT 报错。
        byte[] emptyGraph = new byte[] {0x08, 0x07, 0x3A, 0x04, 0x12, 0x02, 0x67, 0x00};
        assertThrows(OrtException.class, () -> OnnxRunner.open(emptyGraph, INPUT, OUTPUT));
    }

    @Test
    @DisplayName("夹具确实是自包含的（闸门没有把好模型误杀）")
    void fixturePassesSelfContainedCheck() {
        // 这条是防止「闸门写太严 → 所有模型都加载不了」那道防线失效。
        assertNotEquals(0, fixture().length);
        OnnxSelfContained.assertSelfContained(fixture());
    }

    // ---- 造一个带 external_data 的最小模型 ----

    private static void putVarint(ByteBuffer b, long v) {
        while (true) {
            byte x = (byte) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                b.put((byte) (x | 0x80));
            } else {
                b.put(x);
                return;
            }
        }
    }

    private static void putBytes(ByteBuffer b, int field, byte[] value) {
        putVarint(b, ((long) field << 3) | 2);
        putVarint(b, value.length);
        b.put(value);
    }

    private static byte[] modelWithExternalData() {
        // TensorProto{ dims=[1] data_type=FLOAT external_data=[{key:"location", value:"w.bin"}] }
        java.io.ByteArrayOutputStream tensor = new java.io.ByteArrayOutputStream();
        ByteBuffer t = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        putVarint(t, (1L << 3) | 0);
        putVarint(t, 1);
        putVarint(t, (2L << 3) | 0);
        putVarint(t, 1);   // FLOAT
        putBytes(t, 13, "location".getBytes(StandardCharsets.UTF_8));
        putBytes(t, 8, "w".getBytes(StandardCharsets.UTF_8));
        byte[] tensorBytes = java.util.Arrays.copyOf(t.array(), t.position());
        tensor.write(tensorBytes, 0, tensorBytes.length);

        // GraphProto{ name, initializer=[上面那个 TensorProto] }
        ByteBuffer g = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        putBytes(g, 2, "g".getBytes(StandardCharsets.UTF_8));
        putBytes(g, 5, tensorBytes);

        // ModelProto{ ir_version=7, graph }
        ByteBuffer m = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        putVarint(m, (1L << 3) | 0);
        putVarint(m, 7);
        putBytes(m, 7, java.util.Arrays.copyOf(g.array(), g.position()));
        return java.util.Arrays.copyOf(m.array(), m.position());
    }
}
