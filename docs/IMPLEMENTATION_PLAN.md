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

4. **Deduplicate `Dockerfile.ndk-matrix`.**
   Confirmed byte-identical copies at
   `config/includes.chroot/opt/rootforge/docker/Dockerfile.ndk-matrix` and
   `config/includes.chroot/usr/local/share/rootforge/docker/Dockerfile.ndk-matrix`,
   with `build_matrix.sh`'s 3-path fallback only ever actually reaching the
   first. Delete the unreachable copy, collapse `build_matrix.sh`'s fallback
   list to the path(s) that are actually reachable, and add a code comment
   noting why (mirrors the install layout, not stylistic preference).

---

## P1 — High priority

5. **Device abstraction (`rootforge.core.device`).**
   A `Device` dataclass (codename, vendor, slots A/B or single, bootloader
   state, detected root method if any) plus detection logic that queries
   `adb`/`fastboot` once and is reused by every subcommand that currently
   re-derives this state independently (`flash_patched_boot.sh`,
   `backup_partitions.sh`, `unlock_bootloader.sh`). Unsupported/unknown
   vendors must produce the "DETECTED DEVICE ... RootForge cannot safely
   continue" message specified in the governing directive, not a guess.

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

9. **Artifact integrity at build time.**
   Add SHA-256 verification to the six hooks that fetch external content
   during the chroot build: `0040-rpi-imager`, `0050-starship-eza`,
   `0060-magiskboot`, `0062-payload-dumper`, `0085-avbtool`,
   `0095-zygisk-headers`. Pin expected hashes per pulled version; fail the
   build loudly on mismatch rather than silently continuing.

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
