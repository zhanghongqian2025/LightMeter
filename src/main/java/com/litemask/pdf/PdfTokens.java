package com.litemask.pdf;

import com.litemask.core.Change;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 侧的令牌表示。
 *
 * <p>核心引擎发的令牌是中文的（{@code <身份证1>}），但 PDF 里不能直接画它：
 * 内容流里的字符串是**字体编码**，不是 Unicode，而页面上的字体往往是子集内嵌的、
 * 只包含本页用到的那些字形。想用原字体画中文令牌，轻则缺字形（显示成豆腐块或空白，
 * 工具却报告「脱敏成功」），重则要把一份 CJK 字体塞进安装包（+5~15MB）。
 *
 * <p>所以 PDF 用一套 ASCII 令牌 {@code <ID_CARD_1>}，固定用标准 14 字体 Helvetica 画。
 * 标准 14 字体任何阅读器都自带，**不需要内嵌、零体积**，也永远不会缺字形。
 * 代价是 PDF 里的令牌是英文而 Word 里是中文——这个差异写进用户文档，不假装一致。
 */
public final class PdfTokens {

    /** PDF 侧令牌：{@code <规则名_序号>}，规则名取自规则的 key，全大写。 */
    static final Pattern TOKEN = Pattern.compile("<([A-Z][A-Z0-9_]{0,29})(\\d{1,9})>");

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d{1,9})>$");

    private PdfTokens() {
    }

    /**
     * 把引擎的中文令牌改写成 PDF 用的 ASCII 令牌。序号保持不变，因此与中文令牌一一对应。
     *
     * <p>对外公开是因为命令行要如实写进映射文件：引擎的 {@code token()} 是中文的，
     * 而 PDF 里实际画的是 ASCII 那个。若映射文件写中文令牌，等于对用户说了一句
     * 文档里并不存在的话。
     */
    public static String asciiToken(Change change) {
        Matcher m = TRAILING_NUMBER.matcher(change.token());
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "引擎令牌格式异常，末尾没有序号：" + change.token());
        }
        String key = change.ruleKey();
        if (key == null || key.isBlank()) {
            key = "sensitive";
        }
        String prefix = SHORT_PREFIX.getOrDefault(key,
                key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_"));
        return "<" + prefix + "_" + m.group(1) + ">";
    }

    /** 从 ASCII 令牌里取回序号，用于还原时对齐中文令牌。 */
    static String sequenceOf(String asciiToken) {
        Matcher m = TOKEN.matcher(asciiToken);
        return m.matches() ? m.group(2) : null;
    }

    /**
     * 规则 key 到 ASCII 短前缀的映射。
     *
     * <p>为什么要另起一套短名，而不是直接用 key 大写：令牌画在 PDF 上，宽度是真金白银的版面成本。
     * {@code <IP_ADDRESS_1>} 14 个字符比被替换的 {@code 192.168.1.100} 还宽，于是令牌尾巴会压到
     * 后面的字上——阅读器上是糊的，复制出来也是乱的。短名把这个最常见的溢出情形消掉。
     *
     * <p>NER 的两个前缀是这个名单里最该在的：{@code ner_person}／{@code ner_organization}
     * 回退大写后是 {@code <NER_PERSON_1>}（14 字符）与 {@code <NER_ORGANIZATION_1>}（19 字符），
     * 而它们顶替的往往只有两三个汉字（实测夹具里 15pt 与 24pt）。实测渲染确认：
     * 不收进短名单时，一行里的四个令牌会互相叠压成完全不可读的一团，
     * 按坐标抽取出来的文字也是交错的。收进来后是 {@code <PER_1>}／{@code <ORG_1>}，各 6 字符。
     *
     * <p>没有收录的规则回落到 key 大写，仍然可用，只是可能偏宽并给出提示。
     */
    private static final java.util.Map<String, String> SHORT_PREFIX = java.util.Map.ofEntries(
            java.util.Map.entry("id_card", "ID"),
            java.util.Map.entry("credit_code", "USCC"),
            java.util.Map.entry("car_plate", "PLATE"),
            java.util.Map.entry("vin", "VIN"),
            java.util.Map.entry("case_number", "CASE"),
            java.util.Map.entry("bank_card", "CARD"),
            java.util.Map.entry("email", "MAIL"),
            java.util.Map.entry("url", "URL"),
            java.util.Map.entry("ip_address", "IP"),
            java.util.Map.entry("phone", "PHONE"),
            java.util.Map.entry("telephone", "TEL"),
            java.util.Map.entry("passport", "PPT"),
            java.util.Map.entry("stock_code", "STOCK"),
            java.util.Map.entry("wechat_id", "WX"),
            java.util.Map.entry("qq_number", "QQ"),
            java.util.Map.entry("contract_no", "CONTRACT"),
            java.util.Map.entry("document_no", "DOCNO"),
            // NER：见上面的说明。这两个 key 与 NerEntitySource.RULE_PERSON / RULE_ORGANIZATION 对应。
            java.util.Map.entry("ner_person", "PER"),
            java.util.Map.entry("ner_organization", "ORG"));

    /** 资源名要写回内容流，必须重新做一次 PDF 名字转义。 */
    static String escapeName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean regular = c > 0x20 && c < 0x7F && "()<>[]{}/%#".indexOf(c) < 0;
            if (regular) {
                sb.append(c);
            } else {
                sb.append('#').append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
    }
}
