package com.litemask.ner;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.LongBuffer;
import java.util.Map;
import java.util.Objects;

/**
 * ONNX 推理会话的薄封装：只负责「喂 id 序列、拿回逐位置分数」。
 *
 * <p>刻意做得薄——切窗、分词、解码都不在这里。
 * 原因是这几件事各有各的失败模式，各自需要单独的测试标尺；
 * 把它们塞进同一个类里，就会出现「测试只覆盖到 happy path」的组合爆炸。
 *
 * <p><b>输入输出名不写死</b>：不同导出器给出的名字不一样（PyTorch 默认是 {@code input_ids}，
 * 手工导出的可能叫 {@code ids}），所以由模型包的清单指定。
 *
 * <p><b>加载前必过自包含校验</b>：{@link OnnxSelfContained} 是 M3-1b 做的闸门，
 * 而 ORT 会照着 {@code external_data} 的路径去打开本机文件。
 * 本类<b>没有绕过它的入口</b>——{@link #open} 是唯一的加载路径。
 */
public final class OnnxRunner implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final String outputName;
    private int numChannels = -1;
    private boolean closed;

    private OnnxRunner(OrtEnvironment env, OrtSession session,
                       String inputName, String outputName) {
        this.env = env;
        this.session = session;
        this.inputName = inputName;
        this.outputName = outputName;
    }

    /**
     * 加载一个模型。
     *
     * @param onnx       模型字节。必须是<b>自包含单文件</b>：出现 external data 一律拒绝。
     * @param inputName  输入张量名
     * @param outputName 输出张量名
     * @throws OnnxSelfContained.ExternalDataRejected 模型引用了外部权重文件（不接受这种模型）
     * @throws OrtException                            模型无法被 ORT 加载（文件损坏或不受支持）
     */
    public static OnnxRunner open(byte[] onnx, String inputName, String outputName)
            throws OrtException {
        Objects.requireNonNull(onnx, "onnx");
        Objects.requireNonNull(inputName, "inputName");
        Objects.requireNonNull(outputName, "outputName");
        if (onnx.length == 0) {
            throw new IllegalArgumentException("模型字节为空");
        }
        if (inputName.isEmpty() || outputName.isEmpty()) {
            throw new IllegalArgumentException("输入/输出名不能为空");
        }

        // 先过自包含闸门，再交给 ORT。顺序不能反。
        OnnxSelfContained.assertSelfContained(onnx);

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession session = env.createSession(onnx, new OrtSession.SessionOptions());
        return new OnnxRunner(env, session, inputName, outputName);
    }

    /** 输出通道数；首次推理后才知道，未推理过返回 -1。 */
    public int numChannels() {
        return numChannels;
    }

    /**
     * 读出一个**与输入内容无关的常量输出**（典型是 CRF 转移矩阵）。
     *
     * <p>模型把它做成第二个图输出（由 {@code Identity} 从一个初始化张量引出），
     * 而不是由计算得出。这类输出用「跑一次推理再看第二个输出」拿最省事，
     * 也不需要第二套 protobuf 解析。
     *
     * <p><b>用长度为 1 的输入去取常量</b>：CRF 与卷积的结果无关，但短输入仍要能跑通整个图，
     * 否则这里就成了「模型支不支持单字输入」的额外约束。
     * 实测本项目导出的模型在 1 个 id 下正常返回 {@code emission[1,1,C]} 与 {@code transition[C][C]}。
     *
     * @return {@code [R][C]} 的二维矩阵
     * @throws OrtException 该输出不存在、不是张量、或不是二维
     */
    public float[][] readConstantOutput(String name) throws OrtException {
        if (closed) {
            throw new IllegalStateException("会话已关闭");
        }
        Objects.requireNonNull(name, "name");
        // id 0 是 UNK：任何词表里都存在的语义，且不依赖具体字符。
        java.util.Map<String, float[][]> all = runAll(new long[]{0L});
        float[][] out = all.get(name);
        if (out == null) {
            // 之前这里直接 .get(name) 之后就去读 m.length，名字不存在时是
            // 一个 NullPointerException，现场读起来像「模型包内部坏了」，
            // 而实际只是清单里写了一个模型没有的输出名。列出现在有的输出名，
            // 错误信息就能直接对上清单那一行。
            throw new OrtException("模型没有名为 " + name + " 的输出；实际有："
                    + (all.isEmpty() ? "（一个都没有）" : all.keySet()));
        }
        return out;
    }

    /**
     * 跑一次推理并返回<b>全部</b>输出，按名字索引。
     *
     * <p>与 {@link #run} 分开而不是合并：常规路径每次窗口都要调用，
     * 多建一个 Map 与一次名字查找在这个量级上是白付的成本。
     */
    private java.util.Map<String, float[][]> runAll(long[] ids) throws OrtException {
        long[] shape = {1L, (long) ids.length};
        OnnxTensor input = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
        try (OrtSession.Result result = session.run(Map.of(inputName, input))) {
            java.util.Map<String, float[][]> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, OnnxValue> e : result) {
                Object raw = e.getValue().getValue();
                if (raw instanceof float[][] m) {
                    out.put(e.getKey(), m);
                } else if (raw instanceof float[][][] batch && batch.length == 1) {
                    out.put(e.getKey(), batch[0]);
                } else {
                    throw new OrtException("输出 " + e.getKey() + " 不是二维矩阵，实际类型 "
                            + e.getValue().getType());
                }
            }
            return out;
        } finally {
            input.close();
        }
    }

    /**
     * 对一段 id 序列跑一次推理。
     *
     * @param ids 长度 T 的 id 序列（<b>码点</b>数，不是 UTF-16 单元数）
     * @return {@code [T][C]} 的逐位置分数。C 是标签通道数。
     */
    public float[][] run(long[] ids) throws OrtException {
        if (closed) {
            throw new IllegalStateException("会话已关闭");
        }
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return new float[0][];
        }

        // 形状 [1, T]：单条样本、变长时间维。模型清单里的动态维就是按这个来的。
        long[] shape = {1L, (long) ids.length};
        OnnxTensor input = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
        try (OrtSession.Result result = session.run(Map.of(inputName, input))) {
            OnnxValue value = result.get(outputName)
                    .orElseThrow(() -> new OrtException("模型没有输出名为 " + outputName + " 的张量"));
            if (value.getType() != OnnxValue.OnnxValueType.ONNX_TYPE_TENSOR) {
                throw new OrtException("输出不是张量，实际类型 " + value.getType());
            }
            Object raw = ((OnnxTensor) value).getValue();
            float[][] logits = asMatrix(raw);
            validate(logits, ids.length);
            if (numChannels < 0) {
                numChannels = logits[0].length;
            }
            return logits;
        } finally {
            input.close();
        }
    }

    /**
     * 把 ORT 的返回值收敛成 {@code [T][C]}。
     *
     * <p><b>这里的 rank 不是我们能假设的</b>：三维张量 {@code [1,T,C]} 在 Java 侧
     * {@code getValue()} 给的是 {@code float[][][]}，而不是 {@code float[][]}。
     * （同一份模型在 Python 侧 {@code .shape} 明确是 {@code (1,12,5)}，
     * 所以在 Python 里调通不代表 Java 侧能直接强转。）
     *
     * <p>同时兼容没有 batch 维的 {@code [T][C]} 模型——那类模型导出时省了一维，
     * 行为正确，只是形状声明不同，不该因此拒绝。
     */
    private static float[][] asMatrix(Object raw) throws OrtException {
        if (raw instanceof float[][][] batch) {
            if (batch.length != 1) {
                throw new OrtException("一次只应推理 1 条，但输出有 " + batch.length + " 条");
            }
            return batch[0];
        }
        if (raw instanceof float[][] flat) {
            return flat;
        }
        throw new OrtException("输出既不是 [1,T][C] 也不是 [T][C]，实际是 " + describe(raw));
    }

    private static String describe(Object raw) {
        if (raw instanceof Object[] o) {
            return o.length + " 维数组";
        }
        return raw == null ? "null" : raw.getClass().getName();
    }

    /**
     * 校验输出形状与输入对得上。
     *
     * <p>形状不对时如果直接交给解码器，越界会表现为一个毫无线索的
     * {@code ArrayIndexOutOfBoundsException}。这里提前失败并把实际形状打出来。
     */
    private static void validate(float[][] logits, int expectedT) throws OrtException {
        if (logits.length != expectedT) {
            throw new OrtException("推理结果的序列长度与输入不符：输入 " + expectedT
                    + " 个 id，返回 " + logits.length + " 行");
        }
        for (int t = 0; t < logits.length; t++) {
            if (logits[t] == null || logits[t].length == 0) {
                throw new OrtException("第 " + t + " 行的分数为空或长度为 0");
            }
            for (float v : logits[t]) {
                if (Float.isNaN(v)) {
                    throw new OrtException("第 " + t + " 行出现 NaN，说明模型里有未初始化的参数");
                }
            }
        }
        int c = logits[0].length;
        for (int t = 1; t < logits.length; t++) {
            if (logits[t].length != c) {
                throw new OrtException("各行通道数不一致：第 0 行 " + c
                        + "，第 " + t + " 行 " + logits[t].length);
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            session.close();
        } catch (OrtException ignored) {
            // 关闭失败不影响正确性：会话已经不用了，且这里没有可上报的对象。
        }
    }
}
