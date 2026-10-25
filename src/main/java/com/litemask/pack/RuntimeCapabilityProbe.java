package com.litemask.pack;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * 裁剪运行时**能力自检**：这个运行时到底有没有产品要用的那些能力。
 *
 * <p><b>为什么必须要有这个类（2026-10-25 真 Windows 实测踩到的真缺陷）</b>：
 * 打包脚本用 jlink 把运行时裁到只剩几个模块。裁掉的东西里，
 * <b>有一个是模型包签名验证要用的</b>：
 * <pre>
 *   KeyFactory.getInstance("Ed25519")
 * </pre>
 * 的实现（{@code sun.security.ec.ed.EdDSAKeyFactory}）在
 * <b>jdk.crypto.ec</b> 模块里，<b>不在 java.base</b>。
 * 少了它，装好的产品里：
 * <pre>
 *   model install 模型包.zip
 *   → 安装失败: 这串 Base64 不是一把 Ed25519 公钥：Ed25519 KeyFactory not available
 * </pre>
 * —— <b>模型包一件都装不上</b>，而核心包照常工作（规则类脱敏没问题）。
 *
 * <p><b>为什么单测抓不到</b>：单测跑在**完整 JDK** 上，那里有 63 个模块，
 * 什么都有。这条缺陷只在「jlink 裁完 → 装上 → 装模型」这条路上才暴露。
 *
 * <p><b>判据落在哪</b>：打包脚本在 jlink 之后，用<b>裁剪出来的那份 java</b>
 * 跑这个类；缺任何一项就**打包失败**。
 * 放在产品 jar 里（而不是打包脚本里的临时脚本）是为了：
 * 它跟产品用同一套类路径与同一套模块清单，测的就是真正要发出去的东西。
 *
 * <p><b>用法</b>：
 * <pre>
 *   java -cp desens-core.jar com.litemask.pack.RuntimeCapabilityProbe
 * </pre>
 * 退出码 0 = 全部具备；1 = 缺能力（逐条打印缺什么）。
 */
public final class RuntimeCapabilityProbe {

    /** 一项要查的能力。 */
    private record Check(String what, Runnable probe) {
    }

    private RuntimeCapabilityProbe() {
    }

    public static void main(String[] args) {
        List<Check> checks = new ArrayList<>();

        // 1. Ed25519 —— 模型包签名与验签
        checks.add(new Check("KeyFactory Ed25519（模型包签名验签）", () -> {
            try {
                KeyFactory.getInstance("Ed25519");
            } catch (Exception e) {
                throw new IllegalStateException("Ed25519 KeyFactory 不可用（缺 jdk.crypto.ec 模块）：" + e, e);
            }
        }));

        // 2. Ed25519 签名/验签真的能跑（不只是 KeyFactory 在）
        checks.add(new Check("Ed25519 签名与验签（模型包信封）", () -> {
            try {
                java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("Ed25519");
                java.security.KeyPair kp = gen.generateKeyPair();
                byte[] raw = kp.getPublic().getEncoded();
                KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(raw));
                java.security.Signature s = java.security.Signature.getInstance("Ed25519");
                s.initSign(kp.getPrivate());
                s.update("探针".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] sig = s.sign();
                java.security.Signature v = java.security.Signature.getInstance("Ed25519");
                v.initVerify(kp.getPublic());
                v.update("探针".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!v.verify(sig)) {
                    throw new IllegalStateException("Ed25519 验签没过");
                }
            } catch (Exception e) {
                throw new IllegalStateException("Ed25519 签名/验签不可用：" + e, e);
            }
        }));

        // 3. AES-GCM —— 加密映射（口令封装）
        checks.add(new Check("AES/GCM/NoPadding（加密映射）", () -> {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                // 【为什么用简单名而不是 javax.crypto.spec.SecretKeySpec】
                //   在**同时 import 了 javax.crypto.spec 里的其它类**的编译单元里，
                //   写全限定名 `javax.crypto.spec.SecretKeySpec` 会被 javac 解析成
                //   「在类 javax.crypto 里找嵌套类 spec」→ 编译不过。
                //   （本机实测：import 了 GCMParameterSpec 之后，
                //     同一个文件里的全限定名写法就报「找不到符号: 类 spec」。）
                //   → 照产品里 SecretMap 的写法：import + 简单名。
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"),
                        new GCMParameterSpec(128, new byte[12]));
            } catch (Exception e) {
                throw new IllegalStateException("AES-GCM 不可用（加密映射会失败）：" + e, e);
            }
        }));

        // 4. PBKDF2 —— 口令派生密钥
        checks.add(new Check("SecretKeyFactory PBKDF2WithHmacSHA256（口令派生）", () -> {
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            } catch (Exception e) {
                throw new IllegalStateException("PBKDF2 不可用（加密映射会失败）：" + e, e);
            }
        }));

        // 5. HTTPS —— 模型包下载（唯一的出网路径）
        //    【为什么用类字面量而不是 Class.forName】
        //    自家代码**不使用反射**是本项目的硬约束（tools\verify_no_network.py 里有守卫：
        //    「自家类出现了反射入口」直接报红）。
        //    而这里要的只是「这个类在不在」——类字面量就够了，
        //    而且它**在执行到这一行时才解析**：模块不在会抛 NoClassDefFoundError，
        //    被下面的 catch 接住，正好变成一条可读的失败信息。
        checks.add(new Check("HttpsURLConnection（模型包下载）", () -> {
            try {
                Class<?> c = javax.net.ssl.HttpsURLConnection.class;
                if (c == null) {
                    throw new IllegalStateException("HttpsURLConnection 不在");
                }
            } catch (Throwable e) {
                throw new IllegalStateException("HttpsURLConnection 不可用（模型包下载会失败）：" + e, e);
            }
        }));

        List<String> missing = new ArrayList<>();
        for (Check c : checks) {
            try {
                c.probe().run();
                System.out.println("  ok  " + c.what());
            } catch (RuntimeException e) {
                System.out.println("  ng  " + c.what() + " —— " + e.getMessage());
                missing.add(c.what());
            }
        }
        if (!missing.isEmpty()) {
            System.out.println();
            System.out.println("这个裁剪运行时缺少 " + missing.size() + " 项产品要用的能力。");
            System.out.println("→ 补 tools\\package-identity.properties 的 jlink.modules（别用「少一个模块也能跑」的借口）。");
            System.exit(1);
        }
        System.out.println("裁剪运行时能力齐备（" + checks.size() + " 项）。");
    }
}
