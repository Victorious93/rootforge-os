# PitchBlack Recovery (PBRP)

PitchBlack Recovery Project is a TWRP-based custom recovery with extra features and its own theme. People sometimes call it "TWRP Pitch Black". It is a separate project from TWRP, with its own builds per device.

::: warning Check your device first
PBRP is built per device. Official support is a per-device list, and many builds are unofficial community ports (for example the 2020 to 2021 threads for Xiaomi, Samsung and others). An image for a different device or Android version can fail to boot or damage the device. Use the project's own device list, not a search result.
:::

## Where to get it

- The project's site is `pitchblackrecovery.com` (named as its homepage in the project's repository readme). It could not be reached from the sandbox this page was written in, so open it yourself and confirm it is the official site and that your device is listed.
- The Recovery screen in RootForge has a **PitchBlack (PBRP)** button that opens it in your browser. The app has no network access of its own and does not download or bundle a recovery.

## Using it with RootForge OS

1. Read [Custom Recovery Installation Guide](guide:custom-recovery) first. PBRP is installed the same ways as TWRP.
2. Download the build for your exact device, and verify its checksum if the site publishes one. Import it with *Import file*.
3. Put the target in the bootloader. In **Recovery**, choose **Boot once** first. That runs the image without writing it. If it boots, you know the image matches the device.
4. Only then consider **Flash** to a recovery partition. On devices with no recovery partition, or with `vbmeta` or A/B quirks, follow your device guide; the app does not handle `vbmeta` for you.
5. Flashing requires a `<image>.manifest.json` that matches the target, plus a typed confirmation. See [Rooting with RootForge OS](guide:rootforge-workflow).

Related: [Bootloader Unlocking Guide](guide:unlock-bootloader), [Custom ROM installation](guide:custom-rom).
