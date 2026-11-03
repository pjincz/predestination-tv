# Predestination TV Exo

独立 Android TV 投屏接收器，使用 Media3 ExoPlayer。包名为
`io.jcz.predestination.tv.exo`，可与原版同时安装。最低 Android 8.0。

打开电视上的 **Predestination TV Exo**，在同一局域网的手机上选择
**Predestination TV Exo · 电视型号**。应用在前台时接收 DLNA 投屏，退到后台时停止接收和播放。

基础版支持单地址 HTTP(S) 音视频（含常见 HLS 地址）、RTSP、播放、暂停、停止、
可定位媒体的跳转，以及投屏端音量和静音。RTSP 使用 Media3 默认缓冲和传输行为，
支持情况取决于流的编码和 SDP。图片投屏、普通 M3U 队列、本地文件分享、音频可视化和
厂商息屏功能留在原版。

## 构建和检查

使用 JDK 17 或更高版本和 Android SDK 36。在本目录运行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。安装后，在目标电视和手机上验证
设备发现、HTTP 播放、暂停和定位、连续换源、RTSP 起播和停止，以及退出后设备从列表消失。
