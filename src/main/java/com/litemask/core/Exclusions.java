package com.litemask.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「用户决定不脱的命中」集合。
 *
 * <p><b>为什么排除键是 {@code (规则键, 原值)} 而不是令牌或行号</b>：令牌是分配出来的，
 * 一旦「排除了几条」就会影响后面的编号，第二次扫描时同一个原值可能拿到不同令牌——
 * 于是界面发回来的排除项对不上号。行号在 PDF 里根本不存在（同一页文本流位置会变）。
 * 而 {@code (规则键, 原值)} 在「同一份文件 + 同一套规则」下是稳定的：
 * 扫两遍拿到的是同一组键。这不是假设，下面 {@link ExclusionTest} 会把「扫两遍键相同」钉死。
 *
 * <p>为什么不是「每个原值只对应一条排除项」而是允许同值多处命中：用户看到的是
 * 「这条是误报」，他要表达的是<b>这个值别动</b>，不是「第 3 次出现的那次别动」。
 * 同值同符本来就是本工具的核心不变式，按值排除与之一致。
 *
 * <p><b>位置级例外</b>：当同一个值在一篇文档里有的该脱、有的不该脱
 * （身份证在附件里要脱、在正文模板里只是占位符），值级排除就无能为力。
 * 这时用 {@link HitRef#nth}，只放过第 N 次出现，其余照常脱。
 *
 * <p>本类是纯值对象，不可变，线程安全。
 */
public record Exclusions(Set<HitRef> hits) {

    /** 什么都不排除。CLI 路径与绝大部分测试用它。 */
    public static final Exclusions NONE = new Exclusions(Set.of());

    public Exclusions {
        hits = hits == null || hits.isEmpty() ? Set.of() : Set.copyOf(hits);
    }

    /**
     * 一条被排除的命中。
     *
     * <p>{@code original} 是<b>用户文档里的原样字符串</b>——这意味着它本身就是敏感数据。
     * 所以接口层必须只把它回显给通过鉴权的同源页面，绝不写进日志。
     *
     * <p>{@code occurrence} 把排除粒度分成两级：
     * <ul>
     *   <li>{@link #ANY}（-1）：这个值<b>所有</b>出现处都别脱。这是 M2-b 交付的能力，
     *       覆盖了「这个号码是误报」的绝大多数场景。</li>
     *   <li>非负：只别脱<b>第 occurrence 次</b>出现的那一处，其余照常脱。
     *       用于「同一个身份证，附件里那份要脱，正文模板里那份是占位符不要动」。</li>
     * </ul>
     * 序号 0 基，口径见 {@link OccurrenceNumbering}。
     *
     * @param ruleKey    命中它的规则，如 {@code id_card}
     * @param original   命中的原值
     * @param occurrence 0 基出现序号，{@link #ANY} 表示全部出现处
     */
    public record HitRef(String ruleKey, String original, Integer occurrence) {

        /** 「这个值全部出现处都别脱」。 */
        public static final int ANY = -1;

        public HitRef {
            // 缺字段按「全部出现处」处理，而不是按 0。
            // Jackson 给缺失的 int 字段填的是 0，那会把「这个值整条别脱」
            // 悄悄变成「只放过第 0 次出现」——用户以为整条排除了，
            // 实际后面几次照样被脱，而且界面上看不出任何异常。
            // 报文里显式写 occurrence: 0 仍然表示「第 0 次」，两者语义不同，必须区分开。
            occurrence = occurrence == null ? ANY : occurrence;
            if (ruleKey == null || ruleKey.isBlank()) {
                throw new IllegalArgumentException("排除项缺少规则键");
            }
            if (original == null || original.isEmpty()) {
                throw new IllegalArgumentException("排除项缺少原值");
            }
            if (occurrence < ANY) {
                throw new IllegalArgumentException(
                        "出现序号不能小于 -1，收到: " + occurrence + "（-1 表示全部出现处）");
            }
        }

        /** 值级排除：这个值所有出现处都别脱。 */
        public HitRef(String ruleKey, String original) {
            this(ruleKey, original, null);
        }

        /** 位置级排除：只别脱第 {@code index} 次出现（0 基）。 */
        public static HitRef nth(String ruleKey, String original, int index) {
            return new HitRef(ruleKey, original, Integer.valueOf(index));
        }

        /** 这条排除项是否覆盖「规则键 + 原值 + 序号」这一次出现。 */
        boolean covers(String key, String value, int actualOccurrence) {
            if (!ruleKey.equals(key) || !original.equals(value)) {
                return false;
            }
            if (occurrence == ANY) {
                return true;
            }
            // 未编号的一律不算命中位置级排除：宁可多脱，也不要因为序号对不上就放过
            return occurrence >= 0 && actualOccurrence >= 0 && occurrence == actualOccurrence;
        }
    }


    public static Exclusions of(Collection<HitRef> refs) {
        return refs == null || refs.isEmpty() ? NONE : new Exclusions(new LinkedHashSet<>(refs));
    }

    /**
     * 从界面传来的数组形式构造。
     *
     * <p>只丢弃<b>数组里的 null 元素</b>；规则键或原值为空的项<b>不</b>丢弃——
     * {@link HitRef} 的紧凑构造器会先抛异常。那是有意的：
     * 界面回传的就是服务端发下去的键，出现空值只可能是请求被手工构造过。
     * 这种情况<b>让整个请求失败</b>，而不是悄悄少排除一条。
     *
     * <p>为什么「悄悄少排除一条」不算安全：用户会以为「我保留的那个号码留下了」，
     * 实际却没保留。那是<b>朝着多脱敏的方向</b>错，密不透风但会让人不再信任提示。
     * 两种错法都不可接受时，选能让问题当场暴露的那个。
     */
    public static Exclusions ofList(List<HitRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return NONE;
        }
        LinkedHashSet<HitRef> kept = new LinkedHashSet<>();
        for (HitRef r : refs) {
            if (r != null) {
                kept.add(r);
            }
        }
        return kept.isEmpty() ? NONE : new Exclusions(kept);
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    public int size() {
        return hits.size();
    }

    /** 这条命中是否被用户排除（值级：这个值所有出现处都别脱）。 */
    public boolean excludes(String ruleKey, String original) {
        if (hits.isEmpty()) {
            return false;
        }
        for (HitRef ref : hits) {
            if (ref.covers(ruleKey, original, 0) && ref.occurrence() == HitRef.ANY) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一次出现是否被排除：值级（{@link HitRef#ANY}）或位置级（序号相等）都算。
     *
     * <p>未编号（{@code occurrence < 0}）时<b>位置级排除一律不生效</b>。
     * 那条路径要么是单段调用、要么是隐形路径，两者都不该被「第 N 次」命中；
     * 真让一条序号排除在这里静默失效，用户会拿到一份没按他要求处理的文档却毫不知情。
     */
    public boolean covers(String ruleKey, String original, int occurrence) {
        if (hits.isEmpty()) {
            return false;
        }
        for (HitRef ref : hits) {
            if (ref.covers(ruleKey, original, occurrence)) {
                return true;
            }
        }
        return false;
    }
}
