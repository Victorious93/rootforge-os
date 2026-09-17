# RootForge OS — Implementation Plan

Companion to `docs/ARCHITECTURE_AUDIT.md`. That document explains *what exists
today and why it needs to change*; this document is the ordered, prioritized
task list for actually changing it.

Priority key:

- **P0 — Blocking.** Safety gaps, or foundational pieces everything else
  depends on. Nothing else in this plan should proceed past P0 until these
  are done.
- **P1 — High priority.** The core of the "unified platform" ask: device
  abstraction, config, logging, backup integrity, artifact integrity.
- **P2 — Important.** Wraps existing subsystems (modules, boot images, OTA,
  AVD) behind the unified CLI without changing their underlying behavior.
- **P3 — Future.** New subsystems (kernel tooling, dynamic partitions), GUI,
  installer/CI work, and polish. Explicitly out of scope until P0–P2 land.

Per the governing directive: implement in phases, keep each phase's diff
reviewable, run `git diff`/`git status` and any relevant tests after each
phase, and do not begin a later phase while an earlier one is unstable.
Nothing in this plan authorizes deleting or disabling existing working
scripts — P2 items wrap them, they don't replace them until the wrapped path
is proven equivalent.

---

## P0 — Blocking

1. **Fix `flash_patched_boot.sh`'s missing confirmation gate.**
   Add the same class of safeguard already used elsewhere in this repo
   (`unlock_bootloader.sh`'s typed `UNLOCK` confirmation, the `Makefile`
   flash target's warning + abort window): display target device/slot,
   image being flashed, and require explicit typed confirmation before
   invoking `fastboot flash`. This is a real, exploitable safety gap on a
   script that already ships — it does not wait on any other P0 item.

2. **`rootforge-core` package skeleton + `rootforge` CLI entrypoint.**
   Create `usr/local/lib/rootforge/core/` as the initial Python package and
   a single `rootforge` console entrypoint (`usr/local/bin/rootforge`)
   supporting only `--version`/`--help` and subcommand dispatch to start.
   No behavior migrates yet — this just gives later phases somewhere to
   land code instead of each wrapping logic living inside `usr/local/bin/`
   scripts directly.

3. **`rootforge doctor`.**
   First real subcommand on the new CLI. Checks: required host tools present
   (adb, fastboot, python3, git), Ollama reachable, Claude Code CLI present,
   `~/second-brain` vault initialized, disk space, and (once P1 lands)
   config file validity. Must be genuinely useful on day one, not a stub —
   per the directive's explicit ban on "placeholder implementations...
   called complete."

4. **Deduplicate `Dockerfile.ndk-matrix`.** — **Landed** (verified 2026-09-13,
   Phase 6 session). This item had already been done, in commit `6dbd7dd`
   ("Add rootforge CLI skeleton, rootforge doctor, dedupe
   Dockerfile.ndk-matrix"), which predates every documentation session that
   subsequently re-reported it as still open — `docs/ARCHITECTURE_AUDIT.md`
   (2026-08-08) asserted the duplicate existed, and CLAUDE.md's Phase 1/5
   sessions repeated that claim without re-checking the actual filesystem
   (`find . -iname Dockerfile.ndk-matrix` returns exactly one match, at
   `config/includes.chroot/opt/rootforge/docker/Dockerfile.ndk-matrix`;
   `git log --all -- config/includes.chroot/usr/local/share/rootforge/docker/Dockerfile.ndk-matrix`
   shows it was removed in `6dbd7dd` and never re-added). `build_matrix.sh`
   already only has the one real fallback (installed path, then a
   checkout-relative path) — there is no second copy or dead fallback branch
   to remove. No code change was needed for this item; it was a stale-claim
   correction only.

---

## P0.5 — Bug fixes and test coverage (landed)

Work that fell out of a bug sweep across the shipped scripts. None of it was
planned; all of it blocked the phases below, because P2 wraps these scripts
and wrapping code with a silent argument-parsing bug just moves the bug.

- **Argument parsing.** `flash_patched_boot.sh` and `extract_ota.sh` both
  used `shift 2 || true` to skip optional positional arguments. Under a
  one-argument or flag-second invocation that guard left the wrong value in
  `$@`: `flash_patched_boot.sh boot.img` ran `fastboot -s boot.img`, and
  `flash_patched_boot.sh boot.img --both-slots` flashed a partition named
  `--both-slots` while silently not mirroring slots. Both now parse
  positionally and validate.
- **Device detection.** `backup_partitions.sh` decided a device was present
  with `adb devices | grep -qv "List of devices"`, which matches the trailing
  blank line and so reported a device with nothing attached. Enumeration now
  lives in one place (`rf_adb_serials` / `rootforge.core.devices`) and parses
  the state column, so `unauthorized` and `offline` are surfaced as such.
- **Confirmation gates.** `kernelsu_patch_boot.sh --flash` wrote the boot
  partition with no gate at all — the same class of gap item 1 fixed. All the
  destructive scripts now share `rf_confirm`, which prompts on `/dev/tty` so
  the gate stays visible when `fleet_orchestrate.sh` redirects a child's
  stdout to a log (a bare `read -r -p` prompt vanished into that log and the
  run looked hung).
- **Backup integrity** (brings item 8's intent forward). Backups now carry a
  `SHA256SUMS` sidecar, and `restore_partitions.sh` verifies every image
  before flashing and refuses on a mismatch. A restore in which any flash
  failed now exits non-zero instead of printing "complete".
- **Exit codes.** `restore_partitions.sh`, `fleet_orchestrate.sh`,
  `build_matrix.sh` and `build_magisk_module.sh` all reported success after
  total failure, so nothing could wrap them programmatically.
- **`setup_terminal.sh`** wrote `eval "$(starship init bashrc)"` into shell
  rc files (`${RC##*.}` yields `bashrc`, not `bash`), so every new shell
  printed an error and got no prompt.
- **`brain.py`** never split a paragraph longer than `CHUNK_CHARS`, so a
  pasted log became one oversized chunk the embedding model silently
  truncates; and `cosine()` used `zip()`, which scored mismatched-dimension
  embeddings over a prefix rather than reporting the model change.
- **Tests.** `tests/` is a hermetic suite (stubbed `adb`/`fastboot`, scratch
  `HOME`, no network or Docker) covering every fix above, wired into CI. The
  lint pipeline now selects scripts by shebang instead of `*.sh`, which is
  why the `rootforge` and `brain` entrypoints had never been checked, and
  byte-compiles the shipped Python, which it never did.
- **CLI.** `rootforge doctor` gained `--json`/`--quiet`/`--strict` and checks
  for the tooling the scripts actually shell out to; `rootforge devices` is
  the first slice of item 5's device abstraction.

---

## P1 — High priority

5. **Device abstraction (`rootforge.core.device`).** — **Landed** (module +
   CLI verb + tests, 2026-09-13 Phase 6 session; shell-script retrofit
   still open — see below). Added `config/includes.chroot/usr/local/lib/
   rootforge/core/device.py`: a `DeviceProfile` dataclass (codename,
   vendor, `slot_mode` "single"/"ab"/"unknown", `current_slot`,
   `bootloader_unlocked`, `root_method`) plus `profile_fastboot()` (one
   `fastboot getvar all` call) and `profile_adb()` (one `getprop` call per
   field), reusing `rootforge.core.devices._run` rather than duplicating
   its no-raise subprocess handling. Named `DeviceProfile`, not `Device`,
   because `rootforge.core.devices.Device` (plural module, enumeration-only)
   already uses that name — a same-named class in a sibling module would
   be a real hazard, not a cosmetic one. Wired into a new `rootforge device
   info [SERIAL] [--json]` CLI verb in `cli.py`, with `_select_device()`
   auto-picking the sole usable device when no serial is given and refusing
   (not guessing) when zero or multiple are attached. 32 new unit tests in
   `tests/test_device.py`, mirroring `tests/test_devices.py`'s style
   (canned `getvar`/`getprop` text fed via monkeypatched `_run`); full
   suite re-verified green (161 Python tests, 420/0 shell+python via
   `tests/run-tests.sh`).

   **Unsupported-vendor refusal message:** no "governing directive"
   document exists anywhere in this repository — grepping for that exact
   phrase finds only the two sentences citing it in this file and
   `docs/ARCHITECTURE_AUDIT.md`, no separate checked-in file. The verbatim
   spec for the "DETECTED DEVICE ... cannot safely continue" message is
   therefore not independently verifiable from this repo. `DeviceProfile.
   refusal_message()` reconstructs it from `docs/ARCHITECTURE_AUDIT.md`
   §3.2's own example (`DETECTED DEVICE / Vendor: Samsung / Automatic
   fastboot workflow unavailable`) and `unlock_bootloader.sh`'s existing
   Samsung/Xiaomi refusal text — this is stated plainly in the module's own
   docstring rather than presented as a verified quote. If the actual
   governing-directive text surfaces later, `refusal_message()` is the one
   place to correct it.

   **Shell-script retrofit — Landed (2026-09-14 Phase 6 session).**
   `flash_patched_boot.sh` and `unlock_bootloader.sh` now call `rootforge
   device info [SERIAL] --json` (via two new `common.sh` helpers,
   `rf_rootforge` and `rf_device_profile_json`) for slot/product/vendor/
   unlock-state detection, in place of their own separate `getvar`/`grep`
   calls; `backup_partitions.sh` does the same for its no-serial
   auto-detect mode resolution. Every one of these calls falls back to the
   script's original direct-query logic whenever the shared path comes back
   empty, for any reason — `rootforge`/python3/jq unavailable, or the
   shared path not resolving a device — so a broken or missing Python
   install degrades detection accuracy, never script availability, on
   scripts that write boot partitions and unlock bootloaders.

   `backup_partitions.sh`'s explicit-serial branch is deliberately **not**
   retrofitted: `cli._select_device()` matches a given serial regardless of
   adb usability (this is existing, intentional, tested behavior — see
   `tests/test_device.py`
   `TestSelectDevice.test_explicit_serial_matches_regardless_of_usability`),
   so routing that branch through `rootforge device info` would report
   `MODE=adb` for a serial stuck at e.g. `unauthorized`, silently losing the
   script's own clearer "not usable" message. The two fastboot-only
   scripts don't have this problem — fastboot has no adb-style
   "unauthorized" state — so both retrofit the explicit-serial case too.

   One real bug was caught and fixed before landing, not just during
   review: `rf_require_cmd` (called by `rf_device_profile_json` when jq is
   missing) uses the `exit` builtin, not a normal command failure — and
   `exit` inside a function called *within* a `$(...)` command substitution
   terminates that subshell immediately, before control ever reaches an
   `|| true` written *inside* the same parentheses. Only a `||` placed
   *after* the closing `"$(...)"` can catch it. All three call sites, and a
   dedicated regression test pinning both the correct and the broken
   pattern, are in `tests/run-tests.sh` under "common.sh — rootforge CLI
   bridge". Full suite re-verified green: 161 Python tests, 438/0 shell+
   python via `tests/run-tests.sh` (up from 420 — 18 new checks: 2 pinning
   the exit/subshell fix, 16 exercising the three scripts' new
   `rootforge device info` success and fallback paths).

6. **Central config system (`rootforge.core.config`).**
   `~/.config/rootforge/config.yaml` for user-level settings,
   `rootforge.yaml` for project/workspace-level settings, and
   `devices/<codename>/rootforge.yaml` for per-device overrides. Adds the
   one new apt dependency identified in the audit (`python3-yaml`). Existing
   scripts keep working unmodified until P2 wires them to read from this
   instead of their own env vars/flags.

7. **Structured logging (`rootforge.core.log`).**
   JSON-lines logging with a unique execution ID per invocation and secret
   redaction (API keys, tokens) before anything is written to disk. Used by
   the new CLI from `rootforge doctor` onward; retrofitted into wrapped
   scripts as they're migrated in P2, not all at once.

8. **Backup integrity (`rootforge backup create/list/verify/restore`).**
   Wraps `backup_partitions.sh`/`restore_partitions.sh`. Adds a JSON
   manifest (replacing the current plain-text `manifest.txt`) recording a
   SHA-256 checksum per backed-up partition image, and a `verify` subcommand
   that re-hashes and compares. The underlying `dd`/partition-read logic in
   the existing scripts is reused, not rewritten.

9. **Artifact integrity at build time.** — **Landed** (2026-09-13, Phase 6
   session). All six hooks now pin a specific upstream version/commit and
   verify a SHA-256 hash before installing anything: `0040-rpi-imager`
   (rpi-imager 2.0.4), `0050-starship-eza` (starship v1.26.0, eza v0.23.5),
   `0060-magiskboot` (Magisk v31.0 APK), `0062-payload-dumper` (2.0.2),
   `0085-avbtool` (LineageOS mirror commits, not the mutable `lineage-22.2`
   branch name, for `avbtool.py`/`mkbootimg.py`/`unpack_bootimg.py`/
   `repack_bootimg.py`/`generate_gki_certificate.py`), `0095-zygisk-headers`
   (Magisk v31.0 `zygisk.hpp`). Every pinned hash was computed from a freshly
   downloaded copy of the real artifact at pin time (cross-checked against
   the upstream-published `.sha256`/`sha256checksums.txt` sidecar where one
   exists — starship and payload-dumper-go publish one; rpi-imager, eza,
   Magisk, and the LineageOS raw-file mirrors do not, so those hashes are
   trust-on-first-use, verified on every subsequent build from here on). A
   hash mismatch fails the build (`exit 1`); a network failure to reach the
   pinned URL still degrades the same way these hooks always did (warn and
   continue, since some of what they install is optional at runtime) — the
   two failure modes are handled differently on purpose, since one means
   "try again later" and the other means "something about this artifact
   changed and must not be installed silently." Versions will go stale over
   time by design — bumping one means fetching the new artifact, computing
   its real hash, and updating both together, per the comment at the top of
   each hook.

---

## P2 — Important

10. **`rootforge module create/lint/build`.**
    Wraps `new_module_scaffold.sh` and `lint_module.sh` behind the unified
    CLI. Adds the two module targets the audit found missing (APatch,
    standalone Zygisk) alongside the existing Magisk/KernelSU/Xposed
    targets. Extends the linter with shell-syntax checking, native-lib
    presence checks, and JSON output for CI consumption — extending
    `lint_module.sh`'s real 121-line implementation, not replacing it.

11. **`rootforge boot inspect/unpack/patch/repack/verify`.**
    Unifies the existing, already-real boot-image tooling (magiskboot,
    avbtool, mkbootimg, unpack_bootimg, repack_bootimg) behind one
    subcommand group, recording tool version, patch config, and output hash
    for each operation via the P1 logging module.

12. **`rootforge ota inspect/extract`.**
    Formalizes existing OTA/payload-dumper handling (currently invoked
    directly via `0062-payload-dumper`-provisioned tooling) as CLI
    subcommands with consistent logging and output paths.

13. **`rootforge avd create/list/start/stop/snapshot`.**
    Wraps `setup_rooted_avd.sh`, which already implements create/boot/list
    and a real Magisk ramdisk patch via `magiskboot cpio`. Adds snapshot
    support, which the current script lacks.

14. **Reproducibility manifest.**
    Write `system-manifest.json` at ISO build time (package versions, hook
    versions/hashes, build timestamp, git commit) so a given ISO's contents
    can be verified against its claimed provenance after the fact.

---

## P3 — Future

15. **`rootforge-kernel`.**
    Entirely new subsystem for kernel source management, defconfig/toolchain
    handling, and build orchestration. Nothing today does this — largest net
    -new scope in the plan. Do not start until P0–P2 are stable, per the
    directive's explicit phase-ordering requirement.

16. **Dynamic-partition tooling (`lpunpack`/`lpmake`).**
    Currently entirely absent. Needed for modern A/B devices using
    super.img; scope this against real device coverage once device
    abstraction (P1.5) exists to know which devices need it.

17. **GUI.**
    Deferred correctly, not a gap — no business logic should live only in
    the GUI; it calls the same `rootforge-core` functions the CLI does, once
    that core is stable enough to have a GUI put in front of it.

18. **CI hardening.**
    Boot-test the produced ISO in a VM (CI today only checks `lb build`
    exits 0 — it has never verified the ISO actually boots, the single
    highest-value testing gap identified in the audit); add a CLI test
    suite for `rootforge-core` (none exists today — no `tests/` directory
    at all); add docs-consistency checks so documentation can't silently
    drift from real behavior the way the pre-fix hook-discovery bug did.

19. **Installer/docs polish.**
    `CHANGELOG.md`; reorganize `docs/`; tag README features
    Implemented/Partial/Planned so completeness claims stay honest going
    forward — directly motivated by the audit's finding that documentation
    has historically overclaimed completeness relative to verified behavior.

---

## Sequencing notes

- P0.1 (flash safety gate) can and should land independently and
  immediately — it does not depend on the CLI skeleton.
- P0.2–P0.4 should land together as one reviewable changeset (new package
  skeleton + doctor + dedup), since doctor is the first real consumer of the
  skeleton.
- P1 items depend on P0.2 (the package skeleton) but are otherwise
  independently reviewable; device abstraction (P1.5) should land before
  config (P1.6) since config's device-override layer references it.
- P2 items each wrap one existing subsystem and should land as separate
  changesets per subsystem, not as one large "wrap everything" commit.
- No P3 item should begin before P0–P2 are merged and stable.
