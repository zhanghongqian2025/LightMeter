package com.litemask.pdf;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.util.List;

/**
 * 一个「显示文本」算子（{@code Tj} / {@code TJ} / {@code '} / {@code "}）及其显示时的文本状态。
 *
 * <p>PDF 的文本状态是算子之间累积的全局状态：字体、字号、字间距（Tc）、词间距（Tw）、
 * 水平缩放（Tz）都可能在前面的算子里被改过。同一个字符串在不同状态下显示出来的宽度不同，
 * 所以状态必须**随算子一起冻结**下来，不能在回写时现查。
 *
 * @param elems      算子的操作数，按出现顺序：{@code byte[]} 表示字符串，{@code Double} 表示 TJ 里的间距调整数
 * @param font       当前字体（可能为 null，表示 Tf 还没出现过）
 * @param size       字号 Tf
 * @param charSpacing 字间距 Tc
 * @param wordSpacing 词间距 Tw
 * @param hScale     水平缩放 Tz，已归一化成比例（1.0 表示 Tz=100）
 * @param fontName   字体在资源字典里的名字，回写时需要用它恢复 Tf
 * @param arrayForm  是否是 {@code TJ} 数组形式（其余为 Tj 及其变体）
 * @param renderMode 文本渲染模式 Tr。3 = 不可见：扫描件上面盖的一层隐藏 OCR 文本就是它，
 *                    用户看不见，但复制、检索、抽取都能拿到。对脱敏来说这类文本最危险，
 *                    因此单独计数报出来，免得「零命中」被误读成「文档干净」。
 */
record ShowTextOp(List<Object> elems, PDFont font, float size, float charSpacing,
                  float wordSpacing, float hScale, String fontName, boolean arrayForm,
                  int renderMode) {

    /** 不可见文本（Tr = 3）。 */
    boolean invisible() {
        return renderMode == 3;
    }

    /** 字符串操作数个数（TJ 里的字符串个数）。 */
    int stringCount() {
        int n = 0;
        for (Object e : elems) {
            if (e instanceof byte[]) {
                n++;
            }
        }
        return n;
    }

    /** 把整个算子按当前状态渲染出来的总水平位移（未乘 Tz 的 Tz 已乘过）。 */
    double advance() throws IOException {
        double sum = 0;
        for (Object e : elems) {
            if (e instanceof byte[] b) {
                sum += stringAdvance(font, b, size, charSpacing, wordSpacing, hScale);
            } else if (e instanceof Double d) {
                // TJ 里的数字向左移动 -d/1000 × Tfs × Th
                sum -= d / 1000.0 * size * hScale;
            }
        }
        return sum;
    }

    /** 单个字符串在给定文本状态下的水平位移。 */
    static double stringAdvance(PDFont font, byte[] codes, float size, float charSpacing,
                                float wordSpacing, double hScale) throws IOException {
        if (font == null) {
            return 0;
        }
        double[] sum = {0};
        PdfCodes.forEachCode(font, codes, (code, consumed) -> {
            sum[0] += font.getWidth(code) / 1000.0 * size + charSpacing;
            // 规范 9.3.3：词间距只作用于单字节码 32，多字节码里的 0x20 不受影响
            if (code == 32 && !isMultiByte(font)) {
                sum[0] += wordSpacing;
            }
        });
        return sum[0] * hScale;
    }

    static boolean isMultiByte(PDFont font) {
        return font instanceof PDType0Font || font.isVertical();
    }

    /** 把字符串操作数逐码解码成字符，供规则引擎匹配。 */
    String decodedText() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Object e : elems) {
            if (e instanceof byte[] b) {
                sb.append(decodeString(font, b));
            }
        }
        return sb.toString();
    }

    static String decodeString(PDFont font, byte[] codes) throws IOException {
        if (font == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // 终止条件挂在「还剩几个字节」上——见 PdfCodes 的说明：
        // 按「码位是否为负」判断的话，Type0 字体在输入耗尽后会永远返回 0，循环停不下来。
        PdfCodes.forEachCode(font, codes, (code, consumed) -> {
            String u = font.toUnicode(code);
            sb.append(u == null || u.isEmpty() ? UNDECODABLE : u);
        });
        return sb.toString();
    }

    /** 码位无法映射到 Unicode 时使用的占位符。 */
    static final String UNDECODABLE = "�";
}
