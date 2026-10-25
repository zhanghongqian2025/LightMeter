package com.litemask.ner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「模型必须自包含」这道关卡的验证。
 *
 * <p><b>最关键的一条是 {@link #权重里长得像字段号的字节不会被误判}</b>。
 * 权重就是几千字节的裸数据，里面出现 {@code 0x6A}（= 13&lt;&lt;3|2，external_data 的标签）
 * 与 {@code 0x70}（= 14&lt;&lt;3|0，data_location 的标签）几乎是必然的。
 * 一个靠「扫字节找关键字」的检查会在每一个真实模型上误报，
 * 而误报的代价是所有模型都加载不了——那道闸门会变成「一律拒绝」，等于没有。
 */
class OnnxSelfContainedTest {

    // ------------------------------------------------------------------ 最小合法模型

    @Test
    @DisplayName("空的 graph 也算合法模型")
    void 最小合法模型() {
        byte[] model = 模型(
                bytesField(7, 图()));
        assertDoesNotThrow(() -> OnnxSelfContained.assertSelfContained(model));
    }

    @Test
    @DisplayName("自包含的权重张量可以通过")
    void 自包含张量() {
        byte[] 张量 = concat(
                varintField(1, 2),                  // dims = [2]
                varintField(2, 1),                  // data_type = FLOAT
                bytesField(8, "w1".getBytes()),            // name
                bytesField(9, new byte[4096]));            // raw_data（权重本体）
        byte[] model = 模型(bytesField(7, 图(bytesField(5, 张量))));

        assertDoesNotThrow(() -> OnnxSelfContained.assertSelfContained(model),
                "自包含权重是正常情况，不该被拦");
    }

    // ------------------------------------------------------------------ 必须拦住的

    @Test
    @DisplayName("引用 external_data 的模型被拒")
    void 拦externalData() {
        byte[] 外部条目 = concat(bytesField(1, "location".getBytes()), bytesField(2, "weights.bin".getBytes()));
        byte[] 张量 = concat(
                varintField(2, 1),
                bytesField(13, 外部条目));
        byte[] model = 模型(bytesField(7, 图(bytesField(5, 张量))));

        OnnxSelfContained.ExternalDataRejected e = assertThrows(
                OnnxSelfContained.ExternalDataRejected.class,
                () -> OnnxSelfContained.assertSelfContained(model));
        assertTrue(e.getMessage().contains("外部数据"), e.getMessage());
    }

    @Test
    @DisplayName("data_location=EXTERNAL 的模型被拒")
    void 拦dataLocation() {
        byte[] 张量 = concat(
                varintField(2, 1),
                varintField(14, 1));
        byte[] model = 模型(bytesField(7, 图(bytesField(5, 张量))));

        assertThrows(OnnxSelfContained.ExternalDataRejected.class,
                () -> OnnxSelfContained.assertSelfContained(model));
    }

    @Test
    @DisplayName("data_location=DEFAULT（0）不算外部数据")
    void dataLocation零值放行() {
        byte[] 张量 = concat(
                varintField(2, 1),
                varintField(14, 0));
        byte[] model = 模型(bytesField(7, 图(bytesField(5, 张量))));

        assertDoesNotThrow(() -> OnnxSelfContained.assertSelfContained(model));
    }

    @Test
    @DisplayName("稀疏初始化里的外部数据同样被拒")
    void 拦稀疏初始化() {
        byte[] 稀疏 = bytesField(13, concat(bytesField(1, "location".getBytes()), bytesField(2, "s.bin".getBytes())));
        byte[] model = 模型(bytesField(7, 图(bytesField(15, 稀疏))));

        assertThrows(OnnxSelfContained.ExternalDataRejected.class,
                () -> OnnxSelfContained.assertSelfContained(model),
                "sparse_initializer 也是权重张量，不能只看普通 initializer");
    }

    // ------------------------------------------------------------------ 误报这一面

    @Test
    @DisplayName("权重里长得像字段号的字节不会被误判")
    void 权重不误报() {
        // 手工构造一段「权重」：刻意塞进 13 和 14 的标签字节，加上随机的 32KB 权重。
        // 若实现是按字节搜索，这一段必然误报；按路径走就整段跳过。
        Random rnd = new Random(12345L);
        byte[] weights = new byte[32768];
        rnd.nextBytes(weights);
        weights[0] = 0x6A;   // 13<<3|2  external_data 的标签
        weights[1] = 0x05;
        weights[7] = 0x70;   // 14<<3|0  data_location 的标签
        weights[8] = 0x01;

        byte[] 张量 = concat(
                varintField(2, 1),
                bytesField(9, weights));
        byte[] model = 模型(bytesField(7, 图(bytesField(5, 张量))));

        assertDoesNotThrow(() -> OnnxSelfContained.assertSelfContained(model),
                "权重里的巧合字节被当成了字段——这道闸门会把所有真实模型都拒掉");
    }

    @Test
    @DisplayName("图节点的属性里出现同样字节也不误报")
    void 节点属性不误报() {
        // GraphProto.node 是字段 1，这里也塞进标签字节；它是嵌套结构，
        // 但我们只关心 initializer，别的字段一律跳过。
        byte[] 节点 = concat(
                bytesField(1, "conv_0".getBytes()),
                bytesField(2, "Conv".getBytes()),
                bytesField(5, new byte[]{0x6A, 0x02, 0x01, 0x02}));
        byte[] model = 模型(bytesField(7, 图(bytesField(1, 节点))));

        assertDoesNotThrow(() -> OnnxSelfContained.assertSelfContained(model));
    }

    // ------------------------------------------------------------------ 解析不了就拒绝

    @Test
    @DisplayName("文件损坏一律拒绝，不能当成「扫描通过」")
    void 损坏即拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> OnnxSelfContained.assertSelfContained(new byte[0]), "空文件");
        assertThrows(IllegalArgumentException.class,
                () -> OnnxSelfContained.assertSelfContained(null), "null");
        assertThrows(IllegalArgumentException.class,
                () -> OnnxSelfContained.assertSelfContained(new byte[]{0x0A, 0x7F, 0x01}),
                "长度前缀说有 127 字节内容，实际只有 1 字节");
        assertThrows(IllegalArgumentException.class,
                () -> OnnxSelfContained.assertSelfContained(new byte[]{(byte) 0x80}),
                "varint 只有续位没有结束位");
        assertThrows(IllegalArgumentException.class,
                () -> OnnxSelfContained.assertSelfContained(new byte[]{0x0F, 1, 2, 3, 4}),
                "已废弃的 group 编码");
    }

    @Test
    @DisplayName("没有 graph 的 ModelProto 被拒")
    void 无graph() {
        // 只放一个 ir_version（字段 1），没有 graph。
        // 「没扫到外部数据」不等于「干净」——它压根不是模型。
        assertThrows(IllegalArgumentException.class,
                () -> OnnxSelfContained.assertSelfContained(模型(varintField(1, 8))));
    }

    @Test
    @DisplayName("报错的类型要分得清：是「拒绝这种模型」还是「文件坏了」")
    void 异常类型可分辨() {
        byte[] 外部 = concat(varintField(2, 1), bytesField(13, new byte[]{0x0A, 0x01, 0x61}));
        assertEquals(OnnxSelfContained.ExternalDataRejected.class,
                assertThrows(RuntimeException.class, () -> OnnxSelfContained.assertSelfContained(
                        模型(bytesField(7, 图(bytesField(5, 外部)))))).getClass(),
                "外部数据是「我们不接受这种模型」，与「文件损坏」要能被上层区别对待："
                        + "前者可以提示用户换一个模型包，后者是文件损坏");
    }

    // ------------------------------------------------------------------ 手搓 protobuf

    private static byte[] 模型(byte[] body) {
        return body;
    }

    private static byte[] 图(byte[]... body) {
        return concat(body);
    }

    private static byte[] varint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long x = v;
        while (true) {
            if ((x & ~0x7FL) == 0) {
                out.write((int) x);
                return out.toByteArray();
            }
            out.write((int) ((x & 0x7F) | 0x80));
            x >>>= 7;
        }
    }

    private static byte[] varintField(int number, long value) {
        return concat(tag(number, 0), varint(value));
    }

    private static byte[] bytesField(int number, byte[] content) {
        return concat(tag(number, 2), varint(content.length), content);
    }

    private static byte[] tag(int number, int wireType) {
        return varint(((long) number << 3) | wireType);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
