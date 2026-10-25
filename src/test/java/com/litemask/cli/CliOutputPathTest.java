package com.litemask.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 输出路径推导。
 *
 * <p>这里防的是一条已经真实发生过的 bug：输出文件名只取 basename、丢掉了目录，
 * 于是 `java -jar desens-core.jar redact /某/目录/合同.docx` 把结果写进了当前工作目录，
 * 用户在原目录找不到文件，还以为脱敏失败了。
 */
class CliOutputPathTest {

    @Test
    @DisplayName("输出保留原目录，只换文件名并保住 .docx 后缀")
    void keepsDirectoryAndExtension() {
        assertEquals(Path.of("/某/目录/合同.redacted.docx"),
                Main.redactedName(Path.of("/某/目录/合同.docx")));
        assertEquals(Path.of("/a/b/c/文件.redacted.docx"),
                Main.redactedName(Path.of("/a/b/c/文件.docx")));
    }

    @Test
    @DisplayName("无目录参数时退化为当前目录")
    void bareFileName() {
        assertEquals(Path.of("合同.redacted.docx"), Main.redactedName(Path.of("合同.docx")));
    }

    @Test
    @DisplayName("txt 与五种二进制格式走同一条命名规则（曾经 txt 单独产出 合同.txt.redacted）")
    void txtUsesSameRuleAsBinaryFormats() {
        // 这条防的是一次真实的不一致：txt 分支当年写的是 `input + ".redacted"`，
        // 产出 `合同.txt.redacted`。后缀不再是 .txt，双击打不开，
        // 而同一条命令里的 docx 产出的是 `合同.redacted.docx`。
        // 两种规则并存没有理由，现在统一到 redactedName。
        assertEquals(Path.of("合同.redacted.txt"), Main.redactedName(Path.of("合同.txt")));
        assertEquals(Path.of("a/b/卷宗.redacted.txt"), Main.redactedName(Path.of("a/b/卷宗.txt")));
    }

    @Test
    @DisplayName("无扩展名时只加 .redacted，不凭空补后缀")
    void noExtension() {
        assertEquals(Path.of("README.redacted"), Main.redactedName(Path.of("README")));
    }

    @Test
    @DisplayName("多层后缀只拆最后一个（a.tar.gz 变成 a.tar.redacted.gz）")
    void onlyLastExtensionIsSplit() {
        // 用 lastIndexOf('.') 而不是 indexOf('.')，所以压缩包不会被拆成 a.redacted.tar.gz。
        assertEquals(Path.of("a.tar.redacted.gz"), Main.redactedName(Path.of("a.tar.gz")));
    }

    @Test
    @DisplayName("还原件名与脱敏件名同规则：<基名>.restored<原后缀>")
    void restoredNameFollowsSameRule() {
        // 这条防的是本轮真抓到的分叉：原先还原有**六份**各自抄一遍的推导，
        // 五种二进制格式写成 合同.redacted.restored.docx，txt 却写成 合同.redacted.txt.restored。
        assertEquals(Path.of("合同.redacted.restored.docx"), Main.restoredName(Path.of("合同.redacted.docx")));
        assertEquals(Path.of("合同.redacted.restored.txt"), Main.restoredName(Path.of("合同.redacted.txt")));
        assertEquals(Path.of("a/b/卷宗.redacted.restored.pdf"), Main.restoredName(Path.of("a/b/卷宗.redacted.pdf")));
        assertEquals(Path.of("README.restored"), Main.restoredName(Path.of("README")));
        assertEquals(Path.of("a.tar.restored.gz"), Main.restoredName(Path.of("a.tar.gz")));
    }

    @Test
    @DisplayName("脱敏再还原不会互相吃掉对方")
    void redactThenRestoreNamesDoNotCollide() {
        // 对同一份文件跑两轮脱敏：两次产物与两次映射必须互不相同。
        // 映射从**输入名**派生正是为了这个。
        Path a = Main.redactedName(Path.of("合同.docx"));
        Path b = Main.redactedName(a);
        assertEquals(Path.of("合同.redacted.docx"), a);
        assertEquals(Path.of("合同.redacted.redacted.docx"), b);
        assertEquals(Path.of("合同.redacted.restored.docx"), Main.restoredName(a));
        assertEquals(Path.of("合同.redacted.redacted.restored.docx"), Main.restoredName(b));
    }

    @Test
    @DisplayName("相对路径同样保留目录层级")
    void relativePathKeepsParent() {
        assertEquals(Path.of("out/合同.redacted.docx"),
                Main.redactedName(Path.of("out/合同.docx")));
    }

    @Test
    @DisplayName("扩展名判断不区分大小写，且不误判 .doc 等老格式")
    void docxDetection() {
        assertTrue(Main.isDocx(Path.of("合同.DOCX")));
        assertTrue(Main.isDocx(Path.of("合同.Docx")));
        assertFalse(Main.isDocx(Path.of("合同.doc")));
        assertFalse(Main.isDocx(Path.of("合同.txt")));
        assertFalse(Main.isDocx(Path.of("合同.docx.bak")));
    }
}
