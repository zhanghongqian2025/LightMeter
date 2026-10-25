package com.litemask.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 内容流词法扫描器：把解码后的内容流切成 {@link ContentToken}，每个 token 带精确字节区间。
 *
 * <p>实现刻意「只切分、不解释」：算子参数如何配对、Tf 指向哪个字体、BT/ET 怎么嵌套，
 * 都不在这里处理。这里只保证「切得对」，于是切分逻辑可以用极小的输入单独测透，
 * 而语义错误由上层用真实 PDF 暴露出来。
 *
 * <p>所有畸形输入一律 fail-closed 抛 {@link MalformedPdfException}，不做「尽力而为」的猜测。
 * 理由很直接：切错一次就会漏掉一段文本，而漏脱的代价远高于「这个文件打不开」。
 */
final class ContentScanner {

    private final byte[] content;
    private int pos;

    private ContentScanner(byte[] content) {
        this.content = content;
    }

    static List<ContentToken> scan(byte[] content) throws IOException {
        return new ContentScanner(content).run();
    }

    private List<ContentToken> run() throws IOException {
        List<ContentToken> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= content.length) {
                return out;
            }
            int start = pos;
            byte b = content[pos];
            switch (b) {
                case '[' -> out.add(tok(start, ++pos, ContentToken.Kind.ARRAY_OPEN));
                case ']' -> out.add(tok(start, ++pos, ContentToken.Kind.ARRAY_CLOSE));
                case '{' -> out.add(tok(start, ++pos, ContentToken.Kind.OPERATOR, "{"));
                case '}' -> out.add(tok(start, ++pos, ContentToken.Kind.OPERATOR, "}"));
                case '<' -> {
                    if (peek(1) == '<') {
                        pos += 2;
                        out.add(tok(start, pos, ContentToken.Kind.DICT_OPEN));
                    } else {
                        out.add(scanHexString(start));
                    }
                }
                case '>' -> {
                    if (peek(1) == '>') {
                        pos += 2;
                        out.add(tok(start, pos, ContentToken.Kind.DICT_CLOSE));
                    } else {
                        throw malformed("孤立的 '>'", start);
                    }
                }
                case '(' -> out.add(scanLiteralString(start));
                case '/' -> out.add(scanName(start));
                default -> {
                    if (isRegular(b)) {
                        out.add(scanRegular(start));
                    } else {
                        throw malformed("无法识别的字节 0x" + Integer.toHexString(b & 0xFF), start);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 词法单元

    private ContentToken scanRegular(int start) throws IOException {
        while (pos < content.length && isRegular(content[pos])) {
            pos++;
        }
        String text = new String(content, start, pos - start, java.nio.charset.StandardCharsets.ISO_8859_1);
        // PDF 允许畸形数字（如 "6.-2"、"--5"）。这里只认规范形式；不认识的按算子关键字处理，
        // 与 PDFBox 的分词口径一致，避免同一份文件在两处得到不同的切分。
        if (NUMBER.matcher(text).matches()) {
            return tok(start, pos, ContentToken.Kind.NUMBER, text);
        }
        if ("BI".equals(text)) {
            return scanInlineImage(start);
        }
        return tok(start, pos, ContentToken.Kind.OPERATOR, text);
    }

    /**
     * 内联图像：{@code BI ... ID <二进制> EI}。
     *
     * <p>二进制数据里可能任何字节都出现，包括看起来像算子的东西，因此不能按普通规则继续切分。
     * 只能按规范定位 {@code EI}：前面紧邻一个空白字节、后面是空白或流结束。
     * 这是 PDF 规范给出的唯一判据，也是所有实现（含 PDFBox）实际采用的做法。
     */
    private ContentToken scanInlineImage(int start) throws IOException {
        // BI 之后按普通规则走，直到遇到 ID
        while (pos < content.length) {
            int kwStart = pos;
            skipWhitespaceAndComments();
            if (pos >= content.length) {
                throw malformed("内联图像缺少 ID/EI 结束", start);
            }
            byte b = content[pos];
            boolean idFound = false;
            if (b == '/') {
                scanName(pos);
            } else if (b == '(') {
                scanLiteralString(pos);
            } else if (b == '<') {
                if (peek(1) == '<') {
                    pos += 2;
                } else {
                    scanHexString(pos);
                }
            } else if (b == '[') {
                pos++;
            } else if (b == ']') {
                pos++;
            } else if (isRegular(b)) {
                scanRegular(pos);
                String kw = new String(content, kwStart, pos - kwStart,
                        java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                if ("ID".equals(kw)) {
                    idFound = true;
                }
            } else {
                throw malformed("内联图像头中出现非法字节 0x" + Integer.toHexString(b & 0xFF), pos);
            }
            if (idFound) {
                break;
            }
        }
        if (pos >= content.length) {
            throw malformed("内联图像缺少 ID", start);
        }
        // ID 之后恰好一个空白字节，然后是二进制数据
        if (pos >= content.length || !isWhitespace(content[pos])) {
            throw malformed("ID 之后必须是空白字节", pos);
        }
        pos++;
        while (pos < content.length) {
            if (isWhitespace(content[pos - 1]) && content[pos] == 'E' && peek(1) == 'I'
                    && (pos + 2 >= content.length || isWhitespace(content[pos + 2])
                        || isDelimiter(content[pos + 2]))) {
                pos += 2;
                return tok(start, pos, ContentToken.Kind.INLINE_IMAGE);
            }
            pos++;
        }
        throw malformed("内联图像缺少 EI", start);
    }

    private ContentToken scanLiteralString(int start) throws IOException {
        pos++; // '('
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int depth = 1;
        while (true) {
            if (pos >= content.length) {
                throw malformed("字符串字面量括号不配对", start);
            }
            byte b = content[pos++];
            if (b == '\\') {
                if (pos >= content.length) {
                    throw malformed("字符串以孤立的反斜杠结尾", start);
                }
                byte e = content[pos++];
                switch (e) {
                    case 'n' -> buf.write('\n');
                    case 'r' -> buf.write('\r');
                    case 't' -> buf.write('\t');
                    case 'b' -> buf.write('\b');
                    case 'f' -> buf.write('\f');
                    case '(', ')', '\\' -> buf.write(e);
                    case '\r' -> {
                        // 行续接：\ 后跟 CRLF 或 CR，两个字节都吃掉
                        if (pos < content.length && content[pos] == '\n') {
                            pos++;
                        }
                    }
                    case '\n' -> { /* 行续接：\n 本身被吃掉 */ }
                    default -> {
                        if (e >= '0' && e <= '7') {
                            int v = e - '0';
                            for (int i = 0; i < 2 && pos < content.length; i++) {
                                byte o = content[pos];
                                if (o < '0' || o > '7') {
                                    break;
                                }
                                v = v * 8 + (o - '0');
                                pos++;
                            }
                            buf.write(v & 0xFF);
                        } else {
                            throw malformed("字符串转义 \\" + (char) e + " 非法", pos - 1);
                        }
                    }
                }
            } else if (b == '(') {
                depth++;
                buf.write(b);
            } else if (b == ')') {
                depth--;
                if (depth == 0) {
                    return new ContentToken(ContentToken.Kind.STRING, start, pos, null, buf.toByteArray());
                }
                buf.write(b);
            } else {
                buf.write(b);
            }
        }
    }

    private ContentToken scanHexString(int start) throws IOException {
        pos++; // '<'
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int high = -1;
        while (true) {
            if (pos >= content.length) {
                throw malformed("十六进制字符串未闭合", start);
            }
            byte b = content[pos++];
            if (b == '>') {
                if (high >= 0) {
                    buf.write((high << 4) & 0xFF); // 奇数个半字节，末尾补 0
                }
                return new ContentToken(ContentToken.Kind.STRING, start, pos, null, buf.toByteArray());
            }
            int v = hexValue(b);
            if (v < 0) {
                if (!isWhitespace(b)) {
                    throw malformed("十六进制字符串中出现非十六进制字节", pos - 1);
                }
                continue;
            }
            if (high < 0) {
                high = v;
            } else {
                buf.write((high << 4) | v);
                high = -1;
            }
        }
    }

    private ContentToken scanName(int start) throws IOException {
        pos++; // '/'
        StringBuilder sb = new StringBuilder();
        while (pos < content.length && isRegular(content[pos])) {
            byte b = content[pos++];
            if (b == '#' && pos + 1 < content.length) {
                int hi = hexValue(content[pos]);
                int lo = hexValue(content[pos + 1]);
                if (hi >= 0 && lo >= 0) {
                    sb.append((char) ((hi << 4) | lo));
                    pos += 2;
                    continue;
                }
            }
            sb.append((char) (b & 0xFF));
        }
        return tok(start, pos, ContentToken.Kind.NAME, sb.toString());
    }

    private void skipWhitespaceAndComments() {
        while (pos < content.length) {
            byte b = content[pos];
            if (isWhitespace(b)) {
                pos++;
            } else if (b == '%') {
                while (pos < content.length && content[pos] != '\n' && content[pos] != '\r') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 小工具

    private byte peek(int offset) {
        int i = pos + offset;
        return i < content.length ? content[i] : (byte) 0;
    }

    private ContentToken tok(int start, int end, ContentToken.Kind kind) {
        return new ContentToken(kind, start, end, null, null);
    }

    private ContentToken tok(int start, int end, ContentToken.Kind kind, String text) {
        return new ContentToken(kind, start, end, text, null);
    }

    private MalformedPdfException malformed(String why, int at) {
        return new MalformedPdfException("内容流在偏移 " + at + " 处畸形：" + why);
    }

    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)");

    static boolean isWhitespace(byte b) {
        return b == 0x00 || b == 0x09 || b == 0x0A || b == 0x0C || b == 0x0D || b == 0x20;
    }

    static boolean isDelimiter(byte b) {
        return b == '(' || b == ')' || b == '<' || b == '>' || b == '[' || b == ']'
                || b == '{' || b == '}' || b == '/' || b == '%';
    }

    static boolean isRegular(byte b) {
        return !isWhitespace(b) && !isDelimiter(b);
    }

    static int hexValue(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    /** 内容流畸形即拒绝处理，不做猜测性恢复。 */
    static final class MalformedPdfException extends IOException {
        MalformedPdfException(String message) {
            super(message);
        }
    }
}
