package com.litemask.core;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 占位符分配器。
 *
 * <p>两条约束：
 * <ol>
 *   <li><b>同值同符</b>：同一段原文在全文中只分配一个占位符，避免同一个身份证出现三个不同编号；</li>
 *   <li><b>不撞原文</b>：源文本里本来就可能写着 {@code <身份证3>}（比如客户手工填的模板），
 *       因此分配前先扫一遍已有的占位符，计数器从其上界起步。否则还原时会把原文里
 *       本来就有的占位符一起换掉，破坏往返一致性。</li>
 * </ol>
 */
public final class TokenAllocator {

    private static final Pattern EXISTING_TOKEN = Pattern.compile("<([^<>\\s]{1,32}?)(\\d{1,9})>");

    private final Map<String, String> tokenByOriginal = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();

    public TokenAllocator(String sourceText) {
        if (sourceText != null) {
            Matcher matcher = EXISTING_TOKEN.matcher(sourceText);
            while (matcher.find()) {
                String prefix = matcher.group(1);
                int n;
                try {
                    n = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException ex) {
                    continue;
                }
                counters.merge(prefix, n, Math::max);
            }
        }
    }

    /** 取占位符；同一原文重复调用返回同一个。 */
    public String allocate(String original, String tokenPrefix) {
        String existing = tokenByOriginal.get(original);
        if (existing != null) {
            return existing;
        }
        int n = counters.merge(tokenPrefix, 1, Integer::sum);
        String token = "<" + tokenPrefix + n + ">";
        tokenByOriginal.put(original, token);
        return token;
    }

    public Map<String, String> allocated() {
        return Map.copyOf(tokenByOriginal);
    }
}