package com.litemask.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一条脱敏规则。
 *
 * @param key         规则标识，如 {@code id_card}
 * @param name        中文名称，用于展示
 * @param description 判定说明，写清「什么算、什么不算」
 * @param pattern     正则（Java 语法）
 * @param tokenPrefix 占位符前缀，如 {@code 身份证} → {@code <身份证1>}
 * @param sortOrder   优先级，**数值越大越先生效**；命中重叠时高优先级胜出
 * @param active      是否启用
 * @param category    类别
 * @param validator   命中后的校验器，{@code NONE} 表示不校验
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Rule(
        String key,
        String name,
        String description,
        String pattern,
        String tokenPrefix,
        int sortOrder,
        boolean active,
        Category category,
        Validator validator) {

    /** 归一化：补齐可空字段，避免下游到处判空。 */
    public Rule {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("规则 key 不能为空");
        }
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("规则 " + key + " 缺少正则");
        }
        if (name == null || name.isBlank()) {
            name = key;
        }
        if (tokenPrefix == null || tokenPrefix.isBlank()) {
            tokenPrefix = name;
        }
        if (category == null) {
            category = Category.REGEX;
        }
        if (validator == null) {
            validator = Validator.NONE;
        }
    }
}