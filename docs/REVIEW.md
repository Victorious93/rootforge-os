# RootForge OS — codebase review

**Date:** 2026-10-14 · **Scope:** `main` at `cec7f7e` plus the changes on this branch. Claims tagged **[Certain]** were verified by reading the code and running hermetic tests. **[Likely]** is inferred. **[Guessing]** is not verified.

## What it is

RootForge OS is a Debian 12 live-build distro plus a Termux/PRoot (and rooted-chroot) rootfs, aimed at people who write Magisk / KernelSU / APatch modules and patch Android boot images. The product is a **CLI and a set of scripts**, not a desktop GUI in the ISO: `rootforge` wraps device checks, flashing, backup/restore, module scaffolding, boot unpack/repack, OTA extract, and a rooted AVD helper. Destructive writes use one explicit target, a printed plan, a typed confirmation on `/dev/tty`, and a re-check immediately before the write.

This branch adds a **host-phone Android app** (`android-app/`) that talks to a **target phone** over USB-C using Android's USB Host API and a pure-Kotlin ADB / fastboot / Sahara implementation. droidcommand-ai can drive it over the same JSON bridge envelope `core-rootforge` already uses, via a bound Messenger service instead of SSH.

## Layout

| Path | Role |
|---|---|
| `config/includes.chroot/usr/local/lib/rootforge/core/` | Python CLI (`rootforge`) |
| `config/includes.chroot/usr/local/bin/` | Device-facing Bash scripts |
| `config/includes.chroot/usr/local/lib/rootforge/sh/common.sh` | Shared helpers (confirm, downloads, serials) |
| `config/hooks/` | live-build chroot hooks (pinned downloads, with exceptions) |
| `termux/` | PRoot/chroot rootfs build + install metadata |
| `tests/` | Hermetic shell + Python tests, no device/network |
| `.github/workflows/` | `lint.yml` (PR), `release.yml` (tags), `android-apk.yml` |
| `android-app/` | Kotlin / Compose host-phone toolkit APK |
| `docs/` | Architecture, security model, this review |

## What works **[Certain]**

Hermetic suite after the bugfixes on this branch: `bash tests/run-tests.sh` → **1009 shell + 367 Python, 0 failed**. `bash tests/lint.sh` clean with shellcheck 0.9.0. GitHub `Lint` on `main` was green at `cec7f7e`.

The flash / backup / restore / unlock scripts are the strongest part of the repo: argument validation, vendor refusal (Samsung), slot handling, `ANDROID!` header check, exit codes that distinguish "blocked" from "written but boot unverified". The Python CLI is a thin argv-safe wrapper around those scripts, with real work of its own in `backup` verify, `boot` unpack/repack/cpio, `ota` extract (leftover images no longer count as this run), device profiling, audit JSONL, and log redaction.

The Termux install path generates `install.sh` and the proot-distro plugin **from the real artifacts** at release time (`termux/make-release-metadata.sh` + `tests/verify-release-assets.sh`). There is no checked-in copy with placeholder digests.

The Android app **compiles** (`assembleDebug`), and its protocol / planner / module-builder / bridge tests run without a device. Paparazzi snapshots of Dashboard, ADB, Fastboot, Modules, Settings are in `android-app/screenshots/`. **Not run on a phone or emulator.**

## What does not work / has not been run **[Certain]**

- `lb build` / `sudo make build` — no loop-device ISO in this environment. v0.2.0 is a published GitHub Release with the **debug** APK only; ISO/Termux assets are still missing.
- Calamares install, systemd first boot, QEMU boot of the ISO.
- Any real phone, fastboot, Magisk/KernelSU patch, or EDL device.
- The Android app's USB path: unit-tested against in-memory pipes, not against `UsbManager`.
- UEFI/Secure Boot for the live ISO (BIOS/isolinux only).
- Windows / APK (before this branch) / remote admin — previously listed as not started.

## Code quality

The shell and Python that sit on a destructive path are unusually careful: comments record the bug they close, tests are named after that bug, and `tests/check-tests.sh` refuses a section that never invokes the code it names. Idiom is "fail closed": unknown vendor, unknown slot, unknown lock state all block a write. Config cannot disable a safety check.

Weaker areas: fleet orchestration (until this branch, a serial listed in both adb and fastboot was run twice), network installers (`join_headscale.sh` still pipes `curl` to `sh`), and several chroot hooks that **exit 0 on a failed download** so a "green" ISO can lack magiskboot / avbtool / payload-dumper. The second-brain CLI (`brain.py`) is a separate, lightly tested sqlite + Ollama tool.

The Android app follows the same fail-closed rules in `FlashPlanner` and `BridgeExecutor`. UI is Material 3 / Compose, dark by default, with a typed-phrase dialog for writes. The ADB stack is classic `shell:` (exit status recovered with an echo marker), not shell_v2.

## Security

**Good**

- Destructive device ops: one target, printed plan, typed confirmation, re-check.
- Downloads that go through `rf_download_cached` / hook `record_verified` pin a digest.
- CLI audit trail for state-changing commands; script logs redacted at exit (pattern-based).
- Live ISO `NOPASSWD: ALL` is removed by Calamares (`removeuser` + sudoers cleanup) — covered by tests.
- The Android bridge is off until the owner enables it, identifies the caller by package **and** signing certificate (from the kernel UID, not from JSON), and still prompts on every control/destructive call. RootForge is **not** a droidcommand-ai companion: it has no `DependencyGuard`.

**Concerns**

| Sev | Issue |
|---|---|
| High | `config/hooks/0020-ollama.hook.chroot` downloads and runs `https://ollama.com/install.sh` with no pin. Same class of bug as the 2026-10 merge that restored hooks which defined SHA-256 pins and never compared them. |
| High | `join_headscale.sh` still `curl \| sh` from tailscale.com. |
| Med | Several hooks treat a failed fetch as non-fatal (`exit 0`). |
| Med | Live udev `MODE=0666` on Android USB nodes — any local user on the live session can talk to a plugged-in phone. Intentional for a lab ISO; wrong if it survived install (Calamares removes the live sudoers; udev rules are a different file). |
| Med | Android EDL/Firehose is experimental and untested on hardware. A wrong programmer is a brick. The UI says so; the code still lets you send one. |
| Low | Log redaction is pattern-based; SIGKILL and unrecognised secret shapes bypass it (already documented). |

Nothing is cryptographically signed (ISO, APK, modules). README previously said "Signed artifacts"; that line now matches `docs/SECURITY_MODEL.md`.

## Gaps

1. No hardware or ISO-boot validation — the implementation plan's Stage 5 is still blocked on infrastructure.
2. Ollama / NodeSource / Claude Code npm are unpinned.
3. No release signing, no published `v*` release with installable Termux tarballs.
4. Scaffolded Magisk `update-binary` was a no-op (called `grep_prop` before sourcing `util_functions.sh`, never called `install_module`). Fixed on this branch; still untested in recovery.
5. AVD `cmd_stop` used to report success when `emu kill` failed. Fixed; the CLI `rootforge avd stop` still goes through the shell script, not that function.
6. Desktop (Linux/Windows) and Docker toolkit images were out of scope of the original ISO; they are being added after the APK.

## Prioritized recommendations

1. **Pin or replace the Ollama installer** and stop `curl|sh` in `join_headscale.sh`. `tests/check-hooks.sh` already rejects unused pins; extend it so a hook that `curl`s without `record_verified` fails the build, with an explicit allow-list if something must stay unpinned.
2. **Fail the ISO build** when magiskboot / avbtool / payload-dumper cannot be fetched. A missing tool at runtime is worse than a red hook.
3. **Run Stage 5** on a host with loop devices: `sudo make build`, QEMU boot, scripted Calamares. Until that happens, "hardened distro" is a claim about scripts, not about an image.
4. **Flash/backup contract on a device the owner agrees to brick-risk.** Stub tests cannot catch a vendor `getvar` dialect.
5. **Keep the Android app's USB path honest:** protocol tests against a fake are not a substitute for one Pixel in fastboot over OTG. EDL should stay labelled experimental until a Sahara HELLO is captured from a real 9008 device.
6. Do not start a Windows/APK/GUI **reimplementation** of the flash contract; desktop and Docker should call the same planner and the same confirmation rules.

## Fixes on this branch (verified in hermetic tests)

- `boot cpio` refuses a ramdisk path that escapes the unpack directory.
- Module display names and `brain new` titles cannot inject `module.prop` / YAML keys via newlines.
- Adb+fastboot serial lists are deduplicated in `devices.py` and `fleet_orchestrate.sh`.
- A truncated cached ADBKeyboard APK is discarded instead of installed.
- Fleet treats `flash_patched_boot.sh` exit 4 as "written, boot unverified", not FAIL.
- Live-groups systemd unit now `usermod`s `rootforge`, not `user`.
- Chroot rootfs hostname/motd no longer claim to be unrooted PRoot.
- Scaffolded `update-binary` sources Magisk's `util_functions.sh` and calls `install_module`.
- Desktop launcher test no longer depends on the host lacking `xfce4-session`.

## Bug sweep 2026-10-24: findings (two fixed later the same day, the rest open)

Found while probing untrusted-input code on `main` `5652d24`. The nine defects that were fixed are in `CHANGELOG.md` (each reproduced, then fixed with a test that failed first). What follows was **not** changed when the sweep ended; the first block below records the two redaction findings that were fixed afterwards. Tags: **[Certain]** reproduced or read in the code, **[Likely]** inferred, **[Guessing]** not verified.

**Secret redaction: fixed after the owner's go-ahead (2026-10-24, `ccr-4237cfad-065fob`)**

- **[Certain] Redaction leaked a name glued to a non-ASCII letter.** `redact_text` (Python) and `rf_redact` (sed) both printed `密码password=hunter2`, `éapi_key=abc` and `клюapi_key=abc` unredacted under a UTF-8 locale: the `NAME=value` rule needed a `\b` before the name and none exists between a non-ASCII letter and an ASCII one. Python's own locale coercion (`LC_CTYPE=C.UTF-8` for a child process) is enough to trigger it, so `tests/test_redaction_parity.py` reproduced it with no special setup. Now: the Python rule starts a match only where a `[A-Za-z0-9_.-]` run starts, and `rf_redact` runs sed with `LC_ALL=C` (byte semantics, so the result no longer depends on the caller's locale; UTF-8 text and invalid bytes pass through unchanged).
- **[Certain] The Python `_SECRET_ASSIGNMENT` pattern was quadratic.** `redact_text("a-" * 20000)` took 63 s and `"a-" * 50000` took 395 s (12 KB took 5.3 s). Now 40 KB takes 6 ms, 100 KB 14 ms, 1 MB of `x` 105 ms. The replacement matched the old rule's output on 250,000 random ASCII inputs (`hypothesis`, keyword-rich alphabet) in scratch; `tests/test_log.py` keeps the old pattern and checks agreement on 20,000 seeded ASCII inputs, plus a timing guard.
- **Evidence for the two engines agreeing:** 90,000 random lines (ASCII and non-ASCII neighbours) through the fixed Python and the fixed sed gave no difference. A Python `\s` still spans a newline where sed (line by line) cannot, and Python's `(?i)` folds a few exotic code points (`K` Kelvin sign, `ſ`) that C-locale sed does not; neither was changed.

**Open: the private-key block rules are quadratic on odd input (measured 2026-10-24, not changed)**

- **[Certain] Python:** `redact_text("-----BEGIN PRIVATE KEY-----" * 5000)` (131 KB) takes 3.2 s; 1,000 markers take 0.12 s and 2,500 take 0.83 s. `_PEM_BLOCK` scans to the end for each unterminated marker.
- **[Certain] sed:** one `-----BEGIN PRIVATE KEY-----` line followed by 20,000 lines (1.6 MB) takes 6.5 s and by 5,000 lines (0.4 MB) 0.39 s: the `:pem` loop joins lines until an END marker appears and re-tests the whole pattern space each time. At that rate a 64 MiB log with an early unterminated marker would take hours in `rf_redact_file` at script exit; `rf_redact_file` has no size cap (Python's `redact_file` caps at 64 MiB).
- **Proposed fix, not applied (touches secret redaction, so it needs a go-ahead):** Python: find BEGIN, find the first END after it with `re.search(..., pos)`, and when none exists redact to the end and stop (one pass). sed: stop joining lines once the END marker is found by testing only the newly appended line, or hand the file to the Python engine. Either needs the parity table extended with multi-block and unterminated samples.

**Unverified**

- **[Certain] TWRP / PitchBlack page formats (checked 2026-10-24, fixed on `ccr-4237cfad-065fob`).** With `twrp.me`, `dl.twrp.me`, `pitchblackrecovery.com` and `sourceforge.net` reachable, the lookup was run against the real pages and returned **no offer for any device tried**; the unit fixtures had been written to layouts the sites do not use. `dl.twrp.me/<codename>/` links every file as a root-relative `/<codename>/<file>.html` page (the parser dropped every root-relative link). The PitchBlack device page has no download links in its HTML (a script loads `/assets/json/builds-<codename>.json`), and SourceForge's links end in `/download`. Both sites are case-sensitive (`dl.twrp.me/AI2201/` 200, `/ai2201/` 404; `pitchblackrecovery.com/X00P/` 200, `/x00p` 404) and the lookup lower-cased every codename. See `CHANGELOG.md` for the fix and the end-to-end run.
- **[Certain] Not done:** TWRP's `<file>.md5` / `<file>.sha256` sit next to every image but only each file's own page links them, so a TWRP offer carries no published checksum (downloads are still checked for being an image or zip, and SHA-256 is shown). Fetching them for the offer a person picks is a follow-up in the two download paths (`AppActionsImpl`, `DesktopActions`).
- **[Guessing] The GitHub-release route** (`parsePbrpDevicePage` to `releasesForRepo`, and `lookupPbrpGithub`) was not run: this session's gateway denies `api.github.com` for repositories that are not attached. The builds JSON for `enchilada` lists `"github_release": null` and says "Auto-fetched from SourceForge", so that route may never yield anything.

**Observations (read in the code; not run on hardware)**

- **[Certain]** `HeadlessApi` auto-confirms with a thread that gives up after 5 s. An operation that raises its prompt later (a slow `getvar all`, hashing a large image) times out into a denial after 120 s. It fails safe. The thread also answers *any* pending prompt, including one the person raised in the window: a prompt without a phrase is approved outright, a typed-phrase one when the request's `confirmation` equals it.
- **[Likely]** `DeviceIdentityParser.fromEdl(previous = …)` cannot find the phone's earlier ADB/fastboot identity through the roster: that identity is pruned when its device disappears, and the 9008 port is a different target key. The "brand remembered from the last identify" note is only reachable from the unit test.
- **[Likely]** A target that reports no USB serial keeps its "USB permission needed" stub after the grant: `DeviceRoster.needsIdentify` only checks that an identity of the same mode exists. The Identify button forces it.
- **[Likely]** `AdbConnection.shell` keeps all output in memory until its timeout; an approved command such as `yes` can exhaust the heap.
- **[Certain]** `HostToolset` searches `./platform-tools` (the working directory) before `PATH`, so a launch from a directory with a hostile `platform-tools/adb` runs it.
- **[Certain]** ~~Custom Recovery does not compare the pulled backup's size with the partition size before it overwrites the partition.~~ Fixed in #90: `RootShellResult.ok` is exit code 0 only, and the pulled backup must be exactly `blockdev --getsize64` bytes or it is deleted and nothing is written.
- **[Certain]** `RecoveryCatalogClient.lookupTwrp` tolerates a failed `search.json` fetch but a malformed one fails the whole lookup.
- **[Certain]** `CatalogRecoveryPrefill.encode` escapes `|` in the id and name but not in `pageUrl` or the codenames, so `decode` returns null for such a row and the button does nothing.
- **[Certain]** `HostDevices.parseAdb` ignores the `no permissions` state, so a phone blocked by udev is absent instead of explained.
- **[Certain]** `GuideMarkdown.parse` overflows the stack on 20,000 repeated `*` or `_`; it only parses bundled text. `FirehoseClient.programRaw` zero-fills if its stream ends before `dataSize` (the class is marked untested). (`_PEM_BLOCK` is now measured under "Open" above.)
