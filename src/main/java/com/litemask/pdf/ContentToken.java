package com.litemask.pdf;

import java.util.Arrays;

/**
 * 内容流中的一个词法单元，带精确的字节区间。
 *
 * <p>为什么要自己分词而不用 PDFBox 的 {@code PDFStreamParser}：PDFStreamParser 不保留词法单元在
 * 内容流中的起止字节。想改写内容流，只有三条路——(a) 重新编码整条流，(b) 自己拿到偏移做定点替换，
 * (c) 交给 PDFBox 的文本引擎再让它重新生成流。(a) 会丢掉内联图像的二进制数据与原始数字写法，
 * (c) 等于把「谁在什么位置被替换」的账全交给黑盒，没法精确记账，也没法做对照实验。
 * (b) 是唯一能让「未命中的字节逐字节保持原样」成立的方案。
 *
 * @param kind 词法类型
 * @param start 起始字节偏移（含）
 * @param end   结束字节偏移（不含）
 * @param text  算子关键字 / 数字字面量 / 名字（{@code #xx} 已解码），其余类型为 null
 * @param data  字符串字面量解码后的内容字节（转义与十六进制均已还原），其余类型为 null
 */
record ContentToken(Kind kind, int start, int end, String text, byte[] data) {

    enum Kind {
        OPERATOR, NUMBER, STRING, NAME,
        ARRAY_OPEN, ARRAY_CLOSE, DICT_OPEN, DICT_CLOSE, INLINE_IMAGE
    }

    boolean isOperator() {
        return kind == Kind.OPERATOR;
    }

    /**
     * 是否会终止操作数累积。内联图像整体是一个词法单元，它后面的字节是图像数据而不是操作数，
     * 因此必须和算子一样清空累积栈——否则图像数据里的字节会被当成上一个算子的参数。
     */
    boolean isBoundary() {
        return kind == Kind.OPERATOR || kind == Kind.INLINE_IMAGE;
    }

    /** 原始字节。重建内容流时，未改动的单元一律原样复制。 */
    byte[] raw(byte[] content) {
        return Arrays.copyOfRange(content, start, end);
    }

    @Override
    public String toString() {
        return kind + "[" + start + "," + end + ")"
                + (text != null ? " '" + text + "'" : "")
                + (data != null ? " " + data.length + "B" : "");
    }
}
