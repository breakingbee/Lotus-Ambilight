# Lotus Ambilight

An Android TV prototype that lets a Xiaomi Mi TV Stick capture its screen, sample the dominant RGB colour, and control a Lotus Lantern / BLEDOM LED strip directly over Bluetooth Low Energy.

This first version is deliberately a diagnostic prototype: it tests BLE discovery/control and Android screen capture before we turn it into a polished always-on service.

See the full setup, build, ADB install, and test instructions below.

## Project status

- Direct Bluetooth LE control using Lotus Lantern service `FFF0` and characteristic `FFF3`.
- Screen sampling via Android MediaProjection with an on-screen colour preview.
- Basic colour smoothing and rate-limited RGB updates.
- GitHub Actions debug APK build.

**Important:** Android requires explicit screen-capture approval. Some DRM-protected video apps may return black frames. This prototype is not yet a foreground-service implementation, so keep the app process alive while testing.

See [the project README in the repository](https://github.com/breakingbee/Lotus-Ambilight#readme) for the full instructions.
