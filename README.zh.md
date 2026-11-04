# Predestination TV Exo

Predestination TV Exo 是从 **Predestination** 孵化出来的子项目，现已拆分为独立仓库。
它基于 Media3 ExoPlayer，为 Android 电视、电视盒子、平板和手机提供 **DLNA / UPnP 投屏接收**，
并围绕设备的空闲状态增加了管理界面、Wall Page 和部分电视的息屏支持。

有人投屏时播放内容，播放结束后回到等待页；空闲时可以展示网页看板，或在支持的电视上息屏。

## 主要功能

### DLNA / UPnP 投屏接收

- 接收同一局域网内投屏客户端发送的音视频，支持播放、暂停、停止、定位及音量控制。
- 支持 HTTP(S)、HLS 和 RTSP；具体媒体兼容性取决于编码、设备解码能力和流的格式。
- 支持 JPEG、PNG 图片投屏，以及普通 M3U 播放清单、曲目选择和上一首／下一首。
- 提供音轨、字幕选择，以及总音量增益、分声道增益和动态压缩。
- 支持通过 Android 的“打开方式”或分享入口打开音视频文件。

### 管理界面

通过等待页显示的地址访问 `/admin`，可在浏览器中：

- 查看设备基本信息、可用存储空间和运行日志。
- 设置界面语言。
- 设置最大缓冲内存、缓冲时长和 Wall Page。
- 进行app远程更新。

### Wall Page

Wall Page 用于在空闲时展示网页看板、时钟或其他信息页面。

## 使用方式

1. 在接收设备上安装并打开 **Predestination TV Exo**。
2. 将发送设备连接到同一局域网，在支持 DLNA / UPnP 的应用中选择接收设备。
3. 如需配置，在浏览器打开等待页显示的 `/admin` 地址，并在接收设备上确认授权。

## 构建和检查

使用兼容当前 Android Gradle Plugin 的 JDK 和 Android SDK 36，并配置 `ANDROID_HOME`
或本地 `local.properties`。在仓库根目录运行：

```powershell
# ARMv7，适用于部分电视和盒子
.\gradlew.bat assembleDebug -PtargetAbi=armeabi-v7a

# ARM64，适用于支持该架构的手机、平板和电视
.\gradlew.bat assembleDebug -PtargetAbi=arm64-v8a

# 单元测试和静态检查
.\gradlew.bat testDebugUnitTest lintDebug
```
