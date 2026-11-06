# 第三方组件声明

## ScreenStream（手机端发送 APK）

- 文件：`ScreenStream.apk`
- 版本：4.4.2（versionCode 44002），包名 `info.dvkr.screenstream`，要求 Android 7+
- 来源：F-Droid 官方仓库构建产物
  `https://mirror.fcix.net/fdroid/repo/info.dvkr.screenstream_44002.apk`
- 上游项目：https://github.com/dkrivoruchko/ScreenStream
- 许可证：**MIT License**，Copyright (c) 2016 Dmytro Kryvoruchko
- 本仓库**未做任何修改**，仅内置一份原始二进制，方便离线扫码装机。
  也可以删掉它、改从 F-Droid / Google Play 自行安装。
- 若不需要扫码装机功能，此文件可安全删除；`cast.sh apk` / `danmu` / `wall` 都不依赖它。

### MIT License（ScreenStream 原文）

The MIT License (MIT)

Copyright (c) 2016 Dmytro Kryvoruchko

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

本仓库自身代码（receiver/、cast.sh、cast.py、serve.py、wall.py、static_server.py、
vocr.swift、tvconf.py）适用仓库根目录 `LICENSE`（MIT, Copyright (c) 2026 Simon Yang）。
