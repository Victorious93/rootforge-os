# Flash Tools — phased plan

A host phone (this app) drives a target phone over USB OTG using Android's USB
Host API. No root on the host. Flash Tools is a **guided hub** on top of the
existing `TargetController` / ADB / fastboot / `ApprovalCenter` / typed-phrase
path. It does not add a second USB stack, a bundled `adb` binary, or a setting
that skips a safety check.

Status of this document: Phases 1–3 are implemented on `main` (PR #56).
Phase 4 is **plan only** and must not be implemented until the EDL-Commander
maintainer agrees the JNI surface.

## Why a hub, not a rewrite

The APK already talks ADB, fastboot and Sahara over `UsbManager` (`docs/ANDROID_APP.md`).
RXT Flash Pro-style grouping (scan → connect → ADB tools → fastboot tools →
inspect → log) was missing. Destructive writes already print a plan and require
a typed phrase. What was missing, and what this work adds in `:core` so every
client shares it:

- a **device-match gate** that refuses a flash unless the image claims the
  target (product/codename, slot, and fingerprint *or* a declared sidecar
  manifest)
- **SHA-256** inspect / verify
- a **persistent**, redacted command log
- **USB scan filtering** (ADB / fastboot / EDL vs everything else)

The existing ADB, Fastboot, Recovery and Root screens keep working. They go
through the same `fastbootFlash` / `fastbootUnlock` methods, so the new gate
applies there too.

## Phase 1 — shared rules (`:core`)

Pure JVM. Unit-tested. No Android types.

| Piece | Role |
|---|---|
| `DeviceMatchGate` | Compare `TargetIdentity` to an `ImageClaim`. Unknown is not safe. |
| `FlashManifest` | Sidecar `<image>.manifest.json` (also `<stem>.manifest.json`, `<image>.json`). |
| `FileDigest` | SHA-256 hex; verify is case-insensitive and rejects empty / odd-length expected values. |
| `FlashSafety` | Load sidecar, hash the file, verify digest if declared, then device-match. One call used by the Android and desktop flash paths. |
| `FileInspector` | Kind (boot / vendor_boot / sparse / APK / zip / manifest / unknown) + hash + claim. |
| `UsbScanFilter` | Partition attached devices into flash targets vs ignored. |
| `OutputLog` persist | Optional JSON-lines file; same redaction as the live panel. |

### Manifest shape

```json
{
  "product": "oriole",
  "codename": "oriole",
  "slot": "a",
  "fingerprint": "google/oriole/oriole:14/AP2A.240805.005/12006205:user/release-keys",
  "sha256": "optional hex of the image bytes"
}
```

`slot` may be `"*"`, `"a"`, `"b"`, or omitted. `_a` on the device is normalised
to `a`. A sidecar that names **product or codename** is a **declared manifest**:
that is enough for the fingerprint clause when the image does not carry a
fingerprint (typical for a raw `boot.img`). If the sidecar *does* declare a
fingerprint, the target's build fingerprint must match. `sha256`, when present,
must match the file or the flash is refused before the device is compared.

There is no in-app override. An image with no sidecar is refused.

### How a mismatch is reported

`DeviceMatch.Mismatch.reasons` is a list of sentences. `FlashSafety` and both
flash implementations log each as `Refused: …` and throw `IOException` with
every reason joined. The output panel (and the persisted command log) is the
operator-visible report. Typical reasons:

| Condition | Wording |
|---|---|
| No sidecar / unreadable claim | `Image has no device claim. Import a sidecar <image>.manifest.json declaring product or codename, slot, and either fingerprint or a declared product/slot match.` |
| Sidecar has neither product nor codename | `Manifest does not declare product or codename.` |
| Target product and codename both unknown | `Target product/codename is unknown; cannot confirm the image is for this device.` |
| Product/codename do not overlap | `Image is for product/codename 'X'; target reports product 'Y' / codename 'Z'.` |
| Claimed slot, target slot unknown | `Image is for slot 'a'; target slot is unknown.` |
| Slot differs | `Image is for slot 'a'; target is on slot 'b'.` |
| Claimed fingerprint, target fingerprint unknown | `Image fingerprint is set; target build fingerprint is unknown.` |
| Fingerprint differs | `Image fingerprint does not match the target build.` |
| Manifest SHA-256 differs | `Image SHA-256 does not match the manifest digest.` |

A match is silent aside from a line on the typed-confirmation plan
(`Device match: …`). The typed phrase is still `FLASH <partition>` or `UNLOCK`.

## Phase 2 — Flash Tools hub (`:app`, desktop reuses the same Compose tree)

New drawer destination **Flash Tools**. Buttons call existing `AppActions` /
`TargetController` (and the desktop `HostController` twin). Added actions are
thin wrappers, not a second protocol.

1. **Connection guide** — cable, OTG, USB debugging, bootloader key combo, first
   RSA prompt. Reuses the dashboard copy.
2. **USB / OTG scan** — refresh `UsbHost`, show flash targets, grant permission,
   connect. Ignored devices (HID, charge-only, unknown) are listed as ignored.
3. **ADB tools** — device check, shell, list packages, push/pull, logcat,
   screen info (`wm size` / `wm density`), screenshot (`screencap` then pull
   into the library).
4. **Fastboot tools** — getvar / product, partition-size/type lines, unlock
   (large warning + existing `UNLOCK` phrase), flash a library file (match gate
   + existing plan + `FLASH <partition>`), reboot to system or bootloader.
5. **Image / APK inspector** — kind, size, SHA-256, sidecar claim, typed-hex
   verify.
6. **Command log** — the same `OutputLog` as the bottom panel; persisted as
   JSON-lines under the app files dir (desktop: `~/.rootforge/command-log.jsonl`).

Unlock and flash still go through `ApprovalCenter`. The hub only adds a bigger
on-screen warning; it does not confirm for the operator.

## Phase 3 — tests and docs (this PR)

- `:core`: device-match, SHA-256, persisted log, USB filter, inspector, manifest
  parse.
- `:app`: the same four contracts the hub relies on, plus a Paparazzi shot of
  the Flash Tools screen.
- `bash tests/run-tests.sh`, `bash tests/lint.sh`,
  `./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :desktop:compileKotlin`.
- Docs: this file, `docs/ANDROID_APP.md`, `CHANGELOG.md`, `CLAUDE.md`.

**Not in this PR:** a live host/target pair, USB OTG on a phone, a real unlock
or flash, Paparazzi on a device, the ISO/VM path.

## Phase 4 — EDL via EDL-Commander (plan only; do not implement)

Today's in-app EDL screen is a **Kotlin Sahara / Firehose client** marked
experimental. Retail 9008 devices accept only the OEM-signed programmer; this
app ships none.

A later phase should reuse [Victorious93/EDL-Commander](https://github.com/Victorious93/EDL-Commander)
Rust as a native library (`cdylib`) via JNI/NDK, **not** by copying the crate
into this tree and **not** by shelling out to a random `edl` binary on the
phone.

Coordinate with the EDL-Commander maintainer before pinning a commit or
changing that crate's public surface. Until that agreement exists, keep the
Kotlin client and do not add NDK/JNI.

### Interface the APK will need

A small JNI façade (names illustrative) owned by RootForge, implemented by a
stable C ABI that EDL-Commander exports:

| Call | Direction | Notes |
|---|---|---|
| `edl_open(fd, iface) -> handle` | host → rust | Android already claimed the USB interface; pass the `UsbDeviceConnection` file descriptor, not a second open. |
| `edl_identify(handle) -> {serial, hwid, pkhash, sbl}` | rust → host | Sahara hello / device identity. |
| `edl_upload_programmer(handle, bytes)` | host → rust | Programmer bytes from the app library. RootForge still shows the typed `EDL` phrase *before* this call. |
| `edl_firehose_configure(handle, memory)` | both | After the programmer is running. |
| `edl_firehose_storage_info(handle, lun)` | rust → host | |
| `edl_firehose_program(handle, lun, start, sector, bytes)` | host → rust | RootForge still shows `WRITE LUN … SECTOR …` *before* this call. |
| `edl_reset(handle)` / `edl_close(handle)` | host → rust | |

Errors come back as structured codes (USB timeout, Sahara reject, Firehose NAK,
unsigned programmer), not as panics. Progress is a callback with `done/total`.
No programmer blob is bundled. The existing `BulkTransport` seam stays for
tests; the native path is one more implementation, selected only when the
.so loads and the target is 9008.

### What RootForge still owns

USB permission, device-match (when a Firehose image has a sidecar), typed
phrases, the library, the command log, and the decision that EDL is not a
bridge operation (`docs/DROIDCOMMAND_INTEGRATION.md`). EDL-Commander owns
Sahara / Firehose correctness.

## Safety (applies to every phase)

- One explicit target. A printed plan. A typed phrase on the host. A re-check
  immediately before the write.
- No config key disables a check.
- Unknown product, slot, lock state or fingerprint blocks the write.
- Discovery (a USB device appeared) is not authorization.
