# tv-cast

零硬件，把手机上的直播画面投到老电视；更能把直播间下方的弹幕文字放大成满屏看板。
另外附带一个**完全离线的电视端 K 歌机**：U 盘插电视，电视按「歌名.mp3 / 歌名.lrc / 歌名.伴奏.mp3」加载曲库，按 1 切原唱或伴奏、歌单按点歌次数排序，自己画全屏大字歌词，电脑手机都可以关机。

实测环境：海信 LED50EC280JD（Android 4.0.4 / API 15，系统里没有 Miracast 接收端）+ 红米 Note 10 Pro，同一 WiFi。
**跑起来之后只有两台设备：手机 → WiFi → 电视，电脑不转发、可以关机。**

## 四种用法，先看这张表

| 想看什么 | 命令 | 电脑要常开 | 需要 AI |
| --- | --- | --- | --- |
| 整个直播画面 | `./cast.sh apk` | 不用 | 不用 |
| 只放大直播下方文字（谁在说话 / 谁来了） | `./cast.sh danmu` | 不用 | 不用 |
| 文字重排成超大字看板、消息累积（跨 App 自动聚焦） | `./cast.sh wall` | 要（macOS） | 本地 OCR |
| K 歌：U 盘原唱/伴奏 + 全屏大字歌词（电视独立运行） | `./cast.sh karaoke` | 不用 | 不用 |

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
  karaoke.apk                        现成 APK（直接装，不用编译）
  src/com/xinmin/karaoke/KaraokeActivity.java          界面 / 曲库 / 排序 / 歌词 / 遥控器
  karaoke.conf.example               台名 + 欢迎语的配置模板
  prepare_song.sh                    一条命令：MP3 + LRC -> 歌名.伴奏.mp3 [-> 电视 U 盘]
  make_inst.sh                       简易版：ffmpeg 相位抵消生成伴奏（几秒，效果一般）
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

`karaoke/` 是独立 APK（`com.xinmin.karaoke`），**台名可配置、默认「K歌台」**（见下面「台名与欢迎语」），
图标是深蓝底、金黄「谢」字：**不联网、不用电脑、不用手机、不用平板**。
U 盘插电视 USB 口，电视自己解码 MP3、自己用 Canvas 画 1920×1080 大字歌词，
歌词和音频共用同一个播放时钟，唱完自动下一首。

开机若没插 U 盘，**画面会停在启动页显示「请插入 U 盘」**，插好后自动开始，
不用按任何键，也不会进入空歌单。

### 曲库只认这一种文件规则

U 盘任意子目录里，一首歌就是**同目录、同名**的三个文件：

```
月亮代表我的心.mp3          原唱
月亮代表我的心.lrc          歌词（标准 LRC，UTF-8 / GBK 自动识别）
月亮代表我的心.伴奏.mp3     伴奏（可选；缺了这首歌就只有原唱）
```

- 只扫 `.mp3` 与同名 `.lrc`；`.wav / .m4a / .ogg` 和旧版 `.人声 / -inst / .vocal / .导唱`
  分轨文件**一律忽略**，不会混进歌单。
- 伴奏只认 `歌名.伴奏.mp3` 一种写法；`-伴奏` / `_伴奏` / `(伴奏)` 不再识别。
- 没有 `.lrc` 照样播，只是显示「正在播放」而不显示歌词。
- 歌单**双排序**：累计点歌次数降序 → 文件名升序。次数记在电视的 `karaoke_counts`
  偏好里，每次从头唱一首 +1（切原唱/伴奏、快进、开机恢复不计），
  下次扫描曲库（开机 / 插回 U 盘 / 菜单重扫）生效；歌单每行右侧显示 `N 次`。
  会话内顺序固定，按 0 切歌不会乱跳。

### 台名与欢迎语（可配置，不用改代码）

台名出现在**启动页、顶栏、开机欢迎语**里，默认「K歌台」。两个层级都能改：

| 改哪 | 怎么改 | 生效时机 |
|---|---|---|
| **构建期**（连应用列表里的名字一起改） | `karaoke/karaoke.conf` 写一行 `APP_NAME=你的台名`，再 `./cast.sh karaoke-build` | 重新编译 |
| **运行期**（不动 APK） | U 盘根目录放 `karaoke.conf`，写一行 `app_name=你的台名` | 重插 U 盘或重启 App |

优先级：U 盘配置 > 构建配置 > 默认「K歌台」。欢迎语在同一份文件里写 `welcome=…`，
不写就自动用「欢迎来到 <台名>，」。键名大小写不敏感，等号后直接写文字、不要加引号；
模板见 `karaoke/karaoke.conf.example`。读不到或写错只会回落默认值，不影响唱歌。

### 傻瓜三步：你给 MP3 + 歌词，一条命令搞定

1. 把歌和同名歌词放一个目录（Mac 上任意位置，不用先拷 U 盘）：

   ```
   我的歌单/
     月亮代表我的心.mp3
     月亮代表我的心.lrc
   ```

2. 跑一次（本机离线分离，音乐不上传）：

   ```bash
   ./cast.sh song "我的歌单/月亮代表我的心.mp3"        # 单曲
   ./cast.sh song --dir /Volumes/USB/karaoke           # 整库批量
   ./cast.sh song --tv "我的歌单/月亮代表我的心.mp3"   # 生成后直接推到电视 U 盘
   ```

   产物只多一个 `月亮代表我的心.伴奏.mp3`，三个文件同目录拷进 U 盘即可。
   `--tv` 会在 U 盘里找同名主歌把伴奏补进去；U 盘里还没有这首歌时，
   会连 MP3 和 LRC 一起放进 `/mnt/sdcard/karaoke/`。

3. U 盘插电视，`./cast.sh karaoke`，打开应用列表里你的 K 歌台即可自动开唱；
   遥控器 `1` 原唱/伴奏、`2` 重唱、`3` 歌单、`0` 切歌，`OK` 播放/暂停。
   **装完电脑可以关机。**

第一次跑前准备一次 Demucs（脚本会提示，装过可跳过）：

```bash
/opt/homebrew/bin/python3 -m venv ~/.venvs/demucs
~/.venvs/demucs/bin/pip install demucs numpy soundfile
```

`pip` 装完 demucs 常缺 `numpy` / `soundfile`，不补会直接报错，别漏这步。
本机实测 2:55 的歌约 5 秒分离完。已有伴奏会跳过，重做加 `--force`。

### 遥控器

| 键 | 作用 |
| --- | --- |
| OK / 播放暂停 | 开始、暂停、继续 |
| **1** | **原唱 ↔ 伴奏**（换文件、保持播放位置与歌词；也认静音键 / M） |
| **2** | 重唱当前这首（整首从 00:00 开始） |
| **3** | 打开 / 收起歌单（↑↓ 选、OK 马上唱、3 返回；歌单里 0 直接切歌） |
| **0** | 切到下一首（主菜单第 4 位按钮，角标印 0） |
| 菜单 | 歌单；再按一次关闭并重扫曲库 |
| 返回 | 连按两次退出 K 歌 |

主屏方向键不绑功能（留给歌单选歌），避免老人误切歌。**没有音量菜单**：
原唱和伴奏是两个独立文件，任意时刻只播一个，不能分别调音量；整机音量用电视遥控器调。
歌词支持一行多个时间标签和 `[offset:±毫秒]`，模板见 `karaoke/sample.lrc`；
前奏用 5 个音符按时间染金黄，歌词三行随演唱上滚、当前句逐字染色。

### 原理与踩过的坑

伴奏用 Demucs `--two-stems=vocals --other-method minus` 生成，脚本统一
**CBR 128k / 44.1kHz / 立体声 / 无 Xing**，并校验伴奏与原唱时长差 ≤ 0.5 秒：
4.0.4 的 `MediaPlayer` 遇到 VBR 时 `seekTo` 会被静默忽略，切换音轨就保不住位置。

**坑：不能用两个 `MediaPlayer`。** 这台海信的 `MediaPlayer` 走 MTK 私有
`CmpbPlayer` / `MTK_MW` 独占会话。真机实测两个实例同时 `start()` 时，后一个把前一个
顶成 `PLAY_DONE`，触发 `onCompletion` → 自动切歌 → 每 2.2 秒无限重启（进度永远 `00:00`）。
早期版本曾用 JLayer + `AudioTrack` 双路解码混音绕开，但曲库已收敛成
「原唱 / 伴奏二选一」，单实例 `MediaPlayer` 就够；JLayer 与那双路混音引擎
已于 v5 彻底移除，仓库里不再保留这两份代码和 jar。

`karaoke/make_inst.sh` 是不想装 Demucs 时的简易备选：ffmpeg 相位抵消（左右声道相减）
几秒出伴奏，但鼓和贝斯会一起变薄；加 `--ai` 则走 Demucs。找现成原版伴奏也行，
注意和原唱**等长**。直播属公开传播，伴奏授权自己留意；家里自唱无碍。

### 音频接线：这一步决定能不能直播，先看清楚

电视**只有音频输出，没有可用的麦克风输入**。真机实测：`AudioRecord` 能初始化
（state=1）、能 `startRecording()`（state=3），但连续读 800 个采样点
`peak=0 / avg=0` —— 是个**恒静音的死通道**，背后没有物理麦克风。
所以「人声」必须继续走**布谷鸟无线麦 → 2.4G 接收器 → 直播手机**，这条线一根都别动。

伴奏进耳机（也进直播间）走 **3.5mm 蓝牙发射器（TX）**：电视 3.5mm 耳机口 →
TX 发射器（USB 只取电）→ 蓝牙连到挂脖耳机。实测这台电视插 3.5mm 会
**物理切断内置喇叭**（插座带机械开关），不会出现外放与耳机双份伴奏重影。注意：

- 电视 USB 口只供电，不是 USB 声卡（框架无 usb audio HAL、内核无 `snd-usb-audio`），
  别买插 USB 出声的那种发射器。
- 3.5mm 与喇叭共用同一条 PCM，电视音量就是送进发射器的电平；**别调到 0**，
  会连发射器输入一起静音。
- TX → 耳机的蓝牙延迟约 80~200ms，字幕按电视本地时钟走会慢半拍；
  `karaoke.conf` 的 `audio_delay_ms` 补偿**尚未实现**，需要时再加。

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
./cast.sh song "朋友.mp3"         # MP3+LRC -> 歌名.伴奏.mp3
./cast.sh song --tv "朋友.mp3"     # 同上，并直接推到电视 U 盘
./cast.sh song --dir U盘目录       # 整库批量分离
karaoke/make_inst.sh "朋友.mp3"   # 不想跑 Demucs 时的简易伴奏（加 --ai 走 AI）
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
- **按 1 提示「这首歌没有伴奏版」**：伴奏文件必须叫 `歌名.伴奏.mp3`，和主歌**同一目录、同名**；先跑 `./cast.sh song --tv "歌名.mp3"` 生成。写成 `-伴奏` / `_伴奏` 一律不认。
- **歌单顺序没变**：点歌次数只写进偏好，要等下次扫描曲库（重开 App / 插回 U 盘 / 按菜单）才重排；歌单每行右侧能看到累计次数。
- **歌单里出现奇怪的歌名**：旧版 `.人声.mp3` / `-inst.mp3` 已不再识别，但仍会占 U 盘空间，建议删掉。
- **`./cast.sh song` 报 demucs 缺模块**：补 `~/.venvs/demucs/bin/pip install numpy soundfile`；脚本依赖本机 ffmpeg。

## 原理（一句话）

ScreenStream 把手机画面以 MJPEG 推在 8080；电视端原生 APK 裸扫 JPEG 帧（FFD8/FFD9）自绘上屏，不依赖浏览器、DLNA 或电脑。`danmu` 只是把帧下方那一横条裁出来铺满（这一条本身接近 16:9，放大约 3.5 倍且几乎不变形）；`wall` 多一层 macOS Vision 中文 OCR + 自动定位文字带 + 多帧投票去重 + 本机内存累积。
`karaoke` 是完全另一条路：不碰网络，电视自己读 U 盘的原唱 / 伴奏 MP3 + LRC，用单个 `MediaPlayer` 播放、用它的时钟驱动全屏歌词重绘。

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

本仓库代码采用 **Apache License 2.0**，全文见 `LICENSE`，版权归
**Simon Yang**（https://github.com/vsiryxm/tv-cast ）。

可以自由使用、修改、商用、再分发，但有两条硬性要求：

1. **必须保留版权与许可声明。** 分发源码或二进制时，要附带 `LICENSE` 和 `NOTICE`；
   改动过的文件需标明「已修改」。去掉署名、去掉 `NOTICE` 即属违约。
2. **不得暗示作者为你或你的产品背书**，也不得使用本项目名称、作者姓名做推广
   （商标权与署名权未随许可授权）。

不打算分发、只是自己装着用的话，以上都不用管。

第三方组件：`ScreenStream.apk` 是第三方 MIT 二进制，来源与许可全文见
`THIRD_PARTY_NOTICES.md`，本仓库未修改；不想要可以直接删掉，主流程不依赖它。
除它以外，仓库内全部代码均为本项目原创。
