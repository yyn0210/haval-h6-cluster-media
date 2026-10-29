package aptiv.multidisplay;

import java.io.InputStream;

/**
 * 编译期桩（stub）。真实实现由车机 boot classpath 提供。
 *
 * 签名来自字节码：
 *   notifyValue:(ILjava/lang/Object;)V
 *   transferFile:(IILjava/io/InputStream;)V
 */
public class AptivMultiDisplayManager {

    /**
     * 上报一条 MultiDisplay 属性。
     *
     * @param propertyId 见 MultiDisplayPropertyIds（1=当前音源, 17=播放状态,
     *                   18=曲目信息, 19=播放进度, 16=设备状态, 20=播放模式）
     * @param value      必须是 MultiDisplayDataType 中与 propertyId 对应的类型，
     *                   否则服务端会报 "valueClass not match" 并丢弃
     */
    public void notifyValue(int propertyId, Object value) {
    }

    /**
     * 传输专辑封面等文件数据。
     *
     * @param sourceType 音源类型（与 MediaTrackInfo.albumFileID 配套）
     * @param fileId     文件 ID，需与 MediaTrackInfo.setAlbumFileID 一致
     */
    public void transferFile(int sourceType, int fileId, InputStream inputStream) {
    }
}
