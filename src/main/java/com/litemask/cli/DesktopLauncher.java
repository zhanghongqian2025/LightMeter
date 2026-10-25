package com.litemask.cli;

/**
 * 桌面版启动器：给 {@code .app} / 快捷方式用的入口。
 *
 * <p>为什么不让 {@code .app} 直接指 {@code Main}：双击没有终端，
 * {@code Main} 在无参时会把用法打印到 stdout——在 Finder 里双击的结果是
 * 一个闪一下就退出的窗口，用户完全不知道发生了什么。所以无参时改为
 * <b>起本地服务并打开浏览器</b>，那才是这个产品真正的界面（M2 做的 Web UI）。
 *
 * <p>带参数时原样转交 {@link Main#main}，所以同一个可执行文件在终端里
 * 依然是完整的 CLI：{@code LightMeter redact a.docx} 照样能用。
 * 这不是「多一个入口」，是同一个入口的两种叫法。
 *
 * <p>刻意保持极薄：不加配置、不读环境变量、不改全局状态。
 * 所有真实逻辑仍在 {@code Main} / {@code ServeCommand} 里，
 * 这里只决定「无参时该干什么」。
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            Main.main(args);
            return;
        }
        // 【2026-10-25】双击这条路里控制台是**我们自己拉起来**的，
        //   切它的代码页不影响任何人的终端设置。
        //   不切的话，中文系统（936）会把 UTF-8 的字节显示成乱码 ——
        //   而「端口冲突」「启动失败」这些提示恰好走这条路，
        //   乱码 = 用户根本不知道出了什么事。
        //   带参数那条路**不切**：那时用户在用自己已有的终端。
        ConsoleEncoding.ensureUtf8Console();
        try {
            System.exit(ServeCommand.run(new String[0]));
        } catch (Exception e) {
            // 桌面场景下没人看得到 stderr 的用法说明，所以给一句人话。
            System.err.println("启动失败: " + e.getMessage());
            System.err.println("如果是在终端里跑的，可以加 --no-browser 或换 --port 试试。");
            System.exit(1);
        }
    }
}
