package aptiv.multidisplay;

import android.content.Context;

/**
 * 编译期桩（stub）。真实实现位于车机
 * /system/framework/aptiv.core.jar（BOOTCLASSPATH，由 boot classloader 加载）。
 *
 * 方法签名全部从 AptivMultiDisplay.apk / ThirdMediaPartyService.apk 的字节码还原，
 * 不要修改名称（注意 onConncted 的拼写错误是原厂 API 的真实拼写）。
 *
 * 本模块只用于 compileOnly，不会打进 APK。
 */
public class MultiDisplayInterface {

    public interface ConnectionCallback {
        /** 注意：原厂 API 就是这个拼写（少一个 e），不要"修正"。 */
        void onConncted();

        void onDisconnect();
    }

    public MultiDisplayInterface(Context context, ConnectionCallback callback) {
    }

    public void connect() {
    }

    public void disconnect() {
    }

    public AptivMultiDisplayManager getManager() {
        return null;
    }
}
