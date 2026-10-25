package com.litemask.ner;

/**
 * 实体类型。
 *
 * <p><b>为什么是枚举而不是字符串</b>：类型会同时出现在界面清单、排除键与还原映射里，
 * 字符串比较一旦出现「PER」和「per」两种写法，两边就会当成两类——
 * 而那种错的现场是「排除没生效」，没有任何报错指向它。
 *
 * <p><b>新增类型要改这个枚举</b>，这是刻意的：模型包可以只带它支持的标签，
 * 但代码这边必须有一份已知清单，否则来路不明的标签会直接变成脱敏规则。
 */
public enum EntityType {

    /** 自然人姓名。 */
    PERSON("人名", "person"),

    /** 机构名：企业、事业单位、社会团体等。 */
    ORGANIZATION("机构名", "organization");

    private final String displayName;
    private final String code;

    EntityType(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    /** 界面与报告里的中文名。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 稳定标识，写进模型包清单与还原映射。
     *
     * <p>用英文而不是中文：令牌前缀会被写进文档正文，中文前缀在某些字体下
     * 会显示成方块或触发字体回退，而令牌是用户会直接看到的东西。
     */
    public String code() {
        return code;
    }

    /** 按 {@link #code()} 反查；认不出来返回 {@code null} 而不是抛异常。 */
    public static EntityType byCode(String code) {
        if (code == null) {
            return null;
        }
        for (EntityType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }
}
