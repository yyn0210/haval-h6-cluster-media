package com.janeluo.gwm.clusterbridge;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 极简内存日志缓冲，供界面显示。
 * 同时把内容打到 logcat（tag: ClusterBridge）。
 */
public final class UiLog {

    public static final String TAG = "ClusterBridge";

    public interface Listener {
        void onLog(String line);
    }

    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static volatile Listener sListener;

    private UiLog() {
    }

    public static void setListener(Listener l) {
        sListener = l;
    }

    public static void append(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        Log.i(TAG, msg);
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        Listener l = sListener;
        if (l != null) {
            l.onLog(line);
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String s : LINES) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
