# M1-a 阶段状态：docx 管线（2026-10-14）

## 结论

M0 的纯文本引擎已能处理 docx，**且没有为 docx 引入任何第三方依赖**。
fat jar 从 2.34MB 涨到 2.36MB（+2KB），而能力从"纯文本"扩展到"完整 docx"。

## 技术选型：为什么 docx 不用 POI

实测 `poi-ooxml` 5.3.0 整条依赖栈 **18.4MB**（poi 2.84 + ooxml-lite 5.71 + xmlbeans 2.10 +
commons-compress 1.03 + commons-collections4 0.72 + commons-math3 2.11 + commons-lang3 0.63 +
commons-io 0.49 + log4j-api 0.33 + curvesapi + SparseBitSet + commons-codec），
占 L0 预算 55MB 的三分之一。

**放弃它的理由不是体积，是覆盖面**：

文本框里的段落位于 `w:txbxContent/w:p`，POI **不把它建模为 `XWPFParagraph`**——
`document.getParagraphs()` 拿不到，`getBodyElements()` 也拿不到。
用 POI 就必须同时维护两套遍历机制（XWPF + XMLBeans），漏掉一层就是**静默漏脱**：
文件正常生成，工具正常报告成功，而原文里的身份证还在。

改用 JDK 自带的 zip + DOM：一次深度优先遍历 `<w:p>`，body / 表格 / 页眉页脚 / 脚注尾注 /
批注 / **文本框** 走的是同一条代码路径。且只改 `<w:t>` 的文本节点，样式天然不受影响，
不需要任何"重建富文本"的代码。

POI 留给 xlsx（共享字符串索引、单元格类型、公式）与 pptx，在 M1-b/c 引入。

## 覆盖范围

| 位置 | 处理 |
|---|---|
| 正文段落 | ✅ |
| 表格单元格 | ✅ |
| 页眉 / 页脚 | ✅ |
| 脚注 / 尾注 | ✅（同属 `word/*.xml`） |
| 批注 | ✅ |
| **文本框**（`w:txbxContent`） | ✅ |
| **修订留痕**（`w:delText`） | ✅ 屏幕上看不见但文件里有 |
| 文档属性（作者/最后修改人/公司/自定义属性） | ✅ 清空 |
| `w:instrText`（域代码） | ❌ 刻意不处理，见下 |

**关于 `w:instrText`**：它是域指令（如 HYPERLINK 的目标地址）而非正文，改动会破坏域。
但域目标 URL 里确实可能带身份证号——这是一条真实的泄漏路径，已记为 M1 待办，不在这里悄悄放过。

## 新增模块

| 类 | 职责 |
|---|---|
| `RedactionSession` | 跨段落共用一个令牌分配器。整篇文档的映射合并成一份，同值同符、异值异符 |
| `ParagraphTextMapper` | 段落内跨 `<w:t>` 槽替换。**不碰 XML 的纯函数**，便于手写刁钻输入 |
| `DocxRedactor` | zip + DOM 遍历、替换、元数据清理、还原 |

### 为什么引擎要拆成"分配"和"替换"两步

`RedactionEngine` 原本一趟做完：从文末向前替换的同时发号。
结果编号按**应用顺序**发，读起来是「甲 `<身份证2>` 乙 `<身份证1>`」——M0 就有的缺陷，
纯文本里每类只有一个实体时看不出来，docx 跨段落同类型多实体立刻暴露。
现已拆成：先按文档顺序分配令牌，再从文末向前应用替换。

## 测试规模

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `RuleSampleTest` | 360 | 规则正负样本（M0） |
| `DocxRedactorTest` | 18 | 端到端：跨 run、表格、页眉页脚、文本框、批注、delText、属性、样式、xml:space、fail-closed、往返 |
| `ParagraphTextMapperTest` | 11 | 跨 2/3 槽、同一段多处命中、空槽、令牌不套娃 |
| `CliOutputPathTest` | 4 | 输出路径与扩展名判断 |
| 其余 M0 类 | 23 | — |
| **合计** | **416 全绿** | |

测试里的 docx 是手工拼的 XML 而非 POI 生成：需要构造的场景（实体横跨三个 run、
文本框嵌在 run 里、修订留痕）都不是正常编辑会产生的样子，手写才能精确控制。
读取侧同样用正则而非 DOM——复用被测代码的解析逻辑，那个逻辑本身写错时测试会跟着错。

## 对照实验 10/10

原有 5 条之外，为 docx 新增 5 条，全部咬住：

| 变异 | 咬住它的测试 |
|---|---|
| ① 从文首而非文末应用替换 | `RedactionRoundTripTest` + `EngineBehaviorTest` |
| ② 绕过校验器 | `RuleSampleTest` 失败 10 |
| ③ 不消解重叠 | `EngineBehaviorTest` |
| ④ 不裁剪首尾空白 | `EngineBehaviorTest` |
| ⑤ 占位符撞号 | `RedactionRoundTripTest` 失败 2 |
| **⑥ 段落不跨槽拼接** | `ParagraphTextMapperTest` 失败 6 + `DocxRedactorTest` 失败 2 |
| **⑦ 不处理 delText** | `DocxRedactorTest` |
| **⑧ 不清理文档属性** | `DocxRedactorTest` |
| **⑨ 丢失 xml:space** | `DocxRedactorTest` |
| **⑩ 每段新建分配器** | `DocxRedactorTest` 失败 2 |

**⑩ 第一次跑是绿的**，暴露了测试本身不够敏感：原用例只测"跨段落同值同符"，
而两个段各自新建分配器时恰好都发 1 号，文档看着完全正常。
真正会崩的是"不同段落的不同实体撞同一令牌"——补了 `differentValuesGetDifferentTokensAcrossParagraphs`
与 `roundTripAcrossParagraphs` 两条才咬住。

## 本阶段修掉的真实缺陷

1. **令牌编号按应用顺序发放**（M0 遗留）：「甲 `<身份证2>` 乙 `<身份证1>`」。
   修法是分配与替换分两趟，分配按文档顺序。
2. **change 记录漏报替换位置**：同值三处出现时只记 1 条 change，
   核对报告因此少报了两处实际改动。改为每次替换记一条。
3. **CLI 输出路径丢目录**：`redact /某/目录/合同.docx` 把结果写进了当前工作目录。
   这是端到端跑真实文件时暴露的，已加 `CliOutputPathTest` 钉住。

## 端到端验证（真实 docx）

用 macOS `textutil` 把 `demo/合同.txt` 转成真正的 Word 文档（非测试夹具），再跑完整链路：

- 脱敏：**12 处命中**，与纯文本版一致；身份证、手机号、邮箱、统一社会信用代码、
  银行卡、微信号、QQ 号、IP、案号、合同号、电话 全部替换为占位符
- 样式存活：`w:rFonts`、`w:sz` 等 `rPr` 完好
- 还原：**25 段逐段文本与原文完全一致**
- 残留检查：原值在脱敏件中 0 命中

## 已知边界

- **还原不承诺二进制一致**：zip 压缩参数、XML 属性顺序可能不同。
  承诺的是"抽取出的文本与原文相同"，测试断言的也是这一条。
- **`w:instrText` 未处理**（见上）。
- **不支持** `.doc`（老二进制格式）、`.wps`、`.rar`、`.pst`——按路线图明确不做。
- **无图片 OCR**：图片里的证件照不识别。
- 仅 docx；xlsx/pptx/pdf/ofd 见 M1-b/c/d/e。

## 下一步

1. **M1-b xlsx**：单元格级脱敏（共享字符串 + 内联字符串 + 公式结果），用 POI-lite
2. **M1-c pptx**：文本框、母版、备注页
3. **M1-d PDF**：文本层抽取 + 回写 + 隐写信息清理
4. **M1-e ofd**：ofdrw
5. 补金额类规则（阿拉伯数字金额、中文大写金额）与 VIN 的 ISO 位校验码
6. 对不可信文档做 fuzz 测试（zip 炸弹、嵌套深度、畸形 XML）
