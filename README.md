# Predestination TV Exo

[简体中文](README.zh.md)

Predestination TV Exo grew out of **Predestination** and now lives in its own repository.
Built on Media3 ExoPlayer, it provides **DLNA / UPnP casting reception** for Android TVs,
TV boxes, tablets, and phones, with an admin interface, Wall Page, and screen-off support on select TVs.

It plays incoming cast media and returns to the waiting screen when playback ends.
When idle, it can display a web dashboard or turn off the screen on supported TVs.

## Main Features

### DLNA / UPnP Casting Reception

- Receive audio and video from casting clients on the same local network, with play, pause, stop, seek, and volume controls.
- Support HTTP(S), HLS, and RTSP. Media compatibility depends on the codecs, device decoding capabilities, and stream format.
- Support JPEG and PNG image casting, as well as standard M3U playlists, track selection, and previous/next controls.
- Offer audio track and subtitle selection, overall audio gain, per-channel gain, and dynamic range compression.
- Open audio and video files through Android's "Open with" or sharing options.

### Admin Interface

Visit `/admin` at the address shown on the waiting screen to:

- View basic device information, available storage, and runtime logs.
- Choose the interface language.
- Configure maximum buffer memory, buffer duration, and Wall Page.
- Update the app remotely.

### Wall Page

Wall Page displays web dashboards, clocks, or other information pages while the device is idle.

## Getting Started

1. Install and open **Predestination TV Exo** on the receiving device.
2. Connect the sending device to the same local network and select the receiver in an app that supports DLNA / UPnP.
3. To configure the receiver, open the `/admin` address shown on its waiting screen in a browser and approve access on the receiving device.

## Building and Checking

Use a JDK compatible with the current Android Gradle Plugin and Android SDK 36.
Configure `ANDROID_HOME` or a local `local.properties` file, then run these commands from the repository root:

```powershell
# ARMv7, for some TVs and TV boxes
.\gradlew.bat assembleDebug -PtargetAbi=armeabi-v7a

# ARM64, for phones, tablets, and TVs that support this architecture
.\gradlew.bat assembleDebug -PtargetAbi=arm64-v8a

# x86 / x86_64
.\gradlew.bat assembleDebug -PtargetAbi=x86
.\gradlew.bat assembleDebug -PtargetAbi=x86_64

# Unit tests and static analysis
.\gradlew.bat testDebugUnitTest lintDebug
```
