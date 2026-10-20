# RootForge OS — Linux / Windows desktop and Docker toolkit

The Android host-phone APK in `android-app/` is one client of a shared
Kotlin core (`android-app/core`: command schema, flash planner, module
builder, bridge protocol, approvals). This document is the **desktop and
Docker** clients of the same contract.

Status: **compiles / packages in CI. Not run against a real phone.**
Desktop talks to a target through **host** `adb` / `fastboot` / optional
`edl` binaries (detected on `PATH` or `$ANDROID_HOME/platform-tools`). It
does not reimplement the USB wire protocol the Android app uses. The
**Devices** screen is the same Compose UI as the APK: identify every
plugged-in phone, show the Root plan and brand Root wizard, and pin later
actions to that serial (`adb -s` / `fastboot -s`). A wide window shows two
cards. EDL stays identify-only; Samsung Odin is not implemented.

## Linux desktop

Build (JDK 17, Android SDK needed only because `:app` lives in the same
Gradle build):

```bash
cd android-app
./gradlew :core:test :desktop:packageDeb :desktop:packageDistributionForCurrentOS
# .deb:  desktop/build/compose/binaries/main/deb/
# app:   desktop/build/compose/binaries/main/app/   (portable dir)
# GUI:   ./gradlew :desktop:run
# API:   ./gradlew :desktop:run --args='--headless --port 8787'
```

Install the `.deb`, or run the unpacked `app/bin/RootForgeOS` (name may
vary with the packageName). Put `adb`/`fastboot` on `PATH` (Debian:
`apt install adb fastboot`; or Android platform-tools).

udev: members of `plugdev` can usually use adb without root. EDL (Qualcomm
`05c6:9008`) may need an extra udev rule; this repo does not ship one.

## Windows desktop

CI on `windows-latest` builds an `.exe` (and an `.msi` when jpackage/WiX
allows). Artifact names: `rootforge-os-windows-exe` /
`rootforge-os-windows-msi`.

```bat
cd android-app
gradlew :desktop:packageExe :desktop:packageDistributionForCurrentOS
```

### USB drivers (required before adb/fastboot/EDL see the phone)

Windows will not talk to a phone in adb, fastboot or EDL until a driver
binds the USB device. RootForge does **not** bundle drivers.

1. **Google USB Driver** (adb + fastboot for Pixel / AOSP-class devices).
   Install from Android Studio SDK Manager (`extras;google;usb_driver`) or
   download the standalone package from Google's Android developer site.
   Then, in Device Manager, with the phone plugged in (and in the right
   mode), Update driver → Browse → that `usb_driver` folder. Fastboot and
   adb are different USB IDs; you may need to do this once per mode.
2. **Qualcomm 9008 (EDL)**. The Emergency Download device enumerates as
   `VID_05C6&PID_9008`. Windows typically needs Qualcomm's HS-USB QDLoader
   9008 driver (often installed by QFIL / QPST). This project does not
   redistribute it. Without it, Device Manager shows an unknown device and
   `edl` / QFIL cannot open the port.
3. OEM unlock/flash drivers (Samsung, Xiaomi, …) are **out of band** —
   the CLI already refuses to guess on those vendors.

WSL2 USB passthrough (`usbipd-win`) is a different path and **unverified**
here. Prefer the native Windows build plus the drivers above.

The desktop app uses whatever `adb.exe` / `fastboot.exe` it finds
(`PATH`, `%ANDROID_HOME%\platform-tools`, or a `platform-tools` folder
next to the executable). It will not delete `C:\Users\…\.android\adbkey`.

## Docker

See `docker/README.md`. Image: `docker build -f docker/Dockerfile -t rootforge-os-toolkit .`

```bash
docker run --rm -it --privileged --device /dev/bus/usb \
  -v /dev/bus/usb:/dev/bus/usb \
  -p 127.0.0.1:8787:8787 \
  -e ROOTFORGE_API_TOKEN=… \
  rootforge-os-toolkit
```

CI uploads `rootforge-os-toolkit-docker` (`docker load` the `.tar.gz`).
The API is the same bridge JSON as the apps; destructive calls need a
`confirmation` phrase. USB re-enumeration (adb → fastboot → EDL) is why
`--privileged` is documented, not because the API itself needs root.
`rootforge.capabilities.get` reports `node_kind=desktop-host` and
`usb_host=false` (host `adb`/`fastboot`, not the phone USB-Host stack).
The desktop Settings switch gates the GUI executor; `--headless` is
authenticated by the bearer token instead. That token is read from
`ROOTFORGE_API_TOKEN` or from `api_token` in `~/.rootforge/desktop.properties`.
The properties file is created mode `0600` and an existing copy is tightened
to owner read/write before it is loaded. The `~/.rootforge` directory itself
keeps the process umask. Loopback (`127.0.0.1`) is shared by every account
on the machine, so a group- or world-readable token is enough for another
local user to call unlock or flash with the confirmation phrase.

## Shared core

| Piece | Where |
|---|---|
| Flash planner, module zip, terminal parser, approvals, bridge ops | `android-app/core` |
| Compose screens (Dashboard, **Devices**, Flash Tools … Settings) | `android-app/app/.../ui`, compiled into both APK and desktop. **Devices** identify/plan/wizard logic is `:core`; desktop talks to host `adb`/`fastboot` (`HostController`). Wide windows show two phone cards. Flash uses the same sidecar device-match gate as the phone app. Modules → This phone (su install on the host) is Android-only; desktop keeps USB-target install. |
| Android USB ADB/fastboot/Sahara | `android-app/app` only |
| Host `adb`/`fastboot`/`edl` | `android-app/desktop` |
| Python headless API | `docker/api/rootforge_api.py` (same operation names) |

Writes still print a plan and require the typed phrase. There is no
setting that skips that.

## License

`android-app/core` (`:core`) is Apache-2.0. The desktop client, Docker
toolkit, CLI, and ISO tree are GPL-3.0-or-later. Third-party packages
and build-time downloads keep their upstream licenses. See `LICENSE`,
`android-app/core/LICENSE`, and `NOTICE`.
