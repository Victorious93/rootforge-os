# RootForge OS — Implementation plan

Status as of 2026-10-07. Supersedes the P0–P3 plan (archived at
`docs/archive/IMPLEMENTATION_PLAN_P0-P3_2026-10-07.md`, whose item numbers are still cited
by some code comments). Stages are ordered by dependency: a later stage assumes the earlier
one's contracts hold.

Status words: **Done** (implemented and covered by the hermetic test suite) ·
**Awaiting integration validation** (implemented; needs a real ISO build, VM, phone or
device to count as verified) · **Deferred** · **Blocked** (cannot proceed without something
named) · **Not started**.

## Completion ledger

| Area | Status | Evidence / what is missing |
|---|---|---|
| Device probing, write gate, `device check` | Done · awaiting device validation | `tests/test_device.py`, flash/restore stub tests. No real bootloader exercised |
| Flash semantics (explicit slot, boot verify, exit codes) | Done · awaiting device validation | Stub tests only |
| Backup manifest, verify, import-legacy, restore contract | Done · awaiting device validation | `tests/test_backup_verify.py`, stub tests |
| Config layering and validation | Done | `tests/test_config.py`. Only schema key: `backup.partitions` |
| Structured logging with redaction (CLI); one execution ID shared with wrapped scripts; private, exit-time-redacted script logs | Done | `tests/test_log.py`, `run-tests.sh` "script logs" section. Every state-changing command also writes CLI-side start/finish events (`core/audit.py`, `tests/test_audit.py`); script log redaction is pattern-based and happens at exit |
| Doctor severity model | Done | `tests/test_doctor.py` |
| OTA / boot / module / avd dispatch repairs | Done | `tests/test_*_cli.py` |
| Provisioning (`00_bootstrap_distro.sh`) and Calamares cleanup | Awaiting integration validation | Commands executed in a sandbox; real Calamares/systemd/first boot not run |
| Termux install/launcher/generator/bootstrap | Awaiting integration validation | Tests with stubs; no phone, no `proot-distro` run, no published release |
| Makefile / `auto/build` failure handling | Done (with stubbed `lb`) | Real `lb build` not run: **blocked** — no loop device in the authoring environment |
| `release.yml` gating, metadata generation, asset verifier | Awaiting integration validation | Verifier and generator tested locally; workflow not run on GitHub |
| Build-time download pinning | Partly done | Pins restored for 0040/0050/0060/0062/0085/0095, NodeSource key, Claude Code, `repo`; checked statically only. **Ollama installer (0020) unpinned** — deferred: release digests unreachable from the authoring environment (GitHub returned 403 for repos outside the session scope) |
| UEFI / Secure Boot for the live ISO | **Deferred** (documented unsupported) | `auto/config` uses isolinux; a GRUB image path would need VM testing under OVMF |
| ISO boots; installer installs; first boot completes | **Blocked** | Needs a host with loop devices and a VM |
| Real-device flashing/backup/restore | **Blocked** | Needs hardware and an owner's consent to flash it |
| Windows-hosted, Android APK, GUI, remote administration | **Not started** (Stage 6) | No empty projects are created on purpose |

## Stage 1 — Inventory and baseline  *(Done)*

- Audit result recorded in `docs/ARCHITECTURE.md` and `docs/PLATFORM_SUPPORT.md`; old claims
  are labelled in `docs/ARCHITECTURE_AUDIT.md` and `docs/PROJECT_REVIEW_2026-10-04.md`.
- Baseline gates: `bash tests/run-tests.sh` (983 checks incl. 358 Python tests) and
  `bash tests/lint.sh` (needs `shellcheck`) are green at the head of this branch.
- Lesson recorded: a status line in a previous session's notes is not evidence. A merge
  silently disabled the SHA-256 pins that notes said were done; the new static rules in
  `tests/check-hooks.sh` exist so that cannot recur unnoticed.

## Stage 2 — Device, backup and installer correctness  *(Done in code; awaiting hardware/VM)*

Done: device model and write gate; flash contract; backup manifest and restore; provisioning
and Calamares cleanup.

Remaining exit criteria (all need real systems):
1. Flash, back up and restore a boot image on a **test device the operator owns**, on at
   least one A/B and one non-A/B device, recording `getvar all` output as new fixtures.
2. Install the ISO in a VM; confirm `removeuser` removed the live user, the live sudoers
   rule is gone, `/var/lib/rootforge/install-user` holds the installed name, and first boot
   provisions that user — including an offline first boot that resumes.
3. Confirm Calamares expands `${USER}` in `shellprocess` (documented upstream; unverified).

## Stage 3 — Shared config, dispatch, diagnostics, logging  *(Mostly done)*

Done: layered config consumed by `backup_partitions.sh`; one dispatch path; doctor severity;
redacted, private JSON-lines logs.

Done 2026-10-07: the CLI's execution ID reaches wrapped scripts and is stamped into their
logs; script logs and reports are `0600`; subprocess-level tests cover `boot
inspect/unpack/repack/cpio/verify` with stub `magiskboot`/`avbtool`.

Done 2026-10-07 (later): CLI-side audit events for `flash`, `backup`, `module`, `avd`,
`boot patch` and `boot flash-last` — start and finish, exit status, scripts run, linked
script logs — so each of those commands has both halves of its trail.

Done 2026-10-07 (later still): secrets in script log contents are redacted at script exit
(`rf_redact_registered`, composed with existing `EXIT` traps) and again by the CLI after the
run; one rule set exists as Python and as sed, with a parity test.

Next (in order):
1. Extend the config schema only where a script actually consumes a key; keep the rule that
   no key disables a safety check.
2. Optional: stream-redact at the sink (so a secret never touches the `0600` file) if the
   exit-time window is judged too wide; it needs every `tee -a`/`2>>` site changed.

## Stage 4 — Packaging and verified installation  *(Partly done)*

Done: Termux install generated per release; chroot install requires a digest; arch-honest
SDK bootstrap; ISO checksum and `make flash` verification.

Next:
1. Pin or replace Ollama's installer (hook 0020) with a versioned release asset plus digest.
2. Sign releases (`SHA256SUMS` signature) — currently nothing is signed.
3. Decide how the tools are installed on a non-ISO Debian/Ubuntu host (`.deb`? wheel?);
   today it is copy-from-checkout.
4. Replace the Termux plugin's reliance on a single hosting origin only if a second origin
   is wanted; otherwise leave.

## Stage 5 — Build, VM and runtime validation  *(Blocked on infrastructure)*

Blocked: this requires loop devices and nested virtualization, neither available in the
authoring environment (`losetup -f` finds none).

1. Run `sudo make build` on a host with loop devices; record the log and the ISO digest.
2. CI VM boot test: QEMU boot of the ISO to the live desktop and a scripted Calamares
   install; assert the Stage 2 criteria. Highest-value missing test.
3. Exercise `release.yml` on a throwaway tag; confirm `verify-release-assets.sh` passes on
   the real artifacts and the draft release contains exactly the verified set.
4. Real-phone runs of the PRoot and chroot installs (arm64) and `bootstrap_proot.sh`.
5. Decide UEFI: either keep "unsupported" or implement a GRUB image path and test under
   OVMF, with Secure Boot explicitly out unless a signed shim chain is built.

## Stage 6 — Future platforms  *(Not started; do not scaffold empty projects)*

Gate: Stages 2 and 5 validated on real systems. Then, in this order, each as a client of the
same CLI contracts rather than a new implementation of them:
1. A service/API layer over `rootforge.core` (the CLI's JSON output is the stable seam today).
2. Remote nodes: identity, mutual authentication, authorization (discovery must never
   imply authorization), transport, audit. Requires a threat model first.
3. Windows-hosted: WSL2 + USB passthrough path assessed on real hardware before any native
   code.
4. Android APK / GUI: separate repositories or modules once a service layer exists.
