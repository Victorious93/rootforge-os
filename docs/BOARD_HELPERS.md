# ESP32 and Raspberry Pi helpers (owner unlock / flash only)

RootForge can use a **Raspberry Pi** or an **ESP32** while you unlock the
bootloader or flash a phone **you own**. The safety gates do not change:
dry-run is the default, a typed phrase still gates writes, a recovery write
still backs up first, and nothing reboots by itself.

This is **not** a theft or lock-screen kit.

## Refused on ethics grounds

RootForge will **not** implement, document as a how-to, or ship firmware for:

- Screen-lock bypass (PIN, pattern, password, fingerprint UI)
- Factory Reset Protection (FRP) / Google account lock bypass
- HID or serial injection that types credentials or dismisses an auth screen
- Any helper whose purpose is to use a phone the operator does not own

If a spec, community guide page, or Ask question asks for those, the answer
is no. Use the maker's official account-recovery flow if you are locked out.
Volume-key confirm during `fastboot flashing unlock` is done **by you on the
phone**. Code: `:core` `BoardHelpers` (`REFUSED`), Ask topic
`frp-screen-lock-refused`.

## Raspberry Pi = Linux host (`:desktop`)

A Pi running 64-bit Raspberry Pi OS is a Linux computer. It reuses
`:desktop` `HostController` / `HostToolset`: host `adb` and `fastboot`
(`adb -s` / `fastboot -s`), the same Devices identify / Root plan / wizard,
and the same confirms.

1. Install Raspberry Pi OS 64-bit (official docs). Enable USB host; use a
   data cable.
2. `sudo apt install adb fastboot` (or Google platform-tools on `PATH`).
3. Install JDK 17 and run the desktop client from this repo:

   ```bash
   cd android-app
   ./gradlew :desktop:run
   ```

   CI `.deb` packages are amd64. On a Pi, run from source (or the Python
   CLI). `/proc/device-tree/model` matching `Raspberry Pi` sets the Devices
   caption to **this Raspberry Pi**.
4. Open **Devices**. Identify, then Unlock / flash with dry-run on. Type the
   phrase. Do not skip OEM unlocking on the phone.

Official pages (in-app **Links → ESP32 & Pi helpers**):
[Raspberry Pi documentation](https://github.com/raspberrypi/documentation)
(HTTP 200, 2026-10-20; `raspberrypi.com` documentation returned Cloudflare
403 from this agent),
[product information](https://pip.raspberrypi.com/),
[SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools).

`rpi_fleet_tools.sh` still manages **Pis on a LAN**. It is not an Android
FRP tool.

## ESP32 = USB-serial bridge

An ESP32-S3 can expose Espressif's **USB Serial/JTAG** CDC device so a Pi
or PC has a serial port. Use that for a debug UART you already wired, or to
watch the ESP32 itself.

RootForge does **not** ship HID firmware and will not type volume keys, PIN
digits, or anything else. Button confirm during unlock stays on the phone.

Official pages: [USB Serial/JTAG console](https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/api-guides/usb-serial-jtag-console.html),
[ESP32-S3](https://www.espressif.com/en/products/socs/esp32-s3),
[ESP-IDF get started](https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/get-started/index.html)
(all HTTP 200 on espressif.com, 2026-10-20).

`esp32_toolkit.sh` flashes **ESP32 firmware** (the board). It is not an
Android lock bypass.

## Timing fastboot commands

After **Reboot → Bootloader**, wait until Devices (or `fastboot devices`)
shows the phone. Then Unlock. Do not race `adb reboot bootloader` into
`fastboot flashing unlock`. Dry-run first; typed `UNLOCK` still gates the
write.

AOSP: [Bootloader locking and unlocking](https://source.android.com/docs/core/architecture/bootloader/locking_unlocking).

## Status

Documented and unit-tested in `:core` `BoardHelpers`. **Not run on a Pi, an
ESP32, or a phone.**
