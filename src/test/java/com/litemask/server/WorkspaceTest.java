package com.litemask.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Workspace} 的测试。
 *
 * <p>重点在<b>文件真的落在哪</b>，而不是返回值长得对不对：
 * 「返回的 Path 字符串看起来没穿越」是一次纯字符串断言，
 * 一旦实现里某处多做了一次 normalize 或 resolve.sibling，它照样会绿。
 * 所以穿越用例都用真实的哨兵目录来验——文件有没有真的写出去，看目录里多没多东西。
 */
class WorkspaceTest {

    private static final byte[] CONTENT = "hello".getBytes(StandardCharsets.UTF_8);

    @Test
    void 正常上传落在会话目录内(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            Workspace.StoredFile f = ws.store(CONTENT, "合同.docx");

            assertTrue(Files.isRegularFile(f.path()), "文件应真的存在");
            assertEquals(CONTENT.length, Files.size(f.path()));
            assertEquals("合同.docx", f.displayName());
            assertEquals(".docx", f.extension());
            assertTrue(f.path().startsWith(ws.sessionDir()),
                    "应落在会话目录内，实际 " + f.path());
        }
    }

    @Test
    @DisplayName("磁盘名与外部文件名无关，且不是 UUID 加原名")
    void 磁盘名不可从外部推导(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            Workspace.StoredFile f = ws.store(CONTENT, "身份证扫描件.pdf");
            assertNotEquals("身份证扫描件.pdf", f.diskName(),
                    "磁盘名若等于客户端文件名，路径拼接的风险原样回来了");
            assertTrue(f.diskName().matches("[0-9a-f]{32}\\.pdf"), f.diskName());
        }
    }

    /**
     * 映射件的名字由服务端生成，且它的后缀<b>不进上传白名单</b>。
     *
     * <p>两条是一件事的两面：映射件是服务端自己产的东西，
     * 所以它不能由「用户能传什么」来决定叫什么；而它用的 {@code .json}
     * 也不能因此变成一个用户能传进来的后缀——那是「上传白名单」，
     * 管的是用户文件，不是服务端产物。
     */
    @Test
    @DisplayName("映射件名字由服务端生成，且 .json 不在上传白名单里")
    void 映射件名字与上传白名单(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            String name = ws.newMappingName();
            assertTrue(name.matches("[0-9a-f]{32}\\.json"), name);

            // 上传白名单不能因为映射件用 .json 就被放宽
            assertThrows(Workspace.UnsupportedFileTypeException.class,
                    () -> ws.store(CONTENT, "evil.json"),
                    ".json 进了上传白名单：用户就能往服务里传任意 JSON");
        }
    }

    @Test
    @DisplayName("客户端文件名里的 ../ 不得写到工作目录之外（用真实哨兵目录验）")
    void 路径穿越写不出去(@TempDir Path tmp) throws Exception {
        // 哨兵放在工作目录的<b>旁边</b>：如果实现真的拼了路径，这里会凭空多出文件。
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));
        Path workspaceRoot = Files.createDirectories(scratch.resolve("ws"));
        Path canary = Files.createDirectories(scratch.resolve("canary"));

        try (Workspace ws = Workspace.open(workspaceRoot)) {
            Workspace.StoredFile f = ws.store(CONTENT, "../../../canary/pwned.pdf");

            // 文件确实收下了（.pdf 是白名单内的后缀），但必须落在会话目录里
            assertTrue(f.path().startsWith(ws.sessionDir()),
                    "穿越文件名导致的落盘位置越界: " + f.path());
            assertTrue(Files.isRegularFile(f.path()));

            // 哨兵目录必须还是空的
            try (Stream<Path> kids = Files.list(canary)) {
                List<Path> escaped = kids.toList();
                assertTrue(escaped.isEmpty(),
                        "文件写到了工作目录之外: " + escaped);
            }
        }
    }

    /**
     * 暂存阶段也不许把客户端文件名拼进路径。
     *
     * <p><b>为什么这条要单独测</b>：「上传先落盘」之后，文件<b>先</b>落到暂存件上，
     * 再由「认领」挪成正式件。于是路径拼接这件事发生了<b>两次</b>——
     * 正式件那一次由服务端生成磁盘名，天然安全；可暂存件这一次，
     * 如果把客户端文件名直接拼进 {@code resolve}，原文档就已经写到工作目录之外了。
     * 后面的认领只是把它挪回来，中间那一瞬它已经在外面（而且 {@code newOutputStream}
     * 会把同名文件截断）。
     *
     * <p>所以这条用例盯的是<b>暂存件</b>的落盘位置，不是最终位置——
     * 最终位置那一条（{@code 路径穿越写不出去}）在这条路径上反而会被「认领」掩盖过去。
     */
    @Test
    @DisplayName("暂存阶段也不许把客户端文件名拼进路径（用真实哨兵目录验）")
    void 暂存件的落盘位置不受客户端文件名影响(@TempDir Path tmp) throws Exception {
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));
        Path workspaceRoot = Files.createDirectories(scratch.resolve("ws"));
        Path canary = Files.createDirectories(scratch.resolve("canary"));

        try (Workspace ws = Workspace.open(workspaceRoot)) {
            Workspace.StagedFile staged = ws.stage(
                    new ByteArrayInputStream(CONTENT), "../../../canary/pwned.pdf");

            assertTrue(staged.path().startsWith(ws.sessionDir()),
                    "暂存件落到了会话目录之外: " + staged.path());
            assertEquals(ws.sessionDir(), staged.path().getParent(),
                    "暂存件不该落进任何子目录: " + staged.path());
            try (Stream<Path> kids = Files.list(canary)) {
                List<Path> escaped = kids.toList();
                assertTrue(escaped.isEmpty(),
                        "暂存件写到了工作目录之外: " + escaped);
            }
        }
    }

    @ParameterizedTest(name = "拒绝穿越式文件名: {0}")
    @ValueSource(strings = {
            "../../../../../../etc/passwd.pdf",
            "/etc/cron.d/evil.docx",
            "..\\..\\..\\Windows\\System32\\evil.exe.pdf",
            "C:\\Windows\\System32\\evil.pdf",
            "....//....//x.pdf",
            "..%2f..%2fx.pdf",
            "a/b/c/d.pdf"
    })
    void 穿越式文件名只被剥成basename(String name) throws Exception {
        // 注意：这里断言的不是「拒绝」，而是「只取 basename，磁盘名与它无关」。
        // 拒绝与否由后缀白名单决定；文件名本身无论长什么样都不参与路径拼接。
        try (Workspace ws = Workspace.open(Files.createTempDirectory("ws"))) {
            Workspace.StoredFile f = ws.store(CONTENT, name);
            assertTrue(f.path().startsWith(ws.sessionDir()), "落盘位置越界: " + f.path());
            assertTrue(f.path().getParent().equals(ws.sessionDir()),
                    "不应落进子目录: " + f.path());
            // 显示名里不能再有路径分隔符
            assertFalse(f.displayName().contains("/"), f.displayName());
            assertFalse(f.displayName().contains("\\"), f.displayName());
        }
    }

    @Test
    void 显示名剥掉控制字符与引号(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            // 文件名里塞 HTML 与控制字符：显示名会回到界面上，
            // 前端必须用 textContent，但服务端也不该原样透传。
            String evil = "a\"b<script>alert(1)</script>\u0000\n.docx";
            Workspace.StoredFile f = ws.store(CONTENT, evil);
            assertFalse(f.displayName().contains("\""), f.displayName());
            assertFalse(f.displayName().contains("<"), f.displayName());
            for (int i = 0; i < f.displayName().length(); i++) {
                char c = f.displayName().charAt(i);
                assertFalse(c < 0x20, "残留控制字符 U+" + Integer.toHexString(c));
            }
        }
    }

    @Test
    @DisplayName("剥完没有名字的，占位显示名只在报错文案里出现")
    void 纯路径名给占位显示名(@TempDir Path tmp) throws Exception {
        // 先说清楚为什么不是「store 一个叫 .. 的文件」：
        // 能被存下的文件名必然含有白名单内的后缀，剥掉路径后不可能为空，
        // 所以 占位分支经 store 是不可达的——它真正服务的路径是
        // 「客户端报了个根本没有文件名的怪东西」时的报错文案。测那条真实路径。
        assertEquals("未命名文件", Workspace.displayNameOf(".."));
        assertEquals("未命名文件", Workspace.displayNameOf("/"));
        assertEquals("未命名文件", Workspace.displayNameOf("   "));
        assertEquals("未命名文件", Workspace.displayNameOf(null));
        assertEquals("未命名文件", Workspace.displayNameOf("."));

        try (Workspace ws = Workspace.open(tmp)) {
            Workspace.UnsupportedFileTypeException e = assertThrows(
                    Workspace.UnsupportedFileTypeException.class,
                    () -> ws.store(CONTENT, ".."));
            assertTrue(e.getMessage().contains("未命名文件"),
                    "报错里应当用占位名而不是空白: " + e.getMessage());
        }
    }

    @Test
    void 超长显示名被截断(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            Workspace.StoredFile f = ws.store(CONTENT, "长".repeat(500) + ".pdf");
            assertTrue(f.displayName().length() <= 120, "长度 " + f.displayName().length());
            assertTrue(f.displayName().endsWith(".pdf"));
        }
    }

    @ParameterizedTest(name = "后缀不在白名单内: {0}")
    @ValueSource(strings = {
            "evil.html", "evil.htm", "evil.svg", "page.xhtml",
            "run.jar", "run.sh", "run.exe", "run.bat", "run.ps1",
            "a.php", "a.js", "noextension", "trailing.", ".pdf"
    })
    void 非白名单后缀拒绝(String name) throws Exception {
        try (Workspace ws = Workspace.open(Files.createTempDirectory("ws"))) {
            assertThrows(Workspace.UnsupportedFileTypeException.class,
                    () -> ws.store(CONTENT, name), "应当拒绝: " + name);
        }
    }

    @Test
    @DisplayName(".HTML 之类必须拒——大小写不敏感地判，拒的时候说的是真实类型")
    void 后缀大小写不敏感(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            assertEquals(".pdf", ws.store(CONTENT, "A.PDF").extension());
            assertEquals(".docx", ws.store(CONTENT, "B.DocX").extension());
            Workspace.UnsupportedFileTypeException e = assertThrows(
                    Workspace.UnsupportedFileTypeException.class,
                    () -> ws.store(CONTENT, "C.HTML"));
            assertTrue(e.getMessage().contains(".html"),
                    "报错要说清是什么类型: " + e.getMessage());
        }
    }

    @Test
    void 六种支持的格式都收(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            for (String ext : List.of(".docx", ".xlsx", ".pptx", ".pdf", ".ofd", ".txt")) {
                assertEquals(ext, ws.store(CONTENT, "样本" + ext).extension());
            }
        }
    }

    @Test
    @DisplayName("超限判定生效，且判定本身可直接验证（不为测试真造 256MB）")
    void 超限拒绝(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            // 直接打判定，而不是先分配 256MB 再看它被拒：
            // 后者要么让测试跑得极慢，要么为了迁就内存把上限调小——
            // 而调小的上限就不再是生产用的那个上限了，测了个别的东西。
            ws.checkUploadSize(0);
            ws.checkUploadSize(Workspace.MAX_UPLOAD_BYTES);
            assertThrows(Workspace.FileTooLargeException.class,
                    () -> ws.checkUploadSize(Workspace.MAX_UPLOAD_BYTES + 1));

            assertEquals(".pdf", ws.store(CONTENT, "正常大小.pdf").extension(),
                    "未超限的文件必须正常收下");
            assertTrue(Workspace.MAX_UPLOAD_BYTES >= 64L * 1024 * 1024,
                    "上限至少要够放一份真实文档");
        }
    }

    @Test
    @DisplayName("超限报错说的是实际上限，不是代码里那个 256MB 的常量")
    void 超限报错说的是实际上限(@TempDir Path tmp) throws Exception {
        // 上限已经是实例参数了，报错文案如果去读静态常量 MAX_UPLOAD_BYTES，
        // 那么上限一旦不是 256MB，它就会告诉用户一个错的数——
        // 用户会以为自己还有 256MB 的余量，于是反复重传同一个文件。
        //
        // 这条断言要同时说两件事：说了真上限，且**没有**拿 256MB 说事。
        // 只说前半句的话，文案变成「文件超过 0MB 上限」也算过。
        try (Workspace 小 = Workspace.open(tmp, 4096)) {
            Workspace.FileTooLargeException e = assertThrows(
                    Workspace.FileTooLargeException.class,
                    () -> 小.stage(new ByteArrayInputStream(new byte[8192]), "大文件.docx"),
                    "超过上限必须被拒");
            assertTrue(e.getMessage().contains("4KB"),
                    "报错里必须说的是实际上限（4KB）：" + e.getMessage());
            assertFalse(e.getMessage().contains("256MB"),
                    "报错里不能拿 256MB 那个静态常量说事：" + e.getMessage());
            assertEquals(4096L, e.limit(), "异常必须带着实际上限");
        }
    }

    @Test
    @DisplayName("resolve 只认本次会话里真实存在的文件")
    void resolve校验(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            Workspace.StoredFile f = ws.store(CONTENT, "a.pdf");
            assertEquals(f.path(), ws.resolve(ws.sessionId(), f.diskName()));

            // 别的会话 id
            assertThrows(IOException.class, () -> ws.resolve(
                    "0".repeat(32), f.diskName()));
            // 本会话 id 但格式不对
            assertThrows(IOException.class, () -> ws.resolve(ws.sessionId(), "../../../etc/passwd"));
            assertThrows(IOException.class, () -> ws.resolve(ws.sessionId(), "..%2fx.pdf"));
            assertThrows(IOException.class, () -> ws.resolve(null, f.diskName()));
            assertThrows(IOException.class, () -> ws.resolve(ws.sessionId(), null));
            // 格式合法但文件不存在
            assertThrows(IOException.class, () -> ws.resolve(ws.sessionId(), "a".repeat(32) + ".pdf"));
        }
    }

    @Test
    @DisplayName("两个会话之间互相看不到对方的文件")
    void 会话隔离(@TempDir Path tmp) throws Exception {
        Path root = Files.createTempDirectory(tmp, "shared");
        String aId;
        Workspace.StoredFile aFile;
        try (Workspace a = Workspace.open(root)) {
            aId = a.sessionId();
            aFile = a.store(CONTENT, "甲.pdf");
            try (Workspace b = Workspace.open(root)) {
                assertNotEquals(aId, b.sessionId(), "两次打开应是不同会话");
                // b 拿 a 的会话 id 去解析
                assertThrows(IOException.class, () -> b.resolve(aId, aFile.diskName()));
            }
        }
    }

    @Test
    @DisplayName("关闭后整棵会话目录被删除")
    void close清理目录(@TempDir Path tmp) throws Exception {
        Path root = Files.createTempDirectory(tmp, "root");
        Path sessionDir;
        try (Workspace ws = Workspace.open(root)) {
            sessionDir = ws.sessionDir();
            ws.store(CONTENT, "a.pdf");
            assertTrue(Files.isDirectory(sessionDir));
        }
        assertFalse(Files.exists(sessionDir), "退出后会话目录应被删除");
        // 根目录留着（它是用户可能指定的目录，不该被我们删掉）
        assertTrue(Files.isDirectory(root));
    }

    @Test
    void 关闭后拒绝继续存(@TempDir Path tmp) throws Exception {
        Workspace ws = Workspace.open(tmp);
        ws.close();
        assertThrows(IOException.class, () -> ws.store(CONTENT, "a.pdf"));
        // 再关一次不该炸
        ws.close();
    }

    @Test
    @DisplayName("会话目录在固定根目录下的 sessions/<id> 里")
    void 目录布局(@TempDir Path tmp) throws Exception {
        try (Workspace ws = Workspace.open(tmp)) {
            assertEquals(tmp.resolve("sessions").resolve(ws.sessionId()), ws.sessionDir());
            assertTrue(ws.sessionId().matches("[0-9a-f]{32}"));
        }
    }
}
