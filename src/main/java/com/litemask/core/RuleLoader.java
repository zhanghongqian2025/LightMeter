package com.litemask.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 规则加载器。规则以 JSON 存放，便于版本化与按客户定制。 */
public final class RuleLoader {

    private static final int DEFAULT_SORT_ORDER = 100;
    private static final boolean DEFAULT_ACTIVE = true;

    private RuleLoader() {
    }

    public static List<Rule> loadFromClasspath(String resource) {
        try (InputStream in = RuleLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath 中找不到规则文件: " + resource);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取规则失败: " + resource, e);
        }
    }

    public static List<Rule> load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取规则失败: " + file, e);
        }
    }

    static List<Rule> parse(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT);
            RulesFile file = mapper.readValue(json, RulesFile.class);
            List<Rule> rules = new ArrayList<>();
            for (RuleDto dto : file.rules()) {
                if (dto == null || dto.key() == null) {
                    continue;
                }
                rules.add(new Rule(
                        dto.key(),
                        dto.name(),
                        dto.description(),
                        dto.pattern(),
                        dto.tokenPrefix(),
                        dto.sortOrder() == null ? DEFAULT_SORT_ORDER : dto.sortOrder(),
                        dto.active() == null ? DEFAULT_ACTIVE : dto.active(),
                        parseEnum(Category.class, dto.category(), Category.REGEX),
                        parseEnum(Validator.class, dto.validator(), Validator.NONE)));
            }
            return List.copyOf(rules);
        } catch (IOException e) {
            throw new IllegalArgumentException("规则 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知取值 " + raw + "（可选: "
                    + java.util.Arrays.toString(type.getEnumConstants()) + "）", e);
        }
    }

    /** 使用包装类型接收，缺字段时才能应用默认值。 */
    record RuleDto(String key, String name, String description, String pattern, String tokenPrefix,
                   Integer sortOrder, Boolean active, String category, String validator) {
    }

    /** 容忍未知字段（如 _comment），保证规则文件向前兼容。 */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record RulesFile(int version, List<RuleDto> rules) {
    }
}