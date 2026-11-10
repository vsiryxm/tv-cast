// Copyright 2026 Simon Yang <https://github.com/vsiryxm/tv-cast>
// SPDX-License-Identifier: Apache-2.0
package com.xinmin.karaoke;

/*
 * 电视端离线 K 歌机 —— Android 4.0.4 / API 15，零依赖、零网络、零电脑。
 * 面向 64 岁老人：单屏、大字、图标+文字、数字键 1/2/3/0 直达，无二级菜单层级。
 *
 * 曲库严格三件套（U 盘同目录，只认 .mp3 / .lrc）：
 *   歌名.mp3          原唱
 *   歌名.lrc          歌词
 *   歌名.伴奏.mp3     伴奏（可选；缺了这首歌就只有原唱）
 * 旧版 .人声 / -inst / .vocal / .导唱 等分轨文件一律忽略，不会混进歌单。
 *
 * 歌单双排序：累计点歌次数降序 -> 文件名升序。次数存在 karaoke_counts 偏好里，
 * 每次从头唱一首记一次（切音轨 / seek / 开机恢复不计），扫描曲库时生效
 * （开机、U 盘插回、菜单重扫）；会话内顺序固定，切歌不乱跳。
 *
 * 单屏结构：顶栏（歌名/进度/状态） + 中间内容区（歌词 | 歌单）
 *          + 全曲进度条 + 四个大图标按钮 + 一行底部提示。永远只有一层。
 * 歌词区：前奏用 5 个音符按时间染金黄；歌词三行等距排列，当前句唱完整体上滚
 *          一格（前一句 / 当前句 / 下一句同步变换）；当前句逐字染色，不另加进度条。
 *
 * 按钮：1 原唱/伴奏  2 重唱（整首从头）  3 歌单  0 切歌（数字键直达 / 鼠标点均可）
 * 键盘 / 遥控器：OK 播放暂停，M 切原唱伴奏，菜单开歌单，← → 双击快退快进 10 秒。
 * 原唱 / 伴奏是两个独立文件，切换时保持播放位置与歌词。
 *
 * 音频输出走电视自身扬声器 / 3.5mm 输出；本机不提供麦克风输入，
 * 你自己唱的声音仍由原有无线麦克风链路负责，见 README 的「音频接线」一节。
 */
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KaraokeActivity extends Activity {

    /* 代码分区（按出现顺序，便于定位，改动时不必通读全文）：
       1  常量与可配置项        2  数据模型 Line / Song       3  界面状态字段
       4  生命周期 onCreate     5  偏好与 karaoke.conf 读取   6  曲库扫描与 LRC 解析
       7  播放控制（play/seek） 8  按键与鼠标                  9  绘制辅助
       10 KView：绘制、触摸、全部画面
    */

    private static final String TAG = "Karaoke";
    private static final String PREFS = "karaoke_v4";
    private static final String PREFS_COUNT = "karaoke_counts";
    private static final String[] ROOTS = {
        "/mnt/sdcard", "/mnt/usbdisk", "/mnt/usb", "/sdcard",
        "/storage/sdcard0", "/data/local/tmp/karaoke"
    };
    private static final Pattern LRC_TAG =
        Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern LRC_OFFSET =
        Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);
    /* LRC 前几行常是标题 / 作词 / 作曲，不能拿来当前奏倒数的基准 */
    private static final Pattern LRC_META = Pattern.compile(
        "作词|作曲|演唱|编曲|监制|出品|原唱|歌名|专辑|歌手|词\\s*[:：]|曲\\s*[:：]|歌词|LRC");

    /** 伴奏只认「歌名.伴奏.mp3」这一种写法。 */
    private static final String INST_SUFFIX = ".伴奏";
    /** 旧版分轨残留：不进歌单，避免歌名里混出「歌名.人声」之类条目。 */
    private static final String[] LEGACY_SUFFIXES = {
        ".人声", "-人声", "_人声", "(人声)", " (人声)",
        ".vocal", "-vocal", "_vocal", ".导唱", "-导唱",
        "-inst", "_inst", ".inst", " (inst)", "(inst)"
    };

    /* 简洁 / 优雅 / 精致：纯色底、单一强调色、四级字号、统一圆角。
       API 15 的 Canvas 每 100ms 重绘一次，去掉渐变与描边还有实际性能收益。 */
    private static final int BG       = 0xFF0B1120;
    private static final int CARD     = 0xFF161F33;
    private static final int CARD_ON  = 0xFF243352;
    private static final int INK      = 0xFFF2F5FB;   // 主文字：米白
    private static final int INK_SOFT = 0xFFC3CEE2;   // 辅助：高对比浅灰
    private static final int INK_DIM  = 0xFF8898B4;   // 弱化信息 / 已唱过
    private static final int GOLD     = 0xFFFFC94A;   // 唯一强调色
    private static final int TEAL     = 0xFF7FE3D0;
    private static final int BAD      = 0xFFFF8A80;

    private static final float FS_LYRIC = 0.050f;   // 64px @1280
    private static final float FS_BTN   = 0.027f;   // 34px
    private static final float FS_NAME  = 0.024f;   // 30px
    private static final float FS_SUB   = 0.020f;   // 26px

    private static final int NOTES = 5;             // 前奏音符个数
    private static final int NOTE_OFF = 0xFF44527A; // 未走到的音符
    /* ---------- 可配置项：U 盘根目录 karaoke.conf ----------
       一行一个 key=value，井号开头是注释，键名大小写不敏感。支持两个键：
         app_name  台名（启动页、顶栏、开机欢迎语都用它）
         welcome   跑马灯欢迎语，不写就用「欢迎来到 <台名>，」
       改完重插 U 盘或重启 App 生效；文件不存在、写错、读失败只回落默认，不影响唱歌。
       台名的构建期默认值由 build.sh 从 karaoke.conf 的 APP_NAME 写进 Manifest，
       运行时这份配置再覆盖一次，是为了不重新编译也能改名。 */
    private static final String APP_NAME_FALLBACK = "K歌台";
    private String appName = APP_NAME_FALLBACK;
    private String welcomeText = "";
    private static final int MARQUEE_MS = 50;       // 顶部跑马灯 20fps，匀速横移
    private static final int INTRO_FADE_MS = 450;   // 开唱后前奏音符淡出上浮的时长
    private static final long ROLL_MS = 450;        // 歌词上滚一格时长
    private static final long ROLL_LEAD = 600;      // 换句前多久开始提速采样
    private static final int ROLL_FAST = 25;

    private static final String[] BTN_LABELS = { "原唱", "重唱", "歌单", "切歌" };
    /** 按钮角标：第 4 位是「切歌」，快捷键用 0，不印 4。 */
    private static final String[] BTN_KEYS = { "1", "2", "3", "0" };
    private static final int ICON_VOICE = 0, ICON_REPLAY = 1, ICON_SKIP = 2,
                             ICON_LIST = 3;

    static class Line {
        long ms;
        String text;
        /** LRC 里紧跟这一行的纯空行时间＝「这句唱完了」。0 表示文件没写，扫字终点退回下一行行首。 */
        long endMs = 0;
        Line(long m, String t) { ms = m; text = t; }
    }

    static class Song {
        String name;
        String path;        // 原唱：歌名.mp3
        String instPath;    // 伴奏：歌名.伴奏.mp3；null 表示只有原唱
        int plays;          // 累计点歌次数
        boolean hasLrc;
        ArrayList<Line> lines = new ArrayList<Line>();
    }

    /** 双排序：点歌次数降序；次数相同按文件名升序（忽略大小写，再比原值保稳定）。 */
    private final Comparator<Song> SONG_ORDER = new Comparator<Song>() {
        public int compare(Song a, Song b) {
            if (a.plays != b.plays) return a.plays > b.plays ? -1 : 1;
            int r = a.name.compareToIgnoreCase(b.name);
            return r != 0 ? r : a.name.compareTo(b.name);
        }
    };

    private ArrayList<Song> songs = new ArrayList<Song>();
    private int cur = 0;
    private int listCursor = 0;
    private int libTop = 0;
    private boolean showList = false;      // 中间内容区切成歌单
    private int activeBtn = 0;             // 按钮条上唯一的金黄焦点
    private long focusSwitchAt = 0;        // 焦点切换的 120ms 底色过渡
    private int rollLastLine = Integer.MIN_VALUE;  // 上一帧的当前歌词行
    private long rollStartAt = 0;          // 上滚开始时刻，0 = 没在滚
    private boolean playing = false;
    private boolean useInst = false;       // true=播「歌名.伴奏.mp3」，false=播「歌名.mp3」
    private int seekFloor = 0;
    private long seekFloorUntil = 0;
    /* 调试用：鼠标点/拖全曲进度条快进快退。dragMs >= 0 表示正在拖动，
       画面按目标时间走；电视没有触摸屏，真机上不会触发。 */
    private int dragMs = -1;
    /* 主屏双击 ← / → 快退 / 快进：单击仍不响应（防老人误触），
       350ms 内连按同一个键才生效，步长 10 秒。 */
    private static final long DOUBLE_TAP_MS = 350L;
    private static final int SEEK_STEP_MS = 10000;
    private int lastDirKey = -1;
    private long lastDirAt = 0L;
    private int pressedBtn = -1;                      // 鼠标按下的按钮索引，UP 才触发
    private static final int[] BTN_ACTS = { 1, 2, 3, 0 };  // 按钮 k -> act 的数字键
    /* 鼠标：歌单滚轮 / 按住拖动滚动 / 点击点歌。列表由 listCursor 驱动 libTop，
       滚动必须同步移动选中行，否则每帧 syncLibTop() 会把 libTop 拉回去。 */
    private boolean listTouched = false;
    private float listDownY = 0f;
    private int listDownCursor = 0;
    private int pressedRow = -1;                      // 按下时的行索引，UP 仍在原行才算点击
    private boolean backArmed = false;
    private String err = "";
    private String rootHint = "";

    /* 大字反馈 / 唱完过场 / 拔盘 */
    private long bannerUntil = 0;
    private String bannerText = "";
    private long interludeUntil = 0;
    private String interludeName = "";
    private int pendingIdx = 0;
    private boolean usbGone = false;
    private long lastRecoverAt = 0;

    /* 点歌次数：点歌时只挂「待结算」，自然播到结尾才 +1。
       切歌 / 重唱 / 换音轨 / 退出都不结算；拖到结尾也不算（countVetoed）。 */
    private Song pendingCount = null;
    private boolean countVetoed = false;

    /* 开机记忆：只存文件名，不存序号（以后往 U 盘加歌序号会漂） */
    private SharedPreferences prefs;
    private SharedPreferences counts;
    private String lastSong = "";

    /* 启动过渡页：首帧先画「台名 / 启动中」，曲库扫完才切到主界面。
       首扫没找到歌（U 盘没插）就一直停在启动页提示插盘，每 2 秒重扫。
       scannedHint / scannedWith 由后台扫描线程写、主线程读，只存简单值。 */
    private boolean booting = true;
    private boolean waitingUsb = false;
    private boolean scanning = false;
    private static final int USB_WAIT_RETRY_MS = 2000;
    private final Runnable retryScan = new Runnable() {
        public void run() { if (booting) rescan(); }
    };
    private String scannedHint = "";
    private int scannedWith = 0;

    private MediaPlayer player;
    private KView view;
    private final Handler handler = new Handler();
    private final Runnable nextSong = new Runnable() {
        public void run() { startInterlude(); }
    };
    private PowerManager.WakeLock wake;
    private BroadcastReceiver usbReceiver;

    private final Paint pBg = new Paint();
    private final Paint pCard = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCardOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFocus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFade = new Paint();          // 跑马灯两端渐隐专用（只改 shader）
    private final Paint pPrev = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNext = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBarOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pIco = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNote = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBreath = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFocusBg = new Paint(Paint.ANTI_ALIAS_FLAG);  // 焦点过渡专用，别污染 pCardOn
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);     // 进度条末端发光亮点专用

    private final Runnable tick = new Runnable() {
        public void run() {
            if (view != null) view.invalidate();
            // 换句前后 40fps；平时 20fps 供顶部跑马灯匀速横移（其余画面不常重绘）
            boolean rolling = rollStartAt > 0
                && System.currentTimeMillis() - rollStartAt < ROLL_MS;
            handler.postDelayed(this, (rolling || nearLineChange()) ? ROLL_FAST : MARQUEE_MS);
        }
    };

    /** 换句前提前提速采样。空闲时 100ms 才刷一帧，会让「该翻了」晚半拍被发现，
        看起来就是卡一下再猛跳一格；提前进 40fps 才能接上平滑的滚动。 */
    private boolean nearLineChange() {
        if (!playing || songs.isEmpty() || showList) return false;
        if (usbGone || System.currentTimeMillis() < interludeUntil) return false;
        Song s = songs.get(cur);
        if (!s.hasLrc || s.lines.isEmpty()) return false;
        int pos = position();
        int i = lineAt(s, pos);
        if (i + 1 >= s.lines.size()) return false;
        long left = s.lines.get(i + 1).ms - pos;
        return left > 0 && left < ROLL_LEAD;
    }

    private final Runnable disarmBack = new Runnable() {
        public void run() { backArmed = false; }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        initPaints();
        view = new KView(this);
        view.setKeepScreenOn(true);
        setContentView(view);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG);
        wake.acquire();

        loadPrefs();
        probeMic();
        resolveAppName();      // 先定台名，启动页第一帧就能显示正确的名字
        loadConf();            // 再叠 U 盘配置（台名 / 欢迎语）

        /* 启动过渡画面：booting=true 时 onDraw 只画「台名 / 启动中」，
           曲库扫描放到后台线程，冷启动不再黑屏干等 2~3 秒。
           延迟 120ms 是让首帧先上屏，否则扫描抢在主线程前面又会看到黑屏。 */
        handler.postDelayed(new Runnable() {
            public void run() { rescan(); }
        }, 120);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        usbReceiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                final String act = i.getAction();
                Log.i(TAG, "USB 事件: " + act);
                boolean removed = Intent.ACTION_MEDIA_EJECT.equals(act)
                    || Intent.ACTION_MEDIA_UNMOUNTED.equals(act)
                    || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(act);
                if (removed) {
                    handler.post(new Runnable() {
                        public void run() { onUsbRemoved(); }
                    });
                } else {
                    handler.postDelayed(new Runnable() {
                        public void run() { onUsbMounted(); }
                    }, 1500);
                }
            }
        };
        registerReceiver(usbReceiver, f);
        handler.post(tick);
    }

    private void initPaints() {
        pBg.setColor(BG);
        pCard.setColor(CARD);
        pCardOn.setColor(CARD_ON);
        pGold.setColor(GOLD);
        pFocus.setStyle(Paint.Style.STROKE);
        pFocus.setStrokeWidth(5f);
        pFocus.setColor(GOLD);
        pBar.setColor(0xFF34415E);
        pBarOn.setColor(GOLD);
        pGlow.setColor(GOLD);

        pTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pTitle.setColor(INK);
        pTitle.setTextAlign(Paint.Align.LEFT);
        pSub.setColor(INK_SOFT);
        pSub.setTextAlign(Paint.Align.LEFT);
        pPrev.setTextAlign(Paint.Align.CENTER);
        pPrev.setColor(INK_SOFT);
        pCur.setTextAlign(Paint.Align.CENTER);
        pCur.setColor(INK);
        pCur.setTypeface(Typeface.DEFAULT_BOLD);
        pCur.setShadowLayer(6, 0, 3, 0xAA000000);
        pNext.setTextAlign(Paint.Align.CENTER);
        pNext.setColor(INK_SOFT);
        pHint.setTextAlign(Paint.Align.CENTER);
        pHint.setColor(INK_SOFT);
        pBreath.setTextAlign(Paint.Align.CENTER);
        pBreath.setTypeface(Typeface.DEFAULT_BOLD);
        pNote.setStyle(Paint.Style.FILL);
        pNote.setColor(NOTE_OFF);
        pIco.setStyle(Paint.Style.STROKE);
        pIco.setStrokeCap(Paint.Cap.ROUND);
        pIco.setStrokeJoin(Paint.Join.ROUND);
        pIco.setColor(INK);
        pNum.setTextAlign(Paint.Align.CENTER);
        pNum.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /* ---------------- 记忆 ---------------- */

    private void loadPrefs() {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        counts = getSharedPreferences(PREFS_COUNT, MODE_PRIVATE);
        lastSong = prefs.getString("last", "");
        Log.i(TAG, "读取偏好 上次=" + lastSong);
    }

    private void savePrefs() {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putString("last", lastSong);
        e.commit();
    }

    /** 记一次点歌：只写偏好，不重排当前会话列表，避免切歌顺序跳变。 */
    private void bumpPlay(Song s) {
        if (counts == null) return;
        s.plays = counts.getInt(s.name, 0) + 1;
        counts.edit().putInt(s.name, s.plays).commit();
        Log.i(TAG, "点歌计数 " + s.name + " = " + s.plays);
    }

    /** 唱完整首才结算：自然播到结尾 +1；切歌 / 重唱 / 中途跳到结尾都不算。 */
    private void settleCount(Song s) {
        if (pendingCount == null || !pendingCount.name.equals(s.name)) return;
        Song done = pendingCount;
        pendingCount = null;
        if (countVetoed) {
            countVetoed = false;
            Log.i(TAG, "不算点歌（没完整唱完）: " + done.name);
            return;
        }
        bumpPlay(done);
        if (view != null) view.invalidate();
    }

    /** 开机定位到上次那首并直接唱；找不到就回第 1 首。 */
    private void restoreLast() {
        int idx = 0;
        if (lastSong.length() > 0) {
            for (int i = 0; i < songs.size(); i++) {
                if (songs.get(i).name.equals(lastSong)) { idx = i; break; }
            }
        }
        cur = idx;
        listCursor = idx;
        syncLibTop();
        if (!songs.isEmpty()) {
            Log.i(TAG, "接着上次唱: " + songs.get(idx).name + " (第 " + (idx + 1) + " 首)");
            play(idx, 0, true, false);   // 开机恢复不算一次点歌
        }
    }

    /* ---------------- 麦克风自检（只读 logcat） ---------------- */

    private void probeMic() {
        new Thread(new Runnable() {
            public void run() { doProbeMic(); }
        }, "mic-probe").start();
    }

    private void doProbeMic() {
        AudioRecord ar = null;
        try {
            int min = AudioRecord.getMinBufferSize(8000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 8000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, 4096));
            ar.startRecording();
            short[] buf = new short[800];
            int n = ar.read(buf, 0, buf.length);
            int peak = 0;
            for (int i = 0; i < n; i++) if (Math.abs(buf[i]) > peak) peak = Math.abs(buf[i]);
            Log.i(TAG, "MIC 采样 n=" + n + " peak=" + peak
                + " => " + (n > 0 && peak == 0 ? "通道存在但恒为静音" : "通道有数据"));
        } catch (Throwable t) {
            Log.i(TAG, "MIC 不可用: " + t);
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception e) { }
                try { ar.release(); } catch (Exception e) { }
            }
        }
    }

    /* ---------------- 曲库扫描（三文件规则 + 点歌次数双排序） ---------------- */

    private String canon(File f) {
        try { return f.getCanonicalPath(); } catch (Exception e) { return f.getAbsolutePath(); }
    }

    private void collect(File dir, List<File> out, int depth) {
        if (depth > 5) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isHidden()) continue;
            if (f.isDirectory()) collect(f, out, depth + 1);
            else {
                // 只认 .mp3：曲库规则就是「歌名.mp3 / 歌名.lrc / 歌名.伴奏.mp3」
                if (f.getName().toLowerCase().endsWith(".mp3")) out.add(f);
            }
        }
    }

    private void addMountRoots(ArrayList<File> roots, HashSet<String> seen) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                String mnt = p[1], fs = p[2].toLowerCase();
                if (!(fs.contains("fat") || fs.contains("ntfs") || fs.contains("fuse"))) continue;
                if (!(mnt.startsWith("/mnt/") || mnt.startsWith("/storage/"))) continue;
                File f = new File(mnt);
                if (f.isDirectory() && seen.add(canon(f))) roots.add(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "读挂载表失败: " + e);
        } finally {
            if (br != null) { try { br.close(); } catch (Exception e) { } }
        }
    }

    private String stemOfName(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    /** 伴奏只认同目录下的「歌名.伴奏.mp3」，大小写不敏感。 */
    private File findInst(File main) {
        String stem = stemOfName(main.getName());
        File dir = main.getParentFile();
        if (dir == null) return null;
        File exact = new File(dir, stem + INST_SUFFIX + ".mp3");
        if (exact.isFile()) return exact;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        String want = (stem + INST_SUFFIX + ".mp3").toLowerCase();
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase().equals(want)) return f;
        }
        return null;
    }

    /** 旧版分轨残留一律不进歌单。 */
    private boolean legacyStem(String stem) {
        String s = stem.toLowerCase();
        for (String suf : LEGACY_SUFFIXES) {
            if (s.endsWith(suf.toLowerCase())) return true;
        }
        return false;
    }

    private String audioPath(Song s) {
        return (useInst && s.instPath != null) ? s.instPath : s.path;
    }

    /** 台名先取 Manifest 里的应用名（构建期注入），取不到就用兜底常量。
        U 盘配置能覆盖它，见 loadConf()。 */
    private void resolveAppName() {
        try {
            CharSequence cs = getApplicationInfo().loadLabel(getPackageManager());
            if (cs != null && cs.toString().trim().length() > 0)
                appName = cs.toString().trim();
        } catch (Exception ignored) {
        }
    }

    /** 读 U 盘根目录的 karaoke.conf，返回全部键值（键名已转小写）。
        只在第一个找到的配置文件里读，读不到返回空表。 */
    private HashMap<String, String> readConf() {
        HashMap<String, String> kv = new HashMap<String, String>();
        for (String r : ROOTS) {
            File f = new File(r, "karaoke.conf");
            if (!f.isFile()) continue;
            BufferedReader br = null;
            try {
                br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"), 512);
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.length() > 0 && line.charAt(0) == '\uFEFF')
                        line = line.substring(1);          // 记事本 UTF-8 BOM
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim().toLowerCase();
                    String v = line.substring(eq + 1).trim();
                    if (v.length() > 1 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
                        v = v.substring(1, v.length() - 1).trim();   // 容忍手滑加的引号
                    if (k.length() > 0 && v.length() > 0) kv.put(k, v);
                }
            } catch (Exception ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
            }
            return kv;
        }
        return kv;
    }

    /** 应用配置：台名 + 欢迎语。两者都缺就是「欢迎来到 <默认台名>，」。 */
    private void loadConf() {
        HashMap<String, String> kv = readConf();
        String name = kv.get("app_name");
        if (name != null) appName = name;
        String welcome = kv.get("welcome");
        welcomeText = welcome != null ? welcome : ("欢迎来到 " + appName + "，");
        Log.i(TAG, "配置: 台名=" + appName + "，欢迎语=" + welcomeText);
    }

    /** 后台扫曲库：文件遍历与 LRC 解析都放到工作线程，扫完回主线程一次性替换列表。
        扫描期间界面照常重绘（启动页 / 旧画面），不会卡住两秒。
        返回后直接替换的是整个 songs 引用，UI 线程读到的要么是旧列表要么是新列表。 */
    private void rescan() {
        if (scanning) return;                    // 上一轮还没扫完就别再起线程
        scanning = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final ArrayList<Song> fresh = scanSongs();
                    handler.post(new Runnable() {
                        public void run() { applySongs(fresh); }
                    });
                } finally {
                    scanning = false;
                }
            }
        }, "rescan").start();
    }

    /** 只做 IO 与解析，不碰 UI 状态（cur / listCursor / songs 一律留给 applySongs）。 */
    private ArrayList<Song> scanSongs() {
        loadConf();
        HashSet<String> seen = new HashSet<String>();
        ArrayList<File> roots = new ArrayList<File>();
        for (String r : ROOTS) {
            File f = new File(r);
            if (f.isDirectory() && seen.add(canon(f))) roots.add(f);
        }
        addMountRoots(roots, seen);
        ArrayList<File> files = new ArrayList<File>();
        for (File d : roots) collect(d, files, 0);
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        ArrayList<Song> fresh = new ArrayList<Song>();
        String foundDir = "";
        int withInst = 0;
        for (File f : files) {
            String stem = stemOfName(f.getName());
            if (stem.toLowerCase().endsWith(INST_SUFFIX.toLowerCase())) continue; // 伴奏不算主歌
            if (legacyStem(stem)) continue;                                       // 旧分轨不算主歌
            if (foundDir.length() == 0 && f.getParentFile() != null)
                foundDir = f.getParentFile().getAbsolutePath();
            Song s = new Song();
            s.path = f.getAbsolutePath();
            s.name = stem;
            File inst = findInst(f);
            s.instPath = inst == null ? null : inst.getAbsolutePath();
            if (s.instPath != null) withInst++;
            s.plays = counts == null ? 0 : counts.getInt(s.name, 0);
            File lrc = findLrc(f);
            if (lrc != null) {
                s.lines = loadLrc(lrc);
                s.hasLrc = !s.lines.isEmpty();
            }
            fresh.add(s);
        }
        scannedWith = withInst;
        scannedHint = foundDir.length() > 0 ? ("曲库: " + foundDir)
                   : ("已扫描 " + roots.size() + " 个位置，未找到歌曲");
        return fresh;
    }

    /** 主线程应用扫描结果：排序、替换、修正游标；首扫完成才关掉启动页并接着上次唱。 */
    private void applySongs(ArrayList<Song> fresh) {
        Collections.sort(fresh, SONG_ORDER);   // 点歌次数降序 -> 文件名升序
        songs = fresh;
        if (cur >= songs.size()) cur = 0;
        if (listCursor >= songs.size()) listCursor = 0;
        syncLibTop();
        rootHint = scannedHint;
        int anchors = 0;
        for (Song s : songs) for (Line l : s.lines) if (l.endMs > 0) anchors++;
        Log.i(TAG, "扫描到 " + songs.size() + " 首歌（" + scannedWith
                + " 首带伴奏），空行锚点 " + anchors + " 处，已按点歌次数排序；" + rootHint);
        if (booting) {
            if (songs.isEmpty()) {
                /* 一首都没扫到，多半是 U 盘没插或还没挂载：留在启动页提示插盘，
                   2 秒后自动重扫，插好就自己进主界面，不用按任何键。 */
                if (!waitingUsb) Log.i(TAG, "未找到歌曲，停在启动页等待 U 盘");
                waitingUsb = true;
                handler.postDelayed(retryScan, USB_WAIT_RETRY_MS);
            } else {
                booting = false;                // 先关启动页，再让它 invalidate
                waitingUsb = false;
                restoreLast();
            }
        }
        if (view != null) view.invalidate();
    }

    private File findLrc(File audio) {
        String stem = audio.getName().replaceFirst("\\.[^.]+$", "");
        File dir = audio.getParentFile();
        if (dir == null) return null;
        File exact = new File(dir, stem + ".lrc");
        if (exact.isFile()) return exact;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName();
            if (n.equalsIgnoreCase(stem + ".lrc") || n.equalsIgnoreCase(stem + ".LRC")) return f;
        }
        return null;
    }

    private byte[] readAll(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private String decode(byte[] raw) {
        try {
            String s = new String(raw, "UTF-8");
            if (s.indexOf('\uFFFD') < 0) {
                if (s.length() > 0 && s.charAt(0) == '\uFEFF') return s.substring(1);
                return s;
            }
        } catch (Exception e) { }
        try { return new String(raw, "GBK"); } catch (Exception e) { return new String(raw); }
    }

    private ArrayList<Line> loadLrc(File f) {
        ArrayList<Line> lines = new ArrayList<Line>();
        String txt = decode(readAll(f));
        long offset = 0;
        Matcher om = LRC_OFFSET.matcher(txt);
        if (om.find()) {
            try { offset = -Long.parseLong(om.group(1)); } catch (Exception e) { }
        }
        String[] rows = txt.split("\\r?\\n");
        for (String row : rows) {
            Matcher m = LRC_TAG.matcher(row);
            ArrayList<Long> times = new ArrayList<Long>();
            int end = 0;
            while (m.find()) {
                long ms = Long.parseLong(m.group(1)) * 60000L
                        + Long.parseLong(m.group(2)) * 1000L;
                String frac = m.group(3);
                if (frac != null) {
                    if (frac.length() == 1) ms += Long.parseLong(frac) * 100L;
                    else if (frac.length() == 2) ms += Long.parseLong(frac) * 10L;
                    else ms += Long.parseLong(frac);
                }
                times.add(ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = row.substring(end).replaceAll("\\[[^\\]]*\\]", "").trim();
            if (text.length() == 0) {
                /* 纯空行不是歌词，但它是上一句的「唱完」锚点：
                   记成上一行的 endMs，扫字到这里就扫满，不再一路拖到下一句行首。
                   连续多个空行只认第一个；文件开头的空行没有上一行，忽略。 */
                if (!lines.isEmpty()) {
                    Line prev = lines.get(lines.size() - 1);
                    if (prev.endMs <= 0) prev.endMs = Math.max(0L, times.get(0) + offset);
                }
                continue;
            }
            for (Long t : times) lines.add(new Line(Math.max(0L, t + offset), text));
        }
        Collections.sort(lines, new Comparator<Line>() {
            public int compare(Line a, Line b) { return a.ms < b.ms ? -1 : (a.ms == b.ms ? 0 : 1); }
        });
        /* 头部的「歌手 - 歌名 / 作词 / 作曲」不是歌词；三行上滚时会当成上一句
           露出来，所以只清洗最前面几行，遇到第一句真歌词就停。 */
        int cut = 0;
        while (cut < lines.size() && cut < 8) {
            Line l = lines.get(cut);
            boolean meta = LRC_META.matcher(l.text).find();
            boolean title = l.ms < 1000 && (l.text.indexOf('-') >= 0
                || l.text.indexOf('－') >= 0 || l.text.indexOf('—') >= 0);
            if (meta || title) cut++;
            else break;
        }
        if (cut > 0) lines = new ArrayList<Line>(lines.subList(cut, lines.size()));
        return lines;
    }

    /* ---------------- 歌单 / 动作 ---------------- */

    private String songName(int idx) {
        return (idx >= 0 && idx < songs.size()) ? songs.get(idx).name : "—";
    }

    /** 没有队列：歌单顺序里，当前首的下一首就是预告。 */
    private int peekNext() {
        return songs.isEmpty() ? 0 : (cur + 1) % songs.size();
    }

    private void syncLibTop() {
        int rows = 6;
        if (listCursor < libTop) libTop = listCursor;
        if (listCursor >= libTop + rows) libTop = listCursor - rows + 1;
        int maxTop = Math.max(0, songs.size() - rows);
        if (libTop > maxTop) libTop = maxTop;
    }

    private void showBanner(String s) {
        bannerText = s;
        bannerUntil = System.currentTimeMillis() + 2200;
        if (view != null) view.invalidate();
    }

    private void setActive(int n) {
        activeBtn = n;
        focusSwitchAt = System.currentTimeMillis();
    }

    /** 数字键直达 / 鼠标点按钮：全部动作都印在同一屏上，不换页、不记层级。 */
    private void act(int n) {
        switch (n) {
            case 1: setActive(0); switchTrack(); break;
            case 2: setActive(1); replaySong(); break;
            case 3: setActive(2); toggleList(); break;
            case 0: setActive(3); skip(); break;
        }
    }

    private void toggleList() {
        if (songs.isEmpty()) { rescan(); return; }
        showList = !showList;
        if (showList) {
            listCursor = cur;
            syncLibTop();
        }
        if (view != null) view.invalidate();
    }

    /** 唱完自动切：先给 2 秒大字过场，再按升序播下一首。 */
    private void advance(boolean withInterlude) {
        if (songs.isEmpty() || usbGone) return;
        final int n = (cur + 1) % songs.size();
        if (!withInterlude) { play(n); return; }
        interludeName = songName(n);
        interludeUntil = System.currentTimeMillis() + 2000;
        pendingIdx = n;
        showList = false;
        stopPlayer();
        playing = false;
        Log.i(TAG, "过场 -> " + interludeName);
        handler.postDelayed(new Runnable() {
            public void run() {
                interludeUntil = 0;
                if (usbGone) return;
                play(pendingIdx);
            }
        }, 2000);
    }

    private void startInterlude() { advance(true); }

    private void skip() {
        if (songs.isEmpty() || usbGone) return;
        showBanner("已切歌 → " + songName(peekNext()));
        advance(false);
    }

    /** 重唱＝整首从头，不动歌单位置。 */
    private void replaySong() {
        if (songs.isEmpty()) return;
        play(cur);
        showBanner("从头再唱一遍");
        Log.i(TAG, "重唱整首 " + songName(cur));
    }

    private void onUsbRemoved() {
        if (usbGone) return;
        usbGone = true;
        stopPlayer();
        playing = false;
        showList = false;
        interludeUntil = 0;
        pendingCount = null;          // 没唱完就拔盘，不结算
        countVetoed = false;
        err = "";
        Log.i(TAG, "U 盘拔出：停止播放");
        if (view != null) view.invalidate();
    }

    private void onUsbMounted() {
        boolean was = usbGone;
        usbGone = false;
        rescan();
        if (was && !songs.isEmpty()) {
            restoreLast();
            showBanner("U 盘已插回，接着上次唱");
            Log.i(TAG, "U 盘插回：已自动恢复播放");
        }
    }

    /* ---------------- 播放控制 ---------------- */

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception e) { }
            try { player.release(); } catch (Exception e) { }
            player = null;
        }
        playing = false;
    }

    private MediaPlayer createPlayer(String path, final String tag) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setDataSource(path);
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer m, int what, int extra) {
                err = "播放失败 " + tag + " (" + what + "/" + extra + ")";
                stopPlayer();
                if (view != null) view.invalidate();
                return true;
            }
        });
        mp.prepare();
        return mp;
    }

    /** 从头唱一首：算一次点歌。 */
    private void play(final int idx) { play(idx, 0, true, true); }

    /** 切音轨 / seek：保持位置，不算点歌。 */
    private void play(final int idx, int startAt, boolean autoStart) {
        play(idx, startAt, autoStart, false);
    }

    private void play(final int idx, int startAt, boolean autoStart, boolean countPlay) {
        if (songs.isEmpty() || usbGone) return;
        stopPlayer();
        seekFloor = 0;
        rollLastLine = Integer.MIN_VALUE;   // 换歌 / 重唱 / 换音轨：歌词直接就位，不滚
        rollStartAt = 0;
        cur = ((idx % songs.size()) + songs.size()) % songs.size();
        listCursor = cur;
        syncLibTop();
        final Song s = songs.get(cur);
        err = "";
        lastSong = s.name;
        if (countPlay) {
            /* 先挂待结算，等这首歌自然播到结尾再 +1；中途切走就不算。 */
            pendingCount = s;
            countVetoed = false;
        }
        savePrefs();
        showList = false;
        try {
            player = createPlayer(audioPath(s), "音源");
            // 单轨与双轨统一走「2 秒过场 -> 下一首」，别硬切
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    settleCount(s);              // 唱完整首，才算一次点歌
                    handler.post(nextSong);
                }
            });
            int target = startAt;
            if (target > 0) {
                int dur = player.getDuration();
                if (dur > 0 && target > dur - 500) target = Math.max(0, dur - 500);
            }
            if (autoStart) {
                player.start();
                playing = true;
            } else if (target > 0) {
                player.start();
                player.pause();
            }
            if (target > 0) {
                final MediaPlayer mp = player;
                final int to = target;
                seekFloor = to;
                seekFloorUntil = System.currentTimeMillis() + 800;
                mp.seekTo(to);
                handler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            if (mp != player) return;
                            mp.seekTo(to);
                            Log.i(TAG, "切音轨补 seek -> " + mp.getCurrentPosition() + "ms");
                        } catch (Exception e) { }
                    }
                }, 350);
            }
            Log.i(TAG, "播放 " + s.name + (useInst ? " [伴奏]" : " [原唱]")
                    + " 累计点歌 " + s.plays + " 次");
        } catch (Exception e) {
            err = "无法播放：" + e.getClass().getSimpleName();
            Log.e(TAG, err, e);
            stopPlayer();
        }
        if (view != null) view.invalidate();
    }

    private void toggle() {
        if (songs.isEmpty()) { rescan(); return; }
        if (player == null) { play(cur, 0, true, false); return; }
        if (playing) { player.pause(); playing = false; }
        else { player.start(); playing = true; }
        view.invalidate();
    }

    /** 按钮 1 / 快捷键 0：在「歌名.mp3」与「歌名.伴奏.mp3」之间切换，保持位置。 */
    private void switchTrack() {
        if (songs.isEmpty()) return;
        Song s = songs.get(cur);
        if (s.instPath == null) {
            showBanner("这首歌没有伴奏版");
            return;
        }
        int pos = position();
        boolean wasPlaying = playing;
        useInst = !useInst;
        play(cur, pos, wasPlaying);
        showBanner(useInst ? "现在是：伴奏" : "现在是：原唱");
        Log.i(TAG, "单轨切换 " + (useInst ? "伴奏版" : "原唱版") + "；保持位置 " + pos + "ms");
    }

    /** 按钮 1 的文字显示「现在是什么」，不是「按了会变什么」。 */
    private boolean accompanimentNow() {
        if (songs.isEmpty()) return false;
        return useInst && songs.get(cur).instPath != null;
    }

    private void seekTo(int ms) {
        play(cur, ms, playing);
        if (view != null) view.invalidate();
    }

    private void seek(int delta) {
        if (player == null) return;
        try {
            int dur = player.getDuration();
            int to = player.getCurrentPosition() + delta;
            if (to < 0) to = 0;
            if (dur > 0 && to > dur - 300) to = Math.max(0, dur - 300);
            if (dur > 0 && to >= dur * 4 / 5) countVetoed = true;   // 直接跳到尾声：不算完整唱完
            player.seekTo(to);
            seekFloor = to;
            seekFloorUntil = System.currentTimeMillis() + 800;
        } catch (Exception e) { }
        view.invalidate();
    }

    /** 调试用：跳到绝对位置。不重建播放器、不算点歌，沿用 seekFloor 防抖。 */
    private void seekAbsolute(int ms) {
        if (player == null) return;
        try {
            int dur = player.getDuration();
            if (dur <= 0) return;
            int to = ms < 0 ? 0 : (ms > dur - 300 ? Math.max(0, dur - 300) : ms);
            if (to >= dur * 4 / 5) countVetoed = true;   // 拖到尾声：不算完整唱完
            player.seekTo(to);
            seekFloor = to;
            seekFloorUntil = System.currentTimeMillis() + 800;
            Log.i(TAG, "进度条 seek -> " + mmss(to));
        } catch (Exception e) { }
        if (view != null) view.invalidate();
    }

    private int lineAt(Song s, long pos) {
        ArrayList<Line> ls = s.lines;
        int lo = 0, hi = ls.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (ls.get(mid).ms <= pos) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    private int position() {
        try {
            int p = player == null ? 0 : player.getCurrentPosition();
            if (p < seekFloor && System.currentTimeMillis() < seekFloorUntil) return seekFloor;
            return p;
        } catch (Exception e) { return 0; }
    }

    private int duration() {
        try { return player == null ? 0 : player.getDuration(); }
        catch (Exception e) { return 0; }
    }

    /* ---------------- 遥控器 ---------------- */

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        if (usbGone) {
            if (key == KeyEvent.KEYCODE_MENU) { onUsbMounted(); return true; }
            if (key == KeyEvent.KEYCODE_BACK) { finish(); return true; }
            return true;
        }
        if (showList) return onListKey(key);
        switch (key) {
            case KeyEvent.KEYCODE_1: act(1); return true;
            case KeyEvent.KEYCODE_2: act(2); return true;
            case KeyEvent.KEYCODE_3: act(3); return true;
            case KeyEvent.KEYCODE_0: act(0); return true;      // 切歌
            case KeyEvent.KEYCODE_M:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                setActive(0); switchTrack(); return true;      // 原唱/伴奏
            /* 主屏 ↑↓ 与媒体键仍一律不响应：会和「歌单里 ↑↓ 选歌」抢键。
               ← / → 改成双击快退 / 快进，单击什么都不做，保留防误触。 */
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (e.getRepeatCount() == 0) {
                    long now = System.currentTimeMillis();
                    if (lastDirKey == key && now - lastDirAt <= DOUBLE_TAP_MS) {
                        int step = (key == KeyEvent.KEYCODE_DPAD_RIGHT)
                                 ? SEEK_STEP_MS : -SEEK_STEP_MS;
                        seek(step);
                        showBanner(step > 0 ? "快进 10 秒" : "快退 10 秒");
                        Log.i(TAG, "双击 " + (step > 0 ? "→ 快进" : "← 快退") + " 10 秒");
                        lastDirKey = -1;
                        lastDirAt = 0;
                    } else {
                        lastDirKey = key;
                        lastDirAt = now;
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                /* 空格 = 暂停 / 播放。长按会连发，只认第一次按下，
                   否则按住不放会让播放状态来回抖。 */
                if (key == KeyEvent.KEYCODE_SPACE && e.getRepeatCount() > 0) return true;
                toggle(); return true;
            case KeyEvent.KEYCODE_MENU:
                act(3); return true;
            case KeyEvent.KEYCODE_BACK:
                if (backArmed) { finish(); return true; }
                backArmed = true;
                handler.postDelayed(disarmBack, 2000);
                Toast.makeText(this, "再按一次返回键退出 K 歌", Toast.LENGTH_SHORT).show();
                return true;
            default:
                return super.onKeyDown(key, e);
        }
    }

    /** 歌单：↑↓ 选、OK 马上唱、3 / 菜单 / 返回 收起；0 直接切歌。列表默认落在当前首。 */
    private boolean onListKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:
                showList = false; view.invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: {
                int n = Math.max(1, songs.size());
                listCursor = (listCursor - 1 + n) % n;
                syncLibTop(); view.invalidate(); return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int n = Math.max(1, songs.size());
                listCursor = (listCursor + 1) % n;
                syncLibTop(); view.invalidate(); return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showList = false;
                play(listCursor);
                showBanner("马上唱：" + songName(listCursor));
                return true;
            // 歌单里按别的数字键也能直接用，不用先退出
            case KeyEvent.KEYCODE_1: showList = false; act(1); return true;
            case KeyEvent.KEYCODE_2: showList = false; act(2); return true;
            case KeyEvent.KEYCODE_0: showList = false; act(0); return true;
            default:
                return true;
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && playing) {
            player.pause();
            playing = false;
        }
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        stopPlayer();
        savePrefs();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(disarmBack);
        if (usbReceiver != null) { try { unregisterReceiver(usbReceiver); } catch (Exception e) { } }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    /* ---------------- 全屏绘制 ---------------- */

    private static String mmss(int ms) {
        int t = Math.max(0, ms) / 1000;
        return String.format("%02d:%02d", t / 60, t % 60);
    }

    private void fit(Paint p, String s, float maxW, float start) {
        float size = start;
        p.setTextSize(size);
        while (p.measureText(s) > maxW && size > 22) {
            size -= 2;
            p.setTextSize(size);
        }
    }

    /** 歌名这类「可截断」文字：超出可用宽度就按字符截掉尾部并补省略号，
        保证绝不压到跑马灯、状态标签等相邻区域。字号保持不变。 */
    private String ellipsize(Paint p, String s, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        String ell = "…";
        float room = maxW - p.measureText(ell);
        int n = s.length();
        while (n > 0 && p.measureText(s.substring(0, n)) > room) n--;
        return n > 0 ? s.substring(0, n) + ell : ell;
    }

    /** 歌词这类「不能截断、不能换行」的文字：一次按宽度算准字号，
        不设 22px 硬下限，再长的整句也能完整放进屏幕（代价是字变小）。 */
    private void fitExact(Paint p, String s, float maxW, float start) {
        p.setTextSize(start);
        float w = p.measureText(s);
        if (w > maxW && w > 0f)
            p.setTextSize(Math.max(10f, start * maxW / w * 0.99f));
    }

    private boolean bannerActive() {
        return bannerText.length() > 0 && System.currentTimeMillis() <= bannerUntil;
    }

    /** 统一圆角块：大卡片保留常规圆角；细进度条自动收敛为半圆端（胶囊）。
        老 WebKit/Skia 在 rx、ry 大于半高时不会裁切，会把圆角画成尖锐楔形，
        所以这里按自身宽高封顶，短条（刚开唱）也不会露尖。 */
    private static void card(Canvas c, Paint p, float x, float y, float w, float h, int W) {
        float r = Math.min(W * 0.0094f, Math.min(w, h) * 0.5f);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), r, r, p);
    }

    /** 手绘矢量图标 + 汉字，不用 emoji（API 15 字库不稳、颜色不可控）。 */
    private void icon(Canvas c, int kind, float cx, float cy, float s, int W) {
        float sw = Math.max(3f, W * 0.0032f);
        pIco.setStyle(Paint.Style.STROKE);
        pIco.setStrokeWidth(sw);
        if (kind == ICON_VOICE) {
            // 麦克风：胶囊 + U 形支架 + 底座；伴奏态加一道斜杠
            c.drawRoundRect(new RectF(cx - s * 0.34f, cy - s * 0.92f,
                                      cx + s * 0.34f, cy + s * 0.12f),
                            s * 0.34f, s * 0.34f, pIco);
            Path u = new Path();
            u.moveTo(cx - s * 0.68f, cy - s * 0.10f);
            u.quadTo(cx - s * 0.68f, cy + s * 0.52f, cx, cy + s * 0.52f);
            u.quadTo(cx + s * 0.68f, cy + s * 0.52f, cx + s * 0.68f, cy - s * 0.10f);
            c.drawPath(u, pIco);
            c.drawLine(cx, cy + s * 0.52f, cx, cy + s * 0.86f, pIco);
            c.drawLine(cx - s * 0.38f, cy + s * 0.86f, cx + s * 0.38f, cy + s * 0.86f, pIco);
            if (accompanimentNow())
                c.drawLine(cx - s * 0.92f, cy - s * 0.86f, cx + s * 0.92f, cy + s * 0.86f, pIco);
        } else if (kind == ICON_REPLAY) {
            // 回转箭头：一段圆弧 + 端点 V 形箭头，全程圆头描边。
            // 旧版用 FILL 实心三角压在细线圆弧上，尖角与线宽不一致，接缝处显毛刺。
            float r = s * 0.72f, deg0 = 35f, sweep = 290f;
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r),
                      deg0, sweep, false, pIco);
            double a = Math.toRadians(deg0 + sweep);       // 圆弧终点（右上）
            float ex = cx + (float) Math.cos(a) * r, ey = cy + (float) Math.sin(a) * r;
            float tx = -(float) Math.sin(a), ty = (float) Math.cos(a);  // 切线
            float nx = -ty, ny = tx;                                    // 法线
            float L = s * 0.44f;
            float tipX = ex + tx * L * 0.45f, tipY = ey + ty * L * 0.45f;
            float bx = ex - tx * L * 0.55f, by = ey - ty * L * 0.55f;
            c.drawLine(tipX, tipY, bx + nx * L * 0.52f, by + ny * L * 0.52f, pIco);
            c.drawLine(tipX, tipY, bx - nx * L * 0.52f, by - ny * L * 0.52f, pIco);
        } else if (kind == ICON_SKIP) {
            Path t1 = new Path();
            t1.moveTo(cx - s * 0.82f, cy - s * 0.62f);
            t1.lineTo(cx - s * 0.82f, cy + s * 0.62f);
            t1.lineTo(cx - s * 0.02f, cy);
            t1.close();
            Path t2 = new Path();
            t2.moveTo(cx + s * 0.02f, cy - s * 0.62f);
            t2.lineTo(cx + s * 0.02f, cy + s * 0.62f);
            t2.lineTo(cx + s * 0.62f, cy);
            t2.close();
            pIco.setStyle(Paint.Style.FILL);
            c.drawPath(t1, pIco);
            c.drawPath(t2, pIco);
            pIco.setStyle(Paint.Style.STROKE);
            c.drawLine(cx + s * 0.78f, cy - s * 0.62f, cx + s * 0.78f, cy + s * 0.62f, pIco);
        } else if (kind == ICON_LIST) {
            for (int i = 0; i < 3; i++) {
                float y = cy - s * 0.50f + i * s * 0.50f;
                pIco.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - s * 0.62f, y, sw * 0.9f, pIco);
                pIco.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - s * 0.28f, y, cx + s * 0.80f, y, pIco);
            }
        }
        pIco.setStyle(Paint.Style.STROKE);
    }

    class KView extends View {
        KView(Context c) { super(c); }

        /* ---------- 调试用：鼠标点/拖全曲进度条快进快退 ---------- */
        private boolean barHit(float x, float y) {
            int W = getWidth(), H = getHeight();
            float pad = W * 0.045f;
            float by = H * 0.775f, bh = Math.max(10, H * 0.011f);
            return x >= pad - W * 0.02f && x <= W - pad + W * 0.02f
                && y >= by - H * 0.045f && y <= by + bh + H * 0.015f;
        }

        private int barTargetMs(float x) {
            int W = getWidth();
            float pad = W * 0.045f, bw = W - 2 * pad;
            int dur = duration();
            if (dur <= 0 || bw <= 0) return -1;
            float r = (x - pad) / bw;
            if (r < 0f) r = 0f;
            else if (r > 1f) r = 1f;
            return (int) (r * dur);
        }

        /** 鼠标落在四个大按钮的哪一个上；缝隙与按钮区外返回 -1。
            几何必须与 drawButtonBar 完全一致。 */
        private int btnAt(float x, float y) {
            int W = getWidth(), H = getHeight();
            float pad = W * 0.045f, gap = W * 0.012f;
            float bw = (W - 2 * pad - 3 * gap) / 4f;
            float bh = H * 0.145f, y0 = H * 0.805f;
            if (y < y0 || y > y0 + bh) return -1;
            for (int k = 0; k < 4; k++) {
                float left = pad + k * (bw + gap);
                if (x >= left && x <= left + bw) return k;
            }
            return -1;
        }

        /** 歌单六行列表区（几何与 drawLibList 一致）。 */
        private boolean listHit(float x, float y) {
            int W = getWidth(), H = getHeight();
            float pad = W * 0.045f, y0 = H * 0.215f, rh = H * 0.088f;
            return x >= pad && x <= W - pad && y >= y0 && y <= y0 + 6 * rh;
        }

        /** 屏幕 y 落在列表第几行对应的歌曲索引；行外 / 超出曲库返回 -1。 */
        private int listRowAt(float y) {
            int H = getHeight();
            float y0 = H * 0.215f, rh = H * 0.088f;
            int k = (int) ((y - y0) / rh);
            if (k < 0 || k >= 6) return -1;
            int idx = libTop + k;
            return idx < songs.size() ? idx : -1;
        }

        /** 鼠标滚轮走 generic motion（不是 onTouchEvent）：一格一行，滚到顶 / 底停住。 */
        @Override
        public boolean onGenericMotionEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_SCROLL && showList && !songs.isEmpty()
                    && listHit(e.getX(), e.getY())) {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v == 0f) return true;
                int n = songs.size();
                int to = listCursor + (v > 0f ? -1 : 1);
                listCursor = to < 0 ? 0 : (to >= n ? n - 1 : to);
                syncLibTop();
                Log.i(TAG, "歌单滚轮 -> 第 " + (listCursor + 1) + " / " + n + " 首");
                invalidate();
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getAction() & MotionEvent.ACTION_MASK;
            if (a == MotionEvent.ACTION_DOWN) {
                pressedBtn = -1;
                listTouched = false;
                pressedRow = -1;
                if (showList && !songs.isEmpty() && listHit(e.getX(), e.getY())) {
                    listTouched = true;
                    listDownY = e.getY();
                    listDownCursor = listCursor;
                    pressedRow = listRowAt(e.getY());
                    return true;
                }
                if (!songs.isEmpty() && !usbGone && duration() > 0
                        && System.currentTimeMillis() >= interludeUntil
                        && barHit(e.getX(), e.getY())) {
                    dragMs = barTargetMs(e.getX());
                    if (dragMs >= 0) { invalidate(); return true; }
                }
                pressedBtn = btnAt(e.getX(), e.getY());
                return pressedBtn >= 0;
            }
            if (listTouched) {
                float rh = getHeight() * 0.088f;
                if (a == MotionEvent.ACTION_MOVE) {
                    int delta = (int) ((listDownY - e.getY()) / rh);
                    int n = songs.size();
                    int to = listDownCursor + delta;
                    int nc = to < 0 ? 0 : (to >= n ? n - 1 : to);
                    if (nc != listCursor) {
                        listCursor = nc;
                        syncLibTop();
                        Log.i(TAG, "歌单拖动 -> 第 " + (listCursor + 1) + " / " + n + " 首");
                        invalidate();
                    }
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    listTouched = false;
                    /* 位移不到 0.4 行才算点击；拖动滚动不会误点歌。 */
                    if (a == MotionEvent.ACTION_UP && pressedRow >= 0
                            && Math.abs(e.getY() - listDownY) < rh * 0.4f) {
                        Log.i(TAG, "歌单点击 -> 第 " + (pressedRow + 1) + " 首 "
                                + songName(pressedRow));
                        play(pressedRow);
                        showBanner("马上唱：" + songName(pressedRow));
                    }
                    pressedRow = -1;
                    invalidate();
                }
                return true;
            }
            if (dragMs >= 0) {
                if (a == MotionEvent.ACTION_MOVE) {
                    /* 拖动中只更新金色条与顶栏目标时间，不真正 seek：
                       连续 seekTo 会让 MediaPlayer 卡顿，松手时只跳一次。 */
                    int t = barTargetMs(e.getX());
                    if (t >= 0) dragMs = t;
                    invalidate();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    int to = dragMs;
                    dragMs = -1;
                    if (a == MotionEvent.ACTION_UP) seekAbsolute(to);
                    else invalidate();
                }
                return true;
            }
            if (pressedBtn >= 0) {
                if (a == MotionEvent.ACTION_UP) {
                    int b = pressedBtn;
                    pressedBtn = -1;
                    /* 松手仍在同一个按钮里才算点击，拖出去可取消。 */
                    if (btnAt(e.getX(), e.getY()) == b) {
                        Log.i(TAG, "鼠标点击按钮 " + BTN_LABELS[b]);
                        act(BTN_ACTS[b]);
                    }
                } else if (a == MotionEvent.ACTION_CANCEL) {
                    pressedBtn = -1;
                }
                return true;
            }
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            if (W <= 0 || H <= 0) return;
            float pad = W * 0.045f;

            if (booting) { drawSplash(c, W, H); return; }

            c.drawRect(0, 0, W, H, pBg);

            if (usbGone) { drawUsbGone(c, W, H); return; }
            if (System.currentTimeMillis() < interludeUntil) { drawInterlude(c, W, H); return; }

            drawTopBar(c, W, H, pad);
            drawMarquee(c, W, H);

            if (showList) drawLibList(c, W, H, pad);
            else drawLyricsArea(c, W, H, pad);

            int dur = duration();
            float bw = W - 2 * pad, by = H * 0.775f, bh = Math.max(10, H * 0.011f);
            card(c, pBar, pad, by, bw, bh, W);
            int shown = dragMs >= 0 ? dragMs : position();
            if (dur > 0) {
                float frac = shown / (float) dur;
                if (frac < 0f) frac = 0f;
                if (frac > 1f) frac = 1f;
                card(c, pBarOn, pad, by, bw * frac, bh, W);
                drawGlowDot(c, pad + bw * frac, by + bh * 0.5f, bh, dragMs >= 0);
            }

            drawButtonBar(c, W, H, pad);
            drawBanner(c, W, H);
        }

        /** 进度条末端发光亮点：多层递减透明圆叠出柔光 + 亮核，纯 drawCircle，
            不依赖 BlurMaskFilter / Shader，老 Skia 与硬件加速下都稳；带轻微呼吸。 */
        private void drawGlowDot(Canvas c, float cx, float cy, float bh, boolean dragging) {
            float pulse = 0.94f + 0.06f * (float) Math.sin(System.currentTimeMillis() / 480.0);
            float k = (dragging ? 1.18f : 1f) * pulse;
            for (int n = 6; n >= 1; n--) {
                float r = bh * (0.45f + n * 0.31f) * k;
                int a = (int) ((dragging ? 20 : 14) * (7 - n) * pulse);
                pGlow.setColor(Color.argb(a, 255, 198, 76));
                c.drawCircle(cx, cy, r, pGlow);
            }
            pGlow.setColor(0xFFFFE9A8);
            c.drawCircle(cx, cy, bh * 0.60f * k, pGlow);
            pGlow.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, bh * 0.24f * k, pGlow);
        }

        /* ---------- 顶栏：歌名 / 进度 / 状态 ---------- */

        private void drawTopBar(Canvas c, int W, int H, float pad) {
            String title = songs.isEmpty() ? (appName + " · 等待曲库") : songs.get(cur).name;
            float rightX = W - pad;

            pTitle.setTextSize(W * FS_NAME);
            // 固定字号；超长截尾加省略号。跑马灯已整体移到右半屏（0.50W 起），
            // 歌名可用宽从 0.30W 放宽到 0.44W，长歌名不再只剩两三个字。
            c.drawText(ellipsize(pTitle, title, W * 0.44f), pad, H * 0.098f, pTitle);

            pSub.setTextSize(W * FS_SUB);
            String meta = (songs.isEmpty() ? "0 / 0" : ("第 " + (cur + 1) + " / " + songs.size() + " 首"))
                + "      " + mmss(dragMs >= 0 ? dragMs : position()) + " / "
                + (duration() > 0 ? mmss(duration()) : "--:--");
            c.drawText(meta, pad, H * 0.158f, pSub);

            /* 第二行右侧：操作提示靠左、播放状态贴最右。
               状态原本在第一行最右，跑马灯占掉那半屏后只能挪到这一行。
               顺序先画最右的状态（量出宽度），再把它左边的提示右对齐到剩余空间。 */
            String flags = playing ? "▶ 播放中" : "‖ 已暂停";
            pSub.setTextAlign(Paint.Align.RIGHT);
            pSub.setColor(playing ? GOLD : INK_DIM);
            float flagW = pSub.measureText(flags);
            c.drawText(flags, rightX, H * 0.158f, pSub);

            pSub.setColor(err.length() > 0 ? BAD : INK_DIM);
            fit(pSub, hintText(), W * 0.42f, W * FS_SUB);
            c.drawText(hintText(), rightX - flagW - W * 0.028f, H * 0.158f, pSub);
            pSub.setColor(INK_SOFT);
            pSub.setTextAlign(Paint.Align.LEFT);
        }

        /* ---------- 顶部中间：欢迎语 + 下一首，右→左无缝循环 ---------- */

        private void drawMarquee(Canvas c, int W, int H) {
            String tail = songs.isEmpty() ? "" : ("下一首：" + songName(peekNext()));
            /* 靠右、占半屏：左半屏整块让给歌名（0.045W~0.485W），
               右端与顶栏右边界齐平（W - 0.045W）。 */
            float lx = W * 0.500f, rx = W * 0.955f, base = H * 0.098f;
            pSub.setTextAlign(Paint.Align.LEFT);
            pSub.setTextSize(W * 0.019f);
            float hw = pSub.measureText(welcomeText);
            float unit = hw + pSub.measureText(tail) + W * 0.060f;  // 文本 + 轮次间隔
            float speed = W * 0.00007f;                              // ≈90px/s @1280
            long period = Math.max(1L, (long) (unit / speed));
            float off = (System.currentTimeMillis() % period) * speed;
            c.save();
            c.clipRect(lx, H * 0.055f, rx, H * 0.118f);
            // 多画几份副本，保证窗口左端与右端同时有字，循环处不会突然空白
            for (int k = -2; k <= 2; k++) {
                float x = rx - off + k * unit;
                pSub.setColor(INK_DIM);
                c.drawText(welcomeText, x, base, pSub);
                if (tail.length() > 0) {
                    pSub.setColor(GOLD);
                    c.drawText(tail, x + hw, base, pSub);
                }
            }
            /* 窗口两端各 0.030W 渐隐：文字滚进来 / 滚出去都是淡入淡出，
               不在边界被硬裁成半个字——否则滚到左沿的「下一首：…」会紧贴
               歌名尾部，看着像歌名多长出了几个金色字。 */
            float fw = W * 0.030f, fy0 = H * 0.055f, fy1 = H * 0.118f;
            pFade.setShader(new LinearGradient(lx, fy0, lx + fw, fy0,
                    BG, BG & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(lx, fy0, lx + fw, fy1, pFade);
            pFade.setShader(new LinearGradient(rx - fw, fy0, rx, fy0,
                    BG & 0x00FFFFFF, BG, Shader.TileMode.CLAMP));
            c.drawRect(rx - fw, fy0, rx, fy1, pFade);
            pFade.setShader(null);
            c.restore();
            pSub.setColor(INK_SOFT);
            pSub.setTextSize(W * FS_SUB);
        }

        /* ---------- 四个等宽大按钮：图标 + 汉字 + 数字角标 ---------- */

        private void drawButtonBar(Canvas c, int W, int H, float pad) {
            float gap = W * 0.012f;
            float bw = (W - 2 * pad - 3 * gap) / 4f;
            float bh = H * 0.145f, y0 = H * 0.805f;
            boolean barFocus = !showList && !bannerActive();
            long elapsed = System.currentTimeMillis() - focusSwitchAt;
            float anim = elapsed < 120f ? ((float) elapsed / 120f) : 1f;
            if (anim < 0f) anim = 0f;
            if (anim > 1f) anim = 1f;

            for (int k = 0; k < 4; k++) {
                float x = pad + k * (bw + gap);
                boolean on = (barFocus && k == activeBtn) || k == pressedBtn;
                // 焦点底色 120ms 过渡：唯一保留的动效；用独立 Paint 避免串色
                Paint bg = pCard;
                if (on) {
                    pFocusBg.setARGB(
                        (int) (Color.alpha(CARD) + (Color.alpha(CARD_ON) - Color.alpha(CARD)) * anim),
                        (int) (Color.red(CARD) + (Color.red(CARD_ON) - Color.red(CARD)) * anim),
                        (int) (Color.green(CARD) + (Color.green(CARD_ON) - Color.green(CARD)) * anim),
                        (int) (Color.blue(CARD) + (Color.blue(CARD_ON) - Color.blue(CARD)) * anim));
                    bg = pFocusBg;
                }
                card(c, bg, x, y0, bw, bh, W);
                if (on) card(c, pFocus, x, y0, bw, bh, W);  // 全屏唯一的金黄焦点框

                // 数字角标（1/2/3/0 直达）
                pNum.setStyle(Paint.Style.FILL);
                pNum.setColor(on ? GOLD : INK_DIM);
                c.drawCircle(x + W * 0.020f, y0 + W * 0.020f, W * 0.013f, pNum);
                pNum.setColor(BG);
                pNum.setTextSize(W * 0.017f);
                c.drawText(BTN_KEYS[k], x + W * 0.020f,
                           y0 + W * 0.026f, pNum);

                // 图标
                pIco.setColor(on ? GOLD : INK);
                int kind = (k == 0) ? ICON_VOICE : (k == 1) ? ICON_REPLAY
                         : (k == 2) ? ICON_LIST : ICON_SKIP;
                icon(c, kind, x + bw / 2, y0 + bh * 0.30f, W * 0.016f, W);
                pIco.setColor(INK);

                // 汉字（按钮 1 显示当前状态：原唱 / 伴奏）
                String label = (k == 0) ? (accompanimentNow() ? "伴奏" : "原唱") : BTN_LABELS[k];
                pCur.setTextAlign(Paint.Align.CENTER);
                pCur.setTextSize(W * FS_BTN);
                pCur.setColor(on ? GOLD : INK);
                fit(pCur, label, bw * 0.90f, W * FS_BTN);
                c.drawText(label, x + bw / 2, y0 + bh * 0.86f, pCur);
                pCur.setColor(INK);
            }
        }

        /* ---------- 顶栏一行提示：只留最必要的三条 ---------- */

        private String hintText() {
            if (err.length() > 0) return err;
            if (showList) return "↑↓ 选歌    OK 马上唱    3 返回    0 切歌";
            return "OK 播放 / 暂停";
        }

        /* ---------- 内容区 A：歌词（逐字染色 / 前奏音符 / 上滚切行） ---------- */

        private void drawLyricsArea(Canvas c, int W, int H, float pad) {
            if (songs.isEmpty()) { drawEmpty(c, W, H); return; }
            Song s = songs.get(cur);
            if (!s.hasLrc) { drawNoLrc(c, W, H); return; }
            drawLyrics(c, W, H, s, position());
        }

        private long firstRealMs(Song s) {
            for (int k = 0; k < s.lines.size(); k++) {
                Line l = s.lines.get(k);
                if (l.ms < 1000) continue;
                if (LRC_META.matcher(l.text).find()) continue;
                return l.ms;
            }
            return 0;
        }

        private long nextLineMs(Song s, int i) {
            long start = (i >= 0 && i < s.lines.size()) ? s.lines.get(i).ms : 0;
            boolean hasNext = i >= 0 && i + 1 < s.lines.size();
            long next = hasNext ? s.lines.get(i + 1).ms : -1L;
            /* 有「唱完」锚点就按锚点收尾——这是歌词文件里真实的演唱结束时间，
               不做任何字数估算。夹两下只防呆：不越过下一句行首、至少留 800ms。 */
            if (i >= 0 && i < s.lines.size()) {
                long anchor = s.lines.get(i).endMs;
                if (anchor > 0) {
                    long end = anchor;
                    if (hasNext && next > 0 && end > next) end = next;
                    if (end < start + 800L) end = start + 800L;
                    return end;
                }
            }
            if (hasNext) return next;
            return Math.max(start + 1000L, (long) duration());
        }

        /* 扫字进度完全由 LRC 时间轴决定：本句行首 -> 下一行行首。
           不做字数估算、不做 8 秒封顶，节奏交给歌词文件本身。 */
        private float lineFrac(Song s, long pos, int i) {
            if (i < 0) return 0f;
            long start = s.lines.get(i).ms, end = nextLineMs(s, i);
            if (end <= start) return 1f;
            float f = (pos - start) / (float) (end - start);
            return f < 0f ? 0f : (f > 1f ? 1f : f);
        }

        /* 前奏：NOTES 个手绘音符按时间推进，走过的整颗金黄、正在走的局部染金。
           不显示「间奏 / 换气」：老人看不懂，且 LRC 只有整句时间轴，
           那两个词本来也只是按句间空档猜出来的，不如让歌词自己静止等着。 */

        /** 八分音符：符头 + 符干 + 符尾，纯矢量，不依赖系统字体里的 ♪ 字形。 */
        private void drawNote(Canvas c, float cx, float cy, float s, Paint p) {
            c.save();
            c.translate(cx, cy);
            p.setStyle(Paint.Style.FILL);
            c.save();
            c.rotate(-20f);
            c.drawOval(new RectF(-s * 0.42f, s * 0.14f, s * 0.16f, s * 0.46f), p);
            c.restore();
            c.drawRect(new RectF(s * 0.10f, -s * 0.60f,
                                 s * 0.10f + Math.max(3f, s * 0.055f), s * 0.30f), p);
            Path tail = new Path();
            tail.moveTo(s * 0.15f, -s * 0.60f);
            tail.quadTo(s * 0.54f, -s * 0.48f, s * 0.47f, -s * 0.10f);
            tail.quadTo(s * 0.37f, -s * 0.34f, s * 0.15f, -s * 0.40f);
            tail.close();
            c.drawPath(tail, p);
            c.restore();
        }

        /** 前奏音符条：固定画在歌词第一句上方；fade<1 时缩小、上浮、淡出。
            走过的整颗金黄、正在走的局部染金，与歌词逐字染色同一套裁剪。
            倒计时只留一个数字，紧接在末颗音符后面，整组一起居中、一起淡出。 */
        private void drawIntroNotes(Canvas c, int W, int H, long pos, long first,
                                    float fade, float cy) {
            if (fade <= 0f) return;
            float s = W * 0.0333f * (0.88f + 0.12f * fade);   // 较初版缩小 1/3
            float gap = W * 0.0173f;                           // 间距同比例缩小，不变散
            float notesW = NOTES * s + (NOTES - 1) * gap;
            float ts = W * 0.026f;
            long left = Math.max(0L, first - pos);       // 淡出那 450ms 不出现负数
            long n = left / 1000 + 1;
            String sec = (n < 10 ? "0" : "") + n;        // 固定两位，09 / 08 …
            pNext.setTextSize(ts);
            // 按两位宽度占位，数字变化时不带动音符条左右抖动
            float numGap = W * 0.018f, numW = pNext.measureText("00");
            float x0 = (W - (notesW + numGap + numW)) / 2f;
            float progress = first <= 0 ? 0f : pos / (float) first;
            if (progress < 0f) progress = 0f;
            if (progress > 1f) progress = 1f;
            int alpha = (int) (255 * fade);
            for (int k = 0; k < NOTES; k++) {
                float cx = x0 + k * (s + gap) + s * 0.45f;
                float frac = progress * NOTES - k;
                if (frac < 0f) frac = 0f;
                if (frac > 1f) frac = 1f;
                pNote.setColor(NOTE_OFF);
                pNote.setAlpha(alpha);
                drawNote(c, cx, cy, s, pNote);
                if (frac > 0f) {
                    c.save();
                    c.clipRect(cx - s * 0.50f, cy - s * 0.72f,
                               cx - s * 0.50f + s * 1.05f * frac, cy + s * 0.56f);
                    pNote.setColor(GOLD);
                    pNote.setAlpha(alpha);
                    drawNote(c, cx, cy, s, pNote);
                    c.restore();
                }
            }
            pNote.setAlpha(255);
            // 纯数字倒计时：不再有「还有 N 秒开唱」这类文字
            pNext.setTextAlign(Paint.Align.LEFT);
            pNext.setColor(INK_SOFT);
            pNext.setAlpha(alpha);
            c.drawText(sec, x0 + notesW + numGap, cy + ts * 0.36f, pNext);
            pNext.setAlpha(255);
            pNext.setTextAlign(Paint.Align.CENTER);
        }

        private int mixColor(int a, int b, float t) {
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
            return Color.argb(
                Color.alpha(a) + (int) ((Color.alpha(b) - Color.alpha(a)) * t),
                Color.red(a)   + (int) ((Color.red(b)   - Color.red(a))   * t),
                Color.green(a) + (int) ((Color.green(b) - Color.green(a)) * t),
                Color.blue(a)  + (int) ((Color.blue(b)  - Color.blue(a))  * t));
        }

        /** 已唱部分染成金黄，未唱留白；普通逐行 LRC 只能按句时长线性推进。 */
        private void drawHighlightText(Canvas c, String s, float cx, float y,
                                       float frac, Paint p, int W, int gold) {
            if (s.length() == 0) return;
            float tw = p.measureText(s), left = cx - tw / 2;
            float top = y - W * 0.06f, bottom = y + W * 0.025f;
            int old = p.getColor();
            c.save();
            c.clipRect(left, top, left + tw * frac, bottom);
            p.setColor(gold);
            c.drawText(s, cx, y, p);
            c.restore();
            c.save();
            c.clipRect(left + tw * frac, top, left + tw + 2, bottom);
            p.setColor(old);
            c.drawText(s, cx, y, p);
            c.restore();
            p.setColor(old);
        }

        /** 当前句唱完，三行整体上滚一格：前一句 / 当前句 / 下一句同步变换。
            只在「自然往后走一句」时滚；快进、快退、换歌都直接就位。 */
        private void drawLyrics(Canvas c, int W, int H, Song s, int pos) {
            if (s.lines.isEmpty()) return;
            long first = firstRealMs(s);
            boolean intro = first > 0 && pos < first;
            // 前奏时第一句是「预备态」灰字，开唱后随音符淡出同步点亮成主文字
            float lit = 1f;
            if (first > 0) {
                lit = intro ? 0f
                     : Math.min(1f, Math.max(0f, (pos - first) / (float) INTRO_FADE_MS));
            }

            int i = lineAt(s, pos);
            if (i < 0) i = 0;
            if (i != rollLastLine) {
                // 上一滚没结束又来新句（短句连唱）就直接就位，别叠加动画
                boolean busy = rollStartAt > 0
                    && System.currentTimeMillis() - rollStartAt < ROLL_MS;
                boolean roll = playing && !busy && rollLastLine >= 0 && i == rollLastLine + 1;
                rollStartAt = roll ? System.currentTimeMillis() : 0;
                rollLastLine = i;
            }
            float p = 1f;
            if (rollStartAt > 0) {
                long e = System.currentTimeMillis() - rollStartAt;
                if (e < ROLL_MS) {
                    float q = e / (float) ROLL_MS;
                    // 余弦 ease-in-out：慢起、中间匀、慢收，不再起步猛地一窜
                    p = (float) (0.5 - 0.5 * Math.cos(Math.PI * q));
                } else {
                    rollStartAt = 0;
                }
            }

            float cy = H * 0.46f, gap = H * 0.165f;
            float shift = (1f - p) * gap;           // p=0 时整列还在上一句的位置
            c.save();
            c.clipRect(0, H * 0.205f, W, H * 0.735f);
            pCur.setTextAlign(Paint.Align.CENTER);
            for (int d = -2; d <= 2; d++) {
                int k = i + d;
                if (k < 0 || k >= s.lines.size()) continue;
                float y = cy + d * gap + shift;
                float off = Math.abs(y - cy) / gap;
                // 滚出内容区上下边界时淡出，不留下被裁断的半行字
                float fade = Math.min((y - H * 0.205f) / (H * 0.085f),
                                      (H * 0.735f - y) / (H * 0.085f));
                if (fade <= 0f) continue;
                if (fade > 1f) fade = 1f;
                float near = (1f - Math.min(1f, off)) * fade;   // 1 = 正中
                String txt = s.lines.get(k).text;
                if (txt.length() == 0) continue;
                fitExact(pCur, txt, W * 0.88f,
                         W * (0.026f + (FS_LYRIC - 0.026f) * near));

                int base;
                if (k == i - 1) {
                    // 刚唱完的上一句：满金色，随上滚褪成灰字
                    base = mixColor(mixColor(INK_DIM, INK_SOFT, near), GOLD, 1f - p);
                } else if (k == i) {
                    base = mixColor(INK_SOFT, INK, near * lit);
                } else {
                    base = mixColor(INK_DIM, INK_SOFT, near);
                }
                pCur.setColor(mixColor(BG, base, fade));

                if (k == i) {
                    float frac = lineFrac(s, pos, i) * p;
                    drawHighlightText(c, txt, W / 2, y, frac, pCur, W,
                                      mixColor(BG, GOLD, fade));
                } else {
                    c.drawText(txt, W / 2, y, pCur);
                }
            }
            c.restore();

            /* 前奏音符钉在第一句上方（cy - gap）。开唱不再硬切：
               450ms 内淡出 + 上浮 + 略缩，第一句同时由灰转亮，完成交接。 */
            float fade = 0f;
            if (intro) fade = 1f;
            else if (first > 0 && pos >= first && pos - first < INTRO_FADE_MS)
                fade = 1f - (pos - first) / (float) INTRO_FADE_MS;
            if (fade > 0f) {
                float notesY = H * 0.46f - H * 0.165f - (1f - fade) * H * 0.035f;
                drawIntroNotes(c, W, H, pos, first, fade, notesY);
            }
        }

        /* ---------- 内容区 B：歌单（U 盘目录即已点歌单，三态） ---------- */

        private void drawLibList(Canvas c, int W, int H, float pad) {
            float x = pad, w = W - 2 * pad, y0 = H * 0.215f, rh = H * 0.088f;
            syncLibTop();
            pSub.setTextSize(W * FS_SUB);
            pSub.setTextAlign(Paint.Align.LEFT);
            c.drawText("全部 " + songs.size() + " 首  ·  按点歌次数排序",
                       x, H * 0.198f, pSub);
            int rest = songs.size() - libTop - 6;
            if (rest > 0) {
                pSub.setTextAlign(Paint.Align.RIGHT);
                c.drawText("下面还有 " + rest + " 首，继续按 ↓", x + w, H * 0.198f, pSub);
                pSub.setTextAlign(Paint.Align.LEFT);
            }

            pCur.setTextAlign(Paint.Align.LEFT);
            for (int k = 0; k < 6; k++) {
                int idx = libTop + k;
                if (idx >= songs.size()) break;
                float y = y0 + k * rh, h = rh * 0.86f;
                boolean on = idx == listCursor;
                boolean sung = idx < cur;
                boolean now = idx == cur;
                if (now) card(c, pCardOn, x, y, w, h, W);
                else card(c, pCard, x, y, w, h, W);
                if (on && !bannerActive()) card(c, pFocus, x, y, w, h, W);

                // 序号
                pSub.setTextAlign(Paint.Align.LEFT);
                pSub.setTextSize(W * FS_SUB);
                pSub.setColor(sung ? INK_DIM : (now ? GOLD : INK_SOFT));
                c.drawText(String.format("%02d", idx + 1), x + W * 0.018f, y + h * 0.66f, pSub);

                // 歌名
                pCur.setTextSize(W * FS_BTN);
                pCur.setColor(sung ? INK_DIM : (now ? GOLD : INK));
                String nm = songs.get(idx).name;
                fit(pCur, nm, w * 0.62f, W * FS_BTN);
                c.drawText(ellipsize(pCur, nm, w * 0.62f),
                           x + W * 0.075f, y + h * 0.66f, pCur);

                // 三态标签 + 点歌次数（能直接看到「记下来了」）
                String tag = now ? "▶ 正在唱" : (sung ? "已唱过" : "");
                int pc = songs.get(idx).plays;
                if (pc > 0) tag = tag.length() > 0 ? (tag + "  " + pc + " 次") : (pc + " 次");
                if (tag.length() > 0) {
                    pSub.setTextAlign(Paint.Align.RIGHT);
                    pSub.setTextSize(W * FS_SUB);
                    pSub.setColor(sung ? INK_DIM : GOLD);
                    fit(pSub, tag, w * 0.32f, W * FS_SUB);
                    c.drawText(tag, x + w - W * 0.018f, y + h * 0.66f, pSub);
                }
            }
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setColor(INK);
            pSub.setTextAlign(Paint.Align.LEFT);
        }

        /* ---------- 大字反馈 / 预告 / 过场 / 拔盘 / 空库 ---------- */

        private void drawBanner(Canvas c, int W, int H) {
            if (!bannerActive()) return;
            float bw = W * 0.60f, bh = H * 0.100f, x = (W - bw) / 2, y = H * 0.20f;
            card(c, pCardOn, x, y, bw, bh, W);
            card(c, pFocus, x, y, bw, bh, W);
            pBreath.setTextAlign(Paint.Align.CENTER);
            pBreath.setTextSize(W * 0.026f);
            pBreath.setColor(Color.WHITE);
            fit(pBreath, bannerText, bw * 0.92f, W * 0.026f);
            c.drawText(ellipsize(pBreath, bannerText, bw * 0.92f),
                       W / 2, y + bh * 0.66f, pBreath);
        }

        /** 居中画一行字：对齐 / 字号 / 颜色一次设好，不依赖上一处调用留下的 Paint 状态。 */
        private void centerAt(Canvas c, Paint p, String s, float x, float y,
                              float size, int color) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(size);
            p.setColor(color);
            c.drawText(s, x, y, p);
        }

        private void drawInterlude(Canvas c, int W, int H) {
            float cx = W / 2f;
            centerAt(c, pCur, "接  下  来", cx, H * 0.36f, W * 0.032f, INK_SOFT);
            pCur.setTextSize(W * 0.048f);
            pCur.setColor(GOLD);
            fit(pCur, interludeName, W * 0.86f, W * 0.048f);
            c.drawText(ellipsize(pCur, interludeName, W * 0.86f), cx, H * 0.53f, pCur);
            centerAt(c, pNext, "马上开始…", cx, H * 0.65f, W * 0.024f, INK_SOFT);
        }

        private void drawUsbGone(Canvas c, int W, int H) {
            float cx = W / 2f;
            card(c, pCard, W * 0.16f, H * 0.30f, W * 0.68f, H * 0.38f, W);
            centerAt(c, pCur, "U 盘 已 拔 出", cx, H * 0.45f, W * 0.042f, GOLD);
            centerAt(c, pNext, "请插回 U 盘，插回后自动接着唱", cx, H * 0.55f, W * 0.026f, INK);
        }

        private void drawNoLrc(Canvas c, int W, int H) {
            float cx = W / 2f;
            centerAt(c, pCur, "♪  正在播放  ♪", cx, H * 0.45f, W * 0.040f, INK);
            centerAt(c, pNext, "放一个和歌曲同名的 .lrc 到 U 盘即可显示歌词",
                     cx, H * 0.56f, W * 0.024f, INK_SOFT);
        }

        private void drawEmpty(Canvas c, int W, int H) {
            float cx = W / 2f;
            centerAt(c, pCur, "未找到歌曲", cx, H * 0.40f, W * 0.038f, INK);
            centerAt(c, pNext, "U 盘里放 歌名.mp3 + 歌名.lrc [+ 歌名.伴奏.mp3]",
                     cx, H * 0.52f, W * 0.024f, INK_SOFT);
            centerAt(c, pNext, "插好后按 菜单 重新扫描", cx, H * 0.59f, W * 0.024f, INK_SOFT);
        }

        /* ---------- 启动过渡页 ---------- */

        /** 冷启动第一屏：深底 + 金色台名 + 呼吸省略号；一首歌都没扫到就换成插盘提示。 */
        private void drawSplash(Canvas c, int W, int H) {
            c.drawRect(0, 0, W, H, pBg);
            float cx = W / 2f, cy = H * 0.46f;

            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setTextSize(W * 0.058f);
            pCur.setColor(GOLD);
            fit(pCur, appName, W * 0.86f, W * 0.058f);   // 台名过长自动缩字号，不截断
            c.drawText(appName, cx, cy, pCur);

            /* 分隔线：长度取台名实测字宽（fit 之后量，长名缩字号它也同步变短），
               两端由金到全透明渐变收进底图，不做生硬的平头切断。 */
            float lw = Math.min(pCur.measureText(appName), W * 0.86f);
            float lx0 = cx - lw / 2f, lx1 = cx + lw / 2f;
            float ly = cy + H * 0.050f, lh = Math.max(3f, H * 0.0035f);
            pGold.setColor(GOLD);
            pGold.setShader(new LinearGradient(lx0, ly, lx1, ly, new int[]{
                    GOLD & 0x00FFFFFF, GOLD, GOLD, GOLD & 0x00FFFFFF},
                    new float[]{0f, 0.22f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(lx0, ly, lx1, ly + lh, pGold);
            pGold.setShader(null);          // 别让渐变留下，后面还要复用这支笔

            if (waitingUsb) {
                centerAt(c, pSub, "请插入 U 盘", cx, cy + H * 0.165f, W * 0.024f, GOLD);
                centerAt(c, pSub, "插好后自动开始，不用按键",
                         cx, cy + H * 0.225f, W * 0.019f, INK_DIM);
            } else {
                /* 省略号 400ms 一格循环：扫描期间画面不呆板（主线程已让给绘制）。 */
                StringBuilder dots = new StringBuilder("启动中");
                int n = (int) ((System.currentTimeMillis() / 400L) % 4);
                for (int i = 0; i < n; i++) dots.append('·');
                centerAt(c, pSub, dots.toString(), cx, cy + H * 0.165f, W * 0.024f, INK_SOFT);
            }

            pCur.setColor(INK);
            pSub.setTextAlign(Paint.Align.LEFT);   // 还原对齐，别让顶栏继承居中
        }
    }
}
