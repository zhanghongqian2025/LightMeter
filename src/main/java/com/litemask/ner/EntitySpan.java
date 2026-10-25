package com.litemask.ner;

/**
 * 识别出的一个实体区间，半开区间 {@code [start, end)}。
 *
 * @param start 起始字符下标（含）
 * @param end   结束字符下标（不含）
 * @param type  实体类型
 */
public record EntitySpan(int start, int end, EntityType type) {

    public EntitySpan {
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("实体区间不能为负: [" + start + "," + end + ")");
        }
        if (end <= start) {
            throw new IllegalArgumentException("实体区间不能为空或倒置: [" + start + "," + end + ")");
        }
        if (type == null) {
            throw new IllegalArgumentException("实体类型不能为空: [" + start + "," + end + ")");
        }
    }

    /** 与 {@code other} 是否指向同一个实体（区间与类型都相同）。 */
    public boolean sameEntity(EntitySpan other) {
        return other != null && start == other.start && end == other.end && type == other.type;
    }

    /** 两个区间是否相交（半开区间，端点相接不算相交）。 */
    public boolean overlaps(EntitySpan other) {
        return other != null && start < other.end && other.start < end;
    }

    @Override
    public String toString() {
        return type.code() + "[" + start + "," + end + ")";
    }
}
