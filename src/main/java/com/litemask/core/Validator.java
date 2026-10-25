package com.litemask.core;

/**
 * 命中后的校验器。
 *
 * <p>纯正则无法区分「长得像」和「就是」：一个 18 位数字串可能是身份证，也可能是订单号。
 * 正则负责召回，这里负责精度——校验不过的命中一律丢弃。
 *
 * <p>校验算法均为国家标准公开算法（GB 11643 公民身份号码、GB 32100 统一社会信用代码、
 * ISO/IEC 7812 Luhn）。
 */
public enum Validator {

    /** 不校验。 */
    NONE {
        @Override
        boolean check(String candidate) {
            return true;
        }
    },

    /** 公民身份号码校验位（GB 11643-1999）。 */
    ID_CARD {
        @Override
        boolean check(String candidate) {
            if (candidate == null || candidate.length() != 18) {
                return false;
            }
            int sum = 0;
            for (int i = 0; i < 17; i++) {
                char ch = Character.toUpperCase(candidate.charAt(i));
                if (ch < '0' || ch > '9') {
                    return false;
                }
                sum += (ch - '0') * ID_CARD_WEIGHTS[i];
            }
            char expected = ID_CARD_CHECK[sum % 11];
            return candidate.charAt(17) == expected;
        }
    },

    /** 统一社会信用代码校验位（GB 32100-2015）。 */
    CREDIT_CODE {
        @Override
        boolean check(String candidate) {
            if (candidate == null || candidate.length() != 18) {
                return false;
            }
            int sum = 0;
            for (int i = 0; i < 17; i++) {
                int value = CREDIT_CODE_CHARS.indexOf(candidate.charAt(i));
                if (value < 0) {
                    return false;
                }
                sum += value * CREDIT_CODE_WEIGHTS[i];
            }
            // 必须再取一次模：sum % 31 == 0 时索引 31 会越界，此时检查位为 '0'
            int expectedIndex = (31 - (sum % 31)) % 31;
            return candidate.charAt(17) == CREDIT_CODE_CHARS.charAt(expectedIndex);
        }
    },

    /** 银行卡号 Luhn 校验（ISO/IEC 7812）。 */
    LUHN {
        @Override
        boolean check(String candidate) {
            if (candidate == null || candidate.length() < 12) {
                return false;
            }
            int sum = 0;
            boolean doubleDigit = false;
            boolean allSame = true;
            char first = candidate.charAt(0);
            for (int i = candidate.length() - 1; i >= 0; i--) {
                char ch = candidate.charAt(i);
                if (ch < '0' || ch > '9') {
                    return false;
                }
                if (ch != first) {
                    allSame = false;
                }
                int digit = ch - '0';
                if (doubleDigit) {
                    digit *= 2;
                    if (digit > 9) {
                        digit -= 9;
                    }
                }
                sum += digit;
                doubleDigit = !doubleDigit;
            }
            // 全同数字（含 0000…）的校验和恰好为 0，会被 Luhn 放过，必须单独排除
            return !allSame && sum % 10 == 0;
        }
    },

    /**
     * 车辆识别代号（ISO 3779）：17 位、字符集排除 I/O/Q、且至少含一个字母。
     *
     * <p><b>这里刻意不校验第 9 位校验码</b>，与 {@code rule-samples.json} 里
     * 「计划在 M1 补上」的最初设想相反，理由是实测与查证（见 {@link #vinCheckDigit}）：
     * 校验位在北美与中国强制、<b>欧洲不强制</b>，大众集团只在 2009–2015 部分车型启用，
     * 而维基 VIN 条目收录的两个真实欧洲车架号 {@code WP0ZZZ99ZTS392124}（保时捷）
     * 与 {@code KLATF08Y1VB363636}（GM-T）<b>都过不了校验位</b>。
     * 一旦把它做成门禁，这两辆车就会从「脱敏」变成「原样留在文档里」——
     * 那是隐私泄漏；而多脱一个非车架号的 17 位串，代价只是多一个令牌。
     * 误拒的代价远高于误纳，方向不能反。
     *
     * <p>因此本门禁只负责「滤掉纯数字串和非法字符」，定位是精度而非权威校验。
     * 校验位算法本身由 {@link #vinCheckDigit} 提供，用于诊断与告警。
     */
    VIN {
        @Override
        boolean check(String candidate) {
            if (candidate == null || candidate.length() != 17) {
                return false;
            }
            boolean hasLetter = false;
            for (int i = 0; i < 17; i++) {
                char ch = candidate.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    continue;
                }
                if (VIN_CHARS.indexOf(ch) >= 0) {
                    hasLetter = true;
                    continue;
                }
                return false;
            }
            return hasLetter;
        }
    },

    /** IPv4 四段均在 0-255。 */
    IPV4 {
        @Override
        boolean check(String candidate) {
            if (candidate == null) {
                return false;
            }
            String[] parts = candidate.split("\\.", -1);
            if (parts.length != 4) {
                return false;
            }
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) {
                    return false;
                }
                for (int i = 0; i < part.length(); i++) {
                    if (!Character.isDigit(part.charAt(i))) {
                        return false;
                    }
                }
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
    };

    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CARD_CHECK = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /** 统一社会信用代码字符集：不含 I、O、S、V、Z。 */
    static final String CREDIT_CODE_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    private static final int[] CREDIT_CODE_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    /** VIN 允许的字母：不含 I、O、Q。 */
    private static final String VIN_CHARS = "ABCDEFGHJKLMNPRSTUVWXYZ";

    /**
     * ISO 3779 字母换值表，下标与 {@link #VIN_CHARS} 一一对应（均为 23 位）。
     *
     * <pre>
     * VIN_CHARS:         A B C D E F G H J K L M N P R S T U V W X Y Z
     * VIN_TRANSLITERATION:1 2 3 4 5 6 7 8 1 2 3 4 5 7 9 2 3 4 5 6 7 8 9
     * </pre>
     *
     * <p>两条字符串靠下标对齐，手写容易错位，所以 {@code VinCheckDigitTest} 会对
     * 23 个字母逐个断言，不给错位留缝。
     */
    private static final String VIN_TRANSLITERATION = "12345678123457923456789";

    private static final int[] VIN_WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};

    /**
     * 算 ISO 3779 第 9 位应当是什么（模 11，余 10 记作 {@code X}）。
     *
     * <p>算法来源：ISO 3779，参考 ISO 3779 条目的换值表与权重表
     * （{@code en.wikipedia.org/wiki/Vehicle_identification_number}）：
     * 字母按 EBCDIC 换值、数字用自身值，乘以 {@code [8,7,6,5,4,3,2,10,0,9,8,7,6,5,4,3,2]}，
     * 求和后除以 11 取余，余 10 对应 {@code X}。
     *
     * <p><b>返回 0 表示「算不了」而不是「算出来是 0」</b>：长度不是 17 或含 I/O/Q 的
     * 输入压根不是 VIN，调用方必须能区分这两种情况——前者是数据有问题，后者是算法结论。
     * 调用前请用 {@link #VIN#isValid} 确认形态合法。
     *
     * <p>本方法<b>不做</b>「至少含一个字母」这类语义判断，那是 {@code VIN} 门禁的事。
     */
    public static char vinCheckDigit(String vin) {
        if (vin == null || vin.length() != 17) {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char ch = Character.toUpperCase(vin.charAt(i));
            int value;
            if (ch >= '0' && ch <= '9') {
                value = ch - '0';
            } else {
                // 换值表与 VIN_CHARS 按下标对齐，不能拿字母去查换值串（那串里全是数字）
                int index = VIN_CHARS.indexOf(ch);
                if (index < 0) {
                    return 0;   // I / O / Q 或其他非法字符
                }
                value = VIN_TRANSLITERATION.charAt(index) - '0';
            }
            sum += value * VIN_WEIGHTS[i];
        }
        int remainder = sum % 11;
        return remainder == 10 ? 'X' : (char) ('0' + remainder);
    }

    /**
     * 第 9 位是否与算法一致。
     *
     * <p><b>这不是命中门禁</b>，只是诊断信号：北美与中国强制、欧洲不强制，
     * 真实欧洲车架号确实存在过不了的（见 {@code VIN} 的说明），
     * 用它否决脱敏会造成隐私泄漏。用途是提示「这处车架号可能是抄录有误」。
     */
    public static boolean vinCheckDigitOk(String vin) {
        char expected = vinCheckDigit(vin);
        return expected != 0 && vin.length() == 17
                && Character.toUpperCase(vin.charAt(8)) == expected;
    }

    abstract boolean check(String candidate);

    /** 校验命中片段是否可信。null 视为通过。 */
    public final boolean isValid(String candidate) {
        return this == NONE || (candidate != null && check(candidate));
    }

    /**
     * 这个校验器是不是<b>真的校验位</b>算法。
     *
     * <p><b>为什么要有这个判据</b>：跨单元续接靠「两段拼起来过校验器」来确认
     * 「它们本来是一个值」。但**没有校验位**的规则（NONE、以及只是形态校验的
     * {@link #IPV4}）过不了这一关——它们什么都收，于是「把两段无关的数字串拼起来」
     * 也会被判成同一个值。
     *
     * <p>本项目里两种载体的情况不一样：
     * <ul>
     *   <li><b>OOXML</b>：相邻单元是<b>顺序</b>相邻（上一段接着下一段），
     *       拼起来很可能真是被切断的同一个值 → 允许任何规则参与；</li>
     *   <li><b>PDF</b>：相邻块是<b>空间</b>相邻（同一页两栏也是「相邻」），
     *       拼起来可能压根是<b>两栏里各一个不相干的数</b>
     *       → 只允许有真校验位的规则参与（ID_CARD 的 GB 11643 校验位、
     *       CREDIT_CODE 的 GB 32100 校验位、LUHN）。</li>
     * </ul>
     *
     * <p>注意 {@link #VIN} <b>不算</b>：它刻意不校验第 9 位（见上面的说明），
     * 拿它当「真校验位」就是把形态校验当权威校验用。
     */
    public boolean hasCheckDigit() {
        return this == ID_CARD || this == CREDIT_CODE || this == LUHN;
    }
}