import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 交付包里的 ONNX Runtime 原生库完整性预检。
 *
 * <p>【它挡的是什么】
 * 打包脚本会按平台剔掉「别的平台那套」原生库（macOS 剔 win-x64，Windows 剔 osx-*），
 * 但**原来不检查「自己那套还在不在包里」**。
 * 上游依赖树一变（ONNX Runtime 换版、或不再带 win-x64），
 * 就会**静默**打出一个没有原生库的包 —— 到用户机器上才炸，而且是运行时才炸。
 * 这个工具把「该有什么 / 不该有什么」变成**可断言的事实**。
 *
 * <p>【为什么是纯 JDK 单文件程序】
 * 它要能在 **Windows 构建机**上跑，而那台机器**不一定有 python**。
 * 所以只用 java.util.zip + java.util.Properties，零第三方依赖。
 *
 * <p>【判据从哪来】
 * 策略表 {@code tools/platform-native-policy.properties} 是**唯一真源**；
 * 两个打包脚本与链上的预检都读它（同一个事实只在一处定义）。
 *
 * <p>【用法】
 * <pre>
 * java tools/CheckPlatformNatives.java --policy tools/platform-native-policy.properties \
 *      --platform macos-arm64 target/desens-core.jar
 * </pre>
 * 被测对象可以是 jar，也可以是目录（Windows 的 jpackage app-image 形态：
 * 里面是 jar，脚本会自己找出来逐个查）。
 *
 * <p>【退出码】0 = 全部通过；1 = 有断言失败；2 = 参数/前提不成立。
 */
public final class CheckPlatformNatives {

    /** 一个待检查的容器：jar 或裸目录。 */
    private static final class Container {
        final String label;
        final List<Item> items;

        Container(String label, List<Item> items) {
            this.label = label;
            this.items = items;
        }
    }

    /** 容器里的一个条目。 */
    private static final class Item {
        final String path;
        final boolean dir;
        final long size;

        Item(String path, boolean dir, long size) {
            this.path = path;
            this.dir = dir;
            this.size = size;
        }
    }

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        String policyPath = "tools/platform-native-policy.properties";
        String platform = null;
        Path target = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--policy".equals(a)) {
                if (++i >= args.length) {
                    System.out.println("FAIL --policy 后面缺路径");
                    System.exit(2);
                }
                policyPath = args[i];
            } else if ("--platform".equals(a)) {
                if (++i >= args.length) {
                    System.out.println("FAIL --platform 后面缺平台名");
                    System.exit(2);
                }
                platform = args[i];
            } else if (a.startsWith("--")) {
                System.out.println("FAIL 未知参数：" + a);
                System.exit(2);
            } else {
                target = Paths.get(a);
            }
        }

        // ---- 前提：缺参数就报错，不静默放过 ----
        if (platform == null) {
            System.out.println("FAIL 缺 --platform");
            System.exit(2);
        }
        if (target == null) {
            System.out.println("FAIL 缺被测对象（jar 或目录）");
            System.exit(2);
        }
        Path policyFile = Paths.get(policyPath);
        if (!Files.isRegularFile(policyFile)) {
            System.out.println("FAIL 找不到策略表：" + policyFile);
            System.exit(2);
        }
        if (!Files.exists(target)) {
            System.out.println("FAIL 被测对象不存在：" + target);
            System.exit(2);
        }

        Properties pol = new Properties();
        try (InputStream in = Files.newInputStream(policyFile)) {
            pol.load(in);
        } catch (IOException e) {
            System.out.println("FAIL 读策略表失败：" + e);
            System.exit(2);
        }

        String prefix = pol.getProperty("native.prefix", "");
        String reqKey = "platform." + platform + ".required";
        String forbidKey = "platform." + platform + ".forbidden";
        if (!pol.containsKey(reqKey)) {
            System.out.println("FAIL 策略表里没有平台 " + platform + " 的定义（键 " + reqKey + "）");
            System.exit(2);
        }
        List<String> required = split(pol.getProperty(reqKey, ""));
        List<String> forbidden = split(pol.getProperty(forbidKey, ""));
        List<String> alwaysForbidden = split(pol.getProperty("always.forbidden", ""));
        List<String> forbiddenSuffix = split(pol.getProperty("always.forbiddenSuffix", ""));

        System.out.println("== 平台原生库预检：platform=" + platform + "，被测=" + target);
        System.out.println("   策略表：" + policyFile + "（required=" + required
                + "，forbidden=" + forbidden + "）");

        List<Container> containers = collect(target);
        if (containers.isEmpty()) {
            System.out.println("FAIL 被测对象里找不到任何可检查的内容");
            System.exit(1);
        }
        for (Container c : containers) {
            System.out.println("   -- 容器：" + c.label + "（" + c.items.size() + " 个条目）");
            checkContainer(c, prefix, required, forbidden, alwaysForbidden, forbiddenSuffix);
        }

        if (!failures.isEmpty()) {
            System.out.println();
            for (String f : failures) {
                System.out.println("FAIL " + f);
            }
            System.out.println("=== 预检失败（" + failures.size() + " 条）===");
            System.exit(1);
        }
        System.out.println("=== 预检通过 ===");
    }

    /** 收集要检查的容器：jar 本身，或目录里的所有 jar（都没有就当裸目录查）。 */
    private static List<Container> collect(Path target) {
        List<Container> out = new ArrayList<>();
        if (Files.isRegularFile(target)) {
            try {
                out.add(readJar(target));
            } catch (IOException e) {
                System.out.println("FAIL 读 jar 失败：" + target + "（" + e + "）");
                System.exit(2);
            }
            return out;
        }
        List<Path> jars = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(target)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(jars::add);
        } catch (IOException e) {
            System.out.println("FAIL 遍历目录失败：" + e);
            System.exit(2);
        }
        if (!jars.isEmpty()) {
            for (Path p : jars) {
                try {
                    out.add(readJar(p));
                } catch (IOException e) {
                    System.out.println("FAIL 读 jar 失败：" + p + "（" + e + "）");
                    System.exit(2);
                }
            }
            return out;
        }
        try {
            out.add(readDir(target));
        } catch (IOException e) {
            System.out.println("FAIL 读目录失败：" + e);
            System.exit(2);
        }
        return out;
    }

    private static Container readJar(Path p) throws IOException {
        List<Item> items = new ArrayList<>();
        try (ZipFile zf = new ZipFile(p.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
                ZipEntry ze = e.nextElement();
                items.add(new Item(ze.getName(), ze.isDirectory(), ze.getSize()));
            }
        }
        return new Container(p.toString(), items);
    }

    private static Container readDir(Path root) throws IOException {
        List<Item> items = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            for (Path p : s.toList()) {
                if (p.equals(root)) {
                    continue;
                }
                String rel = root.relativize(p).toString().replace('\\', '/');
                boolean dir = Files.isDirectory(p);
                items.add(new Item(rel, dir, dir ? 0L : Files.size(p)));
            }
        }
        return new Container(root.toString() + "（裸目录形态）", items);
    }

    private static void checkContainer(Container c, String prefix,
                                       List<String> required, List<String> forbidden,
                                       List<String> alwaysForbidden, List<String> forbiddenSuffix) {
        // 平台目录 → 文件数 / 目录条目数 / 字节数
        Map<String, Integer> files = new TreeMap<>();
        Map<String, Integer> dirs = new TreeMap<>();
        Map<String, Long> bytes = new TreeMap<>();
        int malformed = 0;
        int pdb = 0;
        int dsym = 0;

        for (Item it : c.items) {
            String lower = it.path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdb")) {
                pdb++;
            }
            if (hasDsymSegment(it.path)) {
                dsym++;
            }
            if (!it.path.startsWith(prefix)) {
                continue;
            }
            String rest = it.path.substring(prefix.length());
            if (rest.isEmpty()) {
                continue;
            }
            int slash = rest.indexOf('/');
            if (slash < 0) {
                // 前缀下面直接是个文件 —— 结构不对（native 应该按平台目录分层）
                malformed++;
                continue;
            }
            String plat = rest.substring(0, slash);
            if (it.dir) {
                dirs.merge(plat, 1, Integer::sum);
            } else {
                files.merge(plat, 1, Integer::sum);
                bytes.merge(plat, it.size, Long::sum);
            }
        }

        Map<String, Integer> allDirs = new TreeMap<>(dirs);
        for (Map.Entry<String, Integer> e : files.entrySet()) {
            allDirs.putIfAbsent(e.getKey(), 0);
        }
        for (String plat : allDirs.keySet()) {
            System.out.println("      平台目录 " + plat + "：" + files.getOrDefault(plat, 0)
                    + " 个文件 / " + allDirs.get(plat) + " 个目录条目 / "
                    + bytes.getOrDefault(plat, 0L) + " B");
        }

        // ---- 判据 1：required 必须在，且**非空**（不能只有空目录条目）----
        for (String plat : required) {
            int n = files.getOrDefault(plat, 0);
            if (n > 0) {
                System.out.println("      PASS 必须有：" + plat + " 在（" + n + " 个文件）");
            } else {
                failures.add(c.label + "：必须有的平台原生库 " + plat + " 不在包里（或只有空目录条目）");
            }
        }

        // ---- 判据 2：forbidden 平台目录不许有（文件与目录条目都不许）----
        for (String plat : forbidden) {
            if (!allDirs.containsKey(plat)) {
                System.out.println("      PASS 不该有：" + plat + " 不在");
            } else {
                failures.add(c.label + "：不该有的平台原生库 " + plat + " 还在包里");
            }
        }

        // ---- 判据 3：不交付的平台一律不许有 ----
        for (String plat : alwaysForbidden) {
            if (!allDirs.containsKey(plat)) {
                System.out.println("      PASS 不该有（不交付的平台）：" +plat + " 不在");
            } else {
                failures.add(c.label + "：不交付的平台原生库 " + plat + " 还在包里");
            }
        }

        // ---- 判据 4：调试符号一律不许有 ----
        if (pdb == 0 && dsym == 0) {
            System.out.println("      PASS 不该有（调试符号）：无 .pdb / .dSYM");
        } else {
            failures.add(c.label + "：包里有调试符号（.pdb " + pdb + " 个 / .dSYM " + dsym + " 个）");
        }

        // ---- 判据 5：native 前缀下不许有裸文件（结构不对）----
        if (malformed == 0) {
            System.out.println("      PASS 结构：" + prefix + " 下面按平台目录分层");
        } else {
            failures.add(c.label + "：结构不对 —— " + prefix + " 下面有 " + malformed + " 个没分平台的裸文件");
        }
    }

    /** 路径里任一段以 .dSYM 结尾（jar 里是目录条目 + 里面的文件）。 */
    private static boolean hasDsymSegment(String path) {
        for (String seg : path.split("/")) {
            if (seg.toLowerCase(Locale.ROOT).endsWith(".dsym")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private CheckPlatformNatives() {
    }
}
