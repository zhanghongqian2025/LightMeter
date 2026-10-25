package com.litemask.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NER 命中进引擎之后的语义：重叠消解、出现编号、位置级排除、替换。
 *
 * <p>用<b>手写的假来源</b>而不是真模型：这里要验的是引擎的行为，
 * 「模型认不认得出人名」不在本类的射程内。把模型混进来会让「引擎算错了」
 * 与「模型不准」变成同一个红，而两者的修法完全不同。
 *
 * <p>假来源给出的区间是手写的，所以期望结果可以逐字写出来。
 */
class EngineEntitySourceTest {

    /** 人名假来源：按给定区间返回命中。 */
    private static EntitySource personAt(int... spans) {
        return new EntitySource() {
            @Override
            public String name() {
                return "fake-person";
            }

            @Override
            public List<Match> collect(String text) {
                List<Match> out = new ArrayList<>();
                for (int i = 0; i + 1 < spans.length; i += 2) {
                    int s = spans[i];
                    int e = spans[i + 1];
                    out.add(new Match(s, e, text.substring(s, e), "ner_person", "人名", 900));
                }
                return out;
            }
        };
    }

    /** 机构名假来源。 */
    private static EntitySource orgAt(int... spans) {
        return new EntitySource() {
            @Override
            public String name() {
                return "fake-org";
            }

            @Override
            public List<Match> collect(String text) {
                List<Match> out = new ArrayList<>();
                for (int i = 0; i + 1 < spans.length; i += 2) {
                    int s = spans[i];
                    int e = spans[i + 1];
                    out.add(new Match(s, e, text.substring(s, e), "ner_organization", "机构名", 900));
                }
                return out;
            }
        };
    }

    /** 只命中「北京」两个字的低优先级正则，模拟「地名规则切碎了机构名」。 */
    private static Rule beijingRule() {
        return new Rule("place", "地名", null, "北京", "地名", 80, true, Category.REGEX, null);
    }

    @Test
    @DisplayName("AI 命中与正则命中一起进同一条管线")
    void 合流() {
        String text = "甲方张三丰，乙方北京某某科技有限公司。";
        RedactionResult r = RedactionEngine.redact(text, List.of(), new TokenAllocator(text),
                Exclusions.NONE, null, List.of(personAt(2, 5), orgAt(8, 18)));

        assertEquals("甲方<人名1>，乙方<机构名1>。", r.sanitizedText(), "实际：" + r.sanitizedText());
        assertEquals(2, r.changes().size(), "实际 " + r.changes());
    }

    @Test
    @DisplayName("AI 命中压过低优先级的正则命中，机构名不被切成两截")
    void 重叠时AI胜出() {
        String text = "北京某某科技有限公司";
        // 前提：正则确实命中了「北京」，且区间与机构名重叠
        assertEquals(0, text.indexOf("北京"), "夹具前提：北京必须在 0");

        RedactionResult r = RedactionEngine.redact(text, List.of(beijingRule()),
                new TokenAllocator(text), Exclusions.NONE, null, List.of(orgAt(0, text.length())));

        assertEquals("<机构名1>", r.sanitizedText(),
                "NER 优先级更高，机构名应当整段脱掉而不是被「北京」切碎。实际：" + r.sanitizedText());
    }

    @Test
    @DisplayName("重叠消解后，落败命中的非重叠部分仍要脱敏——否则原值裸露成碎片")
    void 落败命中的残余部分仍脱敏() {
        // 现场（2026-10，e2e-pdf 带 v9 模型形态暴露）：模型把拼音邮箱的头 4 个字
        // 认成了人名。NER 的 sortOrder 900 高于邮箱规则的 120，于是邮箱那条**落败**。
        // 旧实现把落败命中整条丢掉，于是邮箱只剩尾巴没人管：
        //     身份证 <身份证1>, <人名1>gsan@corp.example.com
        // 邮箱域名整段原样躺在交付物里——「脱敏过了」而地址还看得见。
        //
        // 关键在两者**互不包含**：NER 区间 ⊂ 邮箱区间但更短（[0,4) vs [0,20)），
        // 所以存在残余。原实现处理得了「NER ⊃ 规则」（见上面的重叠时AI胜出），
        // 因为那时规则整段落在 NER 里面、残余为空。
        String text = "zhangsan@corp.example.com";
        Rule mail = new Rule("email", "邮箱", null,
                "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "邮箱", 120, true, Category.REGEX, null);
        // 夹具前提：规则优先级确实低于 NER 的 900，否则验的是另一件事
        assertTrue(120 < 900, "夹具前提：本测试验的是「优先级较低的规则被 NER 压住」");

        RedactionResult r = RedactionEngine.redact(text, List.of(mail), new TokenAllocator(text),
                Exclusions.NONE, null, List.of(personAt(0, 4)));

        assertEquals("<人名1><邮箱1>", r.sanitizedText(),
                "落败命中的残余部分也必须被脱敏，不能裸露。实际：" + r.sanitizedText());
        // 映射必须两段都在，否则界面列不出「邮箱」，还原也会缺一段
        assertEquals("zhan", r.mapping().get("<人名1>"), "人名段映射");
        assertEquals("gsan@corp.example.com", r.mapping().get("<邮箱1>"),
                "邮箱残余段映射。实际映射：" + r.mapping());
    }

    @Test
    @DisplayName("sortOrder 高于 NER 的正则仍然胜出：NER 不是特权")
    void 更高优先级的正则仍然胜出() {
        String text = "张三丰13800138000";
        // 前提：这条规则的优先级确实高于 NER 的 900
        Rule phone = new Rule("phone", "手机号", null, "1\\d{10}", "手机号", 1000, true,
                Category.REGEX, null);
        assertTrue(1000 > 900, "夹具前提：本测试验的是「比 NER 更高的优先级」");

        RedactionResult r = RedactionEngine.redact(text, List.of(phone), new TokenAllocator(text),
                Exclusions.NONE, null, List.of(personAt(0, 3)));

        assertEquals("<人名1><手机号1>", r.sanitizedText(),
                "两条本来不重叠，关键是「NER 不是特权」这一点没被写死。实际：" + r.sanitizedText());
    }

    @Test
    @DisplayName("sortOrder 低于 NER 的正则被压住：NER 的 900 确实高于规则文件的 120")
    void 更低优先级的正则被压住() {
        String text = "张三丰";
        Rule tag = new Rule("tag", "标记", null, "三", "标记", 120, true, Category.REGEX, null);
        assertTrue(120 < 900, "夹具前提");

        RedactionResult r = RedactionEngine.redact(text, List.of(tag), new TokenAllocator(text),
                Exclusions.NONE, null, List.of(personAt(0, 3)));

        assertEquals("<人名1>", r.sanitizedText(),
                "人名整段应当脱掉，而不是被只命中「三」的规则切碎。实际：" + r.sanitizedText());
    }

    @Test
    @DisplayName("AI 命中参与出现编号，且同值同符")
    void 编号与同值同符() {
        String text = "张三丰与张三丰";
        // 跨段计数器：同值第二次拿到序号 1。前提断言见下。
        java.util.Map<String, Integer> counter = new java.util.HashMap<>();
        OccurrenceNumbering numbering = (key, original) ->
                counter.merge(key + '@' + original, 1, Integer::sum) - 1;
        TokenAllocator allocator = new TokenAllocator(text);

        RedactionResult r = RedactionEngine.redact(text, List.of(), allocator, Exclusions.NONE,
                numbering, List.of(personAt(0, 3, 4, 7)));

        // 前提：两处确实是同一个值、且被分成了两次出现
        assertEquals(4, text.indexOf("张三丰", 1), "夹具前提：第二处起点应为 4");
        // 两处同值 → 同一个令牌
        assertEquals("<人名1>与<人名1>", r.sanitizedText(), "实际：" + r.sanitizedText());
        assertEquals(Map.of("<人名1>", "张三丰"), r.mapping(), "还原映射是「令牌 -> 原值」，且只有一个条目");
    }

    @Test
    @DisplayName("按值排除 AI 命中：整条不脱，也不占令牌")
    void 按值排除() {
        String text = "甲方张三丰，乙方李四";
        Exclusions excl = Exclusions.of(List.of(new Exclusions.HitRef("ner_person", "张三丰")));

        RedactionResult r = RedactionEngine.redact(text, List.of(), new TokenAllocator(text),
                excl, null, List.of(personAt(2, 5, 8, 10)));

        assertEquals("甲方张三丰，乙方<人名1>", r.sanitizedText(), "实际：" + r.sanitizedText());
        assertEquals(1, r.changes().size(), "被排除的那条不该出现在变更清单里");
    }

    @Test
    @DisplayName("排除 AI 命中会挡住整段跨度，不会只挡值")
    void 排除挡跨度() {
        // 前提：手机号区间必须**落在**人名区间内部，两者真重叠。
        // 第一版写的是 personAt(2,5) + 手机号 [5,16)——那是不相交的**相邻**两段，
        // 挡不住是当然的。测试会「因为错误的原因通过」：它其实什么都没验。
        String text = "甲方张三丰13800138000";
        int personStart = text.indexOf("张三丰");
        int phoneStart = text.indexOf("13800138000");
        assertEquals(2, personStart, "夹具前提：人名起点");
        assertEquals(5, phoneStart, "夹具前提：手机号起点");
        // 人名实体一路吞到句末（NER 误报：把人名和后面的号码并成一个实体）
        Rule phone = new Rule("phone", "手机号", null, "1\\d{10}", "手机号", 500, true,
                Category.REGEX, null);
        Exclusions excl = Exclusions.of(List.of(new Exclusions.HitRef("ner_person",
                text.substring(personStart))));

        RedactionResult r = RedactionEngine.redact(text, List.of(phone), new TokenAllocator(text),
                excl, null, List.of(personAt(personStart, text.length())));

        assertEquals(text, r.sanitizedText(),
                "用户排除了整段，手机号虽然在里面也不该再被脱。实际：" + r.sanitizedText());
    }

    @Test
    @DisplayName("AI 命中返回 null 时当没有命中，不打挂整篇")
    void 来源返回null() {
        EntitySource nullSource = new EntitySource() {
            @Override
            public String name() {
                return "null-source";
            }

            @Override
            public List<Match> collect(String text) {
                return null;
            }
        };
        String text = "手机号13800138000";
        Rule phone = new Rule("phone", "手机号", null, "1\\d{10}", "手机号", 500, true,
                Category.REGEX, null);

        RedactionResult r = RedactionEngine.redact(text, List.of(phone), new TokenAllocator(text),
                Exclusions.NONE, null, List.of(nullSource));

        assertEquals("手机号<手机号1>", r.sanitizedText(),
                "正则那条应当照常工作（规则只匹配号码本身，不含「手机号」三个字），实际："
                        + r.sanitizedText());
    }

    @Test
    @DisplayName("来源抛异常时整篇失败，不降级成「没找到实体」")
    void 来源抛异常要失败() {
        EntitySource boom = new EntitySource() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public List<Match> collect(String text) {
                throw new IllegalStateException("模型没加载");
            }
        };
        String text = "手机号13800138000";
        Rule phone = new Rule("phone", "手机号", null, "1\\d{10}", "手机号", 500, true,
                Category.REGEX, null);

        var e = assertThrows(IllegalStateException.class,
                () -> RedactionEngine.redact(text, List.of(phone), new TokenAllocator(text),
                        Exclusions.NONE, null, List.of(boom)),
                "模型挂了还照出文档 = 少脱且无提示");
        assertEquals("模型没加载", e.getMessage());
    }

    @Test
    @DisplayName("已占位化的片段不会被 AI 再次脱敏（套娃防护对 AI 同样生效）")
    void 套娃防护() {
        // 正文里已经有一个令牌化的机构名片段
        String text = "甲方<机构名1>，乙方北京某某科技有限公司";
        RedactionResult r = RedactionEngine.redact(text, List.of(), new TokenAllocator(text),
                Exclusions.NONE, null, List.of(orgAt(2, 8, 11, text.length())));

        // 新实体拿到的是 <机构名2> 而不是 <机构名1>：原文里已经存在 <机构名1>，
        // 分配器必须跳过它。第一版这里断言 <机构名1>，而那个输出里有两个一模一样的令牌，
        // 还原时会指向同一个值——正是这个类要防的错。
        assertEquals("甲方<机构名1>，乙方<机构名2>", r.sanitizedText(),
                "已带令牌的片段必须被跳过，且新令牌不得与已有令牌重号。实际：" + r.sanitizedText());
    }

    @Test
    @DisplayName("不带来源的旧入口行为不变")
    void 旧入口不变() {
        String text = "手机号13800138000";
        Rule phone = new Rule("phone", "手机号", null, "1\\d{10}", "手机号", 500, true,
                Category.REGEX, null);
        RedactionResult a = RedactionEngine.redact(text, List.of(phone));
        RedactionResult b = RedactionEngine.redact(text, List.of(phone), new TokenAllocator(text),
                Exclusions.NONE, null, List.of());
        assertEquals(a.sanitizedText(), b.sanitizedText(), "传空来源与不传来源必须等价");
    }
}
