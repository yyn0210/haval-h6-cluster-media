package aptiv.multidisplay;

/**
 * MultiDisplay 属性 ID 常量。
 *
 * 完整 52 项来自 AptivMultiDisplayService.&lt;clinit&gt; 里 SparseArray 的初始化，
 * 这里只保留媒体/音源相关的部分（分析文档 §4.3）。
 */
public final class MultiDisplayPropertyIds {

    private MultiDisplayPropertyIds() {
    }

    /** InfotainmentCurrentSource —— 声明/释放当前音源 */
    public static final int CURRENT_SOURCE = 1;

    /** InfotainmentInterruptSource —— 音源被打断 */
    public static final int INTERRUPT_SOURCE = 3;

    /** InfotainmentAllData —— 全量状态（音源+打断+媒体） */
    public static final int ALL_DATA = 5;

    /** MediaDeviceStatus —— 媒体设备可播状态 */
    public static final int MEDIA_DEVICE_STATUS = 16;

    /** MediaPlayStatus —— 播放/暂停/停止 */
    public static final int MEDIA_PLAY_STATUS = 17;

    /** MediaTrackInfo —— 歌名/专辑/歌手/封面ID */
    public static final int MEDIA_TRACK_INFO = 18;

    /** MediaPlayTime —— 已播时长/总时长 */
    public static final int MEDIA_PLAY_TIME = 19;

    /** MediaPlayingMode —— 循环/随机 */
    public static final int MEDIA_PLAYING_MODE = 20;
}
