# tv-cast

零硬件，把手机上的直播画面投到老电视；更能把直播间下方的弹幕文字放大成满屏看板。

实测环境：海信 LED50EC280JD（Android 4.0.4 / API 15，系统里没有 Miracast 接收端）+ 红米 Note 10 Pro，同一 WiFi。
**跑起来之后只有两台设备：手机 → WiFi → 电视，电脑不转发、可以关机。**

## 三档用法，先看这张表

| 想看什么 | 命令 | 电脑要常开 | 需要 AI |
| --- | --- | --- | --- |
| 整个直播画面 | `./cast.sh apk` | 不用 | 不用 |
| 只放大直播下方文字（谁在说话 / 谁来了） | `./cast.sh danmu` | 不用 | 不用 |
| 文字重排成超大字看板、消息自动累积 | `./cast.sh wall` | 要（macOS） | 本地 OCR |

前两档完全不用 AI：文字本来就画在画面里，接收端把那一横条像素裁出来铺满即可，实时且零依赖。
第三档用 macOS 自带 Vision 把像素翻译成文字，再重排去重，字号最大、消息不会一闪而过。

## 接收端 / 发送端分别在哪

```
receiver/                          ← 接收端：装在电视上
  tvcast.apk                         现成 APK（20 KB，直接装，不用编译）
  src/com/xinmin/tvcast/ReceiverActivity.java    全部源码，无第三方库
  AndroidManifest.xml / build.sh / debug.keystore    重新编译用
ScreenStream.apk                   ← 发送端：装在手机上（F-Droid 原版 4.4.2，MIT，未修改）
cast.sh                            ← 电脑侧总开关，日常只需要记它
tvconf.py / config.env.example     ← 统一配置（3 个 IP）
serve.py / qrgen.swift             ← 扫码装机台（可选）
wall.py / static_server.py / vocr.swift    ← AI 文字看板（可选）
cast.py                            ← Mac 中继与浏览器方案（备用）
```

## 傻瓜式上手：5 步出画面

### 0. 前提（只做一次）

- 电视、手机连**同一个 WiFi**；手机最好在路由器里设成静态 IP。
- 电视已开 **ADB 网络调试**（本机海信是 5555）：`adb connect 电视IP:5555` 能连上即可。
- 电脑有 `adb`（官方 platform-tools 或 `brew install --cask android-platform-tools`）。路径不同就写进 `config.env` 的 `ADB_BIN`。
- 只有 `wall` 档额外需要 macOS + Xcode（提供 `swiftc` / Vision）+ `/opt/homebrew/bin/python3` 装了 `pillow`。

### 1. 下载并填 3 个 IP

```bash
git clone git@github.com:vsiryxm/tv-cast.git
cd tv-cast
cp config.env.example config.env
```

用文本编辑器打开 `config.env`，只改 3 行（**只写 IP，不带端口、不带 http://**）：

```
TV_IP=192.168.1.98        # 电视
PHONE_IP=192.168.1.83     # 手机
MAC_IP=192.168.1.100      # 电脑（只有扫码装机 / AI 看板用得到）
```

IP 在各自「WiFi 详情」里看；也可以先跑 `./cast.sh scan` 让电视自己搜推流源。
`config.env` 不会进仓库（含家里真实 IP），clone 后必须做这一步 `cp`。

### 2. 手机装发送端，点 Start

- 装 `ScreenStream.apk`；懒得传文件就 `./cast.sh serve`，电视会显示二维码，手机扫码直接装。
- 打开 → **Local (MJPEG)** → 关掉 PIN → **Start** → 系统弹窗点「允许 / 立即开始」。
- 它给出的 `http://手机IP:8080` 不用抄，就是上一步的 `PHONE_IP`。
- 切到抖音直播伴侣正常开播，ScreenStream 留后台即可。

### 3. 电视装接收端（只做一次）

```bash
./cast.sh install     # 装仓库里现成的 tvcast.apk，不需要任何编译工具链
```

想改源码再编译才用 `./cast.sh build`（需要老 Android 工具链，见 `receiver/build.sh`；
`debug.keystore` 只是让重编译的 APK 能覆盖安装，别拿去发布正式应用）。

### 4. 出画面

```bash
./cast.sh check       # 显示「8080 推流：已就绪」再继续
./cast.sh apk         # 电视全屏显示直播画面
./cast.sh danmu       # 改成：只放大直播下方文字，铺满全屏
```

到这里电脑可以关机。想上 AI 大字看板：

```bash
./cast.sh wall        # 前台常驻；Ctrl+C 会自动把电视切回 danmu，不会留黑屏
```

## 遥控器（装好接收端后）

| 键 | 作用 |
| --- | --- |
| 左右 | 切显示模式：0 满屏填充 / 1 裁切 / 2 拉伸 / 3 原始比例 / 4 弹幕文字 |
| 上下 | 弹幕模式下微调裁切窗口（`./cast.sh danmu 0.60 0.90` 也可直接指定） |
| 菜单 | 手动输入串流地址 / 自动搜索 |
| 返回 | 连按两次退出 |

## 命令速查

```bash
./cast.sh                        # 打印全部命令
./cast.sh install                # 装现成接收端 APK
./cast.sh check                  # 手机推流是否就绪
./cast.sh apk                    # 直连手机（默认 http://PHONE_IP:8080，自动补 /stream.mjpeg）
./cast.sh apk 'http://其他IP:8080'   # 手动指定别的 MJPEG / JPEG 流
./cast.sh scan                   # 电视自动搜局域网里的推流源
./cast.sh mode N                 # 命令行切显示模式（0~4）
./cast.sh danmu                  # 只看文字（免电脑）
./cast.sh wall                   # AI 大字看板（需电脑常开）
./cast.sh serve                  # 扫码装机台 + 自动接管电视
./cast.sh apkstop                # 退出接收端回电视桌面
./cast.sh build                  # 重新编译并安装接收端
```

## 出问题先看这 5 条

- **一直「连接中」**：`./cast.sh check` 看手机 8080 是否就绪；确认 ScreenStream 点了 Start、两台设备同 WiFi、`config.env` 的 IP 没错；再试 `./cast.sh scan`。
- **有黑边**：遥控器左右键切到 0（模糊填充）或 1（裁切铺满）——竖屏内容投 16:9 无法同时不变形、不裁切、铺满。
- **adb 连不上**：`adb kill-server && adb start-server`，确认电视没待机。这台老系统有时要重连一次。
- **画面卡 / 断线**：接收端默认 12 fps、断线 1 秒自动重连，连续 3 次失败会从 `stream.mjpeg` 降级为单帧 `stream.jpeg` 轮询。
- **`wall` 关了电视会黑屏吗**：不会，退出时自动切回 `danmu` 并指回手机直连。

## 原理（一句话）

ScreenStream 把手机画面以 MJPEG 推在 8080；电视端原生 APK 裸扫 JPEG 帧（FFD8/FFD9）自绘上屏，不依赖浏览器、DLNA 或电脑。`danmu` 只是把帧下方那一横条裁出来铺满（这一条本身接近 16:9，放大约 3.5 倍且几乎不变形）；`wall` 多一层 macOS Vision 中文 OCR + 多帧投票去重 + 本机内存累积。

## 许可

本仓库代码 MIT（见 `LICENSE`）。`ScreenStream.apk` 是第三方 MIT 二进制，来源与许可全文见
`THIRD_PARTY_NOTICES.md`，本仓库未修改；不想要可以直接删掉，主流程不依赖它。
