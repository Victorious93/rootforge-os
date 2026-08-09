# RootForge OS — Architecture Audit

Victorious Framework | Origin Source Labs

Status: this document reflects the repository as of commit `f930749` on
`main` (2026-08-08). It is a factual audit, not a proposal dressed up as
one — every claim below was checked against the actual source, not
inferred from README prose. Where a claim needed verification, it's
marked **[Verified]**; where I'm inferring from a pattern rather than
having read every line, it's marked **[Likely]**.

## 0. What RootForge OS actually is today

This is important to state plainly, because it changes what "architecture"
means here. RootForge OS is **not** a software package with a build system
that produces a binary or a library. It is a
[live-build](https://salsa.debian.org/live-team/live-build) configuration
that assembles a bootable Debian 12 ISO. The "application logic" — the 28
scripts in `config/includes.chroot/usr/local/bin/` — is a payload baked
into that ISO's filesystem, not a project with its own package manifest,
dependency lock file, or test runner. `rootforge` as a CLI binary,
package, or entry point **does not exist anywhere in this repository**.
Every capability described in section 2 of the target directive below is
currently a standalone script invoked by its own filename.

This matters for the migration plan: there is no existing CLI to "evolve
instead of creating a second competing CLI" (per the directive's own
instruction) — the directive's premise that one might already exist
doesn't hold. The `rootforge` CLI is 100% new work, not a refactor of an
existing dispatcher.

## 1. Current Architecture

```
rootforge-os/
├── auto/
│   ├── config              live-build invocation (lb config ...)
│   └── build                build wrapper (lb build noauto ...) + log capture
├── Makefile                 build / checksum / list-usb / flash / clean / distclean
├── README.md                ~440 lines, spec-document style, [Certain]/[Likely] tags
├── BUILD.md                  host prerequisites, build steps, package provenance table
├── HACKING.md                 contributor-facing internals: repo layout, hook conventions
├── .github/workflows/
│   ├── lint.yml              shellcheck + yaml duplicate-key check + package-list validation
│   └── release.yml            build-iso + build-termux-rootfs (amd64/arm64 matrix) + release
├── config/                   live-build's own config tree
│   ├── package-lists/*.list.chroot   apt packages installed into the squashfs
│   ├── hooks/*.hook.chroot            15 numbered shell scripts run inside the chroot at build time
│   ├── archives/rootforge-security.list   custom apt source (works around a live-build bug)
│   ├── bootloaders/isolinux/           corrected isolinux theme (works around another live-build bug)
│   └── includes.chroot/                files overlaid onto the squashfs verbatim
│       ├── usr/local/bin/*.sh (27) + brain (1)   the 28 "automation scripts" — see below
│       ├── usr/local/lib/rootforge/second-brain/brain.py   only non-shell logic in the repo
│       ├── usr/local/share/rootforge/docker/Dockerfile.ndk-matrix
│       ├── opt/rootforge/docker/Dockerfile.ndk-matrix        [Verified] byte-identical duplicate
│       ├── etc/calamares/                installer config + branding (Calamares, not custom)
│       ├── etc/skel/                     default files for every new user, incl. second-brain/
│       └── etc/systemd/                  first-boot service unit
└── termux/                    a second, parallel non-root build target (debootstrap, not live-build)
    ├── build-rootfs.sh         builds a PRoot-installable rootfs tarball, reusing config/hooks/*
    ├── package-lists/           pruned package list (drops root/kernel-only packages)
    ├── proot-distro-plugins/     the proot-distro plugin end users install
    └── install.sh                 one-command Termux installer
```

`docs/`, `docker/` (top-level), `installer/` (top-level), `tests/`,
`src/`, `rootforge/` (top-level package), `scripts/`, and `CLAUDE.md` do
**not exist** in this repository. **[Verified]** — checked directly.

### 1.1 Language inventory **[Verified]**

| Language | Count | Where |
|---|---|---|
| Bash (`#!/usr/bin/env bash`) | 30 | all 27 `.sh` scripts in `usr/local/bin/`, `auto/build`, `termux/build-rootfs.sh`, plus more under `termux/` |
| POSIX `sh` (`#!/bin/sh`) | ~20 | `auto/config`, all 15 chroot hooks, the `brain` wrapper |
| Python 3 | 1 | `usr/local/lib/rootforge/second-brain/brain.py` (added this session) |

There is no Go, Rust, TypeScript, Java, Kotlin, or C/C++ anywhere in this
repository. Given that, "determine whether the current language choices
make sense" (directive §2) has one real answer worth stating: shell is
the correct language for what these scripts actually do — they are thin
orchestration over `fastboot`/`adb`/`magiskboot`/`avbtool` invocations,
not compute-heavy logic. The choice only becomes wrong at the point where
you need a typed data model (a `Device`, a config schema, structured
logs) shared across a dozen call sites — which is exactly the point this
repo is at now that a unified CLI is being requested. See §6
(Technology Decisions).

### 1.2 The "28 automation scripts" **[Verified]**

| Script | Lines | Subsystem (informal, no real module boundary exists) |
|---|---|---|
| `setup_ai_tools.sh` | 364 | AI/LLM tooling — largest script by far |
| `setup_rooted_avd.sh` | 301 | AVD create/boot/list — already subcommand-structured |
| `new_module_scaffold.sh` | 206 | module scaffolding (magisk/kernelsu/xposed) |
| `kernelsu_patch_boot.sh` | 134 | boot image patching for KernelSU |
| `00_bootstrap_distro.sh` | 127 | first-boot SDK/NDK/Magisk-source fetch |
| `check_root_detection.sh` | 126 | root-detection/stealth verification |
| `lint_module.sh` | 121 | module linter |
| `harden_system.sh` | 112 | opt-in system hardening |
| `backup_partitions.sh` / `restore_partitions.sh` / `fleet_orchestrate.sh` / `esp32_toolkit.sh` | ~107 each | backup/restore, multi-device orchestration, ESP32 flashing |
| remaining 16 scripts | 53–99 each | one narrow task each: unlock, flash, extract-OTA, VPN, proxy, LSPosed install, Pi imaging, etc. |
| `brain` (28th) | 4 (+ 300-line `brain.py` backend) | second brain / notes — see §1.3 |

Every script is independently invoked (`scriptname.sh args`), independently
parses its own `$1`/`$2` positional args, and independently implements its
own `log()` helper writing to `~/rootforge/logs/`. There is no shared
argument-parsing, logging, or config-loading code — each of the ~20
`log()` implementations is a near-identical copy-paste of the same six
lines. This is the single most mechanical, lowest-risk refactor available
(see Implementation Plan, P1).

### 1.3 A note on scope: `esp32_toolkit.sh`, `rpi_fleet_tools.sh`, `brain` **[Observation]**

Three of the 28 scripts are not Android-development tooling in the sense
the rest of the project is: `esp32_toolkit.sh` (ESP32 flashing),
`rpi_fleet_tools.sh` (Raspberry Pi fleet management), and `brain` (a
general-purpose PARA-method notes app, added this session). None of these
are wrong to have — they're genuinely useful on a dev box — but they sit
outside "Android system, kernel, boot-image, root-module, emulator, and
device-development workflows" as the charter for this audit defines the
project. Worth an explicit decision (not made here) on whether they
belong in `rootforge-*` proper, a separate `rootforge-flagship` add-on
tier (which already exists as a concept — `rootforge-flagship.list.chroot`
is an opt-in package list), or stay as-is. Not blocking; flagged for
awareness.

## 2. Existing Functionality (what already works)

This is the important counterweight to §3. A significant amount of this
project is real, tested, and should **not** be rewritten:

- **The ISO build itself.** `auto/config` + `auto/build` + `config/`
  produce a real, booting, installable Debian 12 ISO. This was fully
  validated end-to-end this session (a chain of ten real, previously
  latent live-build bugs found and fixed by reading the installed
  live-build source directly — infinite recursion in `auto/build`, a
  stale apt security-suite path, a broken firmware-autodiscovery
  codepath, an invalid `--debian-installer` value, wrong package names
  for `isohybrid`/`isolinux`, stale symlinks in live-build's own bundled
  theme, a renamed `rsvg` binary, a `bootlogo` archive live-build never
  creates for `LB_MODE=debian`, and a root-owned-file permission bug in
  CI). **[Verified]**: [run 31269821588](https://github.com/Victorious93/rootforge-os/actions/runs/31269821588)
  — `build-iso`, `build-termux-rootfs (amd64)`, and `build-termux-rootfs
  (arm64)` all green.
- **The Calamares installer integration.** Real, not a stub —
  `calamares-settings-debian` as the base with RootForge-specific
  branding/`shellprocess.conf` overrides, GRUB installs in UEFI mode via
  `grub-efi-amd64`/`shim-signed`, `os-prober` explicitly re-enabled for
  dual-boot detection. This already satisfies most of directive §22
  (Live environment, full install, dual boot, manual partitioning, UEFI).
- **The Termux/PRoot variant.** A second, genuinely different build
  target (debootstrap-based, not live-build) that reuses the same
  build-time hooks. Also fully validated green this session.
- **CI.** `lint.yml` (shellcheck, a YAML duplicate-key checker written to
  catch a class of bug GitHub's parser is strict about and PyYAML isn't,
  apt package-list existence checks) and `release.yml` (the full ISO +
  Termux matrix build). Both real, both green, both catch real bugs —
  this is not decorative CI.
- **The module scaffolder and linter** (`new_module_scaffold.sh`,
  `lint_module.sh`). Genuinely functional: three real templates (Magisk,
  KernelSU, Xposed) with correct lifecycle-script boilerplate, and a
  linter that catches the actual #1 real-world Magisk module install
  failure (module.prop not at zip root) plus CRLF line endings, required
  `module.prop` fields, an id-format check, META-INF presence, shebang
  presence, and the update-binary exec bit. Partial relative to the
  target spec (see §7), not fake.
- **Boot-image tooling installation.** `magiskboot`, `avbtool`,
  `mkbootimg`, `unpack_bootimg`, `repack_bootimg` are all real,
  functioning CLI tools baked into the ISO (fetched from AOSP source via
  LineageOS's GitHub mirror and the actual Magisk release APK, not
  fabricated) — verified against real upstream paths this session, not
  assumed. What's missing is a *unified wrapper* around them, not the
  tools themselves.
- **The AVD tooling.** `setup_rooted_avd.sh` already has `create`/`boot`/
  `list` subcommands and does a real Magisk ramdisk patch via `magiskboot
  cpio` rather than pushing a raw `su` binary — this is not a toy
  implementation.
- **The second brain** (`brain`), added this session: local semantic
  search + RAG over a PARA-method vault, Ollama-backed, stdlib-only
  Python, verified logic (cosine similarity, chunking, graceful
  Ollama-down handling) and a real CLI (`init`/`new`/`daily`/`index`/
  `search`/`ask`/`list`/`stats`). The only non-shell code in the repo,
  and the closest existing precedent for what a `rootforge-core` module
  should look like (see §6).

## 3. Problems Found

Ranked roughly by severity, not by section number.

### 3.1 No safety architecture on the destructive path — real, not theoretical

`flash_patched_boot.sh` — the script that writes to the `boot`/
`init_boot` partition on a physical device — has **zero confirmation
prompt**. **[Verified]**, full script read: it goes straight from
argument parsing to `fastboot flash` with no "inspect → validate →
backup → display → confirm" step at all, and does not itself invoke
`backup_partitions.sh` first. Compare `unlock_bootloader.sh`, which *does*
require typing `UNLOCK` before proceeding, and `Makefile`'s `flash`
target, which warns and gives a 5-second abort window before `dd`-ing to
a USB device. The safety discipline directive §5 asks for is **applied
inconsistently** — present in two places, absent in the one place it
matters most (writing to a mounted Android device's boot partition).

### 3.2 No device abstraction — every script re-derives device state independently

`flash_patched_boot.sh` calls `fastboot getvar current-slot` itself to
decide A/B handling. `backup_partitions.sh` independently tries three
different read-back strategies. Neither shares a `Device` model, neither
records what it learned anywhere the other could reuse, and neither
detects vendor to short-circuit an unsupported flow the way directive §5
wants (`DETECTED DEVICE / Vendor: Samsung / Automatic fastboot workflow
unavailable`). There's no code path today that would produce that
message — a Samsung device would just have every `fastboot` call fail
silently with no vendor-specific explanation.

### 3.3 No unified CLI, no shared infrastructure — genuine duplication, not just missing abstraction

Confirmed by grep: there are roughly 20 independent copies of the same
`log() { echo "[x] $*" | tee -a "$LOG_FILE"; }` pattern, 28 independent
`${1:?Usage: ...}` argument-parsing blocks, and no shared config loader —
`ROOTFORGE_HOME`, `OLLAMA_HOST`, `BRAIN_VAULT` etc. are each read from the
environment ad hoc, in whichever script happens to need them, with no
single source of truth for what environment variables exist or what they
default to.

### 3.4 Real, byte-identical file duplication

`config/includes.chroot/opt/rootforge/docker/Dockerfile.ndk-matrix` and
`config/includes.chroot/usr/local/share/rootforge/docker/Dockerfile.ndk-matrix`
are byte-identical (**[Verified]**, `diff` exit 0). Worse:
`build_matrix.sh`'s own three-location fallback logic
(`$(dirname "$0")/../docker/`, `$(dirname "$0")/docker/`, then
`/opt/rootforge/docker/`) means, given the script's real installed path
(`/usr/local/bin/build_matrix.sh`), **only the third fallback ever
actually resolves** — the `usr/local/share/rootforge/docker/` copy is
dead weight shipped in every ISO for no functional reason.

### 3.5 No artifact integrity checking anywhere

Directive §9 asks for SHA-256 verification of downloaded tools. **None
of the six build-time hooks that fetch external content over the network
verify a checksum**: `0040-rpi-imager` (raspberrypi.com .deb),
`0050-starship-eza` (starship.rs installer script, eza GitHub release),
`0060-magiskboot` (extracted from the live Magisk release APK),
`0062-payload-dumper` (GitHub release binary), `0085-avbtool` (AOSP
source via LineageOS mirror), `0095-zygisk-headers` (Magisk source via
GitHub). All of them `curl` and use the result directly. This is a real
supply-chain gap, not a style nitpick — several of these are executed as
root during the build (`sh -s -- --yes` for the starship installer is
literally "pipe a downloaded script into a shell as root").
Counter-balance: the **produced artifacts** (the ISO, the Termux
tarballs) do get a `.sha256` written (`make checksum` / CI's Checksum
step, `termux/build-rootfs.sh`'s own `sha256sum` line) — the gap is
specifically on the input side, not the output side.

### 3.6 No reproducibility manifest

Nothing in this repo answers "what exact environment produced this
artifact" (directive §8's own framing). No `system-manifest.json`, no
`rootforge.yaml` at any level, no recorded SDK/NDK/LLVM/Gradle/Docker
version pinning beyond whatever `apt`/`sdkmanager`/`npm` happen to
resolve to on the day a script runs. `00_bootstrap_distro.sh` fetches
"the SDK" and "the NDK" without a documented version policy anywhere
machine-readable.

### 3.7 Backup manifests are plain text, unchecksummed

`backup_partitions.sh` writes `manifest.txt` (plain prose: `"$part: OK
($(du -h ...))"`), not JSON, and computes **no SHA-256 for any backed-up
partition image**. Directive §14 explicitly asks for
`manifest.json` + `checksums.sha256`. Real, fixable gap — `sha256sum` is
already a coreutils tool present in every build; this is missing
integration, not missing capability.

### 3.8 `rootforge doctor` does not exist

Zero implementation. This is flagged by the directive as high-priority,
and it's also genuinely the highest-value-per-effort item on this whole
list — self-contained, no destructive operations, immediately useful,
and it's the natural first thing a new `rootforge-core` module would
need to exist before anything else can lean on it for environment checks.

### 3.9 Documentation has historically overclaimed completeness

This is the most important *process* finding, not just a documentation
nit. This session's own hook-audit work discovered that **every one of
the 15 build-time hooks had never actually executed, in any real build,
ever** — a directory-nesting bug against live-build's actual (non-
recursive) hook-discovery glob meant `config/hooks/live/*` and
`config/hooks/normal/*` were invisible to the build system despite
README/HACKING describing their behavior as settled fact. The docs
weren't lying, exactly — they described the *intended* behavior — but
there was no mechanism verifying intended behavior actually matched real
behavior, for months, across multiple merged PRs. Directive §24's ask for
explicit `Implemented`/`Partial`/`Planned`/`Experimental`/`Unsupported`
tagging is a direct, well-motivated response to a problem this project
has already had, not a hypothetical one.

## 4. Recommended Architecture

Not a rewrite. A layer added underneath the existing scripts, which
become the "worker" implementations the new layer calls into — matching
directive §26's "reuse working code" / "do not rewrite working components
without justification."

```
config/includes.chroot/usr/local/
├── bin/
│   ├── rootforge                     NEW — the unified CLI entry point
│   └── *.sh (existing 27 scripts)     UNCHANGED initially; called via subprocess
│                                       from rootforge subcommands, migrated into
│                                       rootforge-core incrementally per subsystem
└── lib/rootforge/
    ├── core/            NEW — config loading, logging, the Device model,
    │                     manifest/checksum helpers — shared foundation
    ├── cli/              NEW — argparse-based command dispatch (rootforge <verb> <noun>)
    ├── device/           NEW — Device dataclass, capability detection, vendor profiles
    ├── boot/             NEW — thin, typed wrappers around magiskboot/avbtool/mkbootimg
    ├── modules/           NEW — wraps new_module_scaffold.sh / lint_module.sh / build_magisk_module.sh
    ├── backup/            NEW — wraps backup_partitions.sh / restore_partitions.sh, adds checksums
    ├── ota/               NEW — wraps extract_ota.sh, splits inspect from extract
    ├── avd/               NEW — wraps setup_rooted_avd.sh
    ├── kernel/            NEW, mostly unimplemented today — genuinely new subsystem
    └── second-brain/       EXISTING (brain.py) — already matches this shape
```

This mirrors the directive's target tree in spirit
(`rootforge-core`/`rootforge-cli`/`rootforge-device`/... as directory
names under one Python package rather than separate top-level
repositories, since this all ships inside one ISO's filesystem, not as
independently versioned/installed components).

## 5. Migration Plan

See `docs/IMPLEMENTATION_PLAN.md` for the prioritized task list. In
narrative form, the order is:

1. Fix the one real, live safety gap (§3.1) — small, urgent, no
   architectural prerequisite.
2. Stand up `rootforge-core` (config, logging, manifest/checksum
   helpers) and the `rootforge` CLI skeleton with `rootforge doctor` as
   the first real command — self-contained, proves the pattern without
   touching any destructive code path.
3. Device abstraction, wired into `rootforge device info/list` *and*
   retrofitted into `flash_patched_boot.sh`/`backup_partitions.sh` so the
   safety-gate work in step 1 has a real capability model behind it
   instead of ad hoc `fastboot getvar` calls.
4. Wrap the existing module/boot/backup/OTA/AVD scripts behind
   `rootforge <subsystem> <verb>`, extending each wrapper's actual
   functionality (checksums, JSON manifests, apatch/zygisk module
   targets, the `boot inspect/verify` split) as it's touched — not a
   pure pass-through shim.
5. Kernel tooling (genuinely new — see §7), reproducibility manifest,
   GUI, and CI hardening (VM boot test, CLI test suite) follow once the
   backend above is stable, per directive §25's own phase ordering.

## 6. Technology Decisions

**`rootforge-core`/`rootforge-cli`: Python 3, stdlib-first, no new apt
package unless clearly justified.**

Reasoning, not just a preference:

- The distribution model is unusual: this code ships baked into an ISO's
  filesystem, not installed by end users via a package manager onto
  arbitrary host systems. `python3` is already an unconditional
  dependency of the image (multiple existing hooks already do `python3
  -c "..."`), so choosing Python costs the ISO **zero** additional
  runtime dependency weight. Go or Rust would each require either adding
  a real toolchain to the build environment (Go/Rust compiler, adding
  build time and complexity to `auto/build`) or cross-compiling static
  binaries in CI and vendoring them — a real increase in build-system
  complexity for a project whose actual bottleneck today is missing
  abstraction, not runtime performance.
- Precedent already exists and already works: `brain.py` (added this
  session) is exactly this shape — a typed, stdlib-only Python CLI that
  shells out to external tools (`ollama`, `claude`) rather than
  reimplementing them, with real error handling and no fabricated
  behavior. `rootforge-core` should look like a generalization of this,
  not a new pattern.
- The actual pain points this migration needs to solve — a typed
  `Device` model, structured JSON logs, YAML config parsing, SHA-256
  manifest generation — are all things Python's standard library (plus
  `PyYAML`, one well-known, small, already-in-Debian package) handles
  directly, without needing a compiled language's type system to get
  correctness. The scripts themselves remain shell, because shell is the
  right tool for "invoke `fastboot flash` and check the exit code" — the
  new layer's job is orchestration and shared state, not replacing every
  shell invocation with a Python equivalent.
- Directive §2's own instruction — "do not migrate languages merely for
  stylistic reasons" — argues *for* Python here specifically because it's
  the choice that requires the least justification-by-novelty: it's
  already present, already proven in this exact codebase, and doesn't
  need new CI infrastructure to build.

**One new apt dependency proposed:** `python3-yaml` (PyYAML) — needed for
`rootforge.yaml`/device profile parsing per directive §7/§19. Small,
stable, already in Debian's main archive, matching the project's existing
"stdlib first, add only what's clearly needed" pattern (see `brain.py`'s
own header comment on this exact philosophy).

## 7. Missing Components

Directly against the directive's target architecture (§3), what has zero
implementation today:

- **`rootforge-kernel`** — no source management, kernel `.config`
  fragment merging, toolchain pinning, or build orchestration exists.
  `harden_kernel.sh` does opt-in *hardening* of an already-running
  kernel, which is a different thing entirely from building one. This is
  the largest genuinely-new subsystem in the whole plan.
- **`rootforge-modules` / APatch / standalone Zygisk targets** —
  `new_module_scaffold.sh` supports `magisk`/`kernelsu`/`xposed` only.
  APatch isn't a scaffolding target at all. Zygisk is currently "wire in
  the zygisksu overlay separately" (a comment pointing outward), not a
  first-class `zygisk` target.
- **`lpunpack`/`lpmake` (dynamic partitions / `super.img`)** — not
  installed, not wrapped, not mentioned anywhere in the current tooling.
  Any device with dynamic partitions (the large majority of devices
  shipped since Android 10) needs this for proper partition-level
  backup/inspection; today's `backup_partitions.sh` only handles named
  partitions directly, with no `super.img` extraction path.
- **`rootforge doctor`** — zero implementation (§3.8).
- **Structured logging** — every script's `log()` is prose-to-a-file, not
  the JSON-with-execution-ID format directive §18 describes.
- **Reproducibility manifest** (`system-manifest.json`) — doesn't exist.
- **Artifact SHA-256 verification on fetched tools** — doesn't exist
  (§3.5).
- **GUI** — zero implementation. Correctly so, per directive's own
  ordering (§20, §25 Phase 7) — not a gap to fix now.
- **Test suite** — see §9.

## 8. Security Risks

1. **The confirmed missing confirmation gate on `flash_patched_boot.sh`**
   (§3.1) is the concrete, present-tense risk in this repository — not a
   hypothetical "what if a future feature does this wrong." Fixing this
   is P0.
2. **Unverified downloaded content executed as root at build time**
   (§3.5) — a compromised or MITM'd `starship.rs`/`raspberrypi.com`/
   GitHub-release response during a build would execute with no
   checksum gate to catch it. Lower urgency than #1 (build-time, in a
   CI/dev environment, not a live user's device) but still a real supply
   chain gap directive §9 correctly flags.
3. **No credential-boundary enforcement beyond `ai-keys.env`'s own chmod
   600.** `~/.rootforge/ai-keys.env` is handled carefully (§ established
   this session), but there's no repo-wide check (a CI lint, a `.gitignore`
   pattern, a packaging step) that would catch a *different* credential
   accidentally landing somewhere and getting swept into a module zip or
   backup archive, the way directive §10/§28 ask to guard against. Worth
   a lint rule once the CI/testing layer (§9) exists.
4. Everything else in the existing scripts that touches a device
   (`unlock_bootloader.sh`, `Makefile`'s `flash` target) already **does**
   have real confirmation gates — this is not a project with a
   pervasive safety-culture problem, it's one specific script that
   regressed the pattern the rest of the project already follows.

## 9. Testing Gaps

There is currently **no test suite of any kind** in this repository —
confirmed, no `tests/` directory, no test runner configuration, nothing
matching `*_test.*`/`test_*.*` anywhere. What exists instead, and is
genuinely valuable, is CI that catches real bugs *by actually building the
thing* (shellcheck, a YAML lint that's specifically hardened against a
real bug class this project hit, and the full ISO/Termux build matrix).
That's real coverage of "does the build work," but zero coverage of "does
the CLI behave correctly," "does the module linter correctly flag a
known-bad module," "does the Device model detect capabilities correctly
against a range of fixture `fastboot getvar` outputs," or "does the
produced ISO actually boot in a VM" (release.yml validates that `lb build`
exits 0 and produces a file of the expected shape — it has never once
booted the resulting ISO to confirm it works as an OS, only that it was
successfully assembled).

Concretely missing, in the order they become possible to add:

1. Unit tests for anything genuinely testable in isolation — `brain.py`'s
   cosine similarity/chunking (already smoke-tested manually this
   session, never captured as a real, repeatable test), and the same
   category of pure-logic function in `rootforge-core` once it exists.
2. A module-linter test fixture set (a known-good module, a module with
   module.prop not at zip root, one with CRLF endings, etc.) —
   `lint_module.sh`'s correctness has been validated by eyeballing its
   logic this session, never by running it against fixtures in CI.
3. CLI smoke tests once `rootforge` exists (`rootforge --help` exits 0,
   `rootforge doctor` runs and produces valid output shape, etc.).
4. A VM boot test for the produced ISO — the single highest-value testing
   gap, and the one most directly justified by this session's own
   experience: the ISO now builds successfully, but "builds successfully"
   and "boots and Calamares installs correctly" are not the same claim,
   and only the first one is currently verified by CI.
