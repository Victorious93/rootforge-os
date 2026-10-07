# AGENTS.md — instructions for AI coding agents

Short form. The full session guide, current state and next task are in `CLAUDE.md`; read its
PROJECT STATE section before changing anything.

## Setup and checks

```
bash tests/run-tests.sh      # all hermetic tests (no root, device, Docker or network)
bash tests/lint.sh           # shellcheck + hook/test self-checks + Python byte-compile
```
Python: stdlib `unittest` plus PyYAML (the only third-party import). Tests need
`PYTHONPATH=config/includes.chroot/usr/local/lib` when run directly. `shellcheck` is not
installed in every environment; if it is missing say so rather than claiming lint-clean.

## Layout

- `config/includes.chroot/usr/local/lib/rootforge/core/` — Python CLI package (`rootforge`).
- `config/includes.chroot/usr/local/lib/rootforge/sh/common.sh` — shared shell helpers.
- `config/includes.chroot/usr/local/bin/*.sh` — device-facing scripts.
- `config/hooks/*.hook.chroot` — ISO build hooks (flat dir, POSIX `sh`, `set -e`).
- `termux/` — Termux/PRoot rootfs build, launcher, release-metadata generator.
- `tests/` — `run-tests.sh`, `test_*.py`, `check-*.sh`, `verify-release-assets.sh`, `stubs/`.
- Docs: `docs/ARCHITECTURE.md`, `docs/PLATFORM_SUPPORT.md`, `docs/SECURITY_MODEL.md`,
  `docs/IMPLEMENTATION_PLAN.md`, `CHANGELOG.md`; history in `docs/archive/`.

## Conventions

- Match the surrounding code's idiom and comment density. Prefer extending the CLI or
  `common.sh` over duplicating a rule in a new script.
- Every behaviour change gets a test in the matching `tests/` file; a section of
  `run-tests.sh` must invoke the code it names (`tests/check-tests.sh` enforces this and the
  system-write seams).
- Tests must never touch the real `$HOME` or system paths; use the sandbox helpers and
  environment seams described in `tests/README.md`.
- Downloads pin a version/commit and verify a digest; hooks fail the build on a mismatch.
- Destructive device operations: one explicit target, printed plan, typed confirmation,
  re-check before writing. Never add a config option that disables a safety check.
- Commit messages describe what changed and what was and was not tested; no inflated claims.

## Hard rules

- Do not flash, unlock or write to a real device or block device; do not merge, tag, publish a
  release, rewrite history or force-push; do not commit secrets, images, backups or build
  artifacts. Open a pull request only when asked.
- Do not claim ISO builds, VM boots, hardware runs or GitHub workflow runs that did not
  happen. Separate stubs, real tools, builds, VMs and hardware in reports.
- Do not scaffold empty Windows/APK/GUI projects.
