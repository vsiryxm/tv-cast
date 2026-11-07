package com.xinmin.karaoke;

/*
 * 电视端离线 K 歌机 —— Android 4.0.4 / API 15，零依赖、零网络、零电脑。
 *
 * U 盘插电视 USB 口 -> 扫描 .mp3/.wav/.m4a/.ogg + 同名 .lrc
 * -> MediaPlayer 播伴奏 -> 全屏 Canvas 画大字歌词（上一句/当前句/下一句 + 逐句进度）
 * -> 遥控器选歌、播放暂停、快进快退、歌单、重扫。
 *
 * 音频输出走电视自身扬声器 / 3.5mm 输出；本机不提供麦克风输入，
 * 人声仍由原有无线麦克风链路负责，见 README 的「音频接线」一节。
 */
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KaraokeActivity extends Activity {

    private static final String TAG = "Karaoke";
    private static final String[] ROOTS = {
        "/mnt/sdcard", "/mnt/usbdisk", "/mnt/usb", "/sdcard",
        "/storage/sdcard0", "/data/local/tmp/karaoke"
    };
    private static final Pattern LRC_TAG =
        Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern LRC_OFFSET =
        Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    static class Line {
        long ms;
        String text;
        Line(long m, String t) { ms = m; text = t; }
    }

    static class Song {
        String name;
        String path;
        boolean hasLrc;
        ArrayList<Line> lines = new ArrayList<Line>();
    }

    private ArrayList<Song> songs = new ArrayList<Song>();
    private int cur = 0;
    private int listCursor = 0;
    private boolean showList = false;
    private boolean playing = false;
    private boolean backArmed = false;
    private String err = "";
    private String rootHint = "";

    private MediaPlayer player;
    private KView view;
    private final Handler handler = new Handler();
    private PowerManager.WakeLock wake;
    private BroadcastReceiver usbReceiver;

    private final Paint pBg = new Paint();
    private final Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPrev = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNext = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint();
    private final Paint pBarOn = new Paint();
    private final Paint pMask = new Paint();

    private final Runnable tick = new Runnable() {
        public void run() {
            if (view != null) view.invalidate();
            handler.postDelayed(this, 100);
        }
    };

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

        probeMic();          // 只读自检：把本机有无麦克风输入写进 logcat
        rescan();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addDataScheme("file");
        usbReceiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                Log.i(TAG, "USB 事件: " + i.getAction());
                handler.postDelayed(new Runnable() {
                    public void run() { rescan(); }
                }, 1500);
            }
        };
        registerReceiver(usbReceiver, f);
        // U 盘挂载可能慢于开机，补扫一次
        handler.postDelayed(new Runnable() {
            public void run() { if (songs.isEmpty()) rescan(); }
        }, 4000);
        handler.post(tick);
    }

    private void initPaints() {
        pTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pTitle.setColor(Color.WHITE);
        pTitle.setTextAlign(Paint.Align.LEFT);
        pSub.setColor(0xFF9AA8C0);
        pSub.setTextAlign(Paint.Align.LEFT);
        pPrev.setTextAlign(Paint.Align.CENTER);
        pPrev.setColor(0xFF6B7893);
        pCur.setTextAlign(Paint.Align.CENTER);
        pCur.setColor(0xFFFFFFFF);
        pCur.setTypeface(Typeface.DEFAULT_BOLD);
        pCur.setShadowLayer(6, 0, 3, 0xCC000000);
        pNext.setTextAlign(Paint.Align.CENTER);
        pNext.setColor(0xFF8492AD);
        pHint.setTextAlign(Paint.Align.CENTER);
        pHint.setColor(0xFF64728D);
        pBar.setColor(0xFF26324A);
        pBarOn.setColor(0xFFFFC94A);
        pMask.setColor(0xFA080D18);
    }

    /* 诊断：Android 层有没有真能采到声音的输入通道。放子线程，绝不阻塞 UI。 */
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
            Log.i(TAG, "MIC AudioRecord state=" + ar.getState() + " minBuffer=" + min);
            ar.startRecording();
            Log.i(TAG, "MIC startRecording state=" + ar.getRecordingState());
            short[] buf = new short[800];       // 8000Hz 下约 0.1 秒
            int n = ar.read(buf, 0, buf.length);
            int peak = 0;
            long sum = 0;
            for (int i = 0; i < n; i++) {
                int v = Math.abs(buf[i]);
                if (v > peak) peak = v;
                sum += v;
            }
            Log.i(TAG, "MIC 采样 n=" + n + " peak=" + peak
                + " avg=" + (n > 0 ? sum / n : -1)
                + "  => " + (n > 0 && peak == 0 ? "通道存在但恒为静音" : "通道有数据"));
        } catch (Throwable t) {
            Log.i(TAG, "MIC 不可用: " + t);
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception e) { }
                try { ar.release(); } catch (Exception e) { }
            }
        }
    }

    /* ---------------- 曲库扫描 ---------------- */

    private String canon(File f) {
        try { return f.getCanonicalPath(); } catch (Exception e) { return f.getAbsolutePath(); }
    }

    private void collect(File dir, List<File> out, int depth) {
        if (depth > 5) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isHidden()) continue;
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a")
                    || n.endsWith(".ogg")) out.add(f);
            }
        }
    }

    /* 海信 vold 把 U 盘挂到 /mnt/sdcard，但空目录权限是 000、且不同固件可能挂到别处。
       直接读 /proc/mounts 找真实的可移动存储挂载点，比猜路径可靠。 */
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

    private void rescan() {
        HashSet<String> seen = new HashSet<String>();
        ArrayList<File> roots = new ArrayList<File>();
        for (String r : ROOTS) {
            File f = new File(r);
            // 不用 canRead()：老电视目录权限位常不可信，实际 listFiles() 能读就行
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
        for (File f : files) {
            if (foundDir.length() == 0 && f.getParentFile() != null)
                foundDir = f.getParentFile().getAbsolutePath();
            Song s = new Song();
            s.path = f.getAbsolutePath();
            s.name = f.getName().replaceFirst("\\.[^.]+$", "");
            File lrc = findLrc(f);
            if (lrc != null) {
                s.lines = loadLrc(lrc);
                s.hasLrc = !s.lines.isEmpty();
            }
            fresh.add(s);
        }
        songs = fresh;
        if (cur >= songs.size()) cur = 0;
        if (listCursor >= songs.size()) listCursor = 0;
        rootHint = foundDir.length() > 0 ? ("曲库: " + foundDir)
                 : ("已扫描 " + roots.size() + " 个位置，未找到歌曲");
        Log.i(TAG, "扫描到 " + songs.size() + " 首歌；" + rootHint);
        for (File d : roots) {
            Log.i(TAG, "  扫描根 " + d.getAbsolutePath()
                + " -> " + (d.listFiles() == null ? "不可读" : "可读"));
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

    /* UTF-8 优先；老歌词常见 GBK，用替换符判断后回退，避免满屏乱码 */
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
            if (text.length() == 0) continue;
            for (Long t : times) lines.add(new Line(Math.max(0L, t + offset), text));
        }
        Collections.sort(lines, new Comparator<Line>() {
            public int compare(Line a, Line b) { return a.ms < b.ms ? -1 : (a.ms == b.ms ? 0 : 1); }
        });
        return lines;
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

    private void play(final int idx) {
        if (songs.isEmpty()) return;
        stopPlayer();
        cur = ((idx % songs.size()) + songs.size()) % songs.size();
        listCursor = cur;
        final Song s = songs.get(cur);
        err = "";
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(s.path);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { play(cur + 1); }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    err = "播放失败 (" + what + "/" + extra + ")，换一首试试";
                    stopPlayer();
                    if (view != null) view.invalidate();
                    return true;
                }
            });
            player.prepare();
            player.start();
            playing = true;
        } catch (Exception e) {
            err = "无法播放：" + e.getClass().getSimpleName();
            Log.e(TAG, err, e);
            stopPlayer();
        }
        if (view != null) view.invalidate();
    }

    private void toggle() {
        if (songs.isEmpty()) { rescan(); return; }
        if (player == null) { play(cur); return; }
        if (playing) { player.pause(); playing = false; }
        else { player.start(); playing = true; }
        view.invalidate();
    }

    private void seek(int delta) {
        if (player == null) return;
        try {
            int dur = player.getDuration();
            int to = player.getCurrentPosition() + delta;
            if (to < 0) to = 0;
            if (dur > 0 && to > dur - 300) to = Math.max(0, dur - 300);
            player.seekTo(to);
        } catch (Exception e) { }
        view.invalidate();
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
        try { return player == null ? 0 : player.getCurrentPosition(); }
        catch (Exception e) { return 0; }
    }

    private int duration() {
        try { return player == null ? 0 : player.getDuration(); }
        catch (Exception e) { return 0; }
    }

    /* ---------------- 遥控器 ---------------- */

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (showList) { listCursor = (listCursor - 1 + Math.max(1, songs.size())) % Math.max(1, songs.size()); view.invalidate(); }
                else play(cur - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (showList) { listCursor = (listCursor + 1) % Math.max(1, songs.size()); view.invalidate(); }
                else play(cur + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (showList) { showList = false; play(listCursor); }
                else toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seek(-5000); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seek(5000); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                play(cur + 1); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                play(cur - 1); return true;
            case KeyEvent.KEYCODE_MENU:
                if (showList) { showList = false; rescan(); Toast.makeText(this, "已重新扫描 U 盘", Toast.LENGTH_SHORT).show(); }
                else { showList = true; listCursor = cur; }
                view.invalidate();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (showList) { showList = false; view.invalidate(); return true; }
                if (backArmed) { finish(); return true; }
                backArmed = true;
                handler.postDelayed(disarmBack, 2000);
                Toast.makeText(this, "再按一次返回键退出 K 歌", Toast.LENGTH_SHORT).show();
                return true;
            default:
                return super.onKeyDown(key, e);
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && playing) { player.pause(); playing = false; }
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
        while (p.measureText(s) > maxW && size > 26) {
            size -= 2;
            p.setTextSize(size);
        }
    }

    class KView extends View {
        KView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            if (W <= 0 || H <= 0) return;
            float pad = W * 0.045f;

            pBg.setShader(new LinearGradient(0, 0, 0, H,
                0xFF0B1020, 0xFF18233D, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, pBg);

            pTitle.setTextSize(W * 0.026f);
            pSub.setTextSize(W * 0.013f);
            pHint.setTextSize(W * 0.0115f);

            String title = songs.isEmpty() ? "K 歌 · 等待曲库" : songs.get(cur).name;
            fit(pTitle, title, W * 0.58f, W * 0.026f);
            c.drawText(title, pad, H * 0.105f, pTitle);

            String meta = (songs.isEmpty() ? "0 / 0" : ((cur + 1) + " / " + songs.size()))
                + "     " + mmss(position()) + " / " + (duration() > 0 ? mmss(duration()) : "--:--")
                + "     " + rootHint;
            c.drawText(meta, pad, H * 0.155f, pSub);
            String flags = (playing ? "▶ 播放中" : "‖ 已暂停")
                + (songs.isEmpty() || !songs.get(cur).hasLrc ? "   ·   无歌词文件" : "");
            c.drawText(flags, W - pad - pSub.measureText(flags), H * 0.155f, pSub);

            if (songs.isEmpty()) {
                drawEmpty(c, W, H);
            } else {
                Song s = songs.get(cur);
                if (s.hasLrc) drawLyrics(c, W, H, s, position());
                else drawNoLrc(c, W, H, s.name);
            }

            // 整曲进度条
            int dur = duration();
            float bw = W - 2 * pad, by = H * 0.865f, bh = Math.max(6, H * 0.007f);
            c.drawRect(pad, by, pad + bw, by + bh, pBar);
            if (dur > 0) c.drawRect(pad, by, pad + bw * (position() / (float) dur), by + bh, pBarOn);

            if (err.length() > 0) {
                c.drawText(err, W / 2, H * 0.915f, pHint);
            } else {
                c.drawText("↑↓ 换歌    OK 播放/暂停    ←→ ±5秒    MENU 歌单（再按重扫 U 盘）    BACK 退出",
                           W / 2, H * 0.945f, pHint);
            }

            if (showList) drawSongList(c, W, H);
        }

        private void drawLyrics(Canvas c, int W, int H, Song s, int pos) {
            int i = lineAt(s, pos);
            String prev = i > 0 ? s.lines.get(i - 1).text : "";
            String curT = i >= 0 ? s.lines.get(i).text : "♪";
            String nextT = (i >= 0 && i + 1 < s.lines.size()) ? s.lines.get(i + 1).text
                         : (i < 0 && !s.lines.isEmpty() ? s.lines.get(0).text : "");

            fit(pPrev, prev, W * 0.86f, W * 0.024f);
            c.drawText(prev, W / 2, H * 0.345f, pPrev);

            fit(pCur, curT, W * 0.88f, W * 0.050f);
            c.drawText(curT, W / 2, H * 0.505f, pCur);

            // 当前句进度：两句时间戳之间的比例，模拟逐字推进
            if (i >= 0) {
                long start = s.lines.get(i).ms;
                long end = (i + 1 < s.lines.size()) ? s.lines.get(i + 1).ms
                         : Math.max(start + 1000, duration());
                float frac = end > start ? (pos - start) / (float) (end - start) : 0;
                if (frac < 0) frac = 0;
                if (frac > 1) frac = 1;
                float lw = W * 0.30f, lx = (W - lw) / 2, ly = H * 0.575f;
                float lh = Math.max(5, H * 0.006f);
                c.drawRect(lx, ly, lx + lw, ly + lh, pBar);
                c.drawRect(lx, ly, lx + lw * frac, ly + lh, pBarOn);
            }

            fit(pNext, nextT, W * 0.86f, W * 0.026f);
            c.drawText(nextT, W / 2, H * 0.705f, pNext);
        }

        private void drawNoLrc(Canvas c, int W, int H, String name) {
            pCur.setTextSize(W * 0.042f);
            c.drawText("♪  正在播放  ♪", W / 2, H * 0.48f, pCur);
            pNext.setTextSize(W * 0.022f);
            c.drawText("放一个和歌曲同名的 .lrc 到 U 盘即可显示歌词", W / 2, H * 0.60f, pNext);
        }

        private void drawEmpty(Canvas c, int W, int H) {
            pCur.setTextSize(W * 0.040f);
            c.drawText("未找到歌曲", W / 2, H * 0.40f, pCur);
            pNext.setTextSize(W * 0.022f);
            c.drawText("把 MP3 和同名 .lrc 放进 U 盘的 karaoke 目录，插到电视 USB 口", W / 2, H * 0.52f, pNext);
            c.drawText("例：月亮代表我的心.mp3  +  月亮代表我的心.lrc", W / 2, H * 0.59f, pNext);
            pSub.setTextAlign(Paint.Align.CENTER);
            c.drawText("按 MENU 重新扫描 U 盘", W / 2, H * 0.68f, pSub);
            pSub.setTextAlign(Paint.Align.LEFT);
        }

        private void drawSongList(Canvas c, int W, int H) {
            c.drawRect(0, 0, W, H, pMask);
            pTitle.setTextSize(W * 0.026f);
            pTitle.setTextAlign(Paint.Align.CENTER);
            c.drawText("歌 单   （↑↓ 选择，OK 播放，MENU 关闭并重扫）", W / 2, H * 0.13f, pTitle);
            pTitle.setTextAlign(Paint.Align.LEFT);

            int rows = 9, first = Math.max(0, Math.min(listCursor - rows / 2, songs.size() - rows));
            float rowH = H * 0.082f, y0 = H * 0.22f;
            for (int k = 0; k < rows && first + k < songs.size(); k++) {
                int idx = first + k;
                float y = y0 + k * rowH;
                if (idx == listCursor) {
                    pBar.setColor(0xFF2B3A58);
                    c.drawRect(W * 0.16f, y - rowH * 0.72f, W * 0.84f, y + rowH * 0.16f, pBar);
                    pBar.setColor(0xFF26324A);
                    pCur.setColor(0xFFFFC94A);
                } else {
                    pCur.setColor(0xFFD8E0F0);
                }
                String label = (idx + 1) + ".  " + songs.get(idx).name
                             + (songs.get(idx).hasLrc ? "" : "   (无词)");
                pCur.setTextAlign(Paint.Align.CENTER);
                fit(pCur, label, W * 0.62f, W * 0.023f);
                c.drawText(label, W / 2, y, pCur);
            }
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setColor(0xFFFFFFFF);
        }
    }
}
