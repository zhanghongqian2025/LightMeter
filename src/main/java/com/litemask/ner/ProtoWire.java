package com.litemask.ner;

/**
 * 极简 protobuf 线格式游标：只支持「读出字段号与线类型、跳过内容」。
 *
 * <p><b>刻意不尝试解析未知的嵌套结构</b>——那需要完整 schema，
 * 而 {@link OnnxSelfContained} 与 {@link OnnxTensorShapes} 只需要知道
 * 顶层字段号与线类型就能正确跳过。
 *
 * <p>抽成本包私有类而不是让两个使用方各写一份：ONNX 的线格式解析一旦出现两份实现，
 * 迟早会在「某处多跳了一个字段」上分叉，而这种分叉的现场是
 * 「扫描没报外部数据、其实根本没扫到」——把「没检查」说成「干净」。
 */
final class ProtoWire {

    private ProtoWire() {
    }

    enum WireType {
        VARINT(0), SIXTY_FOUR_BIT(1), LENGTH_DELIMITED(2), THIRTY_TWO_BIT(5),
        /** protobuf 早已废弃的 group 编码。ONNX 不会产生它；出现即视为不认识的结构。 */
        GROUP_START(3), GROUP_END(4);

        private final int code;

        WireType(int code) {
            this.code = code;
        }

        static WireType of(int code) {
            for (WireType w : values()) {
                if (w.code == code) {
                    return w;
                }
            }
            throw new IllegalArgumentException("未知的 protobuf 线类型: " + code);
        }
    }

    static final class Field {
        final int number;
        final WireType wireType;
        /** VARINT 类型时的值。 */
        long varintValue;
        /** LENGTH_DELIMITED 类型时，内容起点与长度。 */
        int dataStart;
        int length;

        Field(int number, WireType wireType) {
            this.number = number;
            this.wireType = wireType;
        }
    }

    static final class Cursor {

        private final byte[] buf;
        private int pos;
        private final int limit;

        Cursor(byte[] buf, int start, int limit) {
            this.buf = buf;
            this.pos = start;
            this.limit = limit;
        }

        boolean hasMore() {
            return pos < limit;
        }

        /** 当前读取位置；用于 {@link #readVarintAt()} 这类「回头读」的场合。 */
        int position() {
            return pos;
        }

        Field next() {
            if (pos >= limit) {
                throw new IllegalArgumentException("模型文件在字段标签处被截断");
            }
            long tag = readVarint();
            int number = (int) (tag >>> 3);
            int wireCode = (int) (tag & 0x7);
            if (number == 0) {
                throw new IllegalArgumentException("protobuf 字段号不能为 0");
            }
            WireType wireType = WireType.of(wireCode);
            Field f = new Field(number, wireType);
            switch (wireType) {
                case VARINT -> f.varintValue = readVarint();
                case SIXTY_FOUR_BIT -> skip(8);
                case THIRTY_TWO_BIT -> skip(4);
                case LENGTH_DELIMITED -> {
                    long len = readVarint();
                    if (len < 0 || len > limit - pos) {
                        throw new IllegalArgumentException(
                                "protobuf 长度前缀越界: " + len + "（剩余 " + (limit - pos) + " 字节）");
                    }
                    f.dataStart = pos;
                    f.length = (int) len;
                    pos += (int) len;
                }
                case GROUP_START, GROUP_END -> throw new IllegalArgumentException(
                        "模型里出现了已废弃的 protobuf group 编码，不接受无法解析的结构");
            }
            return f;
        }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (shift < 64) {
                if (pos >= limit) {
                    throw new IllegalArgumentException("模型文件在 varint 中途被截断");
                }
                int b = buf[pos++] & 0xFF;
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            throw new IllegalArgumentException("varint 超过 10 字节，文件已损坏");
        }

        /**
         * 从指定偏移读一个 varint，<b>不移动游标</b>。
         *
         * <p>用于 packed repeated 字段：LENGTH_DELIMITED 的载荷是一串背靠背的 varint，
         * 外层的一次 {@code next()} 已经把游标推到了载荷末尾，
         * 于是只能记住起点再回读。载荷内部越界仍然要报错——
         * 宁可拒绝，也不返回一份被截断的形状。
         */
        long readVarintAt(int start) {
            if (start < 0 || start > limit) {
                throw new IllegalArgumentException("varint 起点越界: " + start);
            }
            long result = 0;
            int shift = 0;
            int p = start;
            while (shift < 64) {
                if (p >= limit) {
                    throw new IllegalArgumentException("packed varint 载荷在途中被截断");
                }
                int b = buf[p++] & 0xFF;
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            throw new IllegalArgumentException("packed varint 超过 10 字节，文件已损坏");
        }

        void skip(int n) {
            if (pos + n > limit) {
                throw new IllegalArgumentException("模型文件在定长字段中途被截断");
            }
            pos += n;
        }
    }
}
