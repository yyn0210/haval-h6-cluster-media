package com.gwm.clusterbridge;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 核心服务：监听目标音乐 App 的媒体信息，转发到仪表盘。
 *
 * 两条独立的采集通道（互为备份）：
 *   A. MediaSessionManager.getActiveSessions(自己的通知监听组件) —— 只要求通知使用权，
 *      不需要 signature 级的 MEDIA_CONTENT_CONTROL。
 *   B. 通知里的 MediaSession.Token（Notification.EXTRA_MEDIA_SESSION）—— 有些车机版 App
 *      的会话不出现在 getActiveSessions 列表里，但媒体通知一定带着 token，可据此直接建
 *      MediaController。若连 token 都没有，则退回解析通知标题/正文。
 *
 * 使用前提：用户在「设置 → 通知使用权」里启用本服务
 *          （或 adb shell cmd notification allow_listener com.gwm.clusterbridge/.ClusterBridgeService）。
 */
public class ClusterBridgeService extends NotificationListenerService {

    private static final String TAG = UiLog.TAG;

    /** 重新扫描会话的间隔。有些 App 会延迟创建 MediaSession。 */
    private static final long RESCAN_INTERVAL_MS = 3000;
    /** 进度上报间隔。 */
    private static final long PROGRESS_INTERVAL_MS = 1000;

    public static final String PREFS = "bridge";
    public static final String KEY_PACKAGES = "target_packages";
    public static final String KEY_SOURCE_TYPE = "source_type";
    public static final String KEY_ENABLED = "bridge_enabled";

    /** 目标包名。车机版与手机版常常不是同一个包名，这里优先列车机版。 */
    public static final String DEFAULT_PACKAGES =
            "com.tencent.qqmusiccar\n"
            + "cn.kuwo.kwmusiccar\n"
            + "com.tencent.qqmusic\n"
            + "com.kugou.android\n"
            + "com.ximalaya.ting.android";

    /** 历史版本写入过的默认值。读到就整体升级成当前默认，避免老用户漏掉新增的包名。 */
    private static final String[] LEGACY_DEFAULTS = {
            // v1.0：只有手机版 QQ音乐
            "com.tencent.qqmusic\ncom.kugou.android\ncom.ximalaya.ting.android",
            // v1.1：补了 QQ音乐车机版，还没有酷我
            "com.tencent.qqmusiccar\ncom.tencent.qqmusic\ncom.kugou.android\ncom.ximalaya.ting.android",
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, MediaController> mControllers = new HashMap<>();
    private final Map<String, MediaController.Callback> mCallbacks = new HashMap<>();
    private final Map<String, TrackState> mStates = new HashMap<>();
    /** 通过通知 token 建立的会话（会话可能不在 getActiveSessions 列表里）。 */
    private final Set<String> mFromNotification = new HashSet<>();

    private ClusterMediaPublisher mPublisher;
    private final ClusterMediaPublisher.StateListener mPublisherListener =
            new ClusterMediaPublisher.StateListener() {
                @Override
                public void onStateChanged(boolean connected, String detail) {
                    UiLog.append("仪表通道: " + detail);
                }
            };
    private volatile boolean mRunning;
    /** 上一次打印的"可见会话"快照，避免每 3 秒刷屏。 */
    private String mLastSessionDump = "";

    private static final class TrackState {
        String title = "";
        String album = "";
        String artist = "";
        int durationSec;
        boolean playing;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        UiLog.append("服务已创建");
        mPublisher = PublisherHolder.get(this);
        mPublisher.addStateListener(mPublisherListener);
        applySettings();
    }

    @Override
    public void onDestroy() {
        UiLog.append("服务已销毁");
        mRunning = false;
        mHandler.removeCallbacksAndMessages(null);
        detachAll();
        if (mPublisher != null) {
            mPublisher.removeStateListener(mPublisherListener);
            mPublisher = null;
        }
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        UiLog.append("通知使用权已就绪");
        mRunning = true;
        applySettings();
        scanNotifications();
        rescan();
        // 必须在扫描之后再连仪表：先知道谁在播，连上就能立刻把音源和曲目顶上去
        if (mPublisher != null && !mPublisher.isConnected()) {
            mPublisher.connect(this);
        }
        mHandler.removeCallbacks(mRescanTask);
        mHandler.postDelayed(mRescanTask, RESCAN_INTERVAL_MS);
        mHandler.removeCallbacks(mProgressTask);
        mHandler.postDelayed(mProgressTask, PROGRESS_INTERVAL_MS);
    }

    @Override
    public void onListenerDisconnected() {
        UiLog.append("通知使用权已断开");
        mRunning = false;
        mHandler.removeCallbacksAndMessages(null);
        detachAll();
        // 注意：这里不释放音源。系统会周期性地重绑监听器，一放一抢会让仪表闪。
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (!isTarget(pkg)) {
            return;
        }
        // 通知是第二采集通道：先试 token，再退回通知文本
        handleNotification(sbn);
        // 有通知但没拿到会话时，立刻触发一次会话扫描
        if (!mControllers.containsKey(pkg) && mRunning) {
            rescan();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (!isTarget(pkg) || mControllers.containsKey(pkg)) {
            return;
        }
        // 通知消失而会话又没接上，基本等于播放结束
        TrackState st = mStates.get(pkg);
        if (st != null && st.playing) {
            st.playing = false;
            if (mPublisher != null) {
                mPublisher.reportPlayStatus(ClusterMediaPublisher.PLAY_STATUS_PAUSED);
            }
            UiLog.append("播放通知已消失: " + pkg);
        }
    }

    // ---------------------------------------------------------------- settings

    private void applySettings() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int sourceType = sp.getInt(KEY_SOURCE_TYPE, ClusterMediaPublisher.DEFAULT_SOURCE_TYPE);
        if (mPublisher != null) {
            mPublisher.setSourceType(sourceType);
        }
        UiLog.append("sourceType = " + sourceType + "，目标 = " + readTargets(this));
    }

    public static List<String> readTargets(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> out = splitPackages(sp.getString(KEY_PACKAGES, DEFAULT_PACKAGES));

        // 存的正好是某个历史默认值 → 说明用户没自定义过，直接整体升级
        String normalized = TextUtils.join("\n", out);
        for (String legacy : LEGACY_DEFAULTS) {
            if (normalized.equals(legacy)) {
                sp.edit().putString(KEY_PACKAGES, DEFAULT_PACKAGES).apply();
                return splitPackages(DEFAULT_PACKAGES);
            }
        }
        return out;
    }

    private static List<String> splitPackages(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 目标包名匹配。支持通配符 *（接管所有有媒体会话的 App，但不会接管自己）。 */
    private boolean isTarget(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) {
            return false;
        }
        List<String> targets = readTargets(this);
        return targets.contains("*") || targets.contains(pkg);
    }

    // ---------------------------------------------------------------- sessions

    private final Runnable mRescanTask = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) {
                return;
            }
            rescan();
            mHandler.postDelayed(this, RESCAN_INTERVAL_MS);
        }
    };

    private final Runnable mProgressTask = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) {
                return;
            }
            tickProgress();
            mHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    private void rescan() {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (msm == null) {
                UiLog.append("MediaSessionManager 为空");
                return;
            }
            ComponentName self = new ComponentName(this, ClusterBridgeService.class);
            List<MediaController> list = msm.getActiveSessions(self);
            if (list == null) {
                list = new ArrayList<>();
            }

            StringBuilder sb = new StringBuilder();
            for (MediaController c : list) {
                String pkg = c.getPackageName();
                PlaybackState ps = c.getPlaybackState();
                sb.append(pkg).append('(').append(stateName(ps)).append(')');
                MediaMetadata md = c.getMetadata();
                String t = md == null ? "" : text(md, MediaMetadata.METADATA_KEY_TITLE);
                if (!t.isEmpty()) {
                    sb.append('"').append(t).append('"');
                }
                sb.append("  ");

                if (!isTarget(pkg) || mControllers.containsKey(pkg)) {
                    continue;
                }
                attach(c, pkg, false);
            }

            String dump = sb.toString().trim();
            if (!dump.equals(mLastSessionDump)) {
                mLastSessionDump = dump;
                UiLog.append("可见会话 " + list.size() + " 个: " + (dump.isEmpty() ? "无" : dump));
            }
        } catch (SecurityException e) {
            UiLog.append("无通知使用权，无法读取媒体会话: " + e.getMessage());
        } catch (Throwable t) {
            UiLog.append("扫描媒体会话失败: " + t);
        }
    }

    /** 已存在的通知也扫一遍：服务启动时 QQ 音乐可能已经在播了。 */
    private void scanNotifications() {
        StatusBarNotification[] arr;
        try {
            arr = getActiveNotifications();
        } catch (Throwable t) {
            return;
        }
        if (arr == null) {
            return;
        }
        for (StatusBarNotification sbn : arr) {
            if (sbn != null && isTarget(sbn.getPackageName())) {
                handleNotification(sbn);
            }
        }
    }

    /**
     * 从通知里取媒体会话。
     * 通知里的 token 是"捷径"：不用等系统把会话列进 getActiveSessions，也不受激活状态影响。
     */
    private void handleNotification(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) {
            return;
        }
        Bundle ex = n.extras;

        Object rawToken = null;
        try {
            rawToken = ex.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        } catch (Throwable ignored) {
        }
        boolean isMediaNotification = rawToken instanceof MediaSession.Token
                || Notification.CATEGORY_TRANSPORT.equals(n.category)
                || ex.containsKey(Notification.EXTRA_MEDIA_SESSION);
        if (!isMediaNotification) {
            return;
        }

        // 1) 有 token 且还没接管 → 直接建控制器，拿到完整元数据与播放状态
        if (rawToken instanceof MediaSession.Token && !mControllers.containsKey(pkg)) {
            try {
                MediaController c = new MediaController(this, (MediaSession.Token) rawToken);
                attach(c, pkg, true);
                UiLog.append("通过通知接管会话: " + pkg);
            } catch (Throwable t) {
                UiLog.append("从通知建立控制器失败 " + pkg + ": " + t);
            }
        }

        // 2) 没有会话可用（或会话不给元数据）→ 用通知文本兜底
        TrackState st = stateOf(pkg);
        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        String sub = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (title.isEmpty()) {
            return;
        }
        // 已经有会话且给了元数据，就不用通知文本覆盖它
        if (mControllers.containsKey(pkg) && !st.title.isEmpty()) {
            return;
        }
        // 纯通知模式：按标题/正文判断是否换歌（同一首歌的进度刷新会被这里挡掉）
        if (title.equals(st.title) && text.equals(st.artist) && sub.equals(st.album)) {
            return;
        }
        st.title = title;
        st.artist = text;
        st.album = sub;
        int progress = ex.getInt(Notification.EXTRA_PROGRESS, -1);
        int max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, -1);
        if (max > 0) {
            st.durationSec = max;
        }
        UiLog.append("通知文本: " + title + " / " + text + (sub.isEmpty() ? "" : " / " + sub));
        if (mPublisher != null) {
            mPublisher.ensureSource();
            mPublisher.reportTrackInfo(title, sub, text, 0);
            if (max > 0 && progress >= 0) {
                mPublisher.reportPlayTime(progress, max);
            }
        }
    }

    private void attach(MediaController controller, String pkg, boolean fromNotification) {
        MediaController.Callback cb = new CallbackImpl(pkg);
        try {
            controller.registerCallback(cb, mHandler);
        } catch (Throwable t) {
            UiLog.append("注册回调失败 " + pkg + ": " + t);
            return;
        }
        mControllers.put(pkg, controller);
        mCallbacks.put(pkg, cb);
        mStates.put(pkg, new TrackState());
        if (fromNotification) {
            mFromNotification.add(pkg);
        }
        UiLog.append("已接管媒体会话: " + pkg + (fromNotification ? "（来源：通知 token）" : "（来源：会话列表）"));

        // 立即把当前状态推一次，避免等下一次变化
        MediaMetadata md = controller.getMetadata();
        if (md != null) {
            onMetadata(pkg, md);
        }
        PlaybackState ps = controller.getPlaybackState();
        if (ps != null) {
            onPlaybackState(pkg, ps);
        }
        // 会话在播但元数据还没到，先占住音源
        if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING && mPublisher != null) {
            mPublisher.ensureSource();
        }
    }

    private void detachAll() {
        for (Map.Entry<String, MediaController> e : mControllers.entrySet()) {
            MediaController.Callback cb = mCallbacks.get(e.getKey());
            if (cb != null) {
                try {
                    e.getValue().unregisterCallback(cb);
                } catch (Throwable ignored) {
                }
            }
        }
        mControllers.clear();
        mCallbacks.clear();
        mStates.clear();
        mFromNotification.clear();
        mLastSessionDump = "";
    }

    // ---------------------------------------------------------------- callbacks

    private final class CallbackImpl extends MediaController.Callback {
        private final String pkg;

        CallbackImpl(String pkg) {
            this.pkg = pkg;
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (metadata != null) {
                onMetadata(pkg, metadata);
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null) {
                onPlaybackState(pkg, state);
            }
        }

        @Override
        public void onSessionDestroyed() {
            UiLog.append("会话已销毁: " + pkg);
            mControllers.remove(pkg);
            mCallbacks.remove(pkg);
            mStates.remove(pkg);
            mFromNotification.remove(pkg);
            mLastSessionDump = "";
        }
    }

    private void onMetadata(String pkg, MediaMetadata md) {
        TrackState st = stateOf(pkg);
        String title = text(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST);
        String album = text(md, MediaMetadata.METADATA_KEY_ALBUM);
        long durMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);

        boolean changed = !title.equals(st.title)
                || !artist.equals(st.artist)
                || !album.equals(st.album);

        st.title = title;
        st.artist = artist;
        st.album = album;
        if (durMs > 0) {
            st.durationSec = (int) (durMs / 1000);
        }

        if (!changed) {
            return;
        }
        if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) {
            return;
        }

        UiLog.append("元数据: " + title + " / " + artist + " / " + album);
        if (mPublisher != null) {
            mPublisher.ensureSource();
            mPublisher.reportTrackInfo(title, album, artist, 0);
            if (st.durationSec > 0) {
                mPublisher.reportPlayTime(0, st.durationSec);
            }
        }
    }

    private void onPlaybackState(String pkg, PlaybackState ps) {
        TrackState st = stateOf(pkg);
        int state = ps.getState();
        boolean playing = state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING;

        if (playing != st.playing) {
            st.playing = playing;
            if (mPublisher != null) {
                if (playing) {
                    mPublisher.ensureSource();
                }
                mPublisher.reportPlayStatus(playing
                        ? ClusterMediaPublisher.PLAY_STATUS_PLAYING
                        : ClusterMediaPublisher.PLAY_STATUS_PAUSED);
            }
            UiLog.append("播放状态: " + (playing ? "播放中" : "暂停/停止") + " (" + stateName(ps) + ")");
        }

        if (playing) {
            pushProgress(pkg, ps);
        }
    }

    /** 每秒把当前进度推给仪表，让进度条动起来。 */
    private void tickProgress() {
        for (Map.Entry<String, MediaController> e : mControllers.entrySet()) {
            TrackState st = mStates.get(e.getKey());
            if (st == null || !st.playing) {
                continue;
            }
            PlaybackState ps = e.getValue().getPlaybackState();
            if (ps != null) {
                pushProgress(e.getKey(), ps);
            }
        }
    }

    private void pushProgress(String pkg, PlaybackState ps) {
        TrackState st = stateOf(pkg);
        int elapsed = (int) (ps.getPosition() / 1000);
        if (elapsed < 0) {
            elapsed = 0;
        }
        if (mPublisher != null) {
            mPublisher.reportPlayTime(elapsed, st.durationSec);
        }
    }

    private TrackState stateOf(String pkg) {
        TrackState st = mStates.get(pkg);
        if (st == null) {
            st = new TrackState();
            mStates.put(pkg, st);
        }
        return st;
    }

    private static String text(MediaMetadata md, String key) {
        CharSequence cs = md.getText(key);
        return cs == null ? "" : cs.toString();
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private static String stateName(PlaybackState ps) {
        if (ps == null) {
            return "无状态";
        }
        switch (ps.getState()) {
            case PlaybackState.STATE_NONE:
                return "NONE";
            case PlaybackState.STATE_STOPPED:
                return "STOPPED";
            case PlaybackState.STATE_PAUSED:
                return "PAUSED";
            case PlaybackState.STATE_PLAYING:
                return "PLAYING";
            case PlaybackState.STATE_FAST_FORWARDING:
                return "FF";
            case PlaybackState.STATE_REWINDING:
                return "REW";
            case PlaybackState.STATE_BUFFERING:
                return "BUFFERING";
            case PlaybackState.STATE_ERROR:
                return "ERROR";
            case PlaybackState.STATE_CONNECTING:
                return "CONNECTING";
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                return "PREV";
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
                return "NEXT";
            default:
                return String.valueOf(ps.getState());
        }
    }
}
