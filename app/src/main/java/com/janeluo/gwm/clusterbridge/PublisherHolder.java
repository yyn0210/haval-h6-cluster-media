package com.janeluo.gwm.clusterbridge;

import android.content.Context;

/**
 * 进程内唯一的仪表发布器。
 *
 * 为什么必须是单例：MultiDisplay 的「当前音源」是全局唯一的槽位，
 * 如果界面和通知监听服务各建一个连接，两者会互相抢音源
 * （表现为日志里 action=2 / action=1 反复横跳，仪表内容闪烁）。
 */
public final class PublisherHolder {

    private static ClusterMediaPublisher sInstance;

    private PublisherHolder() {
    }

    /** 取单例并确保已发起连接。 */
    public static synchronized ClusterMediaPublisher get(Context context) {
        if (sInstance == null) {
            sInstance = new ClusterMediaPublisher();
            sInstance.connect(context.getApplicationContext());
        }
        return sInstance;
    }

    /** 只取不创建（可能为 null）。 */
    public static synchronized ClusterMediaPublisher peek() {
        return sInstance;
    }
}
