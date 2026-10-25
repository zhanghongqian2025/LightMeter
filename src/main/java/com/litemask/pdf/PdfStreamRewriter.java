package com.litemask.pdf;

import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 内容流回写：把命中的字符换成令牌，同时**把版面按原样摆回去**。
 *
 * <p>PDF 没有「文本节点」，替换字符会直接改变后续文字的水平位置。所以每删掉一段字符，
 * 就要在同一时刻补一个位移补偿，让渲染位置回到原来的地方。
 *
 * <p>补偿用的是 {@code TJ} 数组里的数字：规范 9.4.4 说 {@code TJ} 中的数值 n 会让位置
 * 左移 {@code n/1000 × Tfs × Th}。于是「删字符」与「补回位移」可以合并成同一个
 * {@code TJ} 算子，不需要跟踪文本矩阵，也就不用去复现 {@code Td} 累加的全部细节。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>整算子处理</b>：一个显示算子要么整段原样复制（字节不动），要么整段重写。
 *       半改半留会让 TJ 数组的元素配对错位，而那种错在渲染时往往看不出来。</li>
 *   <li><b>只改文本，不改坐标</b>：绝不发 {@code Tm/Td/T*}。一旦动了定位算子，
 *       整页布局都可能被牵动，而我们无法在不重排全文的前提下保证视觉一致。</li>
 *   <li><b>删掉的每一段都要补位移</b>，一段都不能少——见 {@link #emitRewritten} 里
 *       「跨算子续段」那段注释，漏补的后果不只是版面歪，而是原文残留在文件里。</li>
 * </ol>
 */
final class PdfStreamRewriter {

    /** 按资源名查字体；查不到返回 null，由调用方决定回退到哪一个。 */
    @FunctionalInterface
    interface FontLookup {
        PDFont find(String resourceName) throws IOException;
    }

    /**
     * 一次改动：在某个显示算子的局部文本坐标上，把 [start, end) 换成 {@code replacement}。
     *
     * <p>脱敏时 {@code replacement} 是令牌，还原时是**原文**——两者不是一回事，
     * 所以这里必须显式带上替换文本，不能只记一个「令牌」字段了事。
     * 跨算子的命中只有第一段带 {@code replacement}，其余段为 null（只删字符，不重复写入）。
     */
    record OpEdit(int start, int end, String replacement) {
    }

    /** 重建过程中发出的一段，供测试核对「哪个片段属于哪个原算子」。 */
    record Piece(int opIndex, boolean token, String text) {
    }

    /**
     * 替换文字用什么方式画。
     *
     * <ul>
     *   <li>{@link #TOKEN}：写令牌。用标准 14 字体，宽度不定，所以要补位移补偿。</li>
     *   <li>{@link #NATIVE}：把原文写回去（还原路径）。用令牌算子后面 {@code Tf} 指名的原字体，
     *       宽度自然等于原件，只需要把紧跟着的调整数改掉。</li>
     * </ul>
     */
    enum Render { TOKEN, NATIVE }

    record Result(byte[] content, List<Piece> pieces, List<String> warnings) {
    }

    /**
     * 令牌的**最大**水平缩放；实际取值由 {@link #fitScale} 按槽位宽度定，不会超过它。
     *
     * <p>曾经试过「令牌比原文宽就横向压到与原文等宽」，视觉上更整齐，但代价是还原彻底对不齐：
     * 压扁写在 {@code Tz} 上，而还原时无从知道原文的 {@code Tz} 该是多少，只能留下一个歪掉的版面。
     * 于是宁可让令牌偶尔比原文宽一点（实际绝大多数情况相反：18 位身份证远比 11 个字符的令牌宽），
     * 也要保证**脱敏与还原严格互逆**。宁可版面略挤，不可不可还原。
     *
     * <p>这个「无从知道」后来解决了——见 {@link RestoreState}：脱敏时写回的原字体
     * {@code Tf} 与 {@code Tz} 就躺在令牌后面，还原时扫回来即可。于是压缩重新可行。
     */
    static final double TOKEN_H_SCALE = 1.0;

    /**
     * 令牌允许压到的最小水平缩放。
     *
     * <p>{@code Tz} 只压横向、不压字号，所以压到 45% 仍是正常字高的窄体字，肉眼可读；
     * 再窄就成一道糊条，比「溢出压住后面的字」更难用。压到底还塞不下时选择溢出并告警——
     * 理由是**溢出只影响版面，压成糊条会让令牌本身失去可读性**，而令牌可读性是用户核对脱敏结果的依据。
     */
    static final double MIN_TOKEN_H_SCALE = 0.45;

    /**
     * 该画多少缩放才能把令牌塞进槽位。
     *
     * <p>规范 9.4.4 里 {@code tx = (w0×Tfs + Tc + Tw) × Th}，{@code Tc}／{@code Tw} 也吃 {@code Th}，
     * 所以整体宽度与 {@code Th} 成正比，按比例缩放即可，没有「字间距不缩放」的残留项。
     */
    static double fitScale(double naturalWidth, double slotWidth) {
        if (naturalWidth <= 0 || slotWidth <= 0) {
            return TOKEN_H_SCALE;
        }
        double s = slotWidth / naturalWidth;
        if (s >= TOKEN_H_SCALE) {
            return TOKEN_H_SCALE;
        }
        return Math.max(MIN_TOKEN_H_SCALE, s);
    }

    /**
     * 还原时令牌后面紧跟着的「原字体状态」。
     *
     * <p>{@link #emitToken} 在令牌 TJ 之后必定写回 {@code <原 Tz> Tz} 与 {@code /原字体 原字号 Tf}，
     * 所以这一段状态是从**文件自身**读出来的，不是外部传进来的约定——
     * 这正是压缩能够可逆的前提：还原不需要知道当初压到了多少，只要认得出「压完以后
     * 原文应该按什么状态画」。
     */
    record RestoreState(String fontName, float size, float hPercent) {
    }

    private PdfStreamRewriter() {
    }

    static Result rewrite(byte[] content, List<ContentToken> tokens, List<ShowTextOp> ops,
                          Map<Integer, List<OpEdit>> editsByOp, Render render,
                          PDFont tokenFont, String tokenFontName, FontLookup fontLookup,
                          List<String> warnings)
            throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream(content.length + 256);
        List<Piece> pieces = new ArrayList<>();
        int cursor = 0;
        int operandStart = 0;
        int opIndex = 0;

        for (int i = 0; i < tokens.size(); i++) {
            ContentToken t = tokens.get(i);
            if (!t.isBoundary()) {
                continue;
            }
            // operandStart 是**字节**偏移：上一个算子结束后的位置。操作数本身可能为空
            // （例如 Tj 前面直接跟 ET），所以它必须来自 token 的字节区间而不是下标。
            sink.write(content, cursor, operandStart - cursor);
            cursor = operandStart;

            List<OpEdit> edits = editsByOp == null ? null : editsByOp.get(opIndex);
            if (t.isOperator() && isShowText(t.text()) && edits != null && !edits.isEmpty()) {
                // 还原时原文要用**原字体**编码：令牌是拿标准 14 字体画的，中文根本没有字形。
                // 原字体与原缩放写在令牌算子紧后面那两个算子里（emitToken 回写时留下的），照着取。
                // Tz 一并取回来是必需的：令牌可能被横向压缩过，原文必须按**原来的 Tz** 画回去，
                // 照着令牌的 Tz 算会把整段文字按错误的宽度补偿出去。
                PDFont nativeFont = null;
                String nativeFontName = null;
                RestoreState restoreState = null;
                if (render == Render.NATIVE) {
                    restoreState = nextRestoreState(tokens, i);
                    if (restoreState != null) {
                        nativeFontName = restoreState.fontName();
                        nativeFont = fontLookup.find(nativeFontName);
                    }
                }
                emitRewritten(sink, ops.get(opIndex), edits, render, tokenFont, tokenFontName,
                        nativeFont, nativeFontName, restoreState, opIndex, pieces, warnings);
            } else {
                sink.write(content, operandStart, t.end() - operandStart);
            }
            cursor = t.end();
            operandStart = cursor;

            if (t.isOperator() && isShowText(t.text())) {
                opIndex++;
            }
        }
        sink.write(content, cursor, content.length - cursor);
        return new Result(sink.toByteArray(), List.copyOf(pieces), List.copyOf(warnings));
    }

    private static boolean isShowText(String op) {
        return "Tj".equals(op) || "TJ".equals(op) || "'".equals(op) || "\"".equals(op);
    }

    /**
     * 从 {@code fromIndex} 往后找令牌之后的第一组 {@code Tz} 与 {@code Tf}，即「原文该按什么状态画」。
     *
     * <p>脱敏时 {@link #emitToken} 在令牌 TJ 之后立刻写回了原缩放与原字体的 {@code Tf}，
     * 所以这个位置在我们自己产出的文件里是可靠的；文档被外部工具改过导致找不到时，
     * 调用方回退到算子当前的字体。
     */
    private static RestoreState nextRestoreState(List<ContentToken> tokens, int fromIndex) {
        Float hPercent = null;
        for (int i = fromIndex + 1; i < tokens.size(); i++) {
            ContentToken t = tokens.get(i);
            if (!t.isBoundary() || !t.isOperator()) {
                continue;
            }
            if ("Tz".equals(t.text()) && hPercent == null) {
                hPercent = parseLeadingNumber(tokens, i);
                continue;
            }
            if ("Tf".equals(t.text())) {
                String name = leadingName(tokens, i);
                Float size = parseLeadingNumber(tokens, i);
                if (name == null) {
                    return null;
                }
                return new RestoreState(name, size == null ? 0f : size,
                        hPercent == null ? 100f : hPercent);
            }
        }
        return null;
    }

    private static String leadingName(List<ContentToken> tokens, int opIndex) {
        for (int j = opIndex - 1; j >= 0; j--) {
            ContentToken o = tokens.get(j);
            if (o.isBoundary()) {
                break;
            }
            if (o.kind() == ContentToken.Kind.NAME) {
                return o.text();
            }
        }
        return null;
    }

    /** 取算子前面紧邻的那个数字操作数。 */
    private static Float parseLeadingNumber(List<ContentToken> tokens, int opIndex) {
        for (int j = opIndex - 1; j >= 0; j--) {
            ContentToken o = tokens.get(j);
            if (o.isBoundary()) {
                return null;
            }
            if (o.kind() == ContentToken.Kind.NUMBER) {
                try {
                    return Float.parseFloat(o.text());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 单个算子的重建

    private static void emitRewritten(ByteArrayOutputStream sink, ShowTextOp op, List<OpEdit> edits,
                                      Render render, PDFont tokenFont, String tokenFontName,
                                      PDFont nativeFont, String nativeFontName,
                                      RestoreState restoreState,
                                      int opIndex,
                                      List<Piece> pieces, List<String> warnings)
            throws IOException {
        if (op.font() == null || (render == Render.TOKEN && op.fontName() == null)) {
            throw new IOException("显示文本算子使用了未定义的字体资源，拒绝改写（fail-closed）");
        }
        // 还原时必须先把字体与水平缩放切回原状态再写文字。
        // 只换编码不换 Tf 的话，字节是原字体的码位、解释它们的却是令牌字体——
        // 中文会整段变成乱码，而工具照样报告「已还原」。
        //
        // Tz 同样必须写回，而且它比 Tf 更容易被漏：脱敏时令牌可能按 45% 画（见 fitScale），
        // 若还原只切回 Tf 不切 Tz，原文就会**按 45% 的宽度画回去**，字挤成一小条，
        // 后面跟着的文字全被甩到很远的 x 上——抽取出来是「13800138000 done」这种凭空多出的空格。
        // 这个坑在令牌从不压缩时是隐形的（Tz 恒为 100），所以必须显式写、显式测。
        if (render == Render.NATIVE && nativeFont != null && nativeFontName != null) {
            float backSize = restoreState == null || restoreState.size() <= 0
                    ? op.size() : restoreState.size();
            boolean fontChanged = !nativeFontName.equals(op.fontName());
            boolean scaleChanged = restoreState != null
                    && Math.abs(restoreState.hPercent() - op.hScale() * 100f) > 1e-4f;
            if (fontChanged || scaleChanged) {
                // 【开头必须先放一个分隔符】
                //   规范要求算子以空白或分隔符结束（PDF 32000-1 §7.8.2）。
                //   而这段是**插在前面那个算子后面**的：前面若是「68.2317 Tz」，
                //   直接接上「100 Tz」就粘成了「68.2317 Tz100 Tz」——
                //   「Tz100」是一个**未知算子**，读者会忽略它，
                //   原文于是按 68% 的宽度画回去（版面歪了）；
                //   紧跟着那个没有操作数的 Tz 又是第二个坏算子。
                //   实测（2026-10-20）：还原出来的 PDF 里就是这样，
                //   而我们自己的内容流扫描器也读不懂它，
                //   版面自检因此直接崩掉——缺陷藏了很久。
                //   换句话说：这里少一个换行，产出的就是一个畸形内容流。
                StringBuilder sw = new StringBuilder("\n");
                // Tz 要写在 Tf 之前：Tf 之后再设 Tz 一样有效，但先 Tz 后 Tf 与脱敏时的
                // 书写顺序一致，读内容流的人（和下一次还原）看到的顺序是同一个。
                if (scaleChanged) {
                    sw.append(fmt(restoreState.hPercent())).append(" Tz\n");
                }
                if (fontChanged) {
                    sw.append('/').append(PdfTokens.escapeName(nativeFontName)).append(' ')
                            .append(fmt(backSize)).append(" Tf\n");
                }
                sink.write(sw.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        // 每个字符串操作数的码位区间先算好：一次命中可能横跨同一个 TJ 里的多个字符串，
        // 切片要用，量「这段原本占多宽」也要用。
        List<Spans> spansAll = new ArrayList<>();
        for (Object e : op.elems()) {
            if (e instanceof byte[] codes) {
                spansAll.add(Spans.of(op.font(), codes));
            }
        }
        List<OpEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(OpEdit::start));
        double[] advance = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            OpEdit ed = sorted.get(i);
            advance[i] = glyphAdvance(op, spansAll, ed.start(), ed.end());
        }

        List<Object> buf = new ArrayList<>();
        // 还原时不是「把令牌后面那个调整数删掉」，而是把它调大。删字符的后续算子里各自还留着
        // 自己的补偿，令牌这边必须补出它们的和；直接删掉等于两边都不补，版面会多出一段空白。
        boolean adjustNextNumber = false;
        double numberDelta = 0;
        // 同一个 TJ 数字的含义是 n/1000×字号×水平缩放。脱敏时令牌那个数字是在**令牌的 Tz** 下
        // 写下的，还原时这条 TJ 跑在**原文的 Tz** 下——不换算，同一个数字会多走一倍的位移。
        // 实测：Tz 45→100 时，后面整段文字偏左 5.9pt，往返测试当场抓到。
        double numberRescale = 1.0;
        int textPos = 0;
        int stringIdx = 0;
        // 一条命中可能横跨同一个 TJ 里的多个字符串，也可能横跨多个算子。
        // 每个元素都要重新看到它，但**替换文本只能发一次**：
        // 每个元素都发一次的话，一个手机号会在页面上画出两个令牌，
        // 还原时也会被换回两遍。
        boolean[] done = new boolean[sorted.size()];

        for (Object e : op.elems()) {
            if (e instanceof Double d) {
                buf.add(adjustNextNumber ? d * numberRescale + numberDelta : d);
                adjustNextNumber = false;
                numberDelta = 0;
                numberRescale = 1.0;
                continue;
            }
            byte[] codes = (byte[]) e;
            Spans spans = spansAll.get(stringIdx++);
            String decoded = ShowTextOp.decodeString(op.font(), codes);
            int elemStart = textPos;
            int elemEnd = textPos + decoded.length();
            textPos = elemEnd;

            int p = elemStart;
            int q = 0;
            while (q < sorted.size() && sorted.get(q).end() <= elemStart) {
                q++;
            }
            while (q < sorted.size() && sorted.get(q).start() < elemEnd) {
                OpEdit ed = sorted.get(q);
                int s = Math.max(ed.start(), elemStart);
                int t = Math.min(ed.end(), elemEnd);
                if (s > p) {
                    byte[] keep = spans.slice(codes, p - elemStart, s - elemStart);
                    buf.add(keep);
                    pieces.add(new Piece(opIndex, false, new String(keep, StandardCharsets.ISO_8859_1)));
                }
                if (!done[q]) {
                    done[q] = true;
                    if (ed.replacement() != null) {
                        if (render == Render.NATIVE) {
                            PDFont nf = nativeFont != null ? nativeFont : op.font();
                            byte[] back = nf.encode(ed.replacement());
                            // 令牌原本占多宽（令牌字体 ≠ 本算子的字体，且可能横向压缩过），
                            // 换回原文后多出来或少掉的宽度，统统由紧跟着的那个调整数吸收。
                            //
                            // 两边的水平缩放**故意不同**：`natural` 用令牌的 Tz（当初就是那么画上去的），
                            // `restored` 用原文的 Tz（接下来就是要那么画）。拿同一个 Tz 算会错：
                            // 令牌画在 Tz=45%、原文该在 Tz=100% 时，按 45% 算差值会让整段错位。
                            double tokenH = op.hScale();
                            double backH = restoreState == null ? tokenH : restoreState.hPercent() / 100.0;
                            if (backH <= 0) {
                                backH = tokenH;
                            }
                            double natural = ShowTextOp.stringAdvance(op.font(), codes, op.size(),
                                    op.charSpacing(), op.wordSpacing(), tokenH);
                            double restored = ShowTextOp.stringAdvance(nf, back, op.size(),
                                    op.charSpacing(), op.wordSpacing(), backH);
                            numberDelta += shiftUnits(op.size() * backH, natural - restored, warnings);
                            // 继承来的调整数按缩放比换算：它在 Tz=tokenH 下写的，要搬到 Tz=backH 下
                            // 仍然位移同样的距离，就得乘 tokenH/backH。
                            numberRescale *= tokenH / backH;
                            adjustNextNumber = true;
                            buf.add(back);
                            pieces.add(new Piece(opIndex, true, ed.replacement()));
                        } else {
                            flushTj(sink, buf);
                            emitToken(sink, op, ed.replacement(), tokenFont, tokenFontName,
                                    advance[q], warnings);
                            pieces.add(new Piece(opIndex, true, ed.replacement()));
                        }
                    } else if (render == Render.TOKEN) {
                        // 命中跨算子时只有第一段写令牌，后面这些段只删字符。
                        //
                        // 但删字符不会自己把笔挪回去——不补这段位移，后面的字会压在令牌上；
                        // 更糟的是，**没删干净的那几个数字会原样留在文件里**，抽取时照样拿得到。
                        buf.add(shiftUnits(op, advance[q], warnings));
                    } else {
                        warnings.add("还原时遇到跨算子的令牌片段：已删除该段字符，但无法补回位移，"
                                + "版面可能偏移");
                    }
                }
                p = t;
                q++;
            }
            if (p < elemEnd) {
                byte[] keep = spans.slice(codes, p - elemStart, decoded.length());
                buf.add(keep);
                pieces.add(new Piece(opIndex, false, new String(keep, StandardCharsets.ISO_8859_1)));
            }
        }
        if (adjustNextNumber) {
            buf.add(numberDelta);
        }
        flushTj(sink, buf);
    }

    /** 被删掉的那段文本原本占的水平位移。 */
    private static double glyphAdvance(ShowTextOp op, List<Spans> spansAll, int from, int to)
            throws IOException {
        double sum = 0;
        int p = 0;
        int si = 0;
        for (Object e : op.elems()) {
            if (!(e instanceof byte[])) {
                continue;
            }
            Spans sp = spansAll.get(si++);
            int n = sp.decodedLength();
            int s = Math.max(from, p);
            int t = Math.min(to, p + n);
            if (s < t) {
                sum += sp.width(op.font(), s - p, t - p, op);
            }
            p += n;
        }
        return sum;
    }

    /**
     * 把「想让笔往右移动 w 点」翻译成 {@code TJ} 里的调整数。
     *
     * <p>规范里 {@code TJ} 的数值 n 是往**左**移 {@code n/1000 × Tfs × Th}，所以要往右移就得取负。
     * 缩放取的是比例（1.0 表示 Tz=100）而不是百分数——早先按百分数算，补偿量差了 100 倍，
     * 后续文字会前移将近一个令牌宽，而这种错在渲染时一眼看不出来。
     */
    private static double shiftUnits(ShowTextOp op, double w, List<String> warnings) {
        return shiftUnits(op.size() * op.hScale(), w, warnings);
    }

    private static double shiftUnits(double scale, double w, List<String> warnings) {
        if (w == 0) {
            return 0;
        }
        if (scale <= 0) {
            warnings.add("字号或水平缩放为 0，无法补齐位移补偿，版面可能偏移");
            return 0;
        }
        return -w * 1000.0 / scale;
    }

    /**
     * 发令牌并补齐位移：画完令牌后的累计位移必须等于被删字符的宽度，
     * 否则后续文字会整体错位。
     *
     * <p>令牌按 {@link #fitScale} 压到能塞进槽位为止。这样做的直接好处是**令牌不会盖住后面的字**：
     * 之前只补位移不压宽，笔是回到了原位，可令牌本身的墨迹还伸到后面去，
     * 一行里几个令牌就会互相叠压——实测渲染下那一行完全不可读。
     */
    private static void emitToken(ByteArrayOutputStream sink, ShowTextOp op, String tokenText,
                                  PDFont tokenFont, String tokenFontName, double removedWidth,
                                  List<String> warnings) throws IOException {
        byte[] tokenBytes = tokenFont.encode(tokenText);
        double unscaled = ShowTextOp.stringAdvance(tokenFont, tokenBytes, op.size(),
                op.charSpacing(), op.wordSpacing(), TOKEN_H_SCALE);
        double scale = fitScale(unscaled, removedWidth);
        double natural = unscaled * scale;
        if (scale < TOKEN_H_SCALE) {
            if (scale <= MIN_TOKEN_H_SCALE && natural > removedWidth) {
                // 压到下限还塞不下：宁可溢出，也不能把令牌压成一道糊条。
                // 溢出是可见的、会被告警点名到具体令牌的；糊条则连「这里脱了什么」都读不出来。
                warnings.add("令牌即便压到最小宽度仍宽于原文，其尾部仍会与后文重叠（令牌："
                        + tokenText + "）：" + Math.round(natural) + " vs "
                        + Math.round(removedWidth) + " pt");
            }
        } else if (natural > removedWidth) {
            // 说清楚会发生什么：后文位置不动，令牌尾部可能压到后面的字
            warnings.add("令牌比原文宽，其尾部可能与后文重叠（令牌：" + tokenText + "）："
                    + Math.round(natural) + " vs " + Math.round(removedWidth) + " pt");
        }
        if (op.size() <= 0) {
            warnings.add("字号为 0，无法补齐令牌的位移补偿，版面可能偏移：" + tokenText);
            sink.write(hex(tokenBytes).getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        // 令牌本身已经占掉 natural 的宽度，要补的是 removedWidth - natural。
        // 这里的水平缩放就是令牌自己写下的 Tz，与原算子的 Tz 无关。
        double adjust = (natural - removedWidth) * 1000.0 / (op.size() * scale);

        StringBuilder sb = new StringBuilder();
        sb.append('/').append(PdfTokens.escapeName(tokenFontName)).append(' ')
                .append(fmt(op.size())).append(" Tf\n");
        sb.append(fmt(scale * 100.0)).append(" Tz\n");
        sb.append('[').append(' ').append(hex(tokenBytes)).append(' ')
                .append(fmt(adjust)).append(' ').append("] TJ\n");
        sb.append(fmt(op.hScale() * 100.0)).append(" Tz\n");
        sb.append('/').append(PdfTokens.escapeName(op.fontName())).append(' ')
                .append(fmt(op.size())).append(" Tf\n");
        sink.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void flushTj(ByteArrayOutputStream sink, List<Object> buf) throws IOException {
        if (buf.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("[ ");
        for (Object o : buf) {
            if (o instanceof byte[] b) {
                sb.append(hex(b)).append(' ');
            } else {
                sb.append(fmt((Double) o)).append(' ');
            }
        }
        sb.append("] TJ\n");
        sink.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        buf.clear();
    }

    // ---------------------------------------------------------------- 码位↔字节

    /** 一个码位在「解码文本」与「原始字节」两个坐标系里的区间。 */
    record GlyphSpan(int code, int decStart, int decEnd, int byteStart, int byteEnd) {
    }

    private record Spans(List<GlyphSpan> spans, int decodedLength) {
        static Spans of(PDFont font, byte[] codes) throws IOException {
            List<GlyphSpan> out = new ArrayList<>();
            int[] bytePos = {0};
            int[] decPos = {0};
            PdfCodes.forEachCode(font, codes, (code, consumed) -> {
                int start = bytePos[0];
                bytePos[0] += consumed;
                String u = font.toUnicode(code);
                int len = u == null || u.isEmpty() ? 0 : u.length();
                out.add(new GlyphSpan(code, decPos[0], decPos[0] + len, start, bytePos[0]));
                decPos[0] += len;
            });
            return new Spans(out, decPos[0]);
        }

        /** 取解码坐标 [from, to) 覆盖的码位区间。 */
        private int[] covered(int from, int to) {
            int first = -1;
            int last = -1;
            for (int i = 0; i < spans.size(); i++) {
                GlyphSpan s = spans.get(i);
                if (s.decEnd() > from && s.decStart() < to) {
                    if (first < 0) {
                        first = i;
                    }
                    last = i;
                }
            }
            return first < 0 ? null : new int[]{first, last};
        }

        /** 取解码坐标 [from, to) 对应的原始字节。 */
        byte[] slice(byte[] codes, int from, int to) {
            int[] r = covered(from, to);
            if (r == null) {
                return new byte[0];
            }
            int byteFrom = spans.get(r[0]).byteStart();
            int byteTo = spans.get(r[1]).byteEnd();
            byte[] out = new byte[byteTo - byteFrom];
            System.arraycopy(codes, byteFrom, out, 0, out.length);
            return out;
        }

        /** 按码位逐个量宽度，而不是把字符重新编码后再量——后者会丢掉原字体的编码信息。 */
        double width(PDFont font, int from, int to, ShowTextOp op) throws IOException {
            int[] r = covered(from, to);
            if (r == null) {
                return 0;
            }
            double sum = 0;
            for (int i = r[0]; i <= r[1]; i++) {
                GlyphSpan s = spans.get(i);
                sum += font.getWidth(s.code()) / 1000.0 * op.size() + op.charSpacing();
                if (s.code() == 32 && !ShowTextOp.isMultiByte(font)) {
                    sum += op.wordSpacing();
                }
            }
            return sum * op.hScale();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2 + 2).append('<');
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.append('>').toString();
    }

    /** 输出 PDF 数字：不能出现科学计数法，也不能带负零。 */
    static String fmt(double v) {
        if (v == 0.0) {
            return "0";
        }
        BigDecimal d = BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        if (d.signum() == 0) {
            return "0";
        }
        return d.toPlainString();
    }
}
