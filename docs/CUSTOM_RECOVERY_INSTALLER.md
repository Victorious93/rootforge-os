# Custom Recovery installer — rooted phones, official TWRP / PitchBlack

The **Custom Recovery** drawer screen installs a custom recovery with `su` and
`dd` on a phone that already has root. It does **not** replace the existing
**Recovery** screen, which is the USB fastboot path (`fastboot boot` /
`fastboot flash`) to a target in the bootloader.

**Status:** compiles and unit-tested. **Not run on a phone.** No recovery
images are bundled in the APK.

## What it does

1. Detects **this phone** (Android app) and, if connected, the **USB ADB
   target**. Reads `ro.product.device` and `ro.build.product`, whether `su -c id`
   reports `uid=0`, A/B vs A-only, and whether `/dev/block/by-name` (or
   `bootdevice/by-name`) has `recovery` / `recovery_a` / `recovery_b`.
2. Lists **TWRP** from the official device list (`https://twrp.me/search.json`)
   and directory (`https://dl.twrp.me/<codename>/`), and **PitchBlack** from
   `https://pitchblackrecovery.com/<codename>` plus its GitHub releases /
   SourceForge files. Lookup and download run only when you tap them, or when
   Dashboard **Builds for this device** opens this screen for a matching TWRP
   or PitchBlack Custom RR row (lookup still uses the probed phone).
3. Lets you pick a local `.img` or `.zip` from the library.
4. **Dry-run is the default.** It prints the `dd` commands and stats the
   partition. It does not write.
5. A real write needs the existing typed-confirm dialog (`FLASH RECOVERY`),
   backs up the current recovery partition into app storage first, then
   `dd`s only onto `recovery` / `recovery_a` / `recovery_b`.
6. **Reboot to recovery** is a separate button. A write never reboots by itself.

## What it will not do

- Write `boot`, `vendor_boot`, or any other partition. Devices whose recovery
  lives in boot/vendor_boot get the official **installer zip** path and a
  pointer to **Recovery → Boot once**. Unknown partition layout is not a
  wildcard.
- Guess an A/B slot. If `recovery_a`/`recovery_b` exist and the current slot is
  unknown, it refuses to write.
- Write when `blockdev --getsize64` failed or returned nothing. Unknown
  partition size is not safe.
- `dd` a zip. Zips are for sideload / an already-running recovery.
- Bundle third-party images, follow `http://`, or download from hosts outside
  TWRP's `dl.twrp.me` / `twrp.me` and PitchBlack's site (`pitchblackrecovery.com`,
  `pitchblack.tech` — the host Custom RR publishes) / GitHub / SourceForge.
  Custom RR's OrangeFox, SHRP and RedWolf pages are not on this list and stay
  browser links.
- Skip `ApprovalCenter`. There is no setting that turns the phrase off.

## Checksums

TWRP publishes `.img.md5` (and often a `.asc` PGP file) next to the image. When
that MD5 is fetched, a mismatch refuses the write/download. SHA-256 of the file
is always shown. PGP is **not** verified (the app does not ship a keyring).

## Code

`:core` package `ai.rootforge.os.recovery` (Apache-2.0): layout detection,
command building, catalog parse, https fetch, `RecoveryInstaller` + `RootSession`.
`:core` `ai.rootforge.os.catalog.CatalogRecoveryBridge` maps a Custom RR
RECOVERY row onto this screen (TWRP / PitchBlack only). Android USB ADB and
on-device `su` are `:app`; desktop host `adb` is `:desktop`.

The Custom RR card never writes. A prefilled visit still defaults to dry-run,
shows SHA-256, verifies a published MD5/SHA-256 when the official listing has
one, needs typed `FLASH RECOVERY`, backs up first, writes only
`recovery` / `recovery_a` / `recovery_b`, never `dd`s a zip, and never
reboots by itself.
