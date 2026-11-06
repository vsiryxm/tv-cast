#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""用系统 Python 托管看板图片。

为什么单独一个文件：本机 macOS 会静默丢弃 Homebrew Python 的 LAN 入站连接，
而系统 /usr/bin/python3 正常；wall.py 需要 PIL 只能跑 Homebrew Python，
所以对外服务拆到这里。HTTP/1.1 + 显式 Connection: close 适配 Android 4.0 的老 HTTP 栈。
"""
import functools
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class Handler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache")
        self.send_header("Connection", "close")
        self.close_connection = True
        super().end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port, directory = int(sys.argv[1]), sys.argv[2]
    srv = ThreadingHTTPServer(
        ("0.0.0.0", port), functools.partial(Handler, directory=directory))
    srv.daemon_threads = True
    srv.serve_forever()
