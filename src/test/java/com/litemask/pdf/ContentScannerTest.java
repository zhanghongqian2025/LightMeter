package com.litemask.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ContentScanner} 的词法测试。
 *
 * <p>这个类最容易出的错是「切出来看着对、其实少切或多切了一个字节」——后果是重建内容流时
 * 把一段字节复制错了，或者把算子参数配对错。因此这里主要按**字节区间**断言，不只看语义。
 */
class ContentScannerTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String s(byte[] a) {
        return new String(a, StandardCharsets.ISO_8859_1);
    }

    private static List<String> kinds(byte[] content) throws java.io.IOException {
        return ContentScanner.scan(content).stream().map(ContentToken::kind).map(Enum::name).toList();
    }

    @Test
    @DisplayName("基本算子与数字按顺序切出，且每个区间都能原样取回")
    void basicOperators() throws java.io.IOException {
        byte[] c = b("1 0 0 1 50 700 Tm /F1 12 Tf");
        List<ContentToken> t = ContentScanner.scan(c);
        assertEquals(List.of("NUMBER", "NUMBER", "NUMBER", "NUMBER", "NUMBER",
                "NUMBER", "OPERATOR", "NAME", "NUMBER", "OPERATOR"), kinds(c));
        assertEquals("Tm", t.get(6).text());
        assertEquals("F1", t.get(7).text());
        // 每个 token 的区间取回来必须与原文一致（重建内容流时靠这个逐字节复制）
        int cursor = -1;
        for (ContentToken tok : t) {
            assertTrue(tok.start() > cursor, "区间必须严格递增");
            cursor = tok.end();
            String raw = s(tok.raw(c));
            assertEquals(tok.end() - tok.start(), raw.length());
        }
        assertEquals(c.length, cursor, "最后一个 token 应顶到流末尾");
    }

    @Test
    @DisplayName("字面字符串：转义、嵌套括号、跨行都被正确还原为内容字节")
    void literalString() throws java.io.IOException {
        byte[] c = b("(he\\(llo\\) \\101 \\n) Tj");
        List<ContentToken> t = ContentScanner.scan(c);
        assertEquals(2, t.size());
        assertEquals(ContentToken.Kind.STRING, t.get(0).kind());
        assertEquals("he(llo) A \n", s(t.get(0).data()));
        assertEquals(ContentToken.Kind.OPERATOR, t.get(1).kind());
        assertEquals("Tj", t.get(1).text());
        // 区间精确：从 '(' 到配对的 ')'
        assertEquals('(', (char) c[t.get(0).start()]);
        assertEquals(')', (char) c[t.get(0).end() - 1]);
    }

    @Test
    @DisplayName("字符串里的裸换行按规范保留为换行符，行续接 \\CRLF 与 \\LF 被吃掉")
    void stringNewlines() throws java.io.IOException {
        assertEquals("ab\ncd", s(ContentScanner.scan(b("(ab\ncd) Tj")).get(0).data()));
        assertEquals("abcd", s(ContentScanner.scan(b("(ab\\\r\ncd) Tj")).get(0).data()));
        assertEquals("abcd", s(ContentScanner.scan(b("(ab\\\ncd) Tj")).get(0).data()));
    }

    @Test
    @DisplayName("十六进制字符串忽略空白，奇数个半字节末尾补 0")
    void hexString() throws java.io.IOException {
        assertArrayEquals(new byte[]{0x41, 0x42}, ContentScanner.scan(b("<4142> Tj")).get(0).data());
        assertArrayEquals(new byte[]{0x41, 0x42, 0x43},
                ContentScanner.scan(b("<41 42\n43> Tj")).get(0).data());
        assertArrayEquals(new byte[]{0x41, 0x40}, ContentScanner.scan(b("<414> Tj")).get(0).data());
        assertArrayEquals(new byte[]{}, ContentScanner.scan(b("<> Tj")).get(0).data());
    }

    @Test
    @DisplayName("名字里的 #xx 转义被解码，但原始字节区间保持不变")
    void nameEscapes() throws java.io.IOException {
        byte[] c = b("/A#20B 1 Tf");
        List<ContentToken> t = ContentScanner.scan(c);
        assertEquals("A B", t.get(0).text());
        assertEquals("/A#20B", s(t.get(0).raw(c)));
        // % 是 PDF 定界符，因此想要百分号必须写 #25 —— 这条口径要与真实文件一致
        assertEquals("a%b", ContentScanner.scan(b("/a#25b Tf")).get(0).text());
    }

    @Test
    @DisplayName("字典与数组定界符分别成 token，不会与十六进制字符串混淆")
    void dictAndArray() throws java.io.IOException {
        assertEquals(List.of("DICT_OPEN", "DICT_CLOSE"), kinds(b("<< >>")));
        assertEquals(List.of("ARRAY_OPEN", "ARRAY_CLOSE"), kinds(b("[]")));
        assertEquals(ContentToken.Kind.STRING, ContentScanner.scan(b("<41>")).get(0).kind());
    }

    @Test
    @DisplayName("注释被跳过；但字符串内部的 % 不是注释")
    void comments() throws java.io.IOException {
        assertEquals(List.of("OPERATOR"), kinds(b("% hello\n Tj")));
        assertEquals("a%b", s(ContentScanner.scan(b("(a%b) Tj")).get(0).data()));
    }

    @Test
    @DisplayName("内联图像整块作为一个 token，其中的二进制内容不被误切成算子")
    void inlineImage() throws java.io.IOException {
        // 数据里故意塞入看起来像 EI 的字节（前面不是空白），只有真正合法的终止符才被识别
        String binary = "\u0000\u0001EI\u0002\u0003 \u0004";
        String data = "10 10 100 100 /GS0 gs BI /W 2 /H 2 /BPC 8 /CS /G ID " + binary + " EI Q Q";
        byte[] c = b(data);
        List<ContentToken> t = ContentScanner.scan(c);
        assertEquals(List.of("NUMBER", "NUMBER", "NUMBER", "NUMBER", "NAME",
                "OPERATOR", "INLINE_IMAGE", "OPERATOR", "OPERATOR"), kinds(c));
        // 整块（含 BI 头、ID 与二进制、EI 尾）逐字节不变，图像才能原样透传
        assertEquals("BI /W 2 /H 2 /BPC 8 /CS /G ID " + binary + " EI",
                s(t.get(6).raw(c)));
    }

    @Test
    @DisplayName("畸形内容流一律抛错，不做猜测性恢复")
    void malformedFailsClosed() {
        assertThrows(ContentScanner.MalformedPdfException.class,
                () -> ContentScanner.scan(b("(abc Tj")));          // 括号不配对
        assertThrows(ContentScanner.MalformedPdfException.class,
                () -> ContentScanner.scan(b("<4142 Tj")));           // 十六进制未闭合
        assertThrows(ContentScanner.MalformedPdfException.class,
                () -> ContentScanner.scan(b("> x")));                // 孤立 '>'
        assertThrows(ContentScanner.MalformedPdfException.class,
                () -> ContentScanner.scan(b("(a\\z) Tj")));          // 非法转义
        assertThrows(ContentScanner.MalformedPdfException.class,
                () -> ContentScanner.scan(b("BI /W 1 ID xxxxx")));   // 内联图像无 EI
        assertThrows(ContentScanner.MalformedPdfException.class,
                () -> ContentScanner.scan(b("BI /W 1 ID xx")));      // ID 后缺空白
    }

    @Test
    @DisplayName("TJ 数组的参数结构被完整切出，可直接按序配对")
    void tjArrayStructure() throws java.io.IOException {
        byte[] c = b("[(A) -100 (B) 20 (C)] TJ");
        List<ContentToken> t = ContentScanner.scan(c);
        assertEquals(List.of("ARRAY_OPEN", "STRING", "NUMBER", "STRING",
                "NUMBER", "STRING", "ARRAY_CLOSE", "OPERATOR"), kinds(c));
        assertEquals("A", s(t.get(1).data()));
        assertEquals("-100", t.get(2).text());
        assertEquals("B", s(t.get(3).data()));
        assertEquals("C", s(t.get(5).data()));
        assertEquals("TJ", t.get(7).text());
    }

    private static void assertTrue(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
    }
}
