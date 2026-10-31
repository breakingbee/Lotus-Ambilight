# Lotus Ambilight

An Android TV prototype for the Xiaomi Mi TV Stick 4K. It connects directly to a Lotus Lantern / BLEDOM LED strip over Bluetooth LE, and can sample the screen with Android MediaProjection and send smoothed RGB colours to the strip.

## Current prototype

- Finds common Lotus Lantern advertising names (ELK, BLEDOM, XSL-, CLK-, LED LIGHT STRIP).
- Connects to the strip over BLE.
- Uses Lotus Lantern service `FFF0` and write characteristic `FFF3`.
- Sends the standard 9-byte RGB frame: `7E 07 05 03 RR GG BB 10 EF`.
- Provides manual red, green, blue and white tests.
- Starts Android screen capture after the system consent dialog.
- Samples a reduced-resolution image, smooths the average colour, and updates the strip at a limited rate.

This is a first diagnostic build, not yet a polished background service. Keep the app process alive during the first tests. Android DRM-protected streaming apps may block MediaProjection and produce black frames.

## Build

The GitHub Actions workflow builds a debug APK on every push to `main` and when manually dispatched.

1. Open the repository's **Actions** tab.
2. Select **Build Android APK**.
3. Download the `lotus-ambilight-debug-apk` artifact from a successful run.

Local build requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and Gradle 8.7.

```sh
gradle --no-daemon assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install over ADB

Enable Developer options and USB/network debugging on the Mi Stick. Find its IP address, then from a PC with Android Platform Tools:

```sh
adb connect STICK_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Some Android TV firmware uses a different ADB port or requires confirming the RSA authorization prompt on the TV.

## First test

1. Open Lotus Ambilight on the Mi Stick.
2. Press **Scan for Lotus Lantern** and select the discovered strip.
3. Press **Connect**.
4. Test red, green, blue and white. The strip should change immediately.
5. Press **Start screen sync** and approve Android's screen-capture prompt.
6. Check the preview colour and whether the strip follows it. Then leave the app running and open a video app.

If the scan finds nothing, check that the Lotus Lantern phone app is disconnected and that the strip is powered on and nearby. Only one central colour is sent; ordinary non-addressable RGB strips cannot display independent left/right zones.

## Protocol notes

The BLE service is `0000FFF0-0000-1000-8000-00805F9B34FB`; the write characteristic is `0000FFF3-0000-1000-8000-00805F9B34FB`. Basic colour frames are 9 bytes with header `7E` and terminator `EF`. Some model variants use additional encryption or a different advertising name; this prototype currently sends the standard non-encrypted RGB frame.

## Privacy and limitations

Screen capture is processed locally on the Mi Stick. No frames or screenshots are uploaded. The app does not need OpenWrt, a cloud account, or the Lotus Lantern phone app. Protected streaming apps can return black frames, and the prototype currently has no persistent foreground service; background reliability needs testing.
