package com.litemask.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ISO 3779 第 9 位校验码（{@link Validator#vinCheckDigit}）。
 *
 * <p><b>本测试组的标尺全部来自外部资料，不来自被测代码</b>：向量取自
 * {@code en.wikipedia.org/wiki/Vehicle_identification_number} 的 ISO 3779 条目
 * （换值表、权重表、worked example、straight-ones 性质、以及两个真实欧洲 VIN 的反例）。
 * 手写一份「看起来对」的期望值等于把被测实现抄一遍，证明不了任何事。
 *
 * <p><b>本组的重点不是「算得对」而是「不作门禁」</b>：真实欧洲 VIN 过不了校验位，
 * 这个结论决定了 {@link Validator#VIN} 不能拿校验位否决脱敏。
 * 所以测试里有一条专门的回归防线：那两个反例必须<b>仍然通过门禁</b>。
 * 将来谁「顺手」把校验位加进 VIN.isValid，这条会立刻红。
 */
class VinCheckDigitTest {

    /** ISO 3779 允许出现的 23 个字母（不含 I、O、Q）。 */
    private static final String LETTERS = "ABCDEFGHJKLMNPRSTUVWXYZ";

    /** 维基 ISO 3779 换值表，逐位对齐 {@link #LETTERS}：A=1 B=2 … R=9 S=2 … Z=9。 */
    private static final String ISO_VALUES = "12345678123457923456789";

    /**
     * 把某个字母放在第 1 位（权重 8），其余各位填 0（值 0，权重再大也贡献 0）。
     * 于是和 = 该字母的换值 × 8，第 9 位（权重 0）放不算出来的期望字符。
     *
     * <p>这样每个字母都被单独钉住：换值表一旦错位，这里逐个字母地报出来，
     * 而不是给出一个笼统的「校验位不对」。
     */
    private static String probeWith(char letter) {
        // 1 + 7 + 1 + 8 = 17 位：第 1 位放待测字母，第 9 位放不算出来的期望字符
        return letter + "0000000" + "0" + "00000000";
    }

    @Test
    void 换值表逐字母对齐ISO3779() {
        assertEquals(23, LETTERS.length(), "ISO 3779 允许的字母应为 23 个");
        assertEquals(LETTERS.length(), ISO_VALUES.length(),
                "换值表与字母表必须等长——不等长说明对错位了");
        for (int i = 0; i < LETTERS.length(); i++) {
            int value = ISO_VALUES.charAt(i) - '0';
            int remainder = (value * 8) % 11;
            char expected = remainder == 10 ? 'X' : (char) ('0' + remainder);
            char actual = Validator.vinCheckDigit(probeWith(LETTERS.charAt(i)));
            assertEquals(expected, actual,
                    "字母 " + LETTERS.charAt(i) + " 的 ISO 3779 换值应为 " + value
                            + "（权重 8，模 11 得 " + expected + "）");
        }
    }

    /** 维基 worked example：1M8GDM9A_KP042788，乘积求和 351，351 mod 11 = 10 → X。 */
    @Test
    void 维基算例算出X() {
        assertEquals('X', Validator.vinCheckDigit("1M8GDM9AXKP042788"));
        assertTrue(Validator.vinCheckDigitOk("1M8GDM9AXKP042788"));
    }

    /**
     * 维基给出的性质：17 个连续的 1，其校验位必然是 1。
     * 权重之和 89，89 ÷ 11 余 1。这是一个与厂商无关的通用自检。
     */
    @Test
    void 十七个一的性质成立() {
        assertEquals('1', Validator.vinCheckDigit("11111111111111111"));
        assertTrue(Validator.vinCheckDigitOk("11111111111111111"));
    }

    /** 维基明示的正例。 */
    @Test
    void 维基正例通过校验位() {
        assertTrue(Validator.vinCheckDigitOk("5GZCZ43D13S812715"));
    }

    /**
     * 维基明示的两个反例：真实照片里的欧洲 VIN 过不了校验位。
     * 这两个串是本项目「不用校验位当门禁」的全部理由。
     */
    @Test
    void 真实欧洲车架号过不了校验位() {
        assertFalse(Validator.vinCheckDigitOk("WP0ZZZ99ZTS392124"), "保时捷，应判为不符");
        assertFalse(Validator.vinCheckDigitOk("KLATF08Y1VB363636"), "GM-T，应判为不符");
    }

    /** 小写也要能算，否则 PDF 里抄来的小写车架号会静默地「算不了」。 */
    @Test
    void 小写输入等价于大写() {
        assertEquals('X', Validator.vinCheckDigit("1m8gdm9axkp042788"));
        assertTrue(Validator.vinCheckDigitOk("1M8GDM9AXKP042788"));
    }

    /** 「算不了」必须与「算出来是 0」区分得开。 */
    @Test
    void 非法输入返回0代表算不了而非结果是0() {
        String zeroResult = probeWith('0');          // 全 0，合法结果确实是 '0'
        assertEquals('0', Validator.vinCheckDigit(zeroResult), "这个串的合法结果就是 0");

        assertEquals(0, Validator.vinCheckDigit(null), "null 算不了");
        assertEquals(0, Validator.vinCheckDigit(""), "空串算不了");
        assertEquals(0, Validator.vinCheckDigit("1M8GDM9AXKP04278"), "16 位算不了");
        assertEquals(0, Validator.vinCheckDigit("1M8GDM9AXKP0427888"), "18 位算不了");
        assertEquals(0, Validator.vinCheckDigit("1M8GDM9AXKP04278I"), "含 I 算不了");
        assertEquals(0, Validator.vinCheckDigit("1M8GDM9AXKP04278O"), "含 O 算不了");
        assertEquals(0, Validator.vinCheckDigit("1M8GDM9AXKP04278Q"), "含 Q 算不了");
        assertEquals(0, Validator.vinCheckDigit("1M8GDM9AXKP04278-"), "含非法字符算不了");
    }

    @Test
    void vinCheckDigitOk对算不了的输入返回false() {
        assertFalse(Validator.vinCheckDigitOk(null));
        assertFalse(Validator.vinCheckDigitOk("1M8GDM9AXKP04278I"));
    }

    /**
     * 回归防线：两个真实欧洲 VIN <b>仍然必须被脱敏规则命中</b>。
     *
     * <p>这一条会因「把校验位加进门禁」而红，而那正是它存在的理由。
     */
    @Test
    void 真实欧洲车架号仍然通过门禁被识别() {
        for (String vin : new String[]{"WP0ZZZ99ZTS392124", "KLATF08Y1VB363636"}) {
            assertTrue(Validator.VIN.isValid(vin), vin + " 是真实车架号，不得因为校验位不符而不脱敏");
            Rule rule = RuleLoader.loadFromClasspath("rules/core-rules.json").stream()
                    .filter(r -> r.key().equals("vin"))
                    .findFirst()
                    .orElseGet(() -> fail("规则库里没有 vin"));
            RedactionResult result = RedactionEngine.redact("车架号：" + vin, java.util.List.of(rule));
            if (!result.isChanged()) {
                fail("真实车架号 " + vin + " 没被命中——校验位不该参与门禁");
            }
            assertNotEquals(vin, result.sanitizedText(), "命中后原文应已被替换");
        }
    }
}
