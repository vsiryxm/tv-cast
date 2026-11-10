// Copyright 2026 Simon Yang <https://github.com/vsiryxm/tv-cast>
// SPDX-License-Identifier: Apache-2.0
package com.xinmin.tvcast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 极简投屏接收端：全屏显示 MJPEG 流或单帧 JPEG 轮询，适配 Android 4.0.4 (API 15)。
 *
 * 用法（通过 adb 一次性灌入地址）：
 *   am start -n com.xinmin.tvcast/.ReceiverActivity --es url http://手机IP:8080/stream.mjpeg
 *   am start -n com.xinmin.tvcast/.ReceiverActivity --ei scan 1     # 自动搜索
 * 遥控器：左右键=切换显示方式，菜单键=改地址 / 自动搜索，返回键连按两次=退出。
 */
public class ReceiverActivity extends Activity {

    private static final String TAG = "TVCast";
    private static final String PREF = "tvcast";
    private static final int MAX_FRAME = 8 * 1024 * 1024;
    private static final int[] PORTS = {8080, 8081, 8899, 8888, 9090, 8000};
    private static final String[] PATHS = {
            "/stream.mjpeg", "/frame.jpg", "/stream.jpg", "/screen.jpg",
            "/snapshot.jpg", "/mjpeg", "/video.mjpg", "/?action=stream"
    };

    private CastView castView;
    private TextView statusView;
    private SharedPreferences pref;

    // 竖屏手机画面投到 16:9 电视，无法同时「不变形 + 不裁切 + 铺满」，故提供四种方式
    private static final int MODE_FILL = 0;      // 默认：模糊延展填满黑边 + 完整主画面
    private static final int MODE_CROP = 1;      // 裁切铺满：不变形，裁掉上下
    private static final int MODE_STRETCH = 2;   // 拉伸铺满：内容全，但变形
    private static final int MODE_FIT = 3;       // 原始等比：左右黑边
    private static final int MODE_DANMU = 4;     // 弹幕看板：只裁直播下方文字区放大铺满
    private static final String[] MODE_NAMES = {
            "满屏填充 · 画面放大无黑边", "裁切铺满 · 不变形但裁上下",
            "拉伸铺满 · 变形但内容全", "原始比例 · 有黑边",
            "弹幕看板 · 文字放大全屏"
    };
    // 弹幕区在竖屏帧中的纵向范围（比例），上下键可微调；不同直播间位置不同
    private static final float DEFAULT_TOP = 0.63f;
    private static final float DEFAULT_BOTTOM = 0.90f;

    private volatile HttpURLConnection currentConn;
    private volatile Thread worker;
    private volatile int generation;
    private volatile boolean running;
    private volatile boolean statusPinned;
    private volatile boolean hideScheduled;
    private volatile long lastFrameAt;
    private long lastBackAt;

    // 双缓冲复用位图：老电视堆很小，避免每帧分配 1~3MB 引发频繁 GC
    private final Bitmap[] bitmapPool = new Bitmap[2];
    private int poolIndex;
    private int reuseWidth;
    private int reuseHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        castView = new CastView(this);
        root.addView(castView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFE8E8E8);
        statusView.setTextSize(17);
        statusView.setBackgroundColor(0xB0000000);
        statusView.setPadding(24, 10, 24, 10);
        root.addView(statusView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT));

        setContentView(root);
        pref = getSharedPreferences(PREF, MODE_PRIVATE);
        castView.mode = pref.getInt("mode", MODE_FILL);
        castView.danmuTop = pref.getFloat("dtop", DEFAULT_TOP);
        castView.danmuBottom = pref.getFloat("dbottom", DEFAULT_BOTTOM);
        running = true;
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        int fps = i.getIntExtra("fps", 0);
        if (fps > 0) pref.edit().putInt("fps", Math.min(fps, 30)).commit();

        int mode = i.getIntExtra("mode", -1);
        if (mode >= 0) applyMode(mode, false);

        float top = i.getFloatExtra("top", -1f);
        float bottom = i.getFloatExtra("bottom", -1f);
        if (top >= 0f || bottom >= 0f) {
            setDanmuRegion(top >= 0f ? top : castView.danmuTop,
                    bottom >= 0f ? bottom : castView.danmuBottom);
        }

        String url = i.getStringExtra("url");
        if (i.getIntExtra("scan", 0) == 1) {
            pref.edit().remove("url").commit();
            startStream("", true);
        } else if (url != null && url.length() > 0) {
            url = normalizeUrl(url);
            pref.edit().putString("url", url).commit();
            startStream(url, false);
        } else if ((i.hasExtra("mode") || i.hasExtra("fps")) && worker != null) {
            // 仅调整显示设置，不打断正在播放的流
            return;
        } else {
            startStream(pref.getString("url", ""), false);
        }
    }

    /** 允许直接粘贴 ScreenStream 显示的根地址 http://手机IP:8080 —— 自动补流路径。 */
    private static String normalizeUrl(String u) {
        u = u.trim();
        if (u.length() == 0) return u;
        if (u.indexOf("://") < 0) u = "http://" + u;
        int pathStart = u.indexOf('/', u.indexOf("://") + 3);
        if (pathStart < 0 || pathStart == u.length() - 1) {
            String base = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
            return base + "/stream.mjpeg";
        }
        return u;
    }

    @Override
    protected void onDestroy() {
        running = false;
        generation++;
        disconnect();
        Thread t = worker;
        if (t != null) t.interrupt();
        super.onDestroy();
    }

    private void disconnect() {
        HttpURLConnection c = currentConn;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
        currentConn = null;
    }

    /** 切换地址/模式：让旧线程全部失效，再起新线程。 */
    private void startStream(final String url, boolean forceScan) {
        generation++;
        final int gen = generation;
        disconnect();
        Thread old = worker;
        if (old != null) old.interrupt();
        hideScheduled = false;

        final Runnable task;
        if (forceScan || url == null || url.length() == 0) {
            setStatus("正在局域网查找手机推流…（菜单键可手动填地址）", true);
            task = new Runnable() {
                public void run() { discover(gen); }
            };
        } else {
            setStatus("连接中: " + url, true);
            task = new Runnable() {
                public void run() { streamLoop(url, gen); }
            };
        }
        Thread t = new Thread(task, "tvcast-stream");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    // ---------- 状态栏 ----------

    private void setStatus(final String text, final boolean keep) {
        if (!running) return;
        runOnUiThread(new Runnable() {
            public void run() {
                statusView.setText(text);
                statusView.setVisibility(View.VISIBLE);
                statusPinned = keep;
                // 隐藏调度内聚到 UI 线程，避免与解码线程的 scheduleHide 产生竞态
                hideScheduled = false;
                if (!keep) {
                    hideScheduled = true;
                    castView.postDelayed(new Runnable() {
                        public void run() {
                            if (running && !statusPinned) {
                                statusView.setVisibility(View.GONE);
                                hideScheduled = false;
                            }
                        }
                    }, 3000);
                }
            }
        });
    }

    /** 画面一旦恢复就立即清掉状态栏，避免偶发单次网络抖动留下「断开」残影。 */
    private void hideStatus() {
        runOnUiThread(new Runnable() {
            public void run() {
                statusPinned = false;
                hideScheduled = false;
                statusView.setVisibility(View.GONE);
            }
        });
    }

    private void scheduleHide() {
        if (hideScheduled) return;
        hideScheduled = true;
        castView.postDelayed(new Runnable() {
            public void run() {
                if (running && !statusPinned) statusView.setVisibility(View.GONE);
            }
        }, 4000);
    }

    // ---------- 流接收 ----------

    private void streamLoop(String baseUrl, int gen) {
        int counter = 0;
        int failures = 0;
        boolean recovering = false;
        // ScreenStream 同时提供 /stream.jpeg 单帧端点；老系统 multipart 长连接不稳时自动降级
        String url = baseUrl;
        while (running && gen == generation) {
            HttpURLConnection c = null;
            try {
                String target = url;
                // 单帧模式加时间戳绕开缓存；multipart 模式服务器本身是长连接，不加参数
                if (!url.contains(".mjpeg") && !url.contains(".mjpg")) {
                    target = url + (url.indexOf('?') >= 0 ? "&" : "?")
                            + "t=" + System.nanoTime();
                }

                URL u = new URL(target);
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(12000);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Cache-Control", "no-cache");
                String info = u.getUserInfo();
                if (info != null) {
                    String auth = Base64.encodeToString(info.getBytes("UTF-8"), Base64.NO_WRAP);
                    c.setRequestProperty("Authorization", "Basic " + auth);
                }
                currentConn = c;

                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                String ct = c.getContentType();
                boolean multipart = ct != null && ct.toLowerCase().contains("multipart");
                InputStream in = new BufferedInputStream(c.getInputStream(), 8192);

                if (multipart || counter == 0 || recovering) {
                    hideStatus();
                    recovering = false;
                }
                failures = 0;
                if (multipart) {
                    scanJpegMarkers(in, gen);
                } else {
                    byte[] jpg = readAll(in, 4 * 1024 * 1024);
                    if (jpg != null && jpg.length > 100) {
                        decode(jpg, gen);
                        counter++;
                    } else {
                        throw new IOException("不是 JPEG 图像");
                    }
                    int fps = Math.max(1, pref.getInt("fps", 12));
                    Thread.sleep(Math.max(30, 1000 / fps));
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (!running || gen != generation) break;
                failures++;
                String switchNote = "";
                if (failures >= 3 && url.endsWith(".mjpeg")) {
                    url = url.substring(0, url.length() - 6) + ".jpeg";
                    failures = 0;
                    switchNote = " · 已切换单帧模式";
                }
                recovering = true;
                // 单次抖动不打扰观看；连续两次以上失败才提示
                if (failures >= 2) {
                    setStatus("断开: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " " + e.getMessage())
                            + switchNote + " · 1 秒后重试", true);
                }
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            } finally {
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) { }
                    if (currentConn == c) currentConn = null;
                }
            }
        }
    }

    /**
     * 不依赖 boundary / Content-Length：直接在字节流里找 JPEG 的 SOI(FFD8) / EOI(FFD9)。
     * MJPEG 的熵编码里 FF 只会跟 00 或 RST 标记，FFD9 一定代表一帧结束。
     */
    private void scanJpegMarkers(InputStream in, int gen) throws IOException {
        byte[] chunk = new byte[8192];
        ByteArrayOutputStream frame = null;
        int prev = -1;
        while (running && gen == generation) {
            int n = in.read(chunk);
            if (n < 0) break;
            for (int i = 0; i < n; i++) {
                int b = chunk[i] & 0xFF;
                if (frame == null) {
                    if (prev == 0xFF && b == 0xD8) {
                        frame = new ByteArrayOutputStream(64 * 1024);
                        frame.write(0xFF);
                        frame.write(0xD8);
                    }
                } else {
                    frame.write(b);
                    if (prev == 0xFF && b == 0xD9) {
                        byte[] data = frame.toByteArray();
                        frame = null;
                        if (data.length > 100) decode(data, gen);
                    } else if (frame.size() > MAX_FRAME) {
                        frame = null;
                    }
                }
                prev = b;
            }
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[8192];
        while (true) {
            int n = in.read(buf);
            if (n < 0) break;
            out.write(buf, 0, n);
            if (out.size() > limit) throw new IOException("响应过大");
        }
        return out.toByteArray();
    }

    private void decode(byte[] data, int gen) {
        if (!running || gen != generation) return;
        int fps = Math.max(1, pref.getInt("fps", 12));
        long now = System.currentTimeMillis();
        if (now - lastFrameAt < 1000L / fps) return;   // 限帧，减轻老电视 GC 压力
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;

            // 上限按「最长边」而非横屏 900：竖屏源若按 900 压会只剩 270x600，放大后发糊。
            // 提到 1920 是为了让 AI 文字看板（1920x1080 纯文字图）能 1:1 上屏、不再被降采样。
            int sample = 1;
            while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1920) sample *= 2;
            int w = bounds.outWidth / sample;
            int h = bounds.outHeight / sample;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            opt.inSampleSize = sample;
            Bitmap dest = null;
            if (reuseWidth == w && reuseHeight == h
                    && bitmapPool[poolIndex] != null
                    && !bitmapPool[poolIndex].isRecycled()) {
                dest = bitmapPool[poolIndex];
            }
            opt.inBitmap = dest;
            opt.inMutable = (dest == null);

            Bitmap bm;
            try {
                bm = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
            } catch (IllegalArgumentException e) {
                // 尺寸/配置变化时 inBitmap 会失效，退回常规解码
                opt.inBitmap = null;
                opt.inMutable = true;
                bm = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
            }
            if (bm == null) return;

            if (bm.isMutable()) {
                bitmapPool[poolIndex] = bm;
                poolIndex = 1 - poolIndex;
                reuseWidth = bm.getWidth();
                reuseHeight = bm.getHeight();
            }
            lastFrameAt = now;
            final Bitmap shown = bm;
            runOnUiThread(new Runnable() {
                public void run() {
                    if (!running) return;
                    castView.setFrame(shown);
                    scheduleHide();
                }
            });
        } catch (Throwable ignored) {
            // 单帧解码失败不应中断整个流
        }
    }

    // ---------- 局域网自动发现 ----------

    private void discover(final int gen) {
        final List<String> prefixes = localPrefixes();
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        Log.i(TAG, "discover start, prefixes=" + prefixes);

        // 按端口分阶段：先扫 ScreenStream 默认 8080，命中即停，避免全量组合拖慢老电视
        for (int pi = 0; pi < PORTS.length; pi++) {
            if (!running || gen != generation || !found.isEmpty()) break;
            final int port = PORTS[pi];
            setStatus("正在局域网查找手机推流… 端口 " + port, true);
            ExecutorService pool = Executors.newFixedThreadPool(64);
            for (final String pre : prefixes) {
                for (int i = 2; i < 255; i++) {
                    final String host = pre + i;
                    pool.execute(new Runnable() {
                        public void run() {
                            if (!running || gen != generation || !found.isEmpty()) return;
                            if (!portOpen(host, port)) return;
                            for (int k = 0; k < PATHS.length; k++) {
                                if (!running || gen != generation || !found.isEmpty()) return;
                                String u = "http://" + host + ":" + port + PATHS[k];
                                if (probeUrl(u) != null) {
                                    Log.i(TAG, "found " + u);
                                    found.add(u);
                                    return;
                                }
                            }
                        }
                    });
                }
            }
            pool.shutdown();
            try { pool.awaitTermination(12, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            pool.shutdownNow();
        }

        if (!running || gen != generation) return;
        if (found.isEmpty()) {
            Log.i(TAG, "discover: nothing found");
            setStatus("未找到推流源。按菜单键输入地址，例如 http://手机IP:8080/stream.mjpeg", true);
        } else {
            String u = found.get(0);
            pref.edit().putString("url", u).commit();
            startStream(u, false);
        }
    }

    private static List<String> localPrefixes() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns.hasMoreElements()) {
                NetworkInterface n = ns.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> as = n.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String[] p = a.getHostAddress().split("\\.");
                    if (p.length == 4) out.add(p[0] + "." + p[1] + "." + p[2] + ".");
                }
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) out.add("192.168.2.");
        return out;
    }

    private static boolean portOpen(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 220);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** 探测一个候选 URL 是否是可用的图像流端点。 */
    private static String probeUrl(String u) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(u);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(900);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            String ct = c.getContentType();
            if (ct == null) ct = "";
            ct = ct.toLowerCase();
            InputStream in = c.getInputStream();
            byte[] head = new byte[2];
            int n = in.read(head);
            boolean jpeg = n == 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8;
            if (ct.contains("multipart") || ct.contains("mjpeg")) return "mjpeg";
            if (ct.contains("image/jpeg") || jpeg) return "jpeg";
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- 遥控器 ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            applyMode(castView.mode - 1, true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            applyMode(castView.mode + 1, true);
            return true;
        }
        if (castView.mode == MODE_DANMU && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            shiftDanmuRegion(-0.02f);
            return true;
        }
        if (castView.mode == MODE_DANMU && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            shiftDanmuRegion(0.02f);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_INFO) {
            showUrlDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBackAt < 2000) {
                finish();
            } else {
                lastBackAt = now;
                setStatus("再按一次返回键退出", false);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (statusView.getVisibility() == View.VISIBLE) {
                statusPinned = false;
                statusView.setVisibility(View.GONE);
            } else {
                setStatus(pref.getString("url", "未设置地址 · 按菜单键输入"), true);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showUrlDialog() {
        final EditText et = new EditText(this);
        et.setText(pref.getString("url", ""));
        et.setSingleLine(true);
        et.setHint("http://手机IP:8080/stream.mjpeg");

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("投屏地址（显示方式按遥控器左右键切换）");
        b.setView(et);
        b.setPositiveButton("连接", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String u = normalizeUrl(et.getText().toString());
                pref.edit().putString("url", u).commit();
                startStream(u, false);
            }
        });
        b.setNeutralButton("自动搜索", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                pref.edit().remove("url").commit();
                startStream("", true);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    // ---------- 显示方式 ----------

    private void applyMode(int m, boolean notify) {
        int mode = ((m % MODE_NAMES.length) + MODE_NAMES.length) % MODE_NAMES.length;
        pref.edit().putInt("mode", mode).commit();
        castView.mode = mode;
        castView.invalidate();
        if (notify) setStatus("显示方式: " + MODE_NAMES[mode] + "  · 左右键切换", false);
    }

    /** 调整弹幕看板裁切窗口，上下键微调窗口位置，窗口高度保持不变。 */
    private void setDanmuRegion(float top, float bottom) {
        float height = Math.max(0.08f, Math.min(0.60f, bottom - top));
        top = Math.max(0f, Math.min(1f - height, top));
        bottom = top + height;
        castView.danmuTop = top;
        castView.danmuBottom = bottom;
        pref.edit().putFloat("dtop", top).putFloat("dbottom", bottom).commit();
        castView.invalidate();
    }

    private void shiftDanmuRegion(float delta) {
        setDanmuRegion(castView.danmuTop + delta, castView.danmuBottom + delta);
        setStatus(String.format("弹幕区 %.0f%%–%.0f%% · 上下键微调",
                castView.danmuTop * 100, castView.danmuBottom * 100), false);
    }

    /**
     * 自绘画面层：整帧只解码一次，onDraw 里按当前方式画同一张位图，不额外分配大位图。
     * FILL 用「缩到 64x64 再放大」做零依赖模糊背景（API 15 没有 RenderScript 模糊），
     * 填满左右黑边、不裁内容、不变形。
     */
    private final class CastView extends View {
        private volatile Bitmap frame;
        private volatile int mode = MODE_FILL;
        private volatile float danmuTop = DEFAULT_TOP;
        private volatile float danmuBottom = DEFAULT_BOTTOM;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint danmuPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private final Rect blurRect = new Rect(0, 0, 64, 64);
        private Bitmap blur;
        private Canvas blurCanvas;

        CastView(Context c) {
            super(c);
            // 弹幕多为半透明浮层，轻微提对比度 + 提亮，放大后更好认
            ColorMatrix cm = new ColorMatrix();
            cm.set(new float[]{
                    1.18f, 0, 0, 0, -10,
                    0, 1.18f, 0, 0, -10,
                    0, 0, 1.18f, 0, -10,
                    0, 0, 0, 1, 0
            });
            danmuPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        }

        void setFrame(Bitmap b) {
            frame = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bitmap f = frame;
            int W = getWidth(), H = getHeight();
            canvas.drawColor(0xFF000000);
            if (f == null || f.isRecycled() || W <= 0 || H <= 0) return;
            int fw = f.getWidth(), fh = f.getHeight();
            if (fw <= 0 || fh <= 0) return;

            int m = mode;
            dst.set(0, 0, W, H);
            if (m == MODE_DANMU) {
                // 弹幕区本身接近 16:9 的横条：按目标比例再裁一次，铺满全屏且文字不变形
                int y1 = Math.max(0, Math.min(fh - 4, Math.round(fh * danmuTop)));
                int y2 = Math.max(y1 + 4, Math.min(fh, Math.round(fh * danmuBottom)));
                float ratio = (float) W / H;
                int sh = y2 - y1;
                int sw = Math.min(fw, Math.max(1, Math.round(sh * ratio)));
                if (sw < fw) {
                    src.set((fw - sw) / 2, y1, (fw + sw) / 2, y2);
                } else {
                    int need = Math.max(4, Math.min(fh, Math.round(fw / ratio)));
                    int cy = (y1 + y2) / 2;
                    int t = Math.max(0, Math.min(fh - need, cy - need / 2));
                    src.set(0, t, fw, t + need);
                }
                canvas.drawBitmap(f, src, dst, danmuPaint);
                return;
            }
            if (m == MODE_STRETCH) {
                canvas.drawBitmap(f, null, dst, paint);
                return;
            }
            if (m == MODE_CROP) {
                float s = Math.max((float) W / fw, (float) H / fh);
                int sw = Math.min(fw, Math.max(1, Math.round(W / s)));
                int sh = Math.min(fh, Math.max(1, Math.round(H / s)));
                src.set((fw - sw) / 2, (fh - sh) / 2, (fw + sw) / 2, (fh + sh) / 2);
                canvas.drawBitmap(f, src, dst, paint);
                return;
            }
            if (m == MODE_FILL) {
                if (blur == null) {
                    blur = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565);
                    blurCanvas = new Canvas(blur);
                }
                blurCanvas.drawBitmap(f, null, blurRect, paint);
                canvas.drawBitmap(blur, null, dst, paint);
                canvas.drawColor(0x99000000);   // 压暗背景，突出完整主画面
            }
            // 前景：等比放大。FILL 下再放大 1.3 倍，只轻微裁掉上下边缘，让主画面更饱满；
            // 超出 View 的部分由 Canvas 自然裁剪，两侧仍是模糊延展，不留黑边。
            float scale = Math.min((float) W / fw, (float) H / fh);
            if (m == MODE_FILL) scale *= 1.3f;
            int dw = Math.max(1, Math.round(fw * scale));
            int dh = Math.max(1, Math.round(fh * scale));
            dst.set((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2);
            canvas.drawBitmap(f, null, dst, paint);
        }
    }
}
