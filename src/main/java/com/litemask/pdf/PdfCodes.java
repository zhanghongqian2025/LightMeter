package com.litemask.pdf;

import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 逐码位遍历一个 PDF 字符串操作数。
 *
 * <p>单独抽出来，是因为这个循环有一个**看起来不像 bug 的 bug**：判断终止要挂在
 * 「输入还剩多少字节」上，不能挂在「读出来的码位是否为负」上。
 *
 * <p>PDFBox 3.x 的 {@code PDType0Font.readCode(InputStream)} 在输入耗尽时返回的是
 * <b>0</b> 而不是 -1。于是 {@code while ((code = readCode(in)) >= 0)} 在耗尽后仍然成立，
 * 又没有新字符可解码，{@code StringBuilder} 一直涨到 OOM。
 * Type0/CID 字体是中文字体的唯一形态，所以**任何真实的中文 PDF 都会在这里把进程撑爆**，
 * 而只用标准 14 字体的拉丁夹具永远走不到这条路径——这是一个只在真实文件上才会现形的雷。
 *
 * <p>两层保险：
 * <ol>
 *   <li>主条件 {@code in.available() > 0}，输入一空就停；</li>
 *   <li>兜底：若某次读取**一个字节都没消费**，说明字体不认这段输入，立刻停。
 *       没有这层，遇到不认识的编码就会退化成上一条那种空转。</li>
 * </ol>
 *
 * <p>两道守卫互为冗余：去掉任何一道，另一道都还能收敛。所以「单独去掉一道」的变异
 * 在行为上观察不到差别，能暴露问题的只有把两道一起去掉的整体变异——
 * 而那会直接把 JVM 撑爆，表现为**进程被打死**而不是某条断言变红。
 * 对照实验脚本为这一类变异准备了单独的 CRASH 期望模式，原因记在那里。
 *
 * <p>（试过再加一道迭代上限，结论是多余的：两道守卫已经保证每个码位至少消耗 1 字节，
 * 循环次数天然不超过字节数。留着它只会写出一段看着像在防什么、其实什么都没防的注释。）
 */
final class PdfCodes {

    @FunctionalInterface
    interface CodeVisitor {
        /**
         * @param code           读出来的码位
         * @param bytesConsumed  这一码位在原始字节流里实际占了几个字节。
         *                       **必须用实测值，不能按「多字节字体就是 2」去猜**：
         *                       CMap 的码长是可变的，按猜的宽度去切原始字节，还原出来的
         *                       字符串会整段错位，而且不会报任何错。
         */
        void accept(int code, int bytesConsumed) throws IOException;
    }

    private PdfCodes() {
    }

    static void forEachCode(PDFont font, byte[] codes, CodeVisitor visitor) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(codes);
        while (in.available() > 0) {
            int before = in.available();
            int code = font.readCode(in);
            int consumed = before - in.available();
            if (consumed <= 0) {
                return;
            }
            visitor.accept(code, consumed);
        }
    }
}
