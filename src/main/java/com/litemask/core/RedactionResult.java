package com.litemask.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脱敏结果。
 *
 * @param sanitizedText 脱敏后的文本
 * @param changes       替换明细（按应用顺序）
 * @param mapping       占位符 → 原文，用于还原
 */
public record RedactionResult(String sanitizedText, List<Change> changes, Map<String, String> mapping) {

    public RedactionResult {
        changes = List.copyOf(changes);
        mapping = Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
    }

    /** 用本结果自身的映射还原自身。 */
    public String restore() {
        return restore(sanitizedText);
    }

    /** 用本结果的映射还原任意文本（例如经前端手工编辑后的版本）。 */
    public String restore(String text) {
        return RedactionEngine.restore(text, mapping);
    }

    public boolean isChanged() {
        return !changes.isEmpty();
    }
}