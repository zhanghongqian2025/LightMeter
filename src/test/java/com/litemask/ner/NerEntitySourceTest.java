package com.litemask.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.litemask.core.Match;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NerEntitySource}：NER 结果适配成 {@link Match}。
 *
 * <p>分两批测，用意不同：
 * <ul>
 *   <li><b>翻译逻辑</b>用注入式识别器测——区间越界、返回 null、抛异常这些分支
 *       不需要真的跑 ONNX。把它们和「模型能不能加载」绑在一起，
 *       会让「翻译写错了」和「模型跑不动了」两类故障混成同一个红。</li>
 *   <li><b>端到端</b>用真实夹具跑一次，证明 NerRecognizer → EntitySpan → Match
 *       这条链真的通，且区间能切回原文。</li>
 * </ul>
 */
class NerEntitySourceTest {

    private static final int MAX_ENTITY = 4;

    private OnnxRunner runner;

    private static NerEntitySource sourceOf(List<EntitySpan> spans) {
        return new NerEntitySource("fake", t -> spans);
    }

    @BeforeEach
    void setUp() throws Exception {
        runner = OnnxRunner.open(fixture(), "ids", "logits");
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.close();
        }
    }

    private static byte[] fixture() {
        try (InputStream in = NerEntitySourceTest.class.getResourceAsStream("/ner-fixture.onnx")) {
            if (in == null) {
                throw new IllegalStateException("找不到测试夹具 /ner-fixture.onnx");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CharVocab vocab() {
        return CharVocab.ofListedChars(List.of("。", "张", "三", "丰", "北", "京", "大", "学"));
    }

    private NerEntitySource realSource() {
        return new NerEntitySource(new NerRecognizer(vocab(), runner, TagScheme.bio(), null,
                NerRecognizer.ScoreSpace.LOGIT, 16, MAX_ENTITY, MAX_ENTITY));
    }

    // ---------- 翻译逻辑 ----------

    @Test
    @DisplayName("人名与机构名翻成不同的规则键与令牌前缀")
    void 翻译成Match() {
        String text = "张三丰到北京大学。";
        List<Match> got = sourceOf(List.of(
                new EntitySpan(0, 3, EntityType.PERSON),
                new EntitySpan(4, 8, EntityType.ORGANIZATION))).collect(text);

        assertEquals(2, got.size(), "实际 " + got);

        Match person = got.get(0);
        assertEquals(0, person.start());
        assertEquals(3, person.end());
        assertEquals("张三丰", person.text());
        assertEquals(NerEntitySource.RULE_PERSON, person.ruleKey());
        assertEquals("人名", person.tokenPrefix());

        Match org = got.get(1);
        assertEquals(4, org.start());
        assertEquals(8, org.end());
        assertEquals("北京大学", org.text());
        assertEquals(NerEntitySource.RULE_ORGANIZATION, org.ruleKey());
        assertEquals("机构名", org.tokenPrefix());

        // 前提断言：区间必须真的能切出这两个词。
        // 少了它，上面那些断言可能在「区间是错的」的情况下也成立——
        // 而错区间正是本类最该拦的东西。
        assertEquals("张三丰", text.substring(person.start(), person.end()));
        assertEquals("北京大学", text.substring(org.start(), org.end()));
    }

    @Test
    @DisplayName("NER 优先级高于全部正则规则（规则文件实测最高 120）")
    void 优先级压过正则() {
        assertTrue(NerEntitySource.SORT_ORDER > 120,
                "NER 优先级 " + NerEntitySource.SORT_ORDER + " 必须高于正则实测最大值 120，"
                        + "否则「北京某某科技有限公司」会被只命中「北京」的正则切成两截");
    }

    @Test
    @DisplayName("空文本不调识别器")
    void 空文本不调识别器() {
        boolean[] called = {false};
        NerEntitySource src = new NerEntitySource("fake", t -> {
            called[0] = true;
            return List.of();
        });
        assertEquals(List.of(), src.collect(""));
        assertTrue(!called[0], "空文本不该走到识别器");
    }

    @Test
    @DisplayName("识别器返回 null 时报错，不能当成「这段没有实体」")
    void 返回null要报错() {
        NerEntitySource src = new NerEntitySource("fake", t -> null);
        // 前提：空文本走的是另一条分支（直接返回空列表），不会走到这里
        assertEquals(List.of(), src.collect(""));
        var e = assertThrows(NerRecognizer.NerInferenceException.class,
                () -> src.collect("张三丰"),
                "返回 null 会把「识别器坏了」说成「没有实体」，是静默漏脱");
        assertTrue(e.getMessage().contains("null"), "报错要说清是 null，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("识别抛异常时向上传播，不降级成「没有实体」")
    void 识别异常要传播() {
        NerEntitySource src = new NerEntitySource("fake", t -> {
            throw new OrtException(OrtException.OrtErrorCode.ORT_FAIL, "夹具故障");
        });
        var e = assertThrows(NerRecognizer.NerInferenceException.class,
                () -> src.collect("张三丰"),
                "识别失败必须让调用方知道；返回空列表等于让模型挂了还照出文档");
        assertTrue(e.getMessage().contains("夹具故障") || e.getCause() != null,
                "要么带上原因，要么保留 cause，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("区间越界一律拒绝：截断或丢弃都会让正文少字")
    void 区间越界要拒绝() {
        String text = "张三丰到北京大学。";   // 9 字

        // 终点越界：区间本身合法，但越出了文本
        var e1 = assertThrows(NerRecognizer.NerInferenceException.class,
                () -> sourceOf(List.of(new EntitySpan(0, 99, EntityType.PERSON))).collect(text));
        assertTrue(e1.getMessage().contains("超出文本长度"), "实际：" + e1.getMessage());

        // 起点为负：EntitySpan 构造期就拦下，根本到不了适配层
        assertThrows(IllegalArgumentException.class,
                () -> new EntitySpan(-1, 3, EntityType.PERSON));

        // 零长与倒挂同样在 EntitySpan 构造期被拦。
        // 这两道是纵深防御，适配层自己还有第三道（checkSpan）。
        // 断言成同一个异常类型会掩盖「到底哪一层在挡」，所以这里分开写。
        assertThrows(IllegalArgumentException.class,
                () -> new EntitySpan(2, 2, EntityType.PERSON));
        assertThrows(IllegalArgumentException.class,
                () -> new EntitySpan(5, 2, EntityType.PERSON));
    }

    @Test
    @DisplayName("越界不能被静默修正：一条越界就整条拒绝，不影响别的命中")
    void 越界影响整批() {
        String text = "张三丰到北京大学。";
        assertThrows(NerRecognizer.NerInferenceException.class,
                () -> sourceOf(List.of(
                        new EntitySpan(0, 3, EntityType.PERSON),      // 合法
                        new EntitySpan(4, 99, EntityType.ORGANIZATION) // 越界
                )).collect(text),
                "半批成功会让用户拿到一份少脱了机构名的文件，而且报告正常");
    }

    // ---------- 端到端（真实 ORT + 玩具夹具） ----------

    @Test
    @DisplayName("端到端：真实夹具的识别结果能翻成 Match 且区间切得回原文")
    void 端到端() {
        String text = "张三丰到北京大学。";
        List<Match> got = realSource().collect(text);

        assertEquals(2, got.size(), "夹具里「张三丰」是人名、「北京大学」是机构名，实际 " + got);
        assertEquals("张三丰", got.get(0).text());
        assertEquals(NerEntitySource.RULE_PERSON, got.get(0).ruleKey());
        assertEquals("北京大学", got.get(1).text());
        assertEquals(NerEntitySource.RULE_ORGANIZATION, got.get(1).ruleKey());

        // 前提：区间必须与原值一致，否则下面的比对是空的
        assertEquals(text.substring(got.get(0).start(), got.get(0).end()), got.get(0).text());
        assertEquals(text.substring(got.get(1).start(), got.get(1).end()), got.get(1).text());
    }

    @Test
    @DisplayName("端到端：没有实体的文本返回空列表而不是 null")
    void 端到端无实体() {
        assertEquals(List.of(), realSource().collect("。。。。。"));
    }
}
