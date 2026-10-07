# CLAUDE.md — RootForge-OS session guide and state

Single source of truth for AI-assisted sessions. **Read PROJECT STATE first; update it
before you finish.** History that used to live here (about 115 KB of dated session notes,
phase reports and superseded plans) is archived verbatim at
`docs/archive/CLAUDE_pre-consolidation_2026-10-07.md` — it contains known-false claims, so
read it as history, never as current fact. Architecture: `docs/ARCHITECTURE.md`. Support
matrix: `docs/PLATFORM_SUPPORT.md`. Security: `docs/SECURITY_MODEL.md`. Plan:
`docs/IMPLEMENTATION_PLAN.md`. Change history: `CHANGELOG.md`. Conventions for any agent:
`AGENTS.md`.

## PROJECT STATE

**Last updated:** 2026-10-07 · **Branch:** `claude/new-session-78u9fc` (reset to `main` at `7120aae`
after PR #42 merged) · **Phase:** active development; ISO/VM/hardware validation **blocked** on
infrastructure.

**What is true now** (each verified by running it this session unless marked):
- `bash tests/run-tests.sh` → 983 passed, 0 failed (one check wraps the Python suite, 358 tests).
  `bash tests/lint.sh` → clean locally (shellcheck 0.11.0 from a venv). **GitHub CI also passed
  on the PR #42 head** (`34936af`: `shellcheck`, `tests`, `package-lists`, `yaml-lint`, `python`),
  so lint under CI's own shellcheck is verified. PR #42 was merged by the owner on 2026-10-07.
- Implemented and stub-tested: device model + `device check`, flash contract, backup
  manifest/verify/import-legacy/restore, layered config, redacted private JSON-lines log with a CLI-side audit trail for state-changing commands and exit-time redaction of script logs,
  doctor severity model, OTA/boot/module/avd dispatch, provisioning with Calamares cleanup,
  Termux verified install + per-release metadata generation + CPU-honest SDK bootstrap,
  Makefile/`auto/build` failure handling, release-asset verifier, release workflow gating.
- **Not run anywhere:** `lb build` (no loop device), ISO boot, Calamares install, systemd
  first boot, real devices, a phone, `release.yml` (it only runs on `v*` tags or manual
  dispatch; merging did not trigger it).
- **Known unpinned:** Ollama installer (hook 0020). Claude Code's native npm dependency.
  Nothing is signed.
- **Unsupported/not started:** UEFI/Secure Boot for the live ISO (BIOS only), Windows,
  Android APK, GUI, remote administration. No GitHub Release exists (tags `v0.1.0`,
  `v0.1.1` only), so the Termux install paths need a locally generated or future release.

**Incident worth remembering:** merge `b02e7c4` silently reverted six build hooks to a flavour
that defined SHA-256 pins and never compared them (one also used an undefined variable),
while this file claimed verification. Repaired in `2253488`; `tests/check-hooks.sh` now
rejects unused pins. **A "done" line in a previous session's notes is not evidence — re-run
the check.**

**Next task (in order):**
1. On a host with loop devices: `sudo make build`; record log + digest. Then a QEMU boot +
   scripted Calamares install test (Stage 5 of the plan).
2. Extend the config schema only where a script consumes a key (Stage 3). Done: shared execution ID, `0600` script logs redacted at exit and by the CLI (pattern-based; SIGKILL outside the CLI and unrecognised secret shapes are the known gaps), and CLI-side audit events for `flash`/`backup`/`module`/`avd`/`boot patch`/`boot flash-last`.
3. Pin/replace the Ollama installer; add release signing (Stage 4).
4. Run the flash/backup/restore contract against a test device the owner agrees to flash.
Do not start Stage 6 (Windows/APK/GUI/remote) before Stages 2 and 5 are validated.

**Open questions:** the `refusal_message()` wording in `core/device.py` was reconstructed
from `docs/ARCHITECTURE_AUDIT.md`; the "governing directive" it cites was never found in the
repository. Whether Calamares expands `${USER}` in `shellprocess` as documented upstream is
unverified. An unexplained one-off 853 vs 852 shell-check count was seen once (before the
log/boot tests were added) and not reproduced in five subsequent runs.

## SESSION PROTOCOL

1. Read PROJECT STATE. 2. Inspect the real code and run the checks before trusting any prose
(including this file). 3. Plan, then make the smallest correct change. 4. Run
`bash tests/run-tests.sh` and `bash tests/lint.sh`; report real results and say plainly what
could not run. 5. Update PROJECT STATE (date, what changed, what was tested, what remains)
and any doc whose claims changed. 6. Commit; push only the designated branch; open a PR only
when asked.

Source of truth when sources disagree: the implementation > this file > other docs > plans.

## ACCURACY RULES (non-negotiable)

- Never fabricate facts, commands, flags, versions, digests, test results or capabilities.
  Never describe planned work as implemented. Prefer "I don't know / can't verify".
- Tag substantive claims **[Certain]** (verified), **[Likely]** (inferred), **[Guessing]**.
- Distinguish stubs, real tools, builds, VMs and hardware in every report.
- Do not agree to be agreeable; say when the premise is wrong.
- Stubs and placeholders are not features. A feature is done when its behaviour works and is
  tested or otherwise verifiable.

## SCOPE

In: the shared tool layer (`rootforge` CLI + scripts), the Debian ISO, the Termux/PRoot rootfs,
build/release/packaging, tests, docs. Future (not started): Windows-hosted use, Android app,
GUI, remote management — as clients of the same contracts, not reimplementations.
Out: embedding unrelated OSes or projects (integrate, don't copy), committing user data,
credentials, device images, build artifacts, or machine-specific config. Label anything
experimental `[EXPERIMENTAL]`/`[UNSUPPORTED]`. Never silently escalate privilege or assume
root. Discovery never implies authorization.

Before adding a subsystem: what is its purpose and owner, does it belong in core, platform,
client, tooling or an external integration, what does it cost to maintain, and does it
duplicate something external? If unclear, settle the boundary first.

## ARCHITECTURE RULES

- CLI-first: important behaviour is reachable from the CLI; a GUI or remote client is a
  client of the same contracts. Business logic is not duplicated across Bash and Python —
  scripts call `rootforge` for device/backup/config rules.
- Wrap, don't rewrite working scripts. Unknown is not safe (undeterminable slot, lock state or
  product blocks a write). No config key may disable a safety check.
- Destructive actions: one explicit target, a printed plan, a typed confirmation on
  `/dev/tty`, a re-check before writing.
- Downloads at build or install time pin a version/commit and verify a digest (or a key
  fingerprint / registry integrity) and fail on mismatch.

## ASK FIRST vs. PROCEED

Proceed: bug fixes following existing patterns, tests, doc corrections verified against code,
isolated changes. Ask first: destructive operations (disk/partition/volume deletion, history
rewrite, anything that flashes a real device), new major dependencies, core architecture or
API changes, privilege-model changes, large multi-component refactors, anything touching
secrets.

## COMMANDS

```
bash tests/run-tests.sh                                   # everything (no root/device/network)
bash tests/run-tests.sh shell | python                    # one half
PYTHONPATH=config/includes.chroot/usr/local/lib python3 -m unittest discover -s tests -p 'test_*.py'
bash tests/lint.sh                                        # needs shellcheck
bash tests/check-hooks.sh                                 # static hook rules
sudo make build                                           # ISO; needs root, loop device, ~20 GB
tests/verify-release-assets.sh <dir> --tag vX.Y.Z         # a release directory
```

Key files: `config/includes.chroot/usr/local/lib/rootforge/core/` (CLI package),
`.../sh/common.sh` (shared shell helpers), `config/includes.chroot/usr/local/bin/` (scripts),
`config/hooks/` (chroot hooks), `termux/` (rootfs, launcher, generator), `tests/README.md`.

## RULES

Do: inspect before modifying; run tests after changes; report actual results; update docs
when behaviour changes; keep PROJECT STATE current. Don't: fabricate, claim unverified
features, commit secrets or artifacts, leave PROJECT STATE stale, flash real devices,
merge, tag or publish without being asked.
