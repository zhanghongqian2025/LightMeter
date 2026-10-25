import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litemask.ner.ModelPackageSignature;
import com.litemask.ner.ModelTrust;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.EnumSet;
import java.util.Set;

/**
 * 发布侧的模型包签名工具（<b>不随程序分发</b>）。
 *
 * <p><b>为什么它不在 {@code src/main/java/} 里</b>：这个工具会读写<b>私钥</b>。
 * 发行包里带上「能处理私钥的代码」没有直接危害（私钥本身才是机密），
 * 但它会让一件本来很简单的事变复杂——用户装到自己的电脑上时，
 * 硬盘上躺着一个能签模型包的程序，而它本来不需要这个能力。
 * 私钥保管是发布方的运营决策，工具只负责把动作做对，不负责替谁保管密钥。
 *
 * <p><b>怎么跑</b>（源码单文件模式，需要 JDK 21）：
 * <pre>
 *   java -cp target/classes tools/ModelPackageSigner.java keygen key.json
 *   java -cp target/classes tools/ModelPackageSigner.java pubkey key.json pub.pem
 *   java -cp target/classes tools/ModelPackageSigner.java sign model.zip key.json
 *   java -cp target/classes tools/ModelPackageSigner.java verify model.zip pub.pem
 * </pre>
 *
 * <p><b>为什么公钥与私钥放同一个文件</b>：签名信封里要写「用哪把钥匙签的」
 * （密钥标识），而密钥标识是<b>从公钥派生</b>的。公私钥分成两个文件时，
 * 就有了一个真实的失败模式：拿错了公钥文件，标识写得出来，
 * 但那把公钥根本不是配对的私钥——签名在装的时候会验不过，
 * 而报错发生在用户那边，不在发布这边。
 * 放同一个文件里，这个失败模式在结构上就不存在。
 *
 * <p><b>私钥怎么保管</b>：本工具只做两件事——生成一把密钥、把一把密钥用起来。
 * 它<b>不</b>决定私钥放在哪、谁保管、要不要用密码保护。
 * 那是发布方的运营决策，工具不替用户选。
 * 唯一的技术要求：私钥<b>不能</b>进版本库、不能进分发包。
 */
public final class ModelPackageSigner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelPackageSigner() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage(System.err);
            System.exit(2);
        }
        try {
            int code = switch (args[0]) {
                case "keygen" -> keygen(args);
                case "pubkey" -> pubkey(args);
                case "sign" -> sign(args);
                case "verify" -> verify(args);
                case "keyid" -> keyid(args);
                case "-h", "--help" -> {
                    usage(System.out);
                    yield 0;
                }
                default -> {
                    System.err.println("未知命令: " + args[0]);
                    usage(System.err);
                    yield 2;
                }
            };
            System.exit(code);
        } catch (IOException e) {
            System.err.println("失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 生成一对 Ed25519 密钥，写进<b>一个</b>文件。
     *
     * <p>私钥落盘时权限收紧到 0600（POSIX 上），因为它是<b>私钥</b>——
     * 同机器上别的用户能读的话，等于这把钥匙没设防。
     */
    private static int keygen(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("用法: keygen <密钥文件.json>");
            return 2;
        }
        Path file = Path.of(args[1]);
        if (Files.exists(file)) {
            // 覆盖已有密钥 = 私钥永久丢失（对应的信任锚也立刻作废）。
            // 这是不可逆动作，必须让调用方显式确认。
            System.err.println("密钥文件已存在，不覆盖: " + file);
            System.err.println("（覆盖它等于永久丢弃这一把密钥，信任锚里对应的公钥也会立刻作废）");
            return 1;
        }
        KeyPair kp = ModelPackageSignature.generateKeyPair();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("note", "这是发布方的私钥。放进版本库 = 把签名能力公开。不要这么做。");
        root.put("keyId", ModelPackageSignature.keyIdOf(kp.getPublic()));
        root.put("publicKey", ModelPackageSignature.publicKeyToPem(kp.getPublic()));
        root.put("privateKey", ModelPackageSignature.privateKeyToPem(kp.getPrivate()));
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // 【2026-10-25 真 Windows 实测】这里原来用 Files.write + tightenPermissions，
        //   而 tightenPermissions 在 Windows 上只会「设不了 POSIX 权限，请自己管好」——
        //   也就是说**私钥文件在 Windows 上是按目录继承的默认权限落盘的**。
        //   本机实测：E 盘上的文件继承了 `BUILTIN\Users: ReadAndExecute`
        //   与 `Authenticated Users: Modify` → **同机任何普通账户都读得到发布私钥**。
        //   → 改走产品里那套已经加固过的 PrivateFile.write：
        //     POSIX 上 0600，Windows 上 ACL 只给属主。
        com.litemask.core.PrivateFile.write(file,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        System.out.println("已生成 Ed25519 密钥对");
        System.out.println("  密钥文件: " + file.toAbsolutePath() + "（仅属主可读，别进版本库）");
        System.out.println("  密钥标识: " + ModelPackageSignature.keyIdOf(kp.getPublic()));
        System.out.println("下一步：");
        System.out.println("  1) java -cp target/classes tools/ModelPackageSigner.java pubkey "
                + file + " pub.pem");
        System.out.println("  2) 把 pub.pem 的内容填进 src/main/resources/model-trust-anchors.json，"
                + "程序才会认它签的模型包。");
        return 0;
    }

    /** 从密钥文件里取出公钥（单独一个 PEM），用来填信任锚或交给用户。 */
    private static int pubkey(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("用法: pubkey <密钥文件.json> <公钥.pem>");
            return 2;
        }
        PublicKey key = readPublicKey(Path.of(args[1]));
        Path out = Path.of(args[2]);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(out, ModelPackageSignature.publicKeyToPem(key).getBytes(StandardCharsets.UTF_8));
        System.out.println("已写出公钥: " + out.toAbsolutePath());
        System.out.println("  密钥标识: " + ModelPackageSignature.keyIdOf(key));
        return 0;
    }

    /**
     * 给一个模型包签名，写出 {@code <包名>.sig}。
     *
     * <p>签的是<b>包文件本身的 SHA-256</b>：zip 是确定性产物，摘要就是它的稳定标识。
     * 签完之后包被改一个字节，摘要就变了，验签必然不过。
     */
    private static int sign(String[] args) throws IOException {
        if (args.length != 3 && args.length != 4) {
            System.err.println("用法: sign <模型包.zip> <密钥文件.json> [签名文件]");
            return 2;
        }
        Path zip = Path.of(args[1]);
        if (!Files.isRegularFile(zip)) {
            System.err.println("模型包读不到: " + zip);
            return 1;
        }
        KeyFile kf = readKeyFile(Path.of(args[2]));
        ModelPackageSignature.Envelope env = ModelPackageSignature.sign(zip, kf.privateKey(), kf.keyId());
        // 签完立刻用同一把公钥自验一次：工具自己产出的东西必须自己先验过。
        // 万一密钥文件里公私钥不配对（手改过、复制错了），这里就会停住，
        // 而不是等到用户装模型包时才失败。
        if (!ModelPackageSignature.verify(env, zip, kf.publicKey())) {
            System.err.println("刚签出来的签名自验没过——密钥文件里的公私钥不配对。");
            return 1;
        }
        Path sig = args.length == 4 ? Path.of(args[3]) : ModelTrust.signatureFileFor(zip);
        Files.write(sig, ModelPackageSignature.writeEnvelope(env));
        System.out.println("已签名: " + zip.toAbsolutePath());
        System.out.println("  签名文件: " + sig.toAbsolutePath());
        System.out.println("  算法: " + env.alg() + "，密钥标识: " + env.keyId());
        System.out.println("  覆盖摘要: " + env.sha256());
        return 0;
    }

    /**
     * 用一把公钥本地验一个模型包的签名。
     *
     * <p><b>它验的是「签名与包配不配对」</b>，不验「这把公钥我们信不信」——
     * 后者是信任锚那一层的事（{@link ModelTrust}）。两件事分开，
     * 发布前自查一遍很有用，但它不能替代安装时的那次验签。
     */
    private static int verify(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("用法: verify <模型包.zip> <公钥.pem>");
            return 2;
        }
        Path zip = Path.of(args[1]);
        PublicKey key = ModelPackageSignature.publicKeyFromPem(
                new String(Files.readAllBytes(Path.of(args[2])), StandardCharsets.UTF_8));
        Path sig = ModelTrust.signatureFileFor(zip);
        if (!Files.isRegularFile(sig)) {
            System.err.println("找不到签名文件: " + sig);
            return 1;
        }
        ModelPackageSignature.Envelope env =
                ModelPackageSignature.parseEnvelope(Files.readAllBytes(sig));
        if (!ModelPackageSignature.ALG.equals(env.alg())) {
            System.err.println("算法不是 " + ModelPackageSignature.ALG + "（是 " + env.alg() + "）");
            return 1;
        }
        if (!ModelPackageSignature.keyIdOf(key).equals(env.keyId())) {
            System.err.println("密钥标识对不上: 信封里是 " + env.keyId()
                    + "，这把公钥是 " + ModelPackageSignature.keyIdOf(key));
            return 1;
        }
        if (!ModelPackageSignature.verify(env, zip, key)) {
            System.err.println("验签不过：签名与这个包对不上（包在签名之后被改过，或签名是别人的）");
            return 1;
        }
        System.out.println("验签通过: " + zip.toAbsolutePath());
        System.out.println("  密钥标识: " + env.keyId());
        return 0;
    }

    /** 打印一把公钥派生出来的密钥标识（用来核对信任锚里写的是不是同一把）。 */
    private static int keyid(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("用法: keyid <公钥.pem>");
            return 2;
        }
        PublicKey key = ModelPackageSignature.publicKeyFromPem(
                new String(Files.readAllBytes(Path.of(args[1])), StandardCharsets.UTF_8));
        System.out.println(ModelPackageSignature.keyIdOf(key));
        return 0;
    }

    // ------------------------------------------------------------------ 内部

    /** 密钥文件的内容。 */
    private record KeyFile(String keyId, PublicKey publicKey, PrivateKey privateKey) {
    }

    private static KeyFile readKeyFile(Path file) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IOException("密钥文件读不出来（不是合法 JSON）：" + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("密钥文件不是一个 JSON 对象。");
        }
        String privPem = text(root, "privateKey");
        String pubPem = text(root, "publicKey");
        if (privPem == null || pubPem == null) {
            throw new IOException("密钥文件缺 privateKey 或 publicKey。");
        }
        PrivateKey priv = ModelPackageSignature.privateKeyFromPem(privPem);
        PublicKey pub = ModelPackageSignature.publicKeyFromPem(pubPem);
        // 标识**从公钥现算**，不信文件里写的那串——
        // 写死的标识可能和公钥对不上（手改过），而标识只用于匹配信任锚。
        String keyId = ModelPackageSignature.keyIdOf(pub);
        String stored = text(root, "keyId");
        if (stored != null && !stored.equals(keyId)) {
            throw new IOException("密钥文件里的 keyId（" + stored + "）和公钥对不上（应为 " + keyId + "）。");
        }
        return new KeyFile(keyId, pub, priv);
    }

    private static PublicKey readPublicKey(Path file) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IOException("密钥文件读不出来（不是合法 JSON）：" + e.getMessage(), e);
        }
        String pubPem = root == null ? null : text(root, "publicKey");
        if (pubPem == null) {
            throw new IOException("密钥文件里没有 publicKey。");
        }
        return ModelPackageSignature.publicKeyFromPem(pubPem);
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual()) {
            return null;
        }
        String s = n.textValue();
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * 私钥文件权限收紧到「只有本人可读写」。
     *
     * <p><b>【2026-10-25】这条现在只是兜底</b>：私钥文件由
     * {@link com.litemask.core.PrivateFile#write} 落盘，那边已经
     * POSIX 0600 / Windows ACL 只给属主了。
     * 这里保留是因为还有别的调用点（下面 sign 也会写 .sig 文件）。
     *
     * <p>非 POSIX 平台上原实现只是打印一句「请自己管好」就放过 ——
     * 那等于**没设防**。现在改成走 ACL。
     */
    private static void tightenPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 上没有 POSIX 权限 —— 改用 ACL（com.litemask.core.PrivateFile 那套）。
            try {
                java.nio.file.attribute.AclFileAttributeView view =
                        java.nio.file.attribute.AclFileAttributeView.class.cast(
                                java.nio.file.Files.getFileAttributeView(file,
                                        java.nio.file.attribute.AclFileAttributeView.class));
                if (view == null) {
                    System.err.println("（这个文件系统不支持 ACL，私钥文件权限请自己管好: " + file + "）");
                    return;
                }
                java.nio.file.attribute.UserPrincipal owner = java.nio.file.Files.getOwner(file);
                java.nio.file.attribute.AclEntry entry = java.nio.file.attribute.AclEntry.newBuilder()
                        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(
                                java.nio.file.attribute.AclEntryPermission.READ_DATA,
                                java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
                                java.nio.file.attribute.AclEntryPermission.APPEND_DATA,
                                java.nio.file.attribute.AclEntryPermission.READ_NAMED_ATTRS,
                                java.nio.file.attribute.AclEntryPermission.WRITE_NAMED_ATTRS,
                                java.nio.file.attribute.AclEntryPermission.READ_ATTRIBUTES,
                                java.nio.file.attribute.AclEntryPermission.WRITE_ATTRIBUTES,
                                java.nio.file.attribute.AclEntryPermission.READ_ACL,
                                java.nio.file.attribute.AclEntryPermission.WRITE_ACL,
                                java.nio.file.attribute.AclEntryPermission.DELETE,
                                java.nio.file.attribute.AclEntryPermission.SYNCHRONIZE)
                        .build();
                view.setAcl(java.util.List.of(entry));
            } catch (IOException | RuntimeException e2) {
                System.err.println("（设不了 ACL，私钥文件权限请自己管好: " + file + " —— " + e2.getMessage() + "）");
            }
        }
    }

    private static void usage(java.io.PrintStream out) {
        out.println("""
                用法:
                  keygen <密钥文件.json>              生成一对 Ed25519 密钥（公私钥同一个文件）
                  pubkey <密钥文件.json> <公钥.pem>    从密钥文件里取出公钥
                  sign <模型包.zip> <密钥文件.json>     给模型包签名（默认写 <包名>.sig）
                  verify <模型包.zip> <公钥.pem>       本地验签（不查信任锚）
                  keyid <公钥.pem>                    打印这把公钥派生出的密钥标识

                说明:
                  私钥是发布方的机密：别进版本库、别进分发包、别放在会同步的目录里。
                  信任关系靠「公钥进信任锚」建立——把公钥填进
                  src/main/resources/model-trust-anchors.json，用户才能装你签的模型包。
                """);
    }
}
