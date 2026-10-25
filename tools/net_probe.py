#!/usr/bin/env python3
"""网络探针：证明「连不上」是**沙箱**造成的，不是环境本来就坏。

两个子命令：
  listen <portfile>   在 127.0.0.1 上开一个随机端口，把端口号写进 <portfile>，一直服务
  connect <host> <port>  连一次，打印 OK 或 "ERR <errno> <msg>"，退出码 0 / 3

【为什么要单独写这个探针，而不是在脚本里用内联 python】
内联 python 里的反引号、中文标点、$ 都可能被 shell 吃掉，
出错时看到的现象是「脚本莫名其妙不执行」，极难定位。
探针必须像被测对象一样是**一个独立的、可读的脚本文件**。

【errno 是证据的一部分】
「连不上」有几种完全不同的原因：
  EPERM(1)/EACCES(13) = **被沙箱主动拒绝** → 这才是「封住了」
  ECONNREFUSED(61)    = 对面没人在听 → 网络是通的，只是没人接
  ETIMEDOUT(60)       = 网络层不通（真的断网 / 路由不通）
所以这个探针**必须打印 errno**，只打印「失败」是没有证据力的。
"""
import os
import socket
import sys

TIMEOUT = 5.0


def cmd_listen(portfile):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", 0))
    s.listen(8)
    port = s.getsockname()[1]
    # 先把端口号落盘，再开始服务：读脚本那边可能比监听来得早，
    # 读到空文件会当成「没起来」而误判。
    tmp = portfile + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write("%d\n" % port)
    os.replace(tmp, portfile)
    print("listening on 127.0.0.1:%d" % port, flush=True)
    # 回一个**最小但合法**的 HTTP 响应。
    # 为什么不能 accept 完就关：那会让 HTTP 客户端读到 EOF，
    # 报「Unexpected end of file from server」——
    # 负控制 B 的注入代码正是走 HTTP，于是「不封网时应当通过」那一步
    # 会因为**监听口说得不合格**而红，而那不是我们要测的东西。
    body = b""
    resp = (b"HTTP/1.1 200 OK\r\n"
            b"Content-Type: text/plain\r\n"
            b"Content-Length: %d\r\n"
            b"Connection: close\r\n\r\n" % len(body)) + body
    try:
        while True:
            try:
                conn, _ = s.accept()
            except OSError:
                break
            try:
                conn.settimeout(TIMEOUT)
                conn.sendall(resp)
            except OSError:
                pass
            finally:
                try:
                    conn.close()
                except OSError:
                    pass
    except KeyboardInterrupt:
        pass
    finally:
        s.close()
    return 0


def cmd_connect(host, port):
    s = socket.socket()
    s.settimeout(TIMEOUT)
    try:
        s.connect((host, int(port)))
        print("OK")
        return 0
    except OSError as exc:
        print("ERR %s %s" % (exc.errno, exc.strerror))
        return 3
    finally:
        s.close()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    if cmd == "listen" and len(sys.argv) == 3:
        return cmd_listen(sys.argv[2])
    if cmd == "connect" and len(sys.argv) == 4:
        return cmd_connect(sys.argv[2], sys.argv[3])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
