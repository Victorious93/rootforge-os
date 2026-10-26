# Custom Recovery installer — rooted phones, official TWRP / PitchBlack

The **Custom Recovery** drawer screen installs a custom recovery with `su` and
`dd` on a phone that already has root. It does **not** replace the existing
**Recovery** screen, which is the USB fastboot path (`fastboot boot` /
`fastboot flash`) to a target in the bootloader.

**Status:** compiles and unit-tested. The lookup and download chain was run
against the live TWRP and PitchBlack sites on 2026-10-24 (see "What the sites
serve"). **Not run on a phone.** No recovery images are bundled in the APK.

## What it does

1. Detects **this phone** (Android app) and, if connected, the **USB ADB
   target**. Reads `ro.product.device` and `ro.build.product`, whether `su -c id`
   reports `uid=0`, A/B vs A-only, and whether `/dev/block/by-name` (or
   `bootdevice/by-name`) has `recovery` / `recovery_a` / `recovery_b`.
2. Lists **TWRP** from the official device list (`https://twrp.me/search.json`)
   and directory (`https://dl.twrp.me/<codename>/`), and **PitchBlack** from the
   build list its device page loads
   (`https://pitchblackrecovery.com/assets/json/builds-<codename>.json`), then
   the SourceForge folder and the device repo's GitHub releases if that list is
   missing. Both sites keep a device under one exact spelling
   (`dl.twrp.me/AI2201/` and `pitchblackrecovery.com/X00P/` are pages; the
   lower-case spellings are 404s) and the app passes lower-cased codenames, so
   TWRP is asked for the spelling in its own search title, and PitchBlack is
   tried as given, then in lower case, then in the spelling its home page's
   device list uses (one extra read, only when the first two miss). Lookup and
   download run only when you tap them, or when
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

## What the sites serve (read on 2026-10-24)

- `twrp.me/search.json`: a JSON list of `{title, desc, url}`; the codename is in
  the last parentheses of the title.
- `dl.twrp.me/<codename>/`: a table whose rows link each file as
  `/<codename>/<file>.html`, the file's own download page. The image is
  `/<codename>/<file>`. The host serves it only to a request whose Referer is a
  dl.twrp.me page; without one it returns that page's HTML. The app sends the
  file's own page, which is the route TWRP asks for, downloads to the person's own
  library for their own phone, and never mirrors or republishes a file. `.img.tar`
  rows (Odin, Samsung) are skipped.
- `pitchblackrecovery.com/<codename>/`: the page's HTML has no download links. A
  script loads `/assets/json/builds-<codename>.json` (`latest` and
  `older_builds`, each with a `download_link` such as
  `https://sourceforge.net/projects/pbrp/files/<codename>/<file>.zip/download`,
  which redirects to a mirror).
- `sourceforge.net/projects/pbrp/files/<codename>/`: links each build as
  `<file>.zip/download`; its folder, stats and info links are not downloads.

## Checksums

TWRP publishes `<file>.md5`, `<file>.sha256` and `<file>.asc` next to every
image (read on 2026-10-24: for `twrp-3.2.1-0-enchilada.img` the published `.md5`
matched the downloaded file), but the directory page lists none of them; only
each file's own page links them. The lookup does not fetch them, so a TWRP offer
carries no published checksum yet. The older form, a directory that links a
`.img.md5`, is still read, and a mismatch there refuses the download. A
download is always checked for being an image or a zip, and its SHA-256 is
always shown. The file already in the library under that name is replaced only
after those checks pass; a failed transfer, an HTML page, or a checksum
mismatch leaves the existing file where it is. PitchBlack builds carry no published checksum in the build list.
PGP is **not** verified (the app does not ship a keyring).

## Code

`:core` package `ai.rootforge.os.recovery` (Apache-2.0): layout detection,
command building, catalog parse, https fetch, `RecoveryInstaller` + `RootSession`.
`:core` `ai.rootforge.os.catalog.CatalogRecoveryBridge` maps a Custom RR
RECOVERY row onto this screen (TWRP / PitchBlack only). Android USB ADB and
on-device `su` are `:app`; desktop host `adb` is `:desktop`.

The Custom RR card never writes. A prefilled visit still defaults to dry-run,
shows SHA-256, verifies a published MD5/SHA-256 when the official listing has
one, needs typed `FLASH RECOVERY`, backs up first (a missing exit status
or a backup file that is not the partition's byte size aborts before the
write), writes only
`recovery` / `recovery_a` / `recovery_b`, never `dd`s a zip, and never
reboots by itself.
