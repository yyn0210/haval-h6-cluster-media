package com.janeluo.gwm.clusterbridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import aptiv.multidisplay.AptivMultiDisplayManager;
import aptiv.multidisplay.MultiDisplayDataType;
import aptiv.multidisplay.MultiDisplayInterface;
import aptiv.multidisplay.MultiDisplayPropertyIds;

/**
 * 把媒体信息投送到仪表盘的统一出口。
 *
 * 链路：
 *   本类 → AptivMultiDisplayManager.notifyValue(propertyId, dataType)
 *        → (AIDL) AptivMultiDisplayService (system uid)
 *        → CarPropertyManager.setProperty() → VHAL → QNX CAN 网关 → 仪表
 *
 * 说明：
 * - aptiv.core 在车机 BOOTCLASSPATH 中，类由 boot classloader 加载，无需 uses-library。
 * - 真正写 VHAL 的是 system uid 的 AptivMultiDisplayService，所以本 App 不需要
 *   CAR_VENDOR_EXTENSION 权限。
 */
public class ClusterMediaPublisher {

    public static final String TAG = "ClusterPublisher";

    public interface StateListener {
        void onStateChanged(boolean connected, String detail);
    }

    /** 音源类型：爱趣听实测用 14。仪表按此决定显示样式。 */
    public static final int DEFAULT_SOURCE_TYPE = 14;

    /** 播放状态：1=播放 2=暂停 -1=无效（爱趣听实测值） */
    public static final int PLAY_STATUS_INVALID = -1;
    public static final int PLAY_STATUS_PLAYING = 1;
    public static final int PLAY_STATUS_PAUSED = 2;

    /** 音源动作：1=请求 2=放弃 */
    private static final int SOURCE_ACTION_REQUEST = 1;
    private static final int SOURCE_ACTION_ABANDON = 2;

    private static final int CONNECT_RETRY_DELAY_MS = 2000;
    private static final int CONNECT_MAX_RETRY = 15;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();

    private MultiDisplayInterface mInterface;
    private AptivMultiDisplayManager mManager;
    private final List<StateListener> mListeners = new CopyOnWriteArrayList<>();
    private boolean mConnected;
    private boolean mConnecting;
    private int mRetry;
    private int mSourceType = DEFAULT_SOURCE_TYPE;
    private volatile boolean mReleased;
    /** 上次成功上报音源请求的时间，用于节流（同一首歌不必反复抢音源）。 */
    private long mLastSourceRequestAt;

    /** 连接状态变化回调（主线程）。支持多个订阅者。 */
    public void addStateListener(StateListener l) {
        if (l != null && !mListeners.contains(l)) {
            mListeners.add(l);
        }
    }

    public void removeStateListener(StateListener l) {
        mListeners.remove(l);
    }

    public boolean isConnected() {
        return mConnected;
    }

    public void setSourceType(int sourceType) {
        mSourceType = sourceType;
    }

    public int getSourceType() {
        return mSourceType;
    }

    /** 连接 MultiDisplay 服务，失败自动重试。重复调用是安全的。 */
    public void connect(Context context) {
        if (!mReleased && (mConnected || mConnecting)) {
            return;
        }
        final Context app = context.getApplicationContext();
        mReleased = false;
        mRetry = 0;
        doConnect(app);
    }

    private void doConnect(final Context app) {
        if (mReleased) {
            return;
        }
        mConnecting = true;
        try {
            MultiDisplayInterface iface = new MultiDisplayInterface(app,
                    new MultiDisplayInterface.ConnectionCallback() {
                        @Override
                        public void onConncted() {
                            // 原厂 API 拼写就是 onConncted
                            synchronized (mLock) {
                                mManager = mInterface == null ? null : mInterface.getManager();
                            }
                            mRetry = 0;
                            mConnecting = false;
                            setConnected(mManager != null);
                            if (mManager != null) {
                                log("MultiDisplay 已连接");
                                reportCurrentSource(SOURCE_ACTION_REQUEST);
                            } else {
                                log("连接成功但 manager 为空，重试");
                                scheduleRetry(app);
                            }
                        }

                        @Override
                        public void onDisconnect() {
                            synchronized (mLock) {
                                mManager = null;
                                mInterface = null;
                            }
                            setConnected(false);
                            log("MultiDisplay 断开，准备重连");
                            // mConnecting 保持 true：重试期间不再允许第二次连接
                            scheduleRetry(app);
                        }
                    });
            synchronized (mLock) {
                mInterface = iface;
            }
            iface.connect();
            log("正在连接 MultiDisplay...");
        } catch (Throwable t) {
            log("连接异常: " + t);
            scheduleRetry(app);
        }
    }

    private void scheduleRetry(final Context app) {
        if (mReleased) {
            return;
        }
        if (mRetry >= CONNECT_MAX_RETRY) {
            log("重试次数已达上限，停止重连（可手动点击重连）");
            mConnecting = false;
            setConnected(false);
            return;
        }
        mRetry++;
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                doConnect(app);
            }
        }, CONNECT_RETRY_DELAY_MS);
    }

    private void setConnected(boolean connected) {
        mConnected = connected;
        final boolean c = connected;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                for (StateListener l : mListeners) {
                    l.onStateChanged(c, c ? "已连接" : "未连接");
                }
            }
        });
    }

    /** 释放仪表上的音源占用。 */
    public void release() {
        if (isConnected()) {
            reportCurrentSource(SOURCE_ACTION_ABANDON);
        }
    }

    /** 停止使用（服务销毁时调用）。 */
    public void shutdown() {
        mReleased = true;
        release();
        mConnected = false;
        mConnecting = false;
        mManager = null;
        mInterface = null;
        mMain.removeCallbacksAndMessages(null);
    }

    /** 声明/释放当前音源。开始播放前必须调用 REQUEST，否则仪表不知道谁在播。 */
    public void reportCurrentSource(int sourceAction) {
        AptivMultiDisplayManager mgr = manager();
        if (mgr == null) {
            return;
        }
        try {
            synchronized (mLock) {
                MultiDisplayDataType.InfotainmentCurrentSource src =
                        new MultiDisplayDataType.InfotainmentCurrentSource();
                src.setCurrentSource(mSourceType);
                src.setSourceAction(sourceAction);
                mgr.notifyValue(MultiDisplayPropertyIds.CURRENT_SOURCE, src);
            }
            if (sourceAction == SOURCE_ACTION_REQUEST) {
                mLastSourceRequestAt = SystemClock.elapsedRealtime();
            }
            log("音源动作已上报: action=" + sourceAction);
        } catch (Throwable t) {
            log("上报音源失败: " + t);
        }
    }

    /**
     * 确保音源还在自己手里。
     * 仪表只有一个"当前音源"槽位，别的 App 起来播会把它抢走；这里在切歌/开播时补一次请求，
     * 15 秒内不重复上报，避免刷屏。
     */
    public void ensureSource() {
        if (manager() == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mLastSourceRequestAt < 15000) {
            return;
        }
        reportCurrentSource(SOURCE_ACTION_REQUEST);
    }

    /** 切歌时调用：歌名 / 专辑 / 歌手。 */
    public void reportTrackInfo(String song, String album, String artist, int albumFileId) {
        AptivMultiDisplayManager mgr = manager();
        if (mgr == null) {
            return;
        }
        try {
            synchronized (mLock) {
                MultiDisplayDataType.MediaTrackInfo info =
                        new MultiDisplayDataType.MediaTrackInfo();
                info.setSourceType(mSourceType);
                info.setSongName(nullToEmpty(song));
                info.setAlbumName(nullToEmpty(album));
                info.setArtistName(nullToEmpty(artist));
                info.setAlbumFileID(albumFileId);
                mgr.notifyValue(MultiDisplayPropertyIds.MEDIA_TRACK_INFO, info);
            }
            log("曲目已上报: " + song + " - " + artist + " 《" + album + "》");
        } catch (Throwable t) {
            log("上报曲目失败: " + t);
        }
    }

    /** 播放状态变化。 */
    public void reportPlayStatus(int playStatus) {
        AptivMultiDisplayManager mgr = manager();
        if (mgr == null) {
            return;
        }
        try {
            synchronized (mLock) {
                MultiDisplayDataType.MediaPlayStatus st =
                        new MultiDisplayDataType.MediaPlayStatus();
                st.setSourceType(mSourceType);
                st.setPlayStatus(playStatus);
                mgr.notifyValue(MultiDisplayPropertyIds.MEDIA_PLAY_STATUS, st);
            }
            log("播放状态已上报: " + playStatus);
        } catch (Throwable t) {
            log("上报播放状态失败: " + t);
        }
    }

    /** 播放进度（秒）。 */
    public void reportPlayTime(int elapsedSec, int totalSec) {
        AptivMultiDisplayManager mgr = manager();
        if (mgr == null) {
            return;
        }
        try {
            synchronized (mLock) {
                MultiDisplayDataType.MediaPlayTime t =
                        new MultiDisplayDataType.MediaPlayTime();
                t.setSourceType(mSourceType);
                t.setPlayTime(elapsedSec);
                t.setTotalTime(totalSec);
                mgr.notifyValue(MultiDisplayPropertyIds.MEDIA_PLAY_TIME, t);
            }
        } catch (Throwable t) {
            log("上报进度失败: " + t);
        }
    }

    /** 设备可播状态。 */
    public void reportDeviceStatus(int sourceType, boolean playable) {
        AptivMultiDisplayManager mgr = manager();
        if (mgr == null) {
            return;
        }
        try {
            synchronized (mLock) {
                MultiDisplayDataType.MediaDeviceStatus st =
                        new MultiDisplayDataType.MediaDeviceStatus();
                st.setSourceType(sourceType);
                st.setDeviceStatus(playable ? 1 : 0);
                mgr.notifyValue(MultiDisplayPropertyIds.MEDIA_DEVICE_STATUS, st);
            }
        } catch (Throwable t) {
            log("上报设备状态失败: " + t);
        }
    }

    /** 专辑封面。fileId 需与 reportTrackInfo 的 albumFileId 一致。 */
    public void reportAlbumImage(int albumFileId, InputStream in) {
        AptivMultiDisplayManager mgr = manager();
        if (mgr == null || in == null) {
            return;
        }
        try {
            synchronized (mLock) {
                mgr.transferFile(mSourceType, albumFileId, in);
            }
            log("封面已传输: fileId=" + albumFileId);
        } catch (Throwable t) {
            log("传输封面失败: " + t);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private AptivMultiDisplayManager manager() {
        synchronized (mLock) {
            if (!mConnected || mManager == null) {
                return null;
            }
            return mManager;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static void log(String msg) {
        Log.i(TAG, msg);
        UiLog.append("[Publisher] " + msg);
    }
}
