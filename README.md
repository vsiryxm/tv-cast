# tv-cast

零硬件，把手机上的直播画面投到老电视；更能把直播间下方的弹幕文字放大成满屏看板。
另外附带一个**完全离线的电视端 K 歌机**：U 盘插电视，电视自己播伴奏、自己画全屏大字歌词，电脑手机都可以关机。

实测环境：海信 LED50EC280JD（Android 4.0.4 / API 15，系统里没有 Miracast 接收端）+ 红米 Note 10 Pro，同一 WiFi。
**跑起来之后只有两台设备：手机 → WiFi → 电视，电脑不转发、可以关机。**

## 四种用法，先看这张表

| 想看什么 | 命令 | 电脑要常开 | 需要 AI |
| --- | --- | --- | --- |
| 整个直播画面 | `./cast.sh apk` | 不用 | 不用 |
| 只放大直播下方文字（谁在说话 / 谁来了） | `./cast.sh danmu` | 不用 | 不用 |
| 文字重排成超大字看板、消息累积（跨 App 自动聚焦） | `./cast.sh wall` | 要（macOS） | 本地 OCR |
| K 歌：U 盘伴奏 + 全屏大字歌词（电视独立运行） | `./cast.sh karaoke` | 不用 | 不用 |

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
wall.py / locate.py / static_server.py / vocr.swift    ← AI 文字看板（可选，跨 App 通用定位）
karaoke/                           ← 离线 K 歌机：装在电视上的独立 APK
  karaoke.apk                        现成 APK（21 KB，直接装，不用编译）
  src/com/xinmin/karaoke/KaraokeActivity.java          全部源码，无第三方库
  AndroidManifest.xml / build.sh / sample.lrc          重新编译用 / 歌词模板
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

**开播前先准备**：手机还没点 Start 也能先跑 `./cast.sh wall`，电视会显示
「准备就绪 · 请在手机上打开 ScreenStream 并点 Start」；手机一开始推流就自动变成文字看板，
不用重启任何东西。中途断流同理，会回到准备态并显示提示，不会永远停在上一张旧图。

## 离线 K 歌机（电视当主机，U 盘存歌）

`karaoke/` 是独立 APK（`com.xinmin.karaoke`）：**不联网、不用电脑、不用手机、不用平板**。
U 盘插电视 USB 口，电视自己 `MediaPlayer` 播伴奏、自己用 Canvas 画 1920×1080 大字歌词，
歌词和伴奏共用同一个播放时钟，不需要起拍对齐，唱完自动下一首。

### 傻瓜三步

1. U 盘格式化成 **FAT32**，根目录建 `karaoke/`，每首歌放两个**同名**文件：

   ```
   karaoke/
     月亮代表我的心.mp3
     月亮代表我的心.lrc
     朋友.mp3
     朋友.lrc
   ```

   `.mp3 / .wav / .m4a / .ogg` 都认；子目录也会递归扫；没有 `.lrc` 照样播，只是显示「这首歌没有歌词」。
2. U 盘插电视，`./cast.sh karaoke`（第一次会装 APK，之后直接切过去）。
   **装完电脑可以关机**，电视和 U 盘两台设备就能跑。
3. 遥控器按 `OK` 开始，↑↓ 换歌。

歌词用标准 LRC，UTF-8 或 GBK 自动识别，支持一行多个时间标签和 `[offset:±毫秒]`；
模板见 `karaoke/sample.lrc`。普通 LRC 只有整句时间轴，所以做**整句高亮 + 句内进度条**；
逐字染色需要 QRC/KRC 加密格式，暂不支持。

| 键 | 作用 |
| --- | --- |
| OK / 播放暂停 | 开始、暂停、继续 |
| ↑ / ↓ | 上一首 / 下一首 |
| ← / → | 快退 / 快进 5 秒（也能用来对齐起拍） |
| 菜单 | 打开歌单（↑↓ 选、OK 播）；再按一次关闭并重新扫描 U 盘 |
| 返回 | 连按两次退出 K 歌 |

### 音频接线：这一步决定能不能直播，先看清楚

电视**只有音频输出，没有可用的麦克风输入**。真机实测：
`AudioRecord` 能初始化（state=1）、能 `startRecording()`（state=3），
但连续读 800 个采样点 `peak=0 / avg=0` —— 是个**恒静音的死通道**，背后没有物理麦克风。
所以「人声」必须继续走原来的**布谷鸟无线麦 → 接收器 → 直播手机**，这条线一根都别动。

| 接法 | 怎么做 | 观众听到 | 代价 |
| --- | --- | --- | --- |
| A 零改动 | 电视喇叭直接放伴奏，让无线麦把人声和伴奏一起收进去 | 人声 + 外放伴奏（有串音、回声） | 0 元，音质一般，够先用起来 |
| **B 一根线（推荐）** | 电视 3.5mm 耳机/音频输出 → 布谷鸟接收器的「伴奏 / LINE IN」口 | 干净混音 | 需确认接收器有这个口；一根约 10 元对录线 |
| C 蓝牙发射器 | 电视 3.5mm → 蓝牙发射器 → 耳机 | 不进直播间 | 80~150ms 延迟，K 歌会慢半拍，不推荐 |

**一个还没实测的盲点**：老电视为音画同步通常给音频加缓冲，可能比平板蓝牙返听慢半拍。
先用 A 试唱一首，若明显拖拍，再决定走 B，或者改成「伴奏仍由平板放、电视只显示歌词」
（需要给 K 歌 App 加一个静音跟唱模式 + 手动起拍，目前**未实现**，别当成已有功能）。

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
./cast.sh karaoke                # 离线 K 歌机（U 盘插电视，装完可关电脑）
./cast.sh karaoke-build          # 改了 K 歌 Java 源码后重新编译安装
./cast.sh apk                    # 从 K 歌切回直播投屏 / 文字看板
./cast.sh serve                  # 扫码装机台 + 自动接管电视
./cast.sh apkstop                # 退出接收端回电视桌面
./cast.sh build                  # 重新编译并安装接收端
```

## 出问题先看这 7 条

- **电视显示「准备就绪 / 等待手机推流」**：这是正常准备态，说明手机 ScreenStream 没点 Start 或 8080 连不上；`./cast.sh check` 确认后手机点 Start 即可。所有局域网请求已强制直连，本机装有代理软件也不会被劫持。
- **一直「连接中」**：`./cast.sh check` 看手机 8080 是否就绪；确认 ScreenStream 点了 Start、两台设备同 WiFi、`config.env` 的 IP 没错；再试 `./cast.sh scan`。
- **有黑边**：遥控器左右键切到 0（模糊填充）或 1（裁切铺满）——竖屏内容投 16:9 无法同时不变形、不裁切、铺满。
- **adb 连不上**：`adb kill-server && adb start-server`，确认电视没待机。这台老系统有时要重连一次。
- **画面卡 / 断线**：接收端默认 12 fps、断线 1 秒自动重连，连续 3 次失败会从 `stream.mjpeg` 降级为单帧 `stream.jpeg` 轮询。
- **`wall` 关了电视会黑屏吗**：不会，退出时自动切回 `danmu` 并指回手机直连。
- **K 歌显示「未找到歌曲」**：先按菜单键重扫；U 盘要 FAT32、且 MP3 和 LRC 同名。屏幕上「曲库:」后面会显示实际找到歌曲的目录，方便定位。这台电视的 `/mnt/sdcard` 空目录权限是 `000`，没插 U 盘时读不到是正常的；若插了 U 盘仍读不到，`adb shell ls -l /mnt/sdcard` 看权限，必要时 `adb shell chmod -R a+rX /mnt/sdcard`。App 同时会读 `/proc/mounts` 找真实挂载点，不依赖写死的路径。

## 原理（一句话）

ScreenStream 把手机画面以 MJPEG 推在 8080；电视端原生 APK 裸扫 JPEG 帧（FFD8/FFD9）自绘上屏，不依赖浏览器、DLNA 或电脑。`danmu` 只是把帧下方那一横条裁出来铺满（这一条本身接近 16:9，放大约 3.5 倍且几乎不变形）；`wall` 多一层 macOS Vision 中文 OCR + 自动定位文字带 + 多帧投票去重 + 本机内存累积。
`karaoke` 是完全另一条路：不碰网络，电视自己读 U 盘的 MP3 + LRC，用播放时钟驱动全屏歌词重绘。

## 进阶：`wall` 的跨 App 通用文字定位

`wall` 不再写死裁切坐标，而是两阶段识别（`locate.py` + `wall.py`），**不新增任何模型**，只用 Vision 本来就返回的文字块坐标：

1. **整屏低倍 OCR 定位**：按 y 密度自动找「下半屏文字密集带」，只放大那一带精读。抖音直播伴侣、抖音观众端、微信视频号、横屏全屏、键盘弹出都自适应；约每 4 秒校准一次，其余帧复用上次结果，开销几乎不变。
2. **纯文字页自动切换**：微信群文字直播这类页面文字铺满全屏且行距均匀，判为 `chat` 模式，直接用全屏 OCR 结果，不做二次裁切。
3. **App / 布局切换**：低频缩略图差异连续两次超阈值就清空累积并重新定位，避免把上个直播间的消息混进来。
4. **非直播页暂停**：识别到信息流特征（拍同款 / 相关搜索 / 共 N 人推荐 / 首页朋友消息我 等，需同时出现 ≥2 个）就暂停入库，看板显示「当前不是直播间」；切回直播间自动恢复。刷短视频时不会把标题、话题、广告灌进看板。
5. **快滚动兜底**：弹幕只被抓到一帧时，1.6 秒后按超时提交，配合模糊去重，不会一闪而过就丢。

运行状态看 `http://电脑IP:8897/status.json` 的 `layout`（live / chat / fallback）、`band`（当前聚焦区间）、`paused`。
若某个直播间被误判成信息流，把 `locate.py` 的 `FEED_MARKERS` 调严即可。

## 许可

本仓库代码 MIT（见 `LICENSE`）。`ScreenStream.apk` 是第三方 MIT 二进制，来源与许可全文见
`THIRD_PARTY_NOTICES.md`，本仓库未修改；不想要可以直接删掉，主流程不依赖它。
