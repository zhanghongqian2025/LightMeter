package com.litemask.cli;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫描件（没有文本层的 PDF）不能被当成「脱敏件」交付。
 *
 * <p><b>为什么这条要有测试</b>：没有文本层 → 脱敏跑完零命中 →
 * 产出的文件与**原件逐字相同**（图片里原封不动）。
 * 如果照常交付，用户拿到一份看着正常的「脱敏件」，而里面的内容一个字都没动 ——
 * 那是在**掩盖未处理的内容**，是本工具最不能犯的错。
 * → 命令行必须**报错并删掉产物**（退出码非 0），
 * 而不是打一句提示就把文件交出去。
 */
class ScanPdfGuardTest {

    /** 造一份「扫描件」：页面上只有一张图，一个字都没有。 */
    private static Path 扫描件(Path dir) throws Exception {
        Path out = dir.resolve("扫描件.pdf");
        BufferedImage img = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 160; y++) {
            for (int x = 0; x < 120; x++) {
                img.setRGB(x, y, (x * y) % 0xFFFFFF);
            }
        }
        File png = File.createTempFile("scan", ".png");
        ImageIO.write(img, "png", png);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject ximage = PDImageXObject.createFromByteArray(
                    doc, Files.readAllBytes(png.toPath()), "scan");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(ximage, 50, 50, 300, 400);
            }
            doc.save(out.toFile());
        }
        return out;
    }

    @Test
    @DisplayName("没有文本层的 PDF：命令报错、不产出「脱敏件」")
    void 无文本层不交付(@TempDir Path dir) throws Exception {
        Path src = 扫描件(dir);

        IOException e = assertThrows(IOException.class,
                () -> Main.redact(new String[]{src.toString()}),
                "没有文本层的 PDF 不能被当成脱敏件交付（必须报错）");
        assertTrue(e.getMessage().contains("文本层"), "错误消息要说清是文本层的问题：" + e.getMessage());

        // 产物必须**没有留下** —— 留着一份与原件逐字相同的「脱敏件」比报错更糟。
        Path redacted = dir.resolve("扫描件.redacted.pdf");
        assertFalse(Files.exists(redacted), "不该留下脱敏件产物：" + redacted);
        Path map = dir.resolve("扫描件.pdf.redacted.map.json");
        assertFalse(Files.exists(map), "不该留下映射件：" + map);
    }

    @Test
    @DisplayName("没有文本层：原件一个字都不能被改动")
    void 原件不被改动(@TempDir Path dir) throws Exception {
        Path src = 扫描件(dir);
        long before = Files.size(src);
        byte[] hashBefore = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(src));

        assertThrows(IOException.class, () -> Main.redact(new String[]{src.toString()}));

        assertEquals(before, Files.size(src), "原件被改动了");
        byte[] hashAfter = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(src));
        assertTrue(java.util.Arrays.equals(hashBefore, hashAfter), "原件的内容被改动了");
    }
}
