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

> **Update 2026-10-15 (README):** branch `cursor/readme-flash-tools-7fd1` off `origin/main`
> at `8cf8d53` (Flash Tools already on `main` via #56). README landing page now
> documents Flash Tools, DroidCommand Messenger pairing, git-clone CLI on
> Linux/Termux, Victorious Framework / VictorSuite `:core` sharing, APK /
> desktop / Docker builds, and the GPL-3.0-or-later / Apache-2.0 split.
> EDL-Commander JNI is still plan-only. Still no GitHub Release (tags `v0.1.0`,
> `v0.1.1` only).

**Last updated:** 2026-10-15 · **Branch:** `cursor/readme-flash-tools-7fd1` (from
`origin/main` at `8cf8d53`) · **Phase:** active development; ISO/VM/hardware validation
**blocked** on infrastructure. Android host-phone APK, Flash Tools, desktop/Docker
toolkit, DroidCommand bridge, and license lock are on `main`. This branch is README
accuracy. Not run on a device. License lock (#53): original work GPL-3.0-or-later
(`LICENSE`); shared Kotlin `:core` Apache-2.0 (`android-app/core/LICENSE`);
third-party/Termux/Debian/SDK code not relicensed (`NOTICE`).

**What is true now** (each verified by running it this session unless marked):
- License lock is in-tree from #53: `LICENSE` (GNU GPL v3 text; applying notice is
  GPL-3.0-or-later in `NOTICE`), `android-app/core/LICENSE` (Apache-2.0), `NOTICE`
  (split + third-party not relicensed). README License section still matches that
  split; this session added Flash Tools / DroidCommand / git-clone CLI / Victorious
  Framework context on the same landing page.
- `bash tests/run-tests.sh` → **1016 passed, 0 failed** (376 Python tests).
  `bash tests/lint.sh` → **lint clean** with shellcheck 0.9.0 (installed this
  session; it was missing at first). README/docs-only change; suite count
  matches the Flash Tools tree on `main`.
- Gradle was **not** re-run this session (README/docs only). The Flash Tools PR
  (#56) recorded `:core:test` 98/0, `:app:testDebugUnitTest` 25/0, `assembleDebug`,
  and `:desktop:compileKotlin`. **Not installed on a phone, not bound from a live
  DCA, not exercised over USB, not used to unlock or flash a device.**
- Flash Tools is in-tree: `docs/FLASH_TOOLS_PLAN.md`, `:core` device-match / SHA-256 /
  persisted `OutputLog` / `UsbScanFilter`, Flash Tools Compose hub, same gate on
  desktop `HostController.fastbootFlash`. EDL-Commander JNI is plan-only.
- `./gradlew :desktop:packageDeb` was **not** re-run. Windows `.exe`/`.msi` not built.
  Docker image **not** built (`docker` is not installed on this agent). **Not run against a
  PC/phone pair.**
- `extract_ota.sh` / `rootforge ota extract` count an image as produced only when this run
  created or replaced it, judged by SHA-256 of the file. A reused output directory used to make
  an empty dump exit 0, and the CLI hashed a leftover `*.img` as if it had come from the payload
  just extracted. An earlier stamp compared `stat` `%Y.%N`, but GNU stat's `%N` is the file
  name, not nanoseconds, so a same-sized rewrite in the same second was reported as "produced
  no files" after the bytes had already been replaced.
- Implemented and stub-tested: device model + `device check`, flash contract, backup
  manifest/verify/import-legacy/restore, layered config, redacted private JSON-lines log with a CLI-side audit trail for state-changing commands and exit-time redaction of script logs,
  doctor severity model, OTA/boot/module/avd dispatch, provisioning with Calamares cleanup,
  Termux verified install + per-release metadata generation + CPU-honest SDK bootstrap,
  Makefile/`auto/build` failure handling, release-asset verifier, release workflow gating.
- **Not run anywhere:** `lb build` (no loop device), ISO boot, Calamares install, systemd
  first boot, real devices, a phone, a desktop USB session, `docker build -f docker/Dockerfile`,
  `release.yml` (it only runs on `v*` tags or manual dispatch; merging did not trigger it).
  PR #49 Toolkit CI (run 38523858203) after the `packages: platform-tools` fix: Lint,
  linux-desktop, windows-desktop, and docker all green. PR #50 changed
  `android-apk.yml`; this session did not re-check that workflow's jobs.
- **Known unpinned:** Ollama installer (hook 0020). Claude Code's native npm dependency.
  Nothing is signed.
- **Unsupported/not started:** UEFI/Secure Boot for the live ISO (BIOS only), remote
  administration. EDL-Commander JNI/NDK (Phase 4 of `docs/FLASH_TOOLS_PLAN.md` only).
  Android host-phone GUI APK, Flash Tools, Linux/Windows desktop, and Docker toolkit
  are on `main` (USB Host, not Termux). This branch is README docs. No GitHub Release
  exists (tags `v0.1.0`, `v0.1.1` only), so the Termux *rootfs* install path still
  needs a locally generated or future release; the Python CLI can run from a git clone.
- Review: `docs/REVIEW.md`. App: `docs/ANDROID_APP.md`. Desktop/Docker: `docs/DESKTOP.md`.
  Bridge: `docs/DROIDCOMMAND_INTEGRATION.md`. Flash Tools: `docs/FLASH_TOOLS_PLAN.md`.

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
The Android GUI APK landed in PR #48; Linux/Windows/Docker toolkit in PR #49.
Licensing is PR #53 (now on `main`). DroidCommand Messenger is PR #52 (on `main`).
Flash Tools is PR #56 (now on `main`). This branch is README accuracy. ISO Stage 5
is still blocked and is still the next hardware task. A live host/target USB session
has not been run.

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
build/release/packaging, tests, docs, and (now on `main`) the `android-app/` Gradle project:
`:core` (shared Kotlin, Apache-2.0), `:app` (Android host-phone APK) and `:desktop`
(Compose desktop; Linux .deb/AppImage, Windows .exe/.msi), plus `docker/` toolkit image —
as clients of the same contracts, not reimplementations. Not started: remote management.
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
# android-app/ (Gradle wrapper; same commands CI runs)
cd android-app && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
cd android-app && ./gradlew :app:verifyPaparazziDebug      # screenshot tests (Paparazzi)
cd android-app && ./gradlew :core:test :desktop:packageDeb :desktop:packageAppImage
docker build -f docker/Dockerfile -t rootforge-os-toolkit:ci .
```
`tests/run-tests.sh` needs PyYAML (`python3-yaml`) and several host tools; in a minimal
environment some sections fail for environmental reasons, so compare against CI before
assuming a regression.

## CI (`.github/workflows/`, verified 2026-10-15)

- `lint.yml` ("Lint"): PRs, pushes to `main`, and `workflow_call` from release. Jobs:
  shellcheck (`tests/lint.sh`), yaml-lint, package-lists, tests (`tests/run-tests.sh`),
  python.
- `android-apk.yml` ("Android APK"): PRs, pushes to `main`, `v*` tags, manual. Runs
  `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`; a release APK is built
  only when the `ROOTFORGE_KEYSTORE_*` repository secrets exist.
- `toolkit.yml` ("Toolkit packages"): PRs, pushes to `main`, `v*` tags, manual. Jobs
  linux-desktop, windows-desktop, docker.
- `release.yml`: only on `v*` tags or manual dispatch (`contents: write`); builds the ISO and
  Termux rootfs, generates and verifies release assets. Never trigger it without being asked.

Key files: `config/includes.chroot/usr/local/lib/rootforge/core/` (CLI package),
`.../sh/common.sh` (shared shell helpers), `config/includes.chroot/usr/local/bin/` (scripts),
`config/hooks/` (chroot hooks), `termux/` (rootfs, launcher, generator), `tests/README.md`.

## RULES

Note: `shellcheck_py-0.11.0.1-…whl` at the repo root was committed in `0aafccd`; it looks like
a tooling artifact rather than source [Likely]. Don't add more; ask before removing it.

Do: inspect before modifying; run tests after changes; report actual results; update docs
when behaviour changes; keep PROJECT STATE current. Don't: fabricate, claim unverified
features, commit secrets or artifacts, leave PROJECT STATE stale, flash real devices,
merge, tag or publish without being asked.

## Don's standing rules (apply to every change)
- Work on a branch and open a PR. Never push to `main`.
- Never merge a PR, and never ask to merge one, until every test/check on the PR passes.
- Never commit secrets (API keys, tokens, keystore passwords, `.env` files, credentials).
- Don't claim something works unless it was actually tested; say what was and wasn't run.

Where older text here or in `AGENTS.md` says "open a PR only when asked", Don's standing
rule above wins: changes go on a branch and through a PR.
