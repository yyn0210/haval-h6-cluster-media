package aptiv.multidisplay;

/**
 * 编译期桩（stub）。真实实现由车机 boot classpath 提供。
 *
 * 所有内部类的 setter 都有 int 与枚举两种重载（原厂 API 如此）。
 * 这里只声明本项目用到的 int 版本，减少维护成本。
 *
 * propertyId ↔ 类型 对照见 MultiDisplayPropertyIds。
 */
public class MultiDisplayDataType {

    /** propertyId = 1，当前音源 */
    public static class InfotainmentCurrentSource {
        public void setCurrentSource(int source) {
        }

        public void setSourceAction(int action) {
        }
    }

    /** propertyId = 3，音源打断 */
    public static class InfotainmentInterruptSource {
        public void setInterruptSource(int source) {
        }

        public void setSourceAction(int action) {
        }
    }

    /** propertyId = 16，媒体设备状态 */
    public static class MediaDeviceStatus {
        public void setSourceType(int sourceType) {
        }

        public void setDeviceStatus(int status) {
        }
    }

    /** propertyId = 17，播放状态 */
    public static class MediaPlayStatus {
        public void setSourceType(int sourceType) {
        }

        public void setPlayStatus(int playStatus) {
        }
    }

    /** propertyId = 18，曲目信息 */
    public static class MediaTrackInfo {
        public void setSourceType(int sourceType) {
        }

        public void setSongName(String songName) {
        }

        public void setAlbumName(String albumName) {
        }

        public void setArtistName(String artistName) {
        }

        public void setAlbumFileID(int albumFileId) {
        }
    }

    /** propertyId = 19，播放进度 */
    public static class MediaPlayTime {
        public void setSourceType(int sourceType) {
        }

        public void setPlayTime(int elapsedSec) {
        }

        public void setTotalTime(int totalSec) {
        }
    }

    /** propertyId = 20，播放模式 */
    public static class MediaPlayingMode {
        public void setSourceType(int sourceType) {
        }

        public void setRepeatMode(int repeatMode) {
        }

        public void setShuffleMode(int shuffleMode) {
        }
    }
}
