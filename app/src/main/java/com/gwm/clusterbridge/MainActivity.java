package com.gwm.clusterbridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 主界面：
 *  1) 验证仪表通道（发一条测试数据，不需要任何音乐 App）
 *  2) 配置要桥接的目标音乐 App 与 sourceType
 *  3) 一键跳转开启通知使用权
 *  4) 实时日志
 */
public class MainActivity extends Activity {

    private ClusterMediaPublisher mPublisher;
    private TextView mStateText;
    private TextView mLogText;
    private EditText mPackagesEdit;
    private EditText mSourceTypeEdit;

    private final ClusterMediaPublisher.StateListener mStateListener =
            (connected, detail) -> {
                mStateText.setText(connected ? "仪表通道：已连接" : "仪表通道：" + detail);
                mStateText.setTextColor(connected ? 0xFF2E7D32 : 0xFFC62828);
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStateText = findViewById(R.id.state_text);
        mLogText = findViewById(R.id.log_text);
        mPackagesEdit = findViewById(R.id.packages_edit);
        mSourceTypeEdit = findViewById(R.id.sourcetype_edit);

        SharedPreferences sp = getSharedPreferences(ClusterBridgeService.PREFS, Context.MODE_PRIVATE);
        mPackagesEdit.setText(sp.getString(ClusterBridgeService.KEY_PACKAGES,
                ClusterBridgeService.DEFAULT_PACKAGES));
        mSourceTypeEdit.setText(String.valueOf(sp.getInt(ClusterBridgeService.KEY_SOURCE_TYPE,
                ClusterMediaPublisher.DEFAULT_SOURCE_TYPE)));

        UiLog.setListener(line -> runOnUiThread(() -> appendLog(line)));
        mLogText.setText(UiLog.dump());

        mPublisher = PublisherHolder.get(this);
        mPublisher.addStateListener(mStateListener);
        mPublisher.setSourceType(readSourceType());

        findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btn_test).setOnClickListener(v -> sendTestData());
        findViewById(R.id.btn_release).setOnClickListener(v -> {
            mPublisher.release();
            toast("已释放仪表音源占用");
        });
        findViewById(R.id.btn_notif_access).setOnClickListener(v -> openNotificationAccess());
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            UiLog.clear();
            mLogText.setText("");
        });
        findViewById(R.id.btn_probe).setOnClickListener(v -> showProbeInfo());
    }

    @Override
    protected void onDestroy() {
        UiLog.setListener(null);
        if (mPublisher != null) {
            mPublisher.removeStateListener(mStateListener);
            mPublisher = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ actions

    private int readSourceType() {
        try {
            return Integer.parseInt(mSourceTypeEdit.getText().toString().trim());
        } catch (Exception e) {
            return ClusterMediaPublisher.DEFAULT_SOURCE_TYPE;
        }
    }

    private void saveSettings() {
        String pkgs = mPackagesEdit.getText().toString().trim();
        if (TextUtils.isEmpty(pkgs)) {
            pkgs = ClusterBridgeService.DEFAULT_PACKAGES;
            mPackagesEdit.setText(pkgs);
        }
        int st = readSourceType();
        getSharedPreferences(ClusterBridgeService.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ClusterBridgeService.KEY_PACKAGES, pkgs)
                .putInt(ClusterBridgeService.KEY_SOURCE_TYPE, st)
                .apply();
        mPublisher.setSourceType(st);
        UiLog.append("配置已保存：sourceType=" + st + "，目标=" + pkgs.replace("\n", ","));
        toast("已保存。若桥接服务已启用，重新开关一次通知使用权以生效");
    }

    /** 发一条测试曲目，用于验证「第三方 App 能否写进 MultiDisplay」。 */
    private void sendTestData() {
        if (!mPublisher.isConnected()) {
            toast("仪表通道未连接，请稍候或点重连");
            return;
        }
        int st = readSourceType();
        mPublisher.setSourceType(st);
        mPublisher.reportCurrentSource(1);              // REQUEST
        mPublisher.reportPlayStatus(ClusterMediaPublisher.PLAY_STATUS_PLAYING);
        mPublisher.reportTrackInfo("ClusterProbeTest", "ProbeAlbum", "ProbeArtist", 0);
        mPublisher.reportPlayTime(30, 180);
        UiLog.append("测试数据已发送，请查看仪表是否显示 ClusterProbeTest");
        toast("测试数据已发送");
    }

    private void openNotificationAccess() {
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(i);
        } catch (Throwable t) {
            toast("无法打开设置页，请手动进入：设置 → 应用 → 特殊权限 → 通知使用权");
        }
    }

    private void showProbeInfo() {
        boolean enabled = isNotificationAccessEnabled();
        String info = "通知使用权：" + (enabled ? "已开启" : "未开启") + "\n"
                + "本机可见媒体会话：\n"
                + describeSessions() + "\n"
                + "instrument target:\n"
                + "  " + getPackageName() + "/.ClusterBridgeService\n"
                + "adb 开启命令：\n"
                + "  adb shell cmd notification allow_listener "
                + getPackageName() + "/.ClusterBridgeService";
        UiLog.append(info.replace("\n", " | "));
        new android.app.AlertDialog.Builder(this)
                .setTitle("诊断信息")
                .setMessage(info)
                .setPositiveButton("好", null)
                .show();
    }

    /**
     * 列出本机所有"活跃媒体会话"。
     * 这是排查"改了包名还是没反应"的第一现场：只要目标 App 在播，这里就应该能看到它。
     */
    private String describeSessions() {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (msm == null) {
                return "  MediaSessionManager 不可用";
            }
            List<MediaController> list =
                    msm.getActiveSessions(new ComponentName(this, ClusterBridgeService.class));
            if (list == null || list.isEmpty()) {
                return "  0 个（目标 App 没在播，或系统没把它算作活跃会话）";
            }
            StringBuilder sb = new StringBuilder();
            for (MediaController c : list) {
                sb.append("  ").append(c.getPackageName());
                MediaMetadata md = c.getMetadata();
                if (md != null) {
                    CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
                    if (t != null && t.length() > 0) {
                        sb.append("  ").append(t);
                    }
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "  读取失败：" + t;
        }
    }

    private boolean isNotificationAccessEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null) {
            return false;
        }
        String me = new ComponentName(this, ClusterBridgeService.class).flattenToString();
        return flat.contains(me);
    }

    // ------------------------------------------------------------ ui helpers

    private void appendLog(String line) {
        mLogText.append(line);
        mLogText.append("\n");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
