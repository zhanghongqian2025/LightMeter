package com.litemask.pdf;

import org.apache.pdfbox.pdmodel.font.PDFont;

import java.util.ArrayList;
import java.util.List;

/**
 * 把内容流的 token 序列解释成「按显示顺序排列的 {@link ShowTextOp}」。
 *
 * <p>这一层只回答一个问题：**用户按顺序看到的文字，是哪些字符串算子拼起来的。**
 * 它不关心字画在哪个坐标——版面由 {@link TextAdvanceSimulator} 单独校验。
 * 拆成两层的理由是：解码正确性与版面正确性会独立出错，分开测才不会互相掩盖。
 *
 * <p>槽（slot）粒度 = 一个 {@link ShowTextOp} 里的每个字符串操作数。之所以切到字符串算子
 * 而不是字符，是因为一个字符串可能被替换成「原字体前缀 + 令牌字体 + 原字体后缀」三段，
 * 字符粒度会让重建逻辑没法收敛。
 */
final class ContentInterpreter {

    /** 从资源名解析字体。解析失败必须暴露出来，不能悄悄返回 null。 */
    @FunctionalInterface
    interface FontResolver {
        PDFont font(String resourceName) throws java.io.IOException;
    }

    private ContentInterpreter() {
    }

    /**
     * @param tokens   整条内容流的词法单元
     * @param resolver 字体解析器
     * @return 按出现顺序排列的显示文本算子
     * @throws ContentScanner.MalformedPdfException 遇到「没有 Tf 就显示文本」这类自相矛盾的内容流
     */
    static List<ShowTextOp> interpret(List<ContentToken> tokens, FontResolver resolver)
            throws java.io.IOException {
        return interpretWithBreaks(tokens, resolver).ops();
    }

    /**
     * @param ops             按显示顺序排列的文本算子
     * @param repositionBefore 与 {@code ops} 等长：该算子之前是否出现过定位算子（Tm、Td、TD、T 星号、BT、ET）
     */
    record Interpretation(List<ShowTextOp> ops, boolean[] repositionBefore) {
    }

    /**
     * 解释内容流，并标出每个文本算子之前有没有重新定位。
     *
     * <p>这个标记决定「哪些算子可以拼在一起匹配」。PDF 里一段话常被切成多个 Tj/TJ，
     * 只有**同一行内、没有重新定位**的那些才该直接首尾相接；跨行硬拼会造出原文里
     * 根本不存在的字符串——例如上一行结尾的 {@code zhang@example.com} 拼上下一行开头的
     * {@code Phone}，邮箱规则末尾的负向断言 {@code (?![A-Za-z0-9])} 就会失配，
     * 于是真邮箱被漏掉。这种漏脱比误报难发现得多。
     */
    static Interpretation interpretWithBreaks(List<ContentToken> tokens, FontResolver resolver)
            throws java.io.IOException {
        List<ShowTextOp> ops = new ArrayList<>();
        List<Boolean> breaks = new ArrayList<>();
        boolean repositioned = true; // 流开头即视为新块
        List<ContentToken> operands = new ArrayList<>();

        PDFont font = null;
        String fontName = null;
        float size = 0f;
        float charSpacing = 0f;
        float wordSpacing = 0f;
        float hScale = 1f;
        int renderMode = 0;

        for (ContentToken t : tokens) {
            if (!t.isBoundary()) {
                operands.add(t);
                continue;
            }
            if (!t.isOperator()) {
                operands.clear();
                continue;
            }
            switch (t.text()) {
                case "Tf" -> {
                    if (operands.size() >= 2
                            && operands.get(operands.size() - 2).kind() == ContentToken.Kind.NAME
                            && operands.get(operands.size() - 1).kind() == ContentToken.Kind.NUMBER) {
                        fontName = operands.get(operands.size() - 2).text();
                        size = Float.parseFloat(operands.get(operands.size() - 1).text());
                        font = resolver.font(fontName);
                    }
                }
                case "Tc" -> charSpacing = lastNumber(operands, charSpacing);
                case "Tw" -> wordSpacing = lastNumber(operands, wordSpacing);
                case "Tz" -> hScale = lastNumber(operands, 100f) / 100f;
                case "Tr" -> renderMode = (int) lastNumber(operands, renderMode);
                // Tm / Td / TD / T* 只改位置不改字符，但它们划开了「可以拼接成一句」的范围
                case "Tm", "Td", "TD", "T*", "BT", "ET" -> repositioned = true;
                case "Tj", "'", "\"" -> {
                    ShowTextOp op = singleStringOp(operands, font, fontName, size,
                            charSpacing, wordSpacing, hScale, renderMode);
                    if (op != null) {
                        ops.add(op);
                        breaks.add(repositioned);
                        repositioned = false;
                    }
                }
                case "TJ" -> {
                    ShowTextOp op = arrayOp(operands, font, fontName, size,
                            charSpacing, wordSpacing, hScale, renderMode);
                    if (op != null) {
                        ops.add(op);
                        breaks.add(repositioned);
                        repositioned = false;
                    }
                }
                default -> {
                    // 其余算子不产生文本
                }
            }
            operands.clear();
        }
        boolean[] arr = new boolean[breaks.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = breaks.get(i);
        }
        return new Interpretation(List.copyOf(ops), arr);
    }

    private static ShowTextOp singleStringOp(List<ContentToken> operands, PDFont font, String fontName,
                                             float size, float cs, float ws, float hs, int renderMode) {
        ContentToken str = null;
        for (int i = operands.size() - 1; i >= 0; i--) {
            if (operands.get(i).kind() == ContentToken.Kind.STRING) {
                str = operands.get(i);
                break;
            }
            if (operands.get(i).kind() == ContentToken.Kind.OPERATOR) {
                return null;
            }
        }
        return str == null ? null
                : new ShowTextOp(List.of(str.data()), font, size, cs, ws, hs, fontName, false, renderMode);
    }

    private static ShowTextOp arrayOp(List<ContentToken> operands, PDFont font, String fontName,
                                      float size, float cs, float ws, float hs, int renderMode) {
        int open = -1;
        for (int i = operands.size() - 1; i >= 0; i--) {
            if (operands.get(i).kind() == ContentToken.Kind.ARRAY_OPEN) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return null;
        }
        List<Object> elems = new ArrayList<>();
        for (int i = open + 1; i < operands.size(); i++) {
            ContentToken e = operands.get(i);
            if (e.kind() == ContentToken.Kind.ARRAY_CLOSE) {
                break;
            }
            if (e.kind() == ContentToken.Kind.STRING) {
                elems.add(e.data());
            } else if (e.kind() == ContentToken.Kind.NUMBER) {
                elems.add(Double.parseDouble(e.text()));
            }
        }
        if (elems.isEmpty()) {
            return null;
        }
        return new ShowTextOp(List.copyOf(elems), font, size, cs, ws, hs, fontName, true, renderMode);
    }

    private static float lastNumber(List<ContentToken> operands, float fallback) {
        for (int i = operands.size() - 1; i >= 0; i--) {
            ContentToken e = operands.get(i);
            if (e.kind() == ContentToken.Kind.NUMBER) {
                return Float.parseFloat(e.text());
            }
            if (e.kind() == ContentToken.Kind.OPERATOR) {
                break;
            }
        }
        return fallback;
    }
}
