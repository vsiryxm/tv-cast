#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2026 Simon Yang <https://github.com/vsiryxm/tv-cast>
# SPDX-License-Identifier: Apache-2.0
"""统一配置：读取同目录 config.env，环境变量优先，没配置则用内置默认值。

改一个 config.env 就能让 cast.sh / cast.py / serve.py / wall.py 用同一组 IP。
"""
import os

_HERE = os.path.dirname(os.path.abspath(__file__))


def _load():
    path = os.path.join(_HERE, "config.env")
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip("'\""))


_load()

TV_IP = os.environ.get("TV_IP", "192.168.2.98")
PHONE_IP = os.environ.get("PHONE_IP", "192.168.2.83")
MAC_IP = os.environ.get("MAC_IP", "192.168.2.100")
ADB = os.environ.get("ADB_BIN", "/opt/homebrew/bin/adb")
