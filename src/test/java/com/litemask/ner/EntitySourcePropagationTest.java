package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.litemask.core.Change;
import com.litemask.core.EntitySource;
import com.litemask.core.Exclusions;
import com.litemask.core.Exclusions.HitRef;
import com.litemask.core.Match;
import com.litemask.core.RedactionResult;
import com.litemask.core.RedactionSession;
import com.litemask.core.Rule;
import com.litemask.core.RuleLoader;
import com.litemask.docx.DocxFixture;
import com.litemask.docx.DocxRedactor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NER 来源贯穿整篇文档的不变量。
 *
 * <p>这一组只测一件事：<b>来源必须是会话级的一份，不是每个部件各建一份</b>。
 *
 * <p>为什么单独拎出来测：它是那种「错了也不报错」的不变量。每个部件各自 new 一个来源，
 * 文档照样正常生成、报告照样说命中 N 处、界面照样列出条目，只是
 * ① 同一个机构名在不同部件拿到不同编号，还原时对不上；
 * ② 位置级排除（用户在界面上勾「第 2 次那处保留」）勾到的不是它以为的那一处。
 * 两种后果都要等用户拿还原件去比对才发现，那时已经流出去了。
 *
 * <p>判据用「负向」性质而不是「同值同符」：两个分配器各自从头发号时，
 * 同一个值恰好会拿到相同编号——「同值同符」在错误实现下天然成立，测不出问题。
 * 必须再配一条「不同值不得共用同一编号」，错误实现才会露出来。
 */
class EntitySourcePropagationTest {

    private static final List<Rule> RULES = RuleLoader.loadFromClasspath("rules/core-rules.json");
    private static final String ORG_A = "北京星辰律师事务所";
    private static final String ORG_B = "上海远洋公证处";
    private static final String ID_A = "11010519491231002X";

    @TempDir
    Path dir;

    /** 一个只会按字面量命中固定机构的假来源，用来证明「来源确实传到了每一个部件」。 */
    private static EntitySource fakeOrgSource(String... orgs) {
        List<String> targets = List.of(orgs);
        return new EntitySource() {
            @Override
            public String name() {
                return "fake-org";
            }

            @Override
            public List<Match> collect(String text) {
                List<Match> out = new ArrayList<>();
                for (String org : targets) {
                    int from = 0;
                    int at;
                    while ((at = text.indexOf(org, from)) >= 0) {
                        out.add(new Match(at, at + org.length(), org,
                                "fake-org", "机构名", 900));
                        from = at + org.length();
                    }
                }
                return out;
            }
        };
    }

    // ------------------------------------------------------------------ 会话级

    @Test
    @DisplayName("同一机构名跨段落共用同一令牌")
    void sameOrgSharesTokenAcrossParagraphs() {
        RedactionSession session = new RedactionSession(
                "甲 " + ORG_A + " 乙 " + ORG_A, RULES, Exclusions.NONE,
                List.of(fakeOrgSource(ORG_A)));
        String a = session.redact("甲 " + ORG_A).sanitizedText();
        String b = session.redact("乙 " + ORG_A).sanitizedText();
        assertEquals("甲 <机构名1>", a);
        // 第二段**必须**继续用 <机构名1>。若每段各建一份来源，
        // 这里会是 <机构名1>（恰好相同），所以这一条本身证明不了什么——
        // 它只保证没有出现 <机构名2> 这种更明显的错，见下面那条。
        assertEquals("乙 <机构名1>", b);
    }

    @Test
    @DisplayName("不同段落的不同机构必须拿到不同编号")
    void differentOrgsGetDifferentNumbersAcrossParagraphs() {
        RedactionSession session = new RedactionSession(
                ORG_A + ORG_B, RULES, Exclusions.NONE, List.of(fakeOrgSource(ORG_A, ORG_B)));
        assertEquals("<机构名1>", session.redact(ORG_A).sanitizedText());
        // 这条才是「共用分配器」的真正判据：每段各建一份来源时，
        // 两个段各自的首个机构都会拿到 <机构名1>，而映射里同一个令牌对应两个原值。
        assertEquals("<机构名2>", session.redact(ORG_B).sanitizedText());
        assertEquals(2, session.mapping().size(), "两个原值必须各自占一个令牌");
    }

    @Test
    @DisplayName("来源与正则共用同一个分配器，编号互不打架")
    void sharesAllocatorWithRules() {
        RedactionSession session = new RedactionSession(
                ORG_A + ID_A, RULES, Exclusions.NONE, List.of(fakeOrgSource(ORG_A)));
        assertEquals("<机构名1>", session.redact(ORG_A).sanitizedText());
        assertEquals("<身份证1>", session.redact(ID_A).sanitizedText());
        // 令牌空间是共享的，所以两个原值不能撞到同一个令牌上
        assertEquals(2, session.mapping().size());
    }

    @Test
    @DisplayName("来源命中参与位置级排除：勾掉的那处原样保留")
    void positionalExclusionAppliesToSourceHits() {
        RedactionSession session = new RedactionSession(
                ORG_A + ORG_B, RULES, Exclusions.NONE, List.of(fakeOrgSource(ORG_A, ORG_B)));
        RedactionResult first = session.redact(ORG_A);
        session.redact(ORG_B);
        // 清单里的每一条都带 (规则键, 原值, 第几次出现)，界面拿它当「第 N 次」的身份标识。
        assertEquals(1, first.changes().size(), "第一段应当只报一条命中：" + first.changes());
        Change hit = first.changes().get(0);
        assertEquals("fake-org", hit.ruleKey());
        Exclusions onlyFirst = Exclusions.of(List.of(
                new HitRef(hit.ruleKey(), hit.original(), HitRef.ANY)));
        RedactionSession excluded = new RedactionSession(
                ORG_A + ORG_B, RULES, onlyFirst, List.of(fakeOrgSource(ORG_A, ORG_B)));
        assertEquals(ORG_A, excluded.redact(ORG_A).sanitizedText(), "被排除的那处必须原样保留");
        assertEquals("<机构名1>", excluded.redact(ORG_B).sanitizedText(), "另一处不受影响");
    }

    @Test
    @DisplayName("隐藏路径照常脱敏，但不消耗出现序号")
    void hiddenPathStillRunsSources() {
        RedactionSession session = new RedactionSession(
                ORG_A, RULES, Exclusions.NONE, List.of(fakeOrgSource(ORG_A)));
        RedactionResult hidden = session.redactHidden(ORG_A);
        assertEquals("<机构名1>", hidden.sanitizedText(),
                "隐藏路径必须照常脱敏，只是不消耗出现序号");
        // 隐藏的命中仍要进还原映射：附件条目名里那个机构名，用户拿还原件回去比对时
        // 也得能对上，否则「还原后与原文一致」这句话在附件名上是假的。
        assertEquals(ORG_A, session.restore("<机构名1>"), "隐藏路径的命中必须可还原");
    }

    @Test
    @DisplayName("隐藏路径不抢走正文的位置号：勾「第 1 次」勾到的就是正文那处")
    void hiddenPathDoesNotShiftOccurrenceNumbering() {
        // **两套计数器必须分清**，否则这一条会写成一条永远为真的断言：
        // · TokenAllocator 按 tokenPrefix 全局发号，隐藏路径会占掉一个号，
        //   于是正文的机构名可能拿到 <机构名2>。令牌号只是标识符，中间有空洞不影响还原，
        //   界面显示"为什么从 2 开始"顶多是不好看——**不能拿它当判据**。
        // · OccurrenceNumbering 按 (规则键, 原值) 计「第几次出现」，位置级排除只认它。
        //   隐藏路径若推进了它，用户勾「第 1 次」勾到的就不是他以为的那一处。
        // 所以判据只能建立在 occurrence 上，而 occurrence 又只能通过排除项被观察到。
        RedactionSession probe = new RedactionSession(
                ORG_A + ORG_B, RULES, Exclusions.NONE, List.of(fakeOrgSource(ORG_A, ORG_B)));
        probe.redactHidden(ORG_A);
        Change bodyHit = probe.redact(ORG_B).changes().get(0);

        Exclusions keepFirst = Exclusions.of(List.of(
                new HitRef(bodyHit.ruleKey(), bodyHit.original(), 0)));
        RedactionSession onFirst = new RedactionSession(
                ORG_A + ORG_B, RULES, keepFirst, List.of(fakeOrgSource(ORG_A, ORG_B)));
        // 隐藏路径拿不到序号（设计上就是「不编号」），所以位置级排除对它不生效，
        // 这里照样脱敏——这与用户操作一致：清单里没有这一条，他也没有地址去勾它。
        assertEquals("<机构名1>", onFirst.redactHidden(ORG_A).sanitizedText(),
                "隐藏路径不编号，位置级排除覆盖不到它");
        assertEquals(ORG_B, onFirst.redact(ORG_B).sanitizedText(),
                "occurrence=0 必须指向正文那一处：若隐藏路径消耗了序号，这里就对不上");

        // 反向断言，排除项不能空转成"什么都放行"。
        // 只写上面那条的话，一个把 occurrence 恒定当成 0 的实现同样能过。
        Exclusions keepSecond = Exclusions.of(List.of(
                new HitRef(bodyHit.ruleKey(), bodyHit.original(), 1)));
        RedactionSession onSecond = new RedactionSession(
                ORG_A + ORG_B, RULES, keepSecond, List.of(fakeOrgSource(ORG_A, ORG_B)));
        onSecond.redactHidden(ORG_A);
        String secondBody = onSecond.redact(ORG_B).sanitizedText();
        assertNotEquals(ORG_B, secondBody, "occurrence=1 不存在时不应放行任何一处");
        // 不断言具体令牌号：隐藏路径已经占掉 <机构名1>，正文这里拿到的是 2。
        // 令牌号只是标识符，中间有空洞不影响还原——**不能拿它当判据**。
        assertEquals(ORG_B, onSecond.restore(secondBody), "放行判据看的是还原，不是令牌号");
    }

    @Test
    @DisplayName("没有来源时行为不变：源为空的会话只跑规则")
    void emptySourcesIsNoOp() {
        RedactionSession session = new RedactionSession(ID_A, RULES);
        assertEquals(List.of(), session.sources());
        assertEquals("<身份证1>", session.redact(ID_A).sanitizedText());
    }

    // ------------------------------------------------------------------ 真实格式

    @Test
    @DisplayName("docx：来源在两个段落里共用一个编号")
    void docxSameTokenAcrossParagraphs() throws IOException {
        String body = DocxFixture.paragraph(DocxFixture.run(null, "甲 " + ORG_A + "；"))
                + DocxFixture.paragraph(DocxFixture.run(null, "乙 " + ORG_B + "。"));
        Path in = DocxFixture.simple(body).writeTo(dir, "ner-org.docx");
        Path out = dir.resolve("ner-org-out.docx");
        DocxRedactor.redact(in, out, RULES, Exclusions.NONE, List.of(fakeOrgSource(ORG_A, ORG_B)));
        String text = DocxFixture.text(out, "word/document.xml");
        assertEquals("甲 <机构名1>；乙 <机构名2>。", text);
        assertFalse(text.contains(ORG_A), "原机构名仍在文档里：" + text);
    }

    @Test
    @DisplayName("docx：同值跨段落仍是同一个令牌，且还原得回去")
    void docxRoundTrip() throws IOException {
        String plain = "甲方 " + ORG_A + "；乙方 " + ORG_A + "。";
        String body = DocxFixture.paragraph(DocxFixture.run(null, "甲方 " + ORG_A + "；"))
                + DocxFixture.paragraph(DocxFixture.run(null, "乙方 " + ORG_A + "。"));
        Path in = DocxFixture.simple(body).writeTo(dir, "ner-rt.docx");
        Path out = dir.resolve("ner-rt-out.docx");
        DocxRedactor.redact(in, out, RULES, Exclusions.NONE, List.of(fakeOrgSource(ORG_A)));
        String text = DocxFixture.text(out, "word/document.xml");
        assertEquals("甲方 <机构名1>；乙方 <机构名1>。", text);
        // 往返要比**纯文本**，不能拿 XML 去比。
        // DocxFixture.text 抽出来的是拼接后的可见文本，而 body 是带标签的 XML——
        // 拿两者比必然不等，而那个不等与令牌对不对毫无关系。
        // 第一版就是这么写的，红灯指向的是「还原错了」，真实原因却是比错了对象。
        RedactionSession s = new RedactionSession(plain, RULES, Exclusions.NONE,
                List.of(fakeOrgSource(ORG_A)));
        s.redact("甲方 " + ORG_A + "；");
        s.redact("乙方 " + ORG_A + "。");
        assertEquals(plain, s.restore(text), "还原后必须与原文逐字相同");
    }
}
