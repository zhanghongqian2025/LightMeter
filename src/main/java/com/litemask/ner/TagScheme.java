package com.litemask.ner;

/**
 * 标签方案：把「模型的第 i 个输出通道」翻译成「这是哪一类实体的开头还是延续」。
 *
 * <p><b>为什么代码里不写死 5 个标签</b>：模型包可以只带它支持的子集
 * （比如某一版只训了人名）。把通道数写死在解码器里，
 * 换模型包就得改代码，而那正是最容易「改了代码、忘了改界面标签」的地方。
 *
 * <p><b>构造期做严格校验，构造不过直接抛</b>。标签方案来自模型包，
 * 而模型包可能来自离线导入——一条残缺的标签方案如果被「尽量兼容」地放行，
 * 后面的解码会把通道号解释成完全不相干的实体类型，
 * 于是正文里的公司名被当成人名脱敏，而报告里一切正常。
 */
public final class TagScheme {

    private final EntityType[] typeOf;
    private final boolean[] isBegin;
    private final boolean[] allowed;
    private final int outsideTag;

    /**
     * @param typeOf 每个标签对应的实体类型；<b>有且只有一个</b>标签的值为 {@code null}（即「不属于任何实体」）
     * @param isBegin 每个标签是否是「实体的第一个字」（B- 前缀）
     */
    public TagScheme(EntityType[] typeOf, boolean[] isBegin) {
        if (typeOf == null || isBegin == null) {
            throw new IllegalArgumentException("标签方案的 typeOf/isBegin 不能为空");
        }
        if (typeOf.length != isBegin.length) {
            throw new IllegalArgumentException(
                    "typeOf 与 isBegin 长度必须一致: " + typeOf.length + " vs " + isBegin.length);
        }
        if (typeOf.length < 2) {
            throw new IllegalArgumentException("标签方案至少需要「不属于任何实体」+ 一个实体类型，实际 " + typeOf.length);
        }
        this.typeOf = typeOf.clone();
        this.isBegin = isBegin.clone();

        // 「不属于任何实体」的标签必须恰好一个：两个 O 会让解码在 O 之间随机挑，
        // 一个都没有则整段文本都会被塞进实体里。
        int outside = -1;
        int outsideCount = 0;
        for (int i = 0; i < typeOf.length; i++) {
            if (typeOf[i] == null) {
                outside = i;
                outsideCount++;
            }
        }
        if (outsideCount != 1) {
            throw new IllegalArgumentException(
                    "必须有且只有一个「不属于任何实体」的标签（typeOf 为 null），实际 " + outsideCount);
        }
        this.outsideTag = outside;

        // 每种实体类型必须同时有 B 与 I 两种标签。
        // 只有 B 没有 I 会让解码器永远无法给实体续长，超过 4 个字的机构名会被截断；
        // 这种错在界面上表现为「公司名后面几个字没脱」，比整段不脱更容易被漏看。
        for (EntityType t : EntityType.values()) {
            int begin = -1;
            int inside = -1;
            int count = 0;
            for (int i = 0; i < typeOf.length; i++) {
                if (typeOf[i] == t) {
                    count++;
                    if (isBegin[i]) {
                        begin = i;
                    } else {
                        inside = i;
                    }
                }
            }
            if (count != 2 || begin < 0 || inside < 0) {
                throw new IllegalArgumentException(
                        "实体类型 " + t.code() + " 需要恰好两个标签（B- 与 I-），实际 " + count
                                + " 个（B=" + begin + ", I=" + inside + "）");
            }
        }

        // 结构约束：I- 只能接在同类型的 B- 或 I- 后面。
        boolean[] ok = new boolean[typeOf.length * typeOf.length];
        for (int from = 0; from < typeOf.length; from++) {
            for (int to = 0; to < typeOf.length; to++) {
                ok[from * typeOf.length + to] = allowsByBIO(from, to);
            }
        }
        this.allowed = ok;
    }

    private boolean allowsByBIO(int from, int to) {
        EntityType target = typeOf[to];
        if (target == null) {
            // 任何标签后面都可以接「不属于任何实体」
            return true;
        }
        if (isBegin[to]) {
            // B- 可以跟在任何标签后面（包括另一个 B-，表示紧邻的两个实体）
            return true;
        }
        // I- 只能延续自己类型的实体
        return typeOf[from] == target;
    }

    /** 常用的 BIO 方案：O、B-人名、I-人名、B-机构名、I-机构名。 */
    public static TagScheme bio() {
        return new TagScheme(
                new EntityType[]{null, EntityType.PERSON, EntityType.PERSON,
                        EntityType.ORGANIZATION, EntityType.ORGANIZATION},
                new boolean[]{false, true, false, true, false});
    }

    public int size() {
        return typeOf.length;
    }

    /** 「不属于任何实体」那个标签的通道号。 */
    public int outsideTag() {
        return outsideTag;
    }

    public boolean isOutside(int tag) {
        return tag == outsideTag;
    }

    public EntityType typeOf(int tag) {
        checkTag(tag);
        return typeOf[tag];
    }

    public boolean isBegin(int tag) {
        checkTag(tag);
        return isBegin[tag];
    }

    /** 结构上允许 {@code from → to} 这个转移（与模型的分数无关）。 */
    public boolean allows(int from, int to) {
        checkTag(from);
        checkTag(to);
        return allowed[from * typeOf.length + to];
    }

    /**
     * 这个标签能不能出现在序列<b>开头</b>。
     *
     * <p>{@code I-} 不能开头：它表示「延续上一个还没结束的实体」，
     * 而序列开头没有任何前驱。若放任 {@code I-} 开头，
     * 模型在窗口首字给高分的 {@code I-}（多半是因为实体从窗口外延续进来）会被当成实体，
     * 于是窗口最前面凭空多出一个单字实体——
     * 对脱敏工具来说那就是「文档第一行被脱掉一个字」，且没有任何提示。
     */
    public boolean allowsStart(int tag) {
        checkTag(tag);
        return typeOf[tag] == null || isBegin[tag];
    }

    private void checkTag(int tag) {
        if (tag < 0 || tag >= typeOf.length) {
            throw new IllegalArgumentException(
                    "标签号越界: " + tag + "（合法范围 0.." + (typeOf.length - 1) + "）");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TagScheme[");
        for (int i = 0; i < typeOf.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(i).append('=');
            if (typeOf[i] == null) {
                sb.append('O');
            } else {
                sb.append(isBegin[i] ? "B-" : "I-").append(typeOf[i].code());
            }
        }
        return sb.append(']').toString();
    }
}
