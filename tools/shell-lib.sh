#!/usr/bin/env bash
# shell 共用小工具：目前只有一个 —— 可靠地删目录。
#
# 【为什么不用 `rm -rf` —— 2026-10-20 实测踩到的坑】
#   本机的网关会把 shell 里的 `rm -rf` **路由到 mavis-trash**，
#   而 mavis-trash 在本机**不可靠**：有时挂死不返回（删一个 /tmp 下的空目录都卡住），
#   有时报 "failed to trash" 然后**什么都没删**。
#
#   后果不是「删不掉、有点脏」，而是**结论作废**：
#   对照实验每条变异前要清掉 `target/classes`（否则增量编译会拿旧字节码，
#   测试红在「构建产物脏了」而不是「断言发现问题」）。
#   清理失败 → 残留的是**上一条变异的字节码** → 基线复核不绿 →
#   整片作废（实测：CE 的第 87..92 片就是这样失败的，
#   而那个红其实是守卫**工作正确**，只是被环境故障触发的）。
#
#   → 改用 python 的 shutil.rmtree：不经过 shell 的命令路由，行为确定。
#
# 【语义】
#   与 `rm -rf` 等价：路径不存在时什么都不做（不报错），存在时删掉；
#   文件与符号链接走 os.remove，目录走 shutil.rmtree。
#   路径由调用方给（本项目里全是脚本自己 mktemp 出来的目录，不含用户输入）。
#
# 【用法】
#   . "$ROOT/tools/shell-lib.sh"     # 在设好 ROOT 之后
#   rm_rf "$WORK"                    # 删目录
#
# 【唯一的例外：tools/control-experiment.sh】
#   它**没有** source 这个文件，而是内联了同一个函数。
#   原因：CE 脚本在跑动中不能被改动（bash 按字节偏移增量读取脚本，
#   改它会让执行流读错位置），而它是**先**于任何 source 依赖被执行的；
#   内联一份保证「CE 脚本可以单独拷走运行」。
#   两份实现逐字相同（见 control-experiment.sh 里的 rm_rf）。
rm_rf() {
  python3 -c 'import os,shutil,sys; p=sys.argv[1]; (os.remove(p) if (os.path.isfile(p) or os.path.islink(p)) else (shutil.rmtree(p) if os.path.isdir(p) else None))' "$1"
}
