package com.litemask.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验位单元测试。
 *
 * <p>这些值由一份**独立实现**（tools/gen-rule-samples.py）算出，不复用生产代码，
 * 因此两份实现同时算错才可能漏网。
 *
 * <p>其中 {@code N2J1K6FMHDQAEJXEB0} 是回归用例：权重和恰好整除 31，
 * 检查位索引会算出 31（越界）。修复前该输入直接抛 StringIndexOutOfBoundsException。
 */
class ValidatorTest {

    @Test
    @DisplayName("身份证校验位：合法样本通过")
    void idCardAcceptsValid() {
        assertTrue(Validator.ID_CARD.isValid("11010519491231002X"), "校验位为 X");
        assertTrue(Validator.ID_CARD.isValid("52601815908301661X"));
        assertTrue(Validator.ID_CARD.isValid("318609139099603086"));
        assertTrue(Validator.ID_CARD.isValid("246281948219935181"));
    }

    @Test
    @DisplayName("身份证校验位：错一位即失败")
    void idCardRejectsInvalid() {
        assertFalse(Validator.ID_CARD.isValid("11010519491231002A"), "校验位非数字非 X");
        assertFalse(Validator.ID_CARD.isValid("110105194912310021"), "校验位错误");
        assertFalse(Validator.ID_CARD.isValid("11010519491231002"), "长度不足 18");
        assertFalse(Validator.ID_CARD.isValid("11010519491231002XX"), "长度超出");
        assertFalse(Validator.ID_CARD.isValid(null));
    }

    @Test
    @DisplayName("统一社会信用代码：合法样本通过")
    void creditCodeAcceptsValid() {
        assertTrue(Validator.CREDIT_CODE.isValid("91350100M000100Y43"));
        assertTrue(Validator.CREDIT_CODE.isValid("N2J1K6FMHDQAEJXEB0"), "权重和整除 31 的边界样本");
        assertTrue(Validator.CREDIT_CODE.isValid("DEKWM7PHUQMQ3QT99X"));
    }

    @Test
    @DisplayName("统一社会信用代码：错一位 / 非法字符 / 长度不足均失败")
    void creditCodeRejectsInvalid() {
        assertFalse(Validator.CREDIT_CODE.isValid("91350100M000100Y44"));
        assertFalse(Validator.CREDIT_CODE.isValid("91350100M000100Y4I"), "含被排除字符 I");
        assertFalse(Validator.CREDIT_CODE.isValid("91350100M000100Y4O"), "含被排除字符 O");
        assertFalse(Validator.CREDIT_CODE.isValid("91350100M000100Y4S"), "含被排除字符 S");
        assertFalse(Validator.CREDIT_CODE.isValid("91350100M000100Y4V"), "含被排除字符 V");
        assertFalse(Validator.CREDIT_CODE.isValid("91350100M000100Y4Z"), "含被排除字符 Z");
        assertFalse(Validator.CREDIT_CODE.isValid("91350100M000100Y4"));
    }

    @Test
    @DisplayName("Luhn：12 位与 19 位边界样本")
    void luhnBoundaries() {
        // 以公开测试卡 4111111111111111（必为有效）为参照校验本实现
        assertTrue(Validator.LUHN.isValid("4111111111111111"), "公开测试卡应通过");
        assertFalse(Validator.LUHN.isValid("4111111111111112"), "改一位应失败");
        assertTrue(Validator.LUHN.isValid("4111111111119"), "13 位（12 位基数 + 校验位）");
        assertTrue(Validator.LUHN.isValid("4111111111111111110"), "19 位");
        assertFalse(Validator.LUHN.isValid("411111111111"), "11 位视为过短");
        assertFalse(Validator.LUHN.isValid("62220212345678904"), "校验位错误");
    }

    @Test
    @DisplayName("IPv4：段值边界")
    void ipv4Boundaries() {
        assertTrue(Validator.IPV4.isValid("0.0.0.0"));
        assertTrue(Validator.IPV4.isValid("255.255.255.255"));
        assertFalse(Validator.IPV4.isValid("256.0.0.1"));
        assertFalse(Validator.IPV4.isValid("1.2.3"));
        assertFalse(Validator.IPV4.isValid("1.2.3.4.5"));
        assertFalse(Validator.IPV4.isValid("01.2.3.4.5"));
    }

    @Test
    @DisplayName("NONE 恒通过，null 交给调用方判空")
    void noneAlwaysPasses() {
        assertTrue(Validator.NONE.isValid("任意串"));
        assertTrue(Validator.NONE.isValid(null));
    }
}