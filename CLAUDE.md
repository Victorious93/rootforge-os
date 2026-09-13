# CLAUDE.md — RootForge-OS Development Guide & Progress Tracker

> **This file is the single source of truth for Claude Code sessions working on RootForge-OS.**
> Read the **PROJECT STATE** section first, every session, before doing anything else.
> Update the **PROJECT STATE** section at the end of every session before finishing.

---

## 🔖 PROJECT STATE (READ THIS FIRST)

**Last Updated:** `2026-09-13`
**Session status:** `CLOSED — user reviewed and approved this session's changes (commit 4768d50 on claude/continue-per-claude-md-f67jn8, pushed to origin). Nothing further pending from this session; a new session can start clean using PROJECT STATE below. No PR was opened — none was requested.`
**Last Session Summary:** `First real Phase 6 (Active Development) session, on branch claude/continue-per-claude-md-f67jn8. Before doing any of the "What To Do Next" list from the prior session, re-verified it against actual code per the SOURCE OF TRUTH rule — and found item 1 ("dedupe Dockerfile.ndk-matrix, still open") was false: only one copy of the file exists (find confirms it), and git history shows the duplicate was removed in commit 6dbd7dd, before every one of this file's own documentation sessions, all of which repeated the stale "still open" claim from docs/ARCHITECTURE_AUDIT.md (2026-08-08) without re-checking the filesystem. Corrected docs/IMPLEMENTATION_PLAN.md item 4 and every stale mention of this in CLAUDE.md itself rather than doing fake work on an already-solved problem. Also found this file self-contradicted on the README.md scripts/ path fix — the top-of-file summary correctly said it was fixed two sessions ago, but the CURRENT STATE AUDIT section (Phase 5) claimed it was still open; direct grep confirms the fix is real (only 1 "scripts/" match left in README.md, and it is the intentional exception — Magisk's own upstream scripts/boot_patch.sh). Corrected that section too. Then did real Phase 6 work on item 2, P1 item 9 (artifact integrity at build time) — the most consequential open security gap: added version pinning + SHA-256 verification to all 6 chroot hooks that fetch external content during the ISO build (0040-rpi-imager, 0050-starship-eza, 0060-magiskboot, 0062-payload-dumper, 0085-avbtool, 0095-zygisk-headers). Every pinned hash was computed from a real, freshly downloaded copy of the actual artifact (rpi-imager 2.0.4; starship v1.26.0 + eza v0.23.5; Magisk v31.0 APK; payload-dumper-go 2.0.2; the LineageOS avbtool/mkbootimg mirrors pinned to specific commit SHAs instead of the mutable lineage-22.2 branch name; Magisk v31.0's zygisk.hpp) — none were invented. Where upstream publishes its own checksum sidecar (starship, payload-dumper-go), the computed hash was cross-checked against it and matched; where it doesn't (rpi-imager, eza, Magisk, the LineageOS raw files), the hash is trust-on-first-use, verified on every build from here on. Network access to github.com/api.github.com is scoped per-repo in this environment (blocked for repos not attached to the session); reached the needed upstream repos via anonymous shallow git clone (add_repo confirmed this works for public repos without any explicit attach) to get real commit SHAs and release tags rather than guessing them. Verified: dash -n on all 6 rewritten hooks; tests/check-hooks.sh and tests/check-tests.sh both still pass; python3 -m unittest discover (129/129) and bash tests/run-tests.sh (420/0) both still pass; a standalone script re-ran all 14 pinned hashes against the real downloaded files (all matched) plus one deliberate-mismatch negative test (correctly detected and rejected). Did not start P1 items 5-7 (rootforge.core.device/config/log) this session — device abstraction is real, scoped work that deserved its own session rather than being rushed after the security fix, and config additionally introduces this repo's first third-party Python dependency (python3-yaml), which DEVELOPMENT WORKFLOW's own "ask first" list flags explicitly. rootforge-core Python code unchanged this session; only config/hooks/*.hook.chroot, docs/IMPLEMENTATION_PLAN.md, and this file were touched.`

### Current Phase

`[x] PHASES 1-5 COMPLETE — PHASE 6 (ACTIVE DEVELOPMENT) IN PROGRESS`

| Phase | Status | Completed Date | Notes |
|-------|--------|-----------------|-------|
| Phase 0 — Setup & Access | ✅ Complete | 2026-09-11 | See "Phase 0 Findings" below |
| Phase 1 — Repository Inspection | ✅ Complete | 2026-09-13 | See "INSPECTION REPORT" below |
| Phase 2 — Core Architecture Documentation | ✅ Complete | 2026-09-13 | See "CURRENT ARCHITECTURE & IMPLEMENTATION STATE" below |
| Phase 3 — Build System, Testing & Tooling | ✅ Complete | 2026-09-13 | See "BUILD SYSTEM & TOOLING" and "TESTING" below |
| Phase 4 — Workflow & Architecture Rules | ✅ Complete | 2026-09-13 | See "DEVELOPMENT WORKFLOW", "ARCHITECTURAL DECISIONS", "SECURITY CONSIDERATIONS", "KNOWN LIMITATIONS & CONSTRAINTS" below |
| Phase 5 — Final Audit & Next Steps | ✅ Complete | 2026-09-13 | See "CURRENT STATE AUDIT", "RECOMMENDED DEVELOPMENT PRIORITY", "QUICK REFERENCE" below |
| Phase 6 — Active Development (ongoing) | 🟨 In Progress | started 2026-09-13 | P1 item 9 (build-time artifact integrity) landed this session; P0 item 4 (Dockerfile dedup) found already-done and corrected in the docs instead of redone. See RECOMMENDED DEVELOPMENT PRIORITY below for what's left. |
| Phase 7 — Pull Request / Build & Release | ⬜ Not Started | — | — |

**Status Legend:** ⬜ Not Started · 🟨 In Progress · ✅ Complete · 🔁 Needs Revisit

### What To Do Next

`Continue Phase 6 using RECOMMENDED DEVELOPMENT PRIORITY below. P0 item 4 (Dockerfile dedup) and P1 item 9 (build-time SHA-256 verification on the 6 chroot hooks) are both done as of 2026-09-13 — do not redo them; re-verify against code first if something seems off, per the recurring lesson below. Next up, in order: (1) rootforge.core.device (P1 item 5) — device abstraction, no new dependencies needed, removes real duplication across flash_patched_boot.sh/backup_partitions.sh/unlock_bootloader.sh; (2) rootforge.core.config (P1 item 6) — depends on item 5's device model for its per-device override layer, and introduces this repo's first third-party Python dependency (python3-yaml) — flag that explicitly and consider asking before adding it, per DEVELOPMENT WORKFLOW's "ask first" list; (3) rootforge.core.log (P1 item 7); (4) backup integrity CLI (P1 item 8). Do not start any P3 item (rootforge-kernel, dynamic-partition tooling, GUI) before P0-P2 are stable. Every new phase-6 session should re-verify code before trusting this file's prose, per the SOURCE OF TRUTH rule — this file has now gone stale at least twice: the 2026-08-08 ARCHITECTURE_AUDIT.md's claims were carried forward uncritically for weeks, and the "Phase 5 continuation" premise for one session's own branch name turned out to be false. The 2026-09-13 Phase 6 session found and fixed a third instance (the Dockerfile dedup claim) — treat every status claim in this file, including this one, as something to spot-check against the actual repo rather than trust outright.`

### Open Questions / Blockers

- `shellcheck` is not installed in this environment, so `tests/lint.sh` / `make lint` cannot be run locally here (CI installs it via apt in `.github/workflows/lint.yml`). Confirmed still true this session. Not a blocker for inspection/documentation work, but blocks locally verifying lint-clean status before a push — carry into Phase 3 tooling docs.
- `pytest` is not installed, but is not required: `tests/test_*.py` use Python's stdlib `unittest` and are run via `python3 -m unittest discover` / through `tests/run-tests.sh`, not pytest. Confirmed working this session (129/129 pass).
- Android-specific tooling (`adb`, `fastboot`, `aapt`, `repo`) and image-build tooling (`mksquashfs`, `mkbootimg`, `cpio`) are not installed in this container. The test suite stubs these (see `tests/stubs/`, `tests/README.md`) so the hermetic suite does not need them; they would be required for real on-device flashing/building work, which is out of scope unless a session is explicitly asked to do it.
- **Resolved this session:** `docker`'s role — it is an in-ISO runtime package (`docker.io`, installed via `config/package-lists/*.list.chroot` and the bootstrap hook that adds the login user to the `docker` group) used exclusively by `build_matrix.sh` for isolated NDK/API version-matrix builds *on a running RootForge OS install*. It is not used by this repo's own build, lint, or test tooling — the `docker` binary present in this dev container is incidental (base image tooling) and irrelevant to RootForge-OS's own pipeline.
- **New:** `tests/run-tests.sh` reported **420 passed, 0 failed** this session, not the "421-check" figure recorded in the prior session's summary (2026-09-11). The 129-test Python unittest count matches exactly. The 1-check delta in the shell suite is unexplained — `[Guessing]` it reflects either a since-removed/merged check or an environment-conditional check (a tool-presence branch) that counts differently here than in the prior session's container. Not investigated further this session (documentation-inventory scope, not a suite audit) — worth a `git log -p` on `tests/run-tests.sh`/`tests/check-tests.sh` between the two session dates if the exact count matters for Phase 3.
- **New:** `docs/ARCHITECTURE_AUDIT.md` is dated 2026-08-08 and states as fact that no `rootforge` CLI, no `tests/` directory, and no Python package exist in this repository. All three claims are now false — confirmed by direct inspection this session (see INSPECTION REPORT). This is not a contradiction to resolve by editing that file (it's a dated audit, valid as of its own commit), but Phase 2 documentation must not cite it uncritically — cite the current tree instead. `docs/IMPLEMENTATION_PLAN.md`'s own "P0.5 (landed)" section already documents that this gap was closed after the audit was written, which is consistent with what direct inspection shows.
- **Resolved 2026-09-13 (Phase 6 session):** the Dockerfile.ndk-matrix dedup (P0 item 4) that every prior session (including the one that wrote the "What To Do Next" line handed to this session) reported as still open was actually already done, in commit `6dbd7dd`, which predates Phase 0 of this whole documentation effort. `find . -iname Dockerfile.ndk-matrix` returns exactly one match; `git log --all` on the second path shows it removed and never restored. The false claim traces back to `docs/ARCHITECTURE_AUDIT.md` (2026-08-08), which every later session cited without re-running `find`/`diff` to check. Corrected in `docs/IMPLEMENTATION_PLAN.md` item 4 and everywhere this file repeated the claim. Lesson for future sessions: a "confirmed this session" claim in a prior CLAUDE.md session is not itself confirmation — re-run the actual check.
- **Resolved 2026-09-13 (Phase 6 session):** this file self-contradicted on the README.md `scripts/` path fix — the PROJECT STATE summary two sessions ago said it was fixed, but the CURRENT STATE AUDIT section (written in the *same* session, Phase 5) said it was still open. Direct check: `grep -c "scripts/" README.md` → 1, and that one match is the intentional exception (Magisk's own upstream `scripts/boot_patch.sh`). The fix is real; the CURRENT STATE AUDIT section's claim was wrong and has been corrected.
- **New:** GitHub access (`github.com`/`api.github.com`/`codeload.github.com`) in this remote execution environment is scoped per-repository to whatever this session has attached — requests for any other repo return a proxy-injected 403 with an `add_repo`-pointing error body, not a real GitHub error. `raw.githubusercontent.com` was *not* subject to this restriction in this session (plain content fetches succeeded for arbitrary public repos without attaching them first). Anonymous shallow `git clone` of public GitHub repos also worked directly without needing `add_repo` at all (confirmed for eza-community/eza, topjohnwu/Magisk, ssut/payload-dumper-go, starship/starship, and two LineageOS mirror repos) — `add_repo`'s own response for public repos says as much. Relevant to any future session that needs to verify an external hash/version: don't assume `api.github.com` "latest release" lookups work for third-party repos; clone shallow and read tags/commits directly instead.

---

## Phase 0 Findings (Setup & Access)

- **Repository path:** `/home/user/rootforge-os`, git repo confirmed (`git status` clean).
- **Remote:** `origin` → `https://github.com/Victorious93/rootforge-os` (fetch & push). Default branch confirmed via `git remote show origin`: `main`.
- **Branch:** working branch `claude/rootforge-os-setup-ul61a4`, currently identical to `origin/main` (`git rev-list --left-right --count HEAD...origin/main` → `0 0`), working tree clean, nothing staged or untracked.
- **Branching convention (observed, not documented elsewhere):** feature branches named `claude/<slug>` merged into `main` via PR (recent history: `claude/rootforge-os-readme-*`, `claude/tool-bugs-improvements-*`, plus many `claude/p0-*`/`p1-*`/`p2-*` branches present on the remote for what look like phased implementation slices).
- **Top-level layout:** `README.md`, `BUILD.md`, `HACKING.md`, `Makefile`, `assets/`, `auto/` (live-build hooks), `config/` (live-build config: archives, bootloaders, hooks, package-lists, includes.chroot), `docs/` (`ARCHITECTURE_AUDIT.md`, `IMPLEMENTATION_PLAN.md`), `termux/` (PRoot/Termux integration scripts), `tests/`. No `CLAUDE.md` existed prior to this session — this file is newly created.
- **Toolchain available in this environment:**
  - `python3` 3.11.15, `pip3` 24.0 — present
  - `bash` 5.2.21, `make` 4.3, `gcc` 13.3.0, `git` 2.43.0 — present
  - `docker` 29.3.1 — present
  - `java` (OpenJDK 21.0.10) — present
  - `xz`, `gzip`, `curl`, `wget` — present
  - `pytest` — **not installed** (not needed; suite uses stdlib `unittest`)
  - `shellcheck` — **not installed** (needed for `tests/lint.sh` / `make lint`; CI installs it)
  - `adb`, `fastboot`, `repo`, `aapt`, `cpio`, `mksquashfs`, `mkbootimg` — **not installed** (device/image-build tooling; hermetic tests stub these)
- **Test suite verified runnable:**
  - `python3 -m unittest discover -s tests -p 'test_*.py'` → 129 tests, all pass.
  - `bash tests/run-tests.sh` → 421 checks (shell + python), 0 failed. Per `tests/README.md`, the suite is designed to be hermetic (no device, Docker, or network required) and uses environment-variable seams (`ROOTFORGE_SYSCTL_FILE`, `ROOTFORGE_GRUB_DEFAULTS`, etc.) to avoid touching the host system.
- **CI:** `.github/workflows/lint.yml` (shellcheck, YAML duplicate-key lint, package-list resolution against Debian bookworm, test suite, CLI smoke test) and `.github/workflows/release.yml` (referenced by `Makefile` comments as building/publishing ISOs and Termux/PRoot tarballs to GitHub Releases on tagged pushes — not yet independently inspected).
- **`.gitignore`:** excludes live-build artifacts (`cache/`, `chroot/`, `binary/`, `live-image/`, `*.iso`, `*.img`, `*.log`, `.build/`), `termux/build-rootfs.sh` output (`dist/`, `rootforge-proot-*.tar.*`), and Python bytecode.

**Exit criteria met:** repository is accessible, current branch/state is known and in sync with `origin/main`, and the toolchain gaps above are documented rather than blocking. Phase 1 can proceed.

---

## HOW TO USE THIS FILE (SESSION PROTOCOL)

Every Claude Code session working on RootForge-OS must follow this protocol:

1. **Read the PROJECT STATE section above.** Determine the current phase and what was left incomplete.
2. **Read the relevant phase section below** for detailed tasks, deliverables, and success criteria.
3. **Inspect the actual repository** — never assume state from memory or from this file's descriptions alone. Verify against real code.
4. **Do the work for the current phase.** Do not skip ahead to later phases unless explicitly instructed.
5. **Update the PROJECT STATE section** at the end of the session:
   - Update `Last Updated` date
   - Update `Last Session Summary`
   - Update the phase status table
   - Update `What To Do Next`
   - Add any new `Open Questions / Blockers`
6. **Commit the updated CLAUDE.md** along with any other changes, so the next session (yours or a teammate's) picks up exactly where this one left off.

**This file is not a static planning document — it is a living state file.** Treat every session like resuming a saved game: read state → act → save state.

---

## FOUNDATIONAL PRINCIPLES

### ACCURACY & HONESTY (NON-NEGOTIABLE)

- Never lie, fabricate, hallucinate, or invent facts, sources, commands, dependencies, capabilities, test results, or information.
- Never agree merely to be agreeable. If the user is wrong, mistaken, or overlooking something important, explain clearly.
- Clearly distinguish verified facts, reasonable inferences, assumptions, and speculation.
- When uncertain, state what is uncertain and what information would resolve it.
- Never claim to have performed an action, accessed a system, tested code, or verified information unless you actually did.
- Prefer "I don't know" or "I cannot verify that" over an unsupported guess.
- Never invent files, commands, APIs, dependencies, build systems, features, test results, platform support, or hardware support.
- Never describe planned functionality as implemented.
- Fabricated code, fake APIs, placeholder implementations, TODO-driven "implementations," and hardcoded secrets are all unacceptable.

### REASONING & PROBLEM-SOLVING

- Challenge assumptions when appropriate instead of blindly following the initial approach.
- Identify missing requirements, contradictions, edge cases, failure modes, security concerns, compatibility issues, and better alternatives.
- Before recommending an approach, consider whether there is a simpler, safer, more reliable, more efficient, less expensive, or more maintainable solution.
- Recommend the objectively better option when one exists, even when it differs from the initial idea.
- Do not add unnecessary complexity merely to make a solution appear more sophisticated.
- Prioritize correctness → architecture → security → testability → maintainability → functionality.

### CONFIDENCE TAGGING

When making substantive claims, use:
- `[Certain]` — directly verified or a well-established fact
- `[Likely]` — a strong conclusion supported by available evidence but not fully verified
- `[Guessing]` — speculation, assumption, or estimate

Do not present assumptions or guesses as facts. Do not use confidence labels as a substitute for evidence.

---

## PROJECT IDENTITY

**Project Name:** RootForge-OS

**Vision:** A complete operating-system/platform ecosystem with multiple ways to access and operate the RootForge environment.

### Long-Term Platform Support

RootForge-OS is intended to eventually support:

1. **RootForge-OS for Linux PCs** — Native Linux implementation
2. **RootForge-OS for Windows PCs** — Windows-compatible environment
3. **RootForge-OS Android APK** — Full-featured Android application (NOT a simple companion app)
4. **Headless CLI Access** — Server/automation-focused operation
5. **GUI Access** — User-friendly graphical interface
6. **Android Terminal Environment** — Embedded Termux-based environment
7. **Remote/Local Administration** — Multi-node management and control

### Android Application Scope (Critical)

The Android application is a serious RootForge runtime/management environment, not a companion app. It should eventually provide:
- GUI interface for RootForge management
- Terminal access (via embedded/integrated Termux)
- Headless CLI operation
- Local RootForge management and execution
- Remote RootForge management and control
- Automation and scripting
- Development/management tooling
- Configuration and administration

### CLI-First Design Principle

Important functionality must be accessible through CLI/API interfaces, not locked exclusively inside a GUI.

```
GUI/Terminal/Remote Client
         ↓
RootForge API / Service Layer
         ↓
Core Functionality
         ↓
Platform Implementation
```

Avoid duplicating business logic between GUI, CLI, Android, Linux, Windows, and remote clients.

### Headless Mode

RootForge must be capable of operating without a graphical interface where technically appropriate: server/headless installs, SSH or equivalent remote access, CLI administration, API access, service management, logging, automation, scripting.

### Remote Architecture

```
RootForge Controller (Linux/Windows/Android)
    |
    +---- Linux RootForge node
    |
    +---- Windows RootForge node
    |
    +---- Android RootForge node
    |
    +---- Headless RootForge node
```

The Android application may function as: local controller, remote controller, terminal client, management console, or RootForge node. Do not assume which role is currently implemented — verify against actual code.

---

## REPOSITORY SCOPE & BOUNDARIES

### IN SCOPE

1. **RootForge Core** — configuration, system state, service/process/filesystem/networking/package abstractions, logging, auth, permissions, security policies, automation, APIs/IPC, shared data models. Must remain platform-independent as reasonably practical.
2. **Linux RootForge-OS** — build/boot mechanisms, service/process/networking/storage management, package management, CLI/GUI access, headless operation.
3. **Windows RootForge Platform** — native components, client, management tools, service integration, adapters, WSL/virtualization integration where appropriate. **Never assume Windows can directly implement Linux kernel functionality.**
4. **Android RootForge Application** — GUI, CLI, headless operation, local/remote management, terminal access, APIs, automation, device management.
5. **Termux Integration** — runtime provisioning, package management, command execution, filesystem/process integration, GUI↔terminal communication, Termux↔RootForge service communication. **Do not duplicate an entire external OS project.**
6. **CLI** — first-class component; direct control/management; must not become an independent implementation of business logic.
7. **GUI** — acts as a client of RootForge services/APIs; core business logic does NOT live in the GUI.
8. **Headless Operation** — CLI-only, service operation, remote admin, API access, SSH-equivalent access, logging, automation.
9. **Remote RootForge Management** — node discovery/identity/auth, secure transport, command/event transport, state sync, remote logs/config. **Discovery must never imply authorization.**
10. **Build, Packaging, Release Infrastructure** — build/packaging scripts, ISO/APK/Windows/Linux packaging, CI/CD, signing infra. **Never commit secrets or private keys.**
11. **Tests** — core, API, CLI, Linux, Windows, Android, Termux integration, remote-management, security, build tests.
12. **Documentation** — architecture, install, build, CLI/API, security, platform, dev, deployment docs. **Must reflect current implementation, not planned features.**

### OUT OF SCOPE

1. **Unrelated operating systems** — do not copy/embed unrelated Linux/Android/Windows/BSD distributions. Integration is fine; copying is not.
2. **Unrelated third-party projects** — prefer package dependencies, submodules, documented integrations, APIs/plugins over source duplication.
3. **User data** — never commit personal files, credentials, API keys, SSH private keys, certs with private keys, personal logs, tokens, production data.
4. **Build artifacts** — do not commit compiled binaries, temp build dirs, generated APKs/ISOs/installers, caches.
5. **Local development environment** — do not commit personal editor config, absolute local paths, local credentials, machine-specific config.
6. **Experimental features without ownership** — label clearly: `[EXPERIMENTAL]`, `[PROTOTYPE]`, `[PROOF-OF-CONCEPT]`, `[DEPRECATED]`, `[UNSUPPORTED]`. Never present as production.
7. **Application-specific business logic outside RootForge** — external apps consume stable RootForge interfaces; they don't get absorbed into this repo unless officially part of the platform.

### EXTERNAL DEPENDENCY BOUNDARY

For every significant external dependency, document: what it's used for, why required, how RootForge communicates with it, supported version range, optional/required, fallback if unavailable, licensing, and whether RootForge wraps/embeds/integrates it. **Never silently fork external software.**

### SCOPE DECISION RULE

Before adding new functionality, ask:
1. Does this directly provide RootForge functionality?
2. Is it required to build, run, test, secure, package, or manage RootForge?
3. Does it belong to an officially supported RootForge platform/client?
4. Can it be maintained by the RootForge project?
5. Does it duplicate an existing external project unnecessarily?
6. Does it belong in core, platform, client, tooling, or external-integration layer?

If unclear, don't add it automatically — determine the architectural boundary first.

### NO SCOPE CREEP

Before introducing a major new subsystem: identify purpose, architectural owner, dependencies, security implications, maintenance cost, and whether it belongs in RootForge-OS at all. Prefer a smaller, well-defined architecture over an enormous repository containing unrelated functionality.

---

## ARCHITECTURE PRINCIPLES

### Unified Platform With Platform Layers

```
                    RootForge Core
                         |
          +--------------+--------------+
          |              |              |
       Linux          Windows         Android
          |              |              |
       Native          Native         Native
    implementation   implementation  implementation
          |              |              |
          +--------------+--------------+
                         |
                Common API / IPC
                         |
              +----------+----------+
              |                     |
             CLI                   GUI
              |
        Headless operation
```

This is an architectural model, not a requirement to force identical implementations. Platform differences must be respected.

### Security Foundations

Least privilege, explicit authorization, secure defaults, authentication/authorization, secrets management, secure comms + cert validation, key management, audit logging, command validation, filesystem/process isolation, Android sandbox restrictions, Windows/Linux privilege boundaries.

**Never silently escalate privileges. Never assume root/admin access.**

### Cross-Platform Development

Prefer: `Shared core + Platform adapters + Platform-specific implementations + Common APIs/protocols` over duplicating functionality in every client. Do not force artificial abstractions that make the architecture worse.

### Termux Integration Specifics

Do not assume "Termux built in" means simply bundling an APK. Determine: how the terminal runtime is provisioned, how packages are installed, how commands communicate with RootForge, filesystem boundaries, process lifecycle, Android sandbox limitations, privileged vs. non-root capabilities, GUI↔CLI communication, Termux↔RootForge service communication. **Root access must never be assumed — always verify.**

---

## PHASED WORKFLOW

Development proceeds through 8 phases (0–7). Phases 1–5 build the documentation foundation (this file); Phase 6 is ongoing active development; Phase 7 covers PR/build/release. Update the **PROJECT STATE** table at the top of this file as each phase progresses.

### Phase 0 — Setup & Access

**Objective:** Confirm repository access and environment readiness before any inspection work begins.

**Tasks:**
- Confirm the repository is cloned/accessible and identify its exact path/URL
- Confirm git remote, current branch, and clean working tree status
- Confirm required tools are available (compilers, SDKs, package managers) or document what's missing
- Note the repository's default branch and branching conventions if discoverable

**Deliverable:** Confirmed access + environment notes in PROJECT STATE.

**Exit Criteria:** Repository is accessible, current branch/state is known, and blockers (if any) are documented.

---

### Phase 1 — Repository Inspection & Assessment

**Objective:** Thoroughly inspect the actual repository state without writing final documentation yet.

**Tasks:**
1. Map all directories and their purposes
2. Identify all source code languages and frameworks
3. List all build systems and package managers
4. Inspect configuration files (.yml, .yaml, .json, .toml, etc.)
5. Document CI/CD configuration if present
6. Identify existing documentation (README, guides, API docs)
7. List all major components and their implementation status
8. Identify test infrastructure
9. Document all external dependencies
10. Catalogue TODOs, stubs, placeholders, dead code
11. Assess Linux functionality (what works, what doesn't)
12. Assess Windows functionality
13. Assess Android functionality
14. Assess Termux integration status
15. Assess CLI functionality and available commands
16. Assess GUI status and capabilities
17. Determine current build targets and their status
18. Identify any scope violations (unrelated projects bundled in, etc.)

**Deliverable:** Inspection report appended to this file under `## INSPECTION REPORT` (create the section if it doesn't exist; replace old content if re-running this phase).

**Exit Criteria:** Accurate inventory of actual code and structure; clear IMPLEMENTED / PARTIALLY IMPLEMENTED / SCAFFOLDED / PLANNED / MISSING distinctions for every major area; no fabricated claims.

---

### Phase 2 — Core Architecture & Implementation Documentation

**Objective:** Document the current architecture, implemented components, and actual capabilities based on Phase 1 findings.

**Tasks:**
1. Document RootForge Core — what platform-independent functionality actually exists
2. Document each implemented component: purpose, code location, status, dependencies, tests
3. Document Linux implementation — actual code, actual behavior
4. Document Windows implementation — actual state, architecture decisions, limitations
5. Document Android implementation — frameworks used, actual architecture
6. Document Termux integration — current implementation or planned design
7. Document CLI — actual commands and behavior
8. Document GUI — actual framework, actual features
9. Document APIs/IPC — actual communication mechanisms
10. Document authentication/authorization — actual implementation
11. Document configuration system — actual mechanism
12. Document remote management — actual state if implemented
13. Document logging/audit — actual state
14. Build a component dependency diagram
15. Build a full implementation status table

**Deliverable:** `## CURRENT ARCHITECTURE & IMPLEMENTATION STATE` section, written into this file.

**Exit Criteria:** Every component status is accurate and verifiable; every claimed feature confirmed in code; no planned features presented as implemented.

---

### Phase 3 — Build System, Testing & Tooling

**Objective:** Document how to actually build, test, and develop RootForge-OS.

**Tasks:**
1. Document the complete build process for each platform target
2. Document actual, verified build commands
3. Document build prerequisites and installation steps
4. Document build artifacts and their locations
5. Document testing infrastructure (frameworks, organization, locations)
6. Document actual commands to run tests (all, core-only, platform-specific, single-component)
7. Document current test coverage vs. gaps
8. Document development environment setup steps
9. Document CI/CD pipeline behavior if present
10. Document release/packaging process if present
11. Document common build troubleshooting steps

**Deliverable:** `## BUILD SYSTEM & TOOLING` and `## TESTING` sections, written into this file.

**Exit Criteria:** All build/test commands are real and verified (or explicitly marked unverified); development environment setup is reproducible from the documentation alone.

---

### Phase 4 — Workflow & Architecture Rules

**Objective:** Document how to work on RootForge-OS going forward, including decision frameworks.

**Tasks:**
1. Document the Claude Code session workflow specific to this repo
2. Document security considerations specific to RootForge-OS
3. Document architectural decisions already made, with rationale
4. Apply scope boundaries concretely to this project's actual structure
5. Document privilege handling (root/admin operations, how they're gated)
6. Document remote/multi-node architecture if applicable
7. Build a decision framework for evaluating new functionality
8. Document coding standards actually followed in the repo
9. Clarify what requires asking before proceeding vs. proceeding autonomously

**Deliverable:** `## DEVELOPMENT WORKFLOW`, `## ARCHITECTURAL DECISIONS`, `## SECURITY CONSIDERATIONS`, `## KNOWN LIMITATIONS & CONSTRAINTS` sections, written into this file.

**Exit Criteria:** Workflow reflects actual current practices; architectural decisions have documented rationale; security boundaries are explicit; known limitations are clearly stated.

---

### Phase 5 — Final Audit & Recommended Next Steps

**Objective:** Complete the documentation foundation with a comprehensive status audit and prioritized roadmap.

**Tasks:**
1. Compile the comprehensive implementation status table across all components
2. Audit scope adherence — flag any violations found during Phases 1–4
3. Document what's missing relative to the full RootForge vision
4. Prioritize next development steps by architectural dependency
5. Build a recommended development order with rationale
6. Document known bugs and incomplete work
7. Document external resource links
8. Build a table of contents for this file
9. Final accuracy review — cross-check every claim against actual code

**Deliverable:** `## CURRENT STATE AUDIT`, `## RECOMMENDED DEVELOPMENT PRIORITY`, `## QUICK REFERENCE` sections, written into this file.

**Exit Criteria:** Documentation foundation (Phases 1–5) is complete, accurate, and actionable. This is the point at which CLAUDE.md is considered "established" and Phase 6 (active development) begins.

---

### Phase 6 — Active Development (Ongoing)

**Objective:** Implement actual RootForge-OS functionality using the documented architecture as the contract, and keep the documentation synchronized with reality.

**Session workflow for every development session in this phase:**
1. Read PROJECT STATE — what was being worked on last session?
2. Inspect the relevant code directly — do not trust stale documentation
3. Determine current implementation state of the target area
4. Identify dependencies and architectural constraints
5. Create a plan before writing code
6. Implement the smallest correct change
7. Run relevant tests and report actual results
8. Inspect resulting changes to verify they work
9. Update this file's relevant sections if architecture changed
10. Update PROJECT STATE with exactly what changed, what was tested, what remains incomplete

**Priority order for new work** (adjust if actual repo architecture demonstrates a better order):
1. Core architecture
2. Platform abstraction
3. Configuration/state
4. Security/identity/permissions
5. Core services
6. CLI/API
7. Linux platform
8. Windows platform
9. Android platform
10. Termux integration
11. Remote management
12. GUI
13. Automation
14. Advanced features

**Autonomous vs. ask-first:**
- **Proceed autonomously:** clear non-destructive feature work, bug fixes following established patterns, isolated changes, following existing architectural patterns.
- **Ask first:** destructive operations (`rm -rf`, disk/partition changes, DB/volume deletion, git history rewrite, root ops), changes risking data loss/production breakage/secret exposure, new major dependencies, core architecture/API changes, privilege escalation changes, major multi-component refactors.

**Feature completion standard:** A feature is NOT complete merely because a class/function/UI button exists, a README mentions it, a TODO exists, a stub returns a value, or an API endpoint exists without working behavior. It's implemented only when actual behavior works and is tested or otherwise verifiable.

**Exit Criteria** (per session, not per phase — this phase is ongoing): Session's changes are implemented, tested, reported accurately, and PROJECT STATE is updated.

---

### Phase 7 — Pull Request / Build & Release

**Objective:** Package completed work for review, integration, and release.

**Tasks:**

*Pre-PR checklist:*
- Run full test suite and report actual results
- Run relevant builds for affected platform targets and report actual results
- Confirm no secrets, credentials, or user data are staged for commit
- Confirm no unintended build artifacts are staged
- Confirm CLAUDE.md is updated if architecture changed
- Confirm commit messages accurately describe changes (no inflated claims)

*Pull request creation:*
- Write a PR description covering: what changed, why, what was tested, what remains incomplete or out of scope, any breaking changes
- Link related issues/roadmap items if applicable
- Flag any scope-boundary judgment calls made during the work for reviewer attention

*Build & packaging (when applicable):*
- Document/execute the actual build command(s) for affected targets (Linux/Windows/Android/CLI/GUI)
- Verify build artifacts are produced correctly
- Do not claim a build succeeded unless it was actually run and verified

*Release (when applicable):*
- Follow the repository's actual release process (only if one is documented/discovered — do not invent one)
- Document version bump, changelog update, signing/packaging steps actually performed

**Deliverable:** PR opened (or ready-to-open PR description provided), with accurate test/build evidence attached.

**Exit Criteria:** Changes are reviewable, test/build evidence is real and reported, CLAUDE.md reflects the resulting state, and PROJECT STATE is updated to reflect the PR/release outcome.

---

## RULES FOR CLAUDE CODE (APPLY IN EVERY PHASE)

**Do's**
- ✓ Inspect code before modifying
- ✓ Run tests after changes
- ✓ Report actual verified results
- ✓ Update CLAUDE.md when architecture changes
- ✓ Follow scope boundaries
- ✓ Prioritize architecture over features
- ✓ Prioritize correctness over code quantity
- ✓ Update PROJECT STATE at the end of every session

**Don'ts**
- ✗ Fabricate facts or test results
- ✗ Claim features exist without verifying
- ✗ Introduce scope creep
- ✗ Duplicate external projects
- ✗ Commit secrets or credentials
- ✗ Make destructive changes without justification
- ✗ Present planned work as implemented
- ✗ Override architectural constraints without discussion
- ✗ Leave PROJECT STATE stale or inaccurate at session end

---

## SOURCE OF TRUTH

- When this file and the implementation disagree → implementation is correct; update this file.
- When this file and other documentation disagree → verify against implementation, then correct the wrong document.
- When this file and roadmap/issues/plans disagree → plans reflect future work; this file reflects current state.

---

## INSPECTION REPORT

**Run:** 2026-09-13, on branch `claude/build-per-claude-md-661ey0` (identical to `origin/main` at `efacac8` at session start; no code changes were made during this inspection, only this file). All claims below marked `[Certain]` were directly verified this session (file read, command run, or test executed); `[Likely]` are strong inferences not exhaustively verified; `[Guessing]` are explicitly flagged as such.

### 1. Directory map and purpose **[Certain]**

| Path | Purpose |
|---|---|
| `auto/config`, `auto/build` | live-build invocation wrapper (`lb config`, `lb build noauto`) — see docs/ARCHITECTURE_AUDIT.md §1 for the recursion bug `auto/build` works around; that fix is present in the current file. |
| `config/package-lists/*.list.chroot` | 4 apt package lists (`rootforge`, `rootforge-ai`, `rootforge-flagship`, `rootforge-installer`) installed into the squashfs. |
| `config/hooks/*.hook.chroot` | 15 numbered POSIX-`sh` scripts run inside the chroot at build time (0005–0098). Flat, not nested under `live/`/`normal/` subdirectories — consistent with the fix the audit describes for the historical hook-discovery bug (§3.9 of the audit). |
| `config/archives/`, `config/bootloaders/isolinux/` | Custom apt source and corrected isolinux theme, each working around a specific live-build bug per the audit. |
| `config/includes.chroot/` | Files overlaid verbatim onto the built filesystem: Calamares installer config/branding, systemd units, udev rule for Android USB devices, Plymouth boot theme, and `usr/local/bin/`+`usr/local/lib/rootforge/` (see §3 below). |
| `docs/ARCHITECTURE_AUDIT.md`, `docs/IMPLEMENTATION_PLAN.md` | Prior architecture audit (dated 2026-08-08) and its companion prioritized task list. Partially stale — see Open Questions above and §7 below. |
| `termux/` | A second, independent build target: a debootstrap-based (not live-build) PRoot/chroot-installable rootfs for Android devices via Termux, reusing `config/hooks/*`. |
| `tests/` | Hermetic test suite: 8 Python `unittest` files, a shell test/lint driver, fake `adb`/`fastboot`/etc. stubs, and static self-checks on the suite itself. |
| `assets/logo/` | Two logo image files. No other binary/media assets. |
| `README.md`, `BUILD.md`, `HACKING.md` | 579 / 76 / 290 lines respectively. User-facing spec/build guide, host prerequisites, contributor-facing internals. |
| `Makefile` | `test`, `lint`, `build`, `clean`, `distclean`, `checksum`, `list-usb`, `flash` targets. |

No `src/`, `rootforge/` (top-level package), `scripts/`, `docker/` (top-level), or `installer/` directories exist — confirmed by direct listing, matching the audit's own claim on this specific point.

### 2. Language inventory **[Certain]**

| Language | Where | Notes |
|---|---|---|
| Python 3 | `config/includes.chroot/usr/local/lib/rootforge/` (core CLI package, 9 files, ~1,309 lines) + `.../second-brain/brain.py` (452 lines) + `tests/test_*.py` (8 files) | stdlib-first; no `requirements.txt`/`pyproject.toml`/`setup.py` anywhere — confirmed no such file exists. No third-party Python packages are imported anywhere in `rootforge.core.*` (grepped; none found). |
| Bash (`#!/usr/bin/env bash` / `#!/bin/bash`) | 27 scripts in `config/includes.chroot/usr/local/bin/`, `auto/build`, `termux/build-rootfs.sh` and siblings, `tests/*.sh` | The bulk of the device-facing tooling. |
| POSIX `sh` (`#!/bin/sh` or no shebang, sourced) | `auto/config`, all 15 `config/hooks/*.hook.chroot`, `termux/proot-distro-plugins/rootforge.sh` | Required by live-build's own execution model (dash, `set -e`, no `pipefail`). |
| QML | `config/includes.chroot/etc/calamares/branding/rootforge/show.qml` | Calamares branding, third-party installer's own config format, not RootForge application code. |

No Go, Rust, TypeScript/JavaScript, Java, Kotlin, C/C++, C#, or Swift exists anywhere in the repository — confirmed by extension search. This directly contradicts nothing in CLAUDE.md's stated long-term vision (Windows/Android native apps), because those platforms are simply **not started** (see §6).

### 3. The `rootforge` CLI and `rootforge-core` package — confirmed real, not a stub **[Certain]**

This is the single biggest correction to `docs/ARCHITECTURE_AUDIT.md`, which states (as of its 2026-08-08 commit) that "`rootforge` as a CLI binary, package, or entry point does not exist anywhere in this repository." As of this session's tree, it does:

- `config/includes.chroot/usr/local/bin/rootforge` — a 4-line POSIX `sh` shim: `exec env PYTHONPATH=... python3 -m rootforge.core.cli "$@"`.
- `config/includes.chroot/usr/local/lib/rootforge/core/` — 9 Python files:

| Module | Lines | Role |
|---|---|---|
| `cli.py` | 145 | `argparse`-based dispatcher, `allow_abbrev=False` throughout (deliberate — see inline comment on abbreviation ambiguity risk for destructive flags). Registers `doctor`, `devices`, and delegates `module`/`flash`/`backup`/`ota`/`boot`/`avd` to their own sub-parsers. |
| `doctor.py` | 284 | `rootforge doctor` — host tool checks, disk space, Ollama reachability, Claude Code CLI presence, `~/rootforge` writability; `--json`/`--quiet`/`--strict`. |
| `devices.py` | 159 | `rootforge devices` — merges `adb`+`fastboot` device enumeration into one list; `-l`/`--detailed`, `--json`. |
| `flashing.py` | 151 | `flash`/`backup` subcommand group — wraps `flash_patched_boot.sh`/`backup_partitions.sh`/`restore_partitions.sh`. |
| `module.py` | 102 | `module scaffold/lint/build` — wraps `new_module_scaffold.sh`/`lint_module.sh`/`build_magisk_module.sh`. |
| `ota.py` | 110 | `ota extract/inspect` — wraps OTA/payload-dumper handling. |
| `boot.py` | 128 | `boot patch/flash-last` (partial — see §7, item still lists `unpack`/`repack`/`verify` as not yet built). |
| `avd.py` | 122 | `avd create/boot/list` — wraps `setup_rooted_avd.sh`. |
| `runner.py` | 101 | Shared subprocess-invocation helper used by the wrapper modules above. |

No `rootforge.core.config` or `rootforge.core.device` (the P1 items in `docs/IMPLEMENTATION_PLAN.md` — central config system and device-abstraction dataclass) exist yet — confirmed by direct file listing and grep for `python3-yaml`/`yaml` (zero matches in package lists or core source). **These remain PLANNED, not implemented**, contrary to what a casual read of the plan's P0.5 "(landed)" heading might suggest if not checked carefully — P0.5 landed the CLI skeleton, `doctor`, `devices`, and the P2 wrapper groups; it did not land P1 items 6 (config) or 5 (`Device` dataclass, beyond `devices.py`'s own ad hoc detection).

The 27 pre-existing standalone shell scripts in `usr/local/bin/` are **unchanged in location and remain independently invocable** — the CLI wraps them via subprocess (per `runner.py`), it has not absorbed or replaced their logic. This matches the audit's own recommended architecture (§4: "not a rewrite, a layer added underneath").

### 4. Build systems **[Certain]**

Three independent build/packaging paths, no conflict between them:

1. **live-build** (`auto/config` + `auto/build` + `config/`) → a bootable, installable (via Calamares) Debian 12 Bookworm amd64 ISO. Invoked via `sudo make build` or `sudo auto/build`.
2. **debootstrap** (`termux/build-rootfs.sh`) → a PRoot- or chroot-installable rootfs tarball for Termux on Android, arm64 or amd64, `proot` or `chroot` flavor (4 combinations), reusing `config/hooks/*` and a pruned package list.
3. **Python** — no build step; `rootforge.core` and `brain.py` run directly via `python3 -m` or a shim script, no packaging/wheel/compiled step exists.

No CMake, autotools, Gradle/Maven (despite Android Studio/Gradle being *target-environment* dependencies documented in README §2 — those are packages the built ISO installs for its *users*, not this repo's own build system), npm, or Cargo anywhere in this repo's own tooling.

### 5. CI/CD **[Certain]**

- **`.github/workflows/lint.yml`** — 5 jobs on PR + push to `main`: `shellcheck` (via `tests/lint.sh`, shared with `make lint`), `yaml-lint` (custom duplicate-key checker — plain `yaml.safe_load` silently accepts dupes, exactly the class of bug that caused a real prior CI failure per the workflow's own comment), `package-lists` (verifies every listed apt package resolves against a real `debian:bookworm` container), `tests` (`tests/run-tests.sh`), `python` (CLI smoke test: `--version`, `--help`, `doctor --json`, `devices --json` shape validation).
- **`.github/workflows/release.yml`** — triggered on `v*` tags or manual dispatch: builds the amd64 ISO (`build-iso` job, ~90 min timeout, frees ~20GB disk first) and the 4-combination Termux rootfs matrix (`build-termux-rootfs`), then creates a **draft** GitHub Release with checksummed artifacts attached (`release` job, tag-push only). The workflow's own header comment states `[Likely]` it has never actually been exercised on GitHub's infrastructure from this repo — this session did not run it either (would require ~20GB disk, loop-device/root access, and 60–90 minutes; out of scope for a documentation-inspection session). **Not independently verified this session** — status as "should work, unexercised" carried forward from the workflow's own self-assessment, not newly confirmed.

### 6. Per-platform / per-vision-item status, against CLAUDE.md's own "Long-Term Platform Support" list **[Certain]** unless noted

| Vision item | Status | Evidence |
|---|---|---|
| RootForge-OS for Linux PCs | **IMPLEMENTED** (ISO build + install path); **NOT independently re-verified this session** that a built ISO actually boots/installs — that claim rests on the audit's cited CI run (31269821588) and release.yml, not on anything run in this session. | live-build pipeline, Calamares integration, both present and code-complete. |
| RootForge-OS for Windows PCs | **MISSING** | Zero Windows-specific code, project files, or references found anywhere in the repo. |
| RootForge-OS Android APK | **MISSING** | No `AndroidManifest.xml`, `.apk`, Gradle Android project, or any Android-app source found. The existing Android-*device* tooling (fastboot/adb scripts) manages external Android hardware from the Linux ISO; it is not an Android application. |
| Headless CLI Access | **IMPLEMENTED** | `rootforge` CLI runs headless by design (argparse, `--json` output modes, non-interactive except confirmation gates). |
| GUI Access | **MISSING** (for RootForge's own GUI layer) | No GTK/Qt/Electron/web-UI framework code found anywhere (grepped). Calamares provides the *installer's* GUI, but that is a third-party tool integrated for one specific task (disk installation), not a RootForge management GUI — matches `docs/IMPLEMENTATION_PLAN.md` item 17, explicitly deferred. |
| Android Terminal Environment (Termux-based) | **IMPLEMENTED** | `termux/` directory: rootfs builder, installer, PRoot/chroot login scripts, optional Termux:X11 desktop instructions in README §17. |
| Remote/Local Administration | **MISSING** | No node discovery, remote transport, or multi-device management code found beyond `fleet_orchestrate.sh` (single-operator, sequential multi-*device* USB/fastboot orchestration — not a client/server remote-administration protocol as CLAUDE.md's "Remote Architecture" section describes). |

### 7. TODOs, stubs, placeholders, dead code **[Certain]**

A repo-wide grep for `TODO|FIXME|XXX|placeholder|not.?implemented|stub` across `config/` and `termux/` (source only, excluding `tests/stubs/` fixture binaries) found exactly **one** genuine placeholder, and it is honestly self-disclosed rather than presented as complete:

- `termux/proot-distro-plugins/rootforge.sh` lines 30–37: `TARBALL_URL`/`TARBALL_SHA256` for both `aarch64` and `x86_64` are set to real GitHub Releases URLs but a literal `TARBALL_SHA256[...]="REPLACE_WITH_SHA256_FROM_BUILD_ROOTFS_SH_OUTPUT"`. The file's own header comment explains this is filled in by a maintainer after a real release exists — "there is no rootfs hosted by this repo automatically." This is the correct, honest way to represent unfinished wiring, not a violation of CLAUDE.md's "no fabricated completeness" rule.

No other stub/placeholder/dead-code pattern was found in source. (`docs/IMPLEMENTATION_PLAN.md` itself lists many genuinely unimplemented *future* items — P3 kernel tooling, dynamic-partition support, GUI, CI VM-boot testing — but those are tracked as a plan, not disguised as shipped code.)

One real, byte-identical file duplication the audit flagged (`Dockerfile.ndk-matrix` under both `opt/rootforge/docker/` and `usr/local/share/rootforge/docker/`) was claimed here as still-open. **Correction (2026-09-13, Phase 6 session):** this was wrong — a fresh `find . -iname Dockerfile.ndk-matrix` returns exactly one match, and `git log --all` on the second path shows it was removed in commit `6dbd7dd`, which predates this Phase 1 session entirely. Whatever check produced the "both copies still exist" claim above did not actually re-verify the filesystem; it repeated `docs/ARCHITECTURE_AUDIT.md`'s (2026-08-08) claim uncritically. `docs/IMPLEMENTATION_PLAN.md` item 4 has been updated to reflect this as landed.

### 8. Test infrastructure — run and verified this session **[Certain]**

- `python3 -m unittest discover -s tests -p 'test_*.py'` → **129 tests, all pass.** Matches the prior session's figure exactly.
- `bash tests/run-tests.sh` → **420 passed, 0 failed.** The prior session (2026-09-11) recorded **421** checks. This 1-check delta is real and unexplained by this session — see Open Questions above. Both runs agree the suite is fully green; only the total count differs.
- `tests/lint.sh` / `make lint` — **could not run**: `shellcheck` is not installed in this container (confirmed again this session, same as 2026-09-11). CI installs it via apt in `lint.yml`.
- Suite design (per `tests/README.md`, read this session): genuinely hermetic — stubbed `adb`/`fastboot`/etc. via `tests/stubs/` on `PATH`, scratch `$HOME` per test, explicit environment-variable seams (`ROOTFORGE_SYSCTL_FILE`, `ROOTFORGE_GRUB_DEFAULTS`, etc.) so scripts that write to real system paths (e.g. `harden_kernel.sh`'s `sysctl`/`grub` writes) can be redirected in tests. The README documents a real prior incident where the suite modified the host running it before these seams existed — worth noting as evidence the hermeticity claim has been tested against failure, not just asserted.
- `tests/check-hooks.sh` and `tests/check-tests.sh` are static self-checks (they read files, don't execute them) guarding against two specific classes of previously-real bug: a swallowed `curl | sh` download failure in a hook, and a test block that never actually invokes the code it claims to cover. Both exist because both bug classes were found for real in this project's history, per the file's own comments.

### 9. External dependencies **[Certain]** (as documented in README §2 and confirmed against actual hook/script references)

| Dependency | Used by | Required/Optional | Notes |
|---|---|---|---|
| `live-build`, `debootstrap`, `squashfs-tools`, `xorriso`, `isolinux`, `syslinux-utils` | ISO build (host-side, via CI or a dev box) | Required for building | Not installed in this session's container; not needed for inspection/CLI work. |
| Calamares + `calamares-settings-debian` | Installer | Required (baked into ISO) | Third-party installer, branded/configured, not forked — confirmed no Calamares source vendored, only config/QML under `etc/calamares/`. |
| `docker.io` | `build_matrix.sh` only, on a *running RootForge OS install* | Optional (only for NDK/API matrix builds) | **Resolved this session** — see Open Questions. Not used by this repo's own CI/build/test tooling. |
| Ollama | `setup_ai_tools.sh`, `brain.py`, `doctor.py`'s optional AI-tooling check | Optional | `doctor` degrades gracefully (warns, doesn't fail hard) when unreachable, per `doctor.py`. |
| Claude Code CLI | `setup_ai_tools.sh`, checked by `doctor.py` | Optional | Same graceful-degradation pattern. |
| `magiskboot`, `avbtool`, `mkbootimg`/`unpack_bootimg`/`repack_bootimg` | Boot-image tooling, baked into ISO at build time from AOSP/Magisk upstream sources | Required (baked in) | Fetched from real upstream sources per hooks `0060`/`0085`. ~~**no SHA-256 verification of these downloads exists**~~ **Fixed 2026-09-13 (Phase 6 session)** — hooks `0060`/`0085` (and the other 4 named in `docs/IMPLEMENTATION_PLAN.md` item 9) now pin a version/commit and verify a SHA-256 hash before installing. |
| `jq`, `e2fsprogs` | `extract_ota.sh`/`install_lsposed.sh` (GitHub API parsing), `inspect_partition_image.sh` (loopback ext4 mount) | Required for those scripts | |
| `adb`, `fastboot`, `repo`, `aapt` (host/device tooling) | Nearly every device-facing script | Required for real device work | Not installed in this container; stubbed in tests. |

No dependency found that duplicates or vendors an unrelated OS/distro's source — Debian and its packages are consumed via `apt`/live-build's normal mechanism, not copied in.

### 10. Documentation-accuracy finding **[Certain]**

`README.md` references a top-level `scripts/` directory (`scripts/new_module_scaffold.sh`, `scripts/unlock_bootloader.sh`, `scripts/backup_partitions.sh`, etc.) in at least 15 places across sections 3, 4, 5, 8–13, and 16. **No `scripts/` directory exists anywhere in this repository** — grep and `find` both confirm zero matches. The actual, correct path for every one of these is `config/includes.chroot/usr/local/bin/<name>.sh`. This is real, repo-wide documentation drift (README describing an earlier or intended layout that the code doesn't match), independent of and in addition to the audit's own §3.9 finding about historically overclaimed completeness. Per this file's "Source of Truth" rule, README.md is wrong here and should be corrected — flagged for Phase 2, not fixed in this Phase 1 session (Phase 1's deliverable is inventory, not remediation).

### 11. Scope check against CLAUDE.md's REPOSITORY SCOPE & BOUNDARIES **[Certain]** for the factual parts, `[Likely]`/judgment call noted where the boundary itself is ambiguous

No hard scope violation found — no unrelated OS/distro embedded, no unrelated third-party project's source vendored, no secrets/credentials/user data found in tracked files (not exhaustively secret-scanned this session; a dedicated secret scan was out of scope for a documentation inspection and should not be assumed done). One **standing judgment call**, carried over from the audit (§1.3) and not yet resolved by anyone: `esp32_toolkit.sh` (ESP32 flashing), `rpi_fleet_tools.sh` (Raspberry Pi fleet management), and `brain` (general-purpose PARA notes app) sit outside the audit's original narrower charter ("Android system/kernel/boot-image/root-module/emulator/device-development workflows") but arguably *inside* CLAUDE.md's own broader project identity ("a complete operating-system/platform ecosystem"). This file takes precedence over the older audit's charter per its own Source of Truth rule, so **these three are not flagged as violations under CLAUDE.md's actual current scope** — noted here only so a future session doesn't need to re-derive this reasoning.

### 12. Summary status table

| Area | Status |
|---|---|
| ISO build pipeline (live-build) | IMPLEMENTED (code-complete; boot/install claim rests on prior CI, not re-verified this session) |
| Calamares installer integration | IMPLEMENTED |
| Termux/PRoot rootfs build | IMPLEMENTED |
| `proot-distro` plugin | PARTIALLY IMPLEMENTED (real script, placeholder SHA-256 pending a real release) |
| `rootforge` CLI + core package | IMPLEMENTED (doctor, devices, module, flash, backup, ota, boot(partial), avd) |
| `rootforge.core.config` (central config) | PLANNED (not started) |
| `rootforge.core.device` (Device abstraction) | PLANNED (not started; `devices.py` does ad hoc detection only) |
| 27 standalone shell scripts | IMPLEMENTED, several now wrapped (not replaced) by the CLI |
| second-brain (`brain`/`brain.py`) | IMPLEMENTED |
| Test suite (Python + shell, hermetic) | IMPLEMENTED, green (129 + 420 checks, this session) |
| Lint pipeline | IMPLEMENTED (verified via CI design; not locally runnable in this container) |
| CI (lint.yml) | IMPLEMENTED, presumed green (not re-run this session; last-known status from repo history) |
| CI (release.yml) | IMPLEMENTED but unexercised on real GitHub infra per its own comment |
| Artifact SHA-256 verification (fetched build-time tools) | ~~MISSING~~ **IMPLEMENTED as of 2026-09-13** (Phase 6 session — all 6 hooks now pin version + SHA-256) |
| Reproducibility manifest (`system-manifest.json`) | MISSING |
| Dockerfile.ndk-matrix dedup | ~~MISSING (P0 item, not yet done)~~ **Correction (2026-09-13): this was already done in commit `6dbd7dd`, before this Phase 1 session ran — the original claim here was never actually re-verified against the filesystem.** |
| Windows platform | MISSING |
| Android APK application | MISSING |
| RootForge GUI | MISSING |
| Remote/multi-node administration | MISSING |
| `rootforge-kernel` subsystem | MISSING |
| Dynamic-partition (`lpunpack`/`lpmake`) tooling | MISSING |
| README `scripts/` path references | ~~INCORRECT (documentation bug, not a code bug)~~ **Fixed in a later session** — confirmed 2026-09-13 (Phase 6): only 1 `scripts/` match remains in README.md, and it is the intentional exception (Magisk's own upstream `scripts/boot_patch.sh`). |

**Exit criteria met:** actual code and structure inventoried directly (not from memory or prior docs' claims alone); IMPLEMENTED / PARTIALLY IMPLEMENTED / PLANNED / MISSING breakdown produced above for every major area; no fabricated claims — items not run this session (ISO boot, release.yml, CI green-status, secret scan) are explicitly marked as not independently verified rather than assumed. Phase 2 can proceed.

---

## CURRENT ARCHITECTURE & IMPLEMENTATION STATE

**Run:** 2026-09-13, same session as Phase 3–5 below, on branch `claude/phase-5-continuation-vy64cu`. All claims verified by direct file reads this session unless marked `[Likely]`/`[Guessing]`.

### 1. RootForge Core — what's actually platform-independent

There is no dedicated "core" package shared across platforms in the sense CLAUDE.md's architecture diagram describes (`rootforge-core` as a cross-platform library consumed by Linux/Windows/Android adapters). What exists instead, under `config/includes.chroot/usr/local/lib/rootforge/`, is:

- **`core/` (Python)** — the `rootforge` CLI's implementation. Platform-independent *in code* (pure Python stdlib, no Linux-only syscalls in the CLI layer itself), but every subcommand's actual work is delegated to `usr/local/bin/*.sh` scripts (via `runner.py`) that assume a Debian/Linux userspace (`adb`, `fastboot`, `dd`, `sha256sum`, `/dev/tty`). So "core" is platform-independent in language choice only — it has no Windows or Android execution target today.
- **`sh/common.sh`** — shared Bash helper library sourced by the 27 standalone scripts (see §2). This is Linux/Bash-specific, not part of any cross-platform core.

**Conclusion:** `rootforge.core.config` and `rootforge.core.device` — the two modules `docs/IMPLEMENTATION_PLAN.md` (P1, items 5–6) designates as the actual platform-independent abstraction layer — do not exist yet (confirmed again this session: no `config.py`/`device.py` in `core/`, no `python3-yaml` in any package list). Until they land, "RootForge Core" is aspirational architecture, not current code.

### 2. Component-by-component status (`rootforge.core`, verified by direct read this session)

| Module | Lines | Status | What it actually does |
|---|---|---|---|
| `cli.py` | 145 | IMPLEMENTED | `argparse` dispatcher, `allow_abbrev=False` (deliberate — ambiguous flag-prefix expansion is a real risk on destructive flash commands, per its own comment). Routes to `doctor`, `devices`, and delegates `module`/`flash`/`backup`/`ota`/`boot`/`avd` to their sub-parsers' own `dispatch()`. |
| `doctor.py` | 284 | IMPLEMENTED | 17 independent, side-effect-free checks (tool presence, disk space, Ollama reachability, `~/rootforge` writability, `~/second-brain` vault, adb device state). Each check is wrapped so one raising exception doesn't hide the rest. `--json`/`--quiet`/`--strict`. |
| `devices.py` | 159 | IMPLEMENTED | Merges `adb`+`fastboot` enumeration into one list; `-l/--detailed`, `--json`. |
| `runner.py` | 101 | IMPLEMENTED | The wrapping mechanism every other subcommand module uses: `find_script()` (installed path → checkout-relative fallback → `PATH`), `run_script()`/`exec_script()`. Explicitly does not capture stdout by default, because the wrapped scripts prompt for confirmation on `/dev/tty` and capturing would hide that prompt (a previously-real hang bug). Exit codes pass through untouched by design — some scripts use non-zero to report a finding, not a crash. |
| `flashing.py` | 151 | IMPLEMENTED (wrapper) | `flash`/`backup` subcommand group over `flash_patched_boot.sh`/`backup_partitions.sh`/`restore_partitions.sh`. |
| `module.py` | 102 | IMPLEMENTED (wrapper) | `module scaffold/lint/build` over `new_module_scaffold.sh`/`lint_module.sh`/`build_magisk_module.sh`. |
| `ota.py` | 110 | IMPLEMENTED (wrapper) | `ota extract/inspect`. |
| `boot.py` | 128 | PARTIALLY IMPLEMENTED | `boot patch/flash-last` exist; IMPLEMENTATION_PLAN item 11's fuller `inspect/unpack/repack/verify` group is not yet built — confirmed by reading the file's subparser list this session. |
| `avd.py` | 122 | IMPLEMENTED (wrapper) | `avd create/boot/list` over `setup_rooted_avd.sh`. Snapshot support (planned, item 13) is not present. |

None of these modules call each other's internals directly — each `dispatch()` function is self-contained and calls into `runner.py`, so there is no hidden coupling between subcommand groups. `rootforge.core.log` (structured JSON-lines logging, IMPLEMENTATION_PLAN item 7) does **not** exist — confirmed no `log.py` in `core/`; logging today is whatever each individual shell script does to `${ROOTFORGE_HOME:-$HOME/rootforge}/logs` (plain text, per `flash_patched_boot.sh`'s own `LOG_DIR` line), not a unified mechanism.

### 3. The shell layer — `usr/local/bin/*.sh` and `sh/common.sh`

27 scripts remain the actual implementation of every device-facing operation; the CLI is a validated front door onto them, not a replacement (confirmed by `runner.py`'s own docstring and by reading `flash_patched_boot.sh` end-to-end this session). `sh/common.sh` (217 lines, read in full this session) is the shared library and is itself a record of specific, real, previously-shipped bugs it now fixes:

| Helper | Purpose | Bug it replaced |
|---|---|---|
| `rf_confirm` | Typed-word confirmation gate, prompts on `/dev/tty` explicitly (not stdout/stdin) | A bare `read -r -p` prompt written to stdout vanished when `fleet_orchestrate.sh` redirected a child's stdout to a per-device log — the run looked hung with no visible prompt. `ROOTFORGE_ASSUME_YES=1` is the only bypass, logged loudly, and scoped to one caller (`fleet_orchestrate.sh`'s own upfront fleet-wide confirmation). |
| `rf_sha256_file` / `rf_sha256_verify` | Backup/restore integrity | `backup_partitions.sh` previously recorded only `du -h` sizes, so a truncated image passed silently to `restore_partitions.sh`. |
| `rf_adb_serials` / `rf_fastboot_serials` | Canonical device enumeration | Old inline `adb devices \| grep -qv "List of devices"` matched the command's own trailing blank line and reported a phantom device with nothing attached. |
| `rf_shell_quote` | Safe single-quoting for values written into sourced shell files | `setup_ai_tools.sh` wrote `export VAR='$key'` unescaped; a key containing a single quote broke the file's quoting, in the worst case executing the remainder of the key as shell commands in every new interactive shell. |
| `rf_write_private` | Create a file at mode 0600 from the moment it exists | The old write-temp-then-chmod pattern created the temp file at the default umask (0644) and filled it with secrets before the `chmod 600`, leaving a real world-readable window on multi-user systems. |
| `rf_download_cached` | Atomic-rename download caching with a minimum-size sanity check | `curl` writing directly to the cache path left a partial file in place on interruption; every subsequent run then trusted the truncated file as "cached" — verified in this project's own history as a 9-byte stub pushed to a device and installed as a Magisk module. |

This is not incidental hardening — it's the direct, load-bearing security surface of the shell layer, and belongs in the SECURITY CONSIDERATIONS section below rather than being treated as generic code quality.

### 4. Linux implementation — live-build ISO

`auto/config` (POSIX `sh`, `lb config` invocation) + `auto/build` (works around a documented live-build recursion bug) + `config/package-lists/*.list.chroot` (4 lists: `rootforge`, `rootforge-ai`, `rootforge-flagship`, `rootforge-installer`) + `config/hooks/*.hook.chroot` (15 numbered chroot-time scripts) + `config/includes.chroot/` (files overlaid onto the built filesystem verbatim: the CLI, the 27 scripts, Calamares branding/config, systemd units, a udev rule for Android USB devices, Plymouth boot theme). Produces a bootable, Calamares-installable Debian 12 Bookworm amd64 ISO via `sudo make build`. IMPLEMENTED and code-complete; whether a built ISO actually boots/installs rests on prior CI history cited in the Phase 1 audit, not re-verified in this session (no ISO was built this session — would need ~20GB disk and 60–90 minutes).

### 5. Windows implementation

MISSING. Zero Windows-specific code, project files, or references exist anywhere in the repository (confirmed by extension/keyword search in Phase 1, not re-run this session since nothing has changed). CLAUDE.md's "Windows RootForge Platform" scope item is entirely unimplemented.

### 6. Android implementation

MISSING as an application. The 27 scripts *manage external Android hardware from the Linux ISO* (via `adb`/`fastboot`) — that is not the same as an Android application, and no `AndroidManifest.xml`, Gradle Android project, or `.apk`-producing build step exists anywhere. CLAUDE.md's "Android RootForge Application" scope item (GUI + terminal + headless + local/remote management, as a serious on-device app) is entirely unimplemented.

### 7. Termux integration — IMPLEMENTED (a second, independent build target)

`termux/build-rootfs.sh` runs `debootstrap` (not live-build) to produce a PRoot- or chroot-installable rootfs tarball for arm64 or amd64, in `proot` or `chroot` flavor (4 combinations), reusing `config/hooks/*` and a pruned package list. `termux/proot-distro-plugins/rootforge.sh` is the `proot-distro` plugin that installs it from a GitHub Release — PARTIALLY IMPLEMENTED, because its `TARBALL_SHA256` values are still the literal placeholder `REPLACE_WITH_SHA256_FROM_BUILD_ROOTFS_SH_OUTPUT` pending a real tagged release (confirmed unchanged this session; the file's own header comment explains this honestly rather than hiding it). README §17 documents an optional Termux:X11 desktop path for the chroot flavor.

### 8. CLI — actual commands (this session's ground truth, from `cli.py`)

```
rootforge --version
rootforge doctor [--json] [--quiet] [--strict]
rootforge devices [--json] [-l|--detailed]
rootforge module <scaffold|lint|build> ...
rootforge flash ...
rootforge backup ...
rootforge ota <extract|inspect> ...
rootforge boot <patch|flash-last> ...
rootforge avd <create|boot|list> ...
```
No other top-level subcommand exists. `rootforge` with no subcommand prints help and exits 0.

### 9. GUI

MISSING for RootForge's own management GUI. Calamares provides the *installer's* GUI (a third-party tool, configured/branded via `config/includes.chroot/etc/calamares/`, not forked — confirmed no Calamares source vendored) for one specific task (disk installation) — that is not a RootForge management GUI and should not be conflated with one. No GTK/Qt/Electron/web-UI framework code exists anywhere in the repo.

### 10. APIs / IPC

MISSING as a formal mechanism. The only "IPC" in the current system is `runner.py` invoking scripts as subprocesses and reading their exit code (and optionally captured stdout/stderr). There is no HTTP/gRPC/socket API, no service daemon, and nothing matching CLAUDE.md's "Common API / IPC" architectural layer. Any future GUI or remote-management client would have to call into `rootforge.core` functions directly (in-process) or shell out to the `rootforge` binary — neither is built yet.

### 11. Authentication / Authorization

MISSING as a system, in the sense of user accounts, tokens, or role-based access. What exists is operational safety gating, not authn/authz: `rf_confirm`'s typed-word confirmation before destructive operations (flash/restore/harden), gated per-operation rather than per-user. There is no concept of "who is allowed to run `rootforge flash`" beyond OS-level file permissions and physical device access. This matches CLAUDE.md's "Never assume root/admin access" principle in spirit (nothing here escalates privilege silently), but there is no authorization layer to document beyond that.

### 12. Configuration system

PLANNED, not implemented. `docs/IMPLEMENTATION_PLAN.md` P1 item 6 specifies `~/.config/rootforge/config.yaml` + project-level `rootforge.yaml` + per-device `devices/<codename>/rootforge.yaml`, requiring a new `python3-yaml` dependency. None of this exists — confirmed again this session (no YAML config loader anywhere in `core/`, no such dependency in any package list). Every script today reads its own environment variables/flags independently (e.g., `ROOTFORGE_HOME`, `ROOTFORGE_ASSUME_YES`, `ROOTFORGE_BRAIN_VAULT`), which is exactly the fragmentation P1.6 is meant to replace.

### 13. Remote management

MISSING beyond `fleet_orchestrate.sh`, which is single-operator sequential multi-*device* USB/fastboot orchestration (one host, many attached devices) — not a client/server remote-administration protocol with node discovery, auth, or secure transport as CLAUDE.md's "Remote Architecture" section describes. No node-identity, discovery, or remote-transport code exists anywhere.

### 14. Logging / audit

Ad hoc only. Individual scripts write plain-text logs to `${ROOTFORGE_HOME:-$HOME/rootforge}/logs` (confirmed in `flash_patched_boot.sh`); `rootforge doctor`'s checks are side-effect-free and don't log. The planned `rootforge.core.log` (structured JSON-lines, secret redaction, one execution ID per invocation — IMPLEMENTATION_PLAN item 7) does not exist. There is no audit trail correlating a CLI invocation to the shell scripts it ran.

### 15. Component dependency diagram (actual, not aspirational)

```
rootforge (usr/local/bin/rootforge, POSIX sh shim)
    -> python3 -m rootforge.core.cli
        -> doctor.py       (standalone; imports devices.py lazily for one check)
        -> devices.py      (standalone; shells out to adb/fastboot directly)
        -> module.py   -\
        -> flashing.py  \
        -> ota.py        +--> runner.py --> subprocess --> usr/local/bin/*.sh
        -> boot.py      /                                        |
        -> avd.py      -/                                        v
                                                     usr/local/lib/rootforge/sh/common.sh
                                                     (rf_confirm, rf_sha256_*, rf_adb_serials, ...)
```
No module in `core/` imports another wrapper module (`flashing.py` never calls `module.py`, etc.) — each is an independent leaf that only depends on `runner.py` and, in `doctor.py`'s one case, `devices.py`.

### 16. Full implementation status table (components not already covered in the Phase 1 summary table)

| Component | Status |
|---|---|
| `rootforge` CLI skeleton + dispatch | IMPLEMENTED |
| `doctor`, `devices` subcommands | IMPLEMENTED |
| `module`, `flash`/`backup`, `ota`, `avd` wrapper groups | IMPLEMENTED (wrap real scripts, don't reimplement) |
| `boot` wrapper group | PARTIALLY IMPLEMENTED (patch/flash-last only) |
| `rootforge.core.device` (Device abstraction) | PLANNED |
| `rootforge.core.config` (central config) | PLANNED |
| `rootforge.core.log` (structured logging) | PLANNED |
| Shared shell safety library (`common.sh`) | IMPLEMENTED |
| Live-build ISO pipeline | IMPLEMENTED |
| Termux/PRoot rootfs build | IMPLEMENTED |
| `proot-distro` plugin | PARTIALLY IMPLEMENTED (placeholder checksums) |
| Windows platform | MISSING |
| Android application | MISSING |
| RootForge management GUI | MISSING |
| Formal API/IPC layer | MISSING |
| Authn/authz beyond `rf_confirm` gating | MISSING (not planned in current docs either) |
| Remote/multi-node administration | MISSING |
| Unified audit logging | MISSING (planned) |

---

## BUILD SYSTEM & TOOLING

**Run:** 2026-09-13. Commands below marked `[Certain]` were executed this session in this container; commands marked `[Likely]`/`[Guessing]` (anything requiring root, a real device, or ~20GB disk/60–90 min for an ISO build) were **not** run this session and are carried forward from Phase 0/1's verification plus the scripts'/CI's own documented behavior — do not report them as freshly verified.

### 1. Build targets, by platform

| Target | Tooling | Command | Verified this session? |
|---|---|---|---|
| Linux ISO (amd64, Debian 12 Bookworm) | `live-build`, `debootstrap`, `squashfs-tools`, `xorriso`, `isolinux`, `syslinux-utils` (host prerequisites, per `BUILD.md`) | `sudo make build` (→ `auto/build`, tee'd to a timestamped log) | **No** — requires root + ~20GB disk + 60–90 min; not attempted. `[Likely]` works, per Phase 1's citation of prior CI run 31269821588 and `release.yml`'s own `build-iso` job. |
| Termux/PRoot rootfs (arm64/amd64 × proot/chroot) | `debootstrap` | `termux/build-rootfs.sh <arch> <flavor>` (exact flag syntax not independently re-verified this session — read the script's own `--help`/usage block before running) | **No** — same resource constraints as the ISO build. |
| `rootforge` CLI (no build step) | `python3` only | `python3 -m rootforge.core.cli <args>`, or the installed `rootforge` shim | **Yes** — invoked via the test suite's CLI smoke checks and this session's own reads of `cli.py`. |

### 2. Build prerequisites (host-side, per `BUILD.md` — not independently re-verified installable in this container)

`live-build`, `debootstrap`, `squashfs-tools`, `xorriso`, `isolinux`, `syslinux-utils`, ~20GB free disk, and root (`sudo`) for the actual `lb build` step. `BUILD.md` (76 lines) is the canonical host-setup reference; this file does not duplicate its content, only points to it.

### 3. Build artifacts and locations

- ISO build → `rootforge-os-amd64.hybrid.iso` at repo root, plus `rootforge-build-<timestamp>.log`; `make checksum` writes `<iso>.sha256` alongside it. All of these are `.gitignore`d (confirmed in Phase 0), along with live-build's own `cache/`, `chroot/`, `binary/`, `live-image/` working directories.
- Termux rootfs build → tarballs under `dist/` (per `.gitignore`'s `termux/build-rootfs.sh` output entry), not committed.
- `rootforge` CLI → no build artifact; it runs from source in place (`config/includes.chroot/usr/local/lib/rootforge/`).

### 4. Makefile targets — read in full this session (`/home/user/rootforge-os/Makefile`)

| Target | Needs root? | What it does |
|---|---|---|
| `make test` | No | Runs `tests/run-tests.sh` (hermetic — stubs `adb`/`fastboot`, scratch `HOME`). |
| `make lint` | No | Runs `tests/lint.sh` — the single definition of "lint," shared verbatim with the CI workflow so the two can't drift apart. |
| `sudo make build` | Yes (`check-root` target enforces this) | `auto/build` piped to `tee` on a timestamped log, then `make checksum`. |
| `make checksum` | No | `sha256sum` on the built ISO; fails loudly if the ISO doesn't exist yet. |
| `make list-usb` | No | Lists removable block devices via `lsblk`, filtered to `RM=1`, as a sanity check before `make flash`. |
| `sudo make flash USB=/dev/sdX` | Yes | Verifies the ISO's own `.sha256` if present (refuses to flash on mismatch), prints the target device, gives a 5-second `Ctrl-C` abort window, then `dd`s the ISO to the device. **Destructive** — overwrites all data on the target block device; the 5-second window and pre-flash `lsblk` print are the only safeguards, there is no typed-confirmation gate here (unlike the device-flashing scripts under `usr/local/bin/`, which use `rf_confirm`). |
| `sudo make clean` / `distclean` | Yes | `lb clean --purge`; `distclean` additionally removes `cache/` and all build outputs. |

### 5. Development environment setup (reproducible from this file alone)

For CLI/Python work (no ISO build needed): `python3 -m unittest discover -s tests -p 'test_*.py'` and `bash tests/run-tests.sh` are the only prerequisites, and both need nothing beyond `python3`/`bash` — confirmed runnable in this container with no other setup. For ISO/Termux build work: follow `BUILD.md` on a host with the prerequisites in §2 and sufficient disk/root access — not reproducible inside this container, and this file should not claim otherwise.

### 6. CI/CD pipeline behavior (`.github/workflows/`, read in Phase 1; not re-run this session)

- **`lint.yml`** (PR + push to `main`): 5 jobs — `shellcheck` (via `tests/lint.sh`), `yaml-lint` (custom duplicate-key checker, added after a real duplicate-key bug plain `yaml.safe_load` silently accepted), `package-lists` (resolves every listed apt package against a live `debian:bookworm` container), `tests` (`tests/run-tests.sh`), `python` (CLI smoke test: `--version`, `--help`, `doctor --json`, `devices --json` shape validation).
- **`release.yml`** (tag `v*` push, or manual dispatch): `build-iso` job (frees ~20GB disk first, ~90 min timeout) + `build-termux-rootfs` matrix (4 combinations) + a `release` job that creates a **draft** GitHub Release with checksummed artifacts, tag-push only. `[Likely]`, not `[Certain]`: the workflow's own header comment states it has never actually been exercised on GitHub's infrastructure from this repo; this session did not run it either.

### 7. Troubleshooting (from documented/observed gaps, not invented)

- `shellcheck` missing locally → `make lint`/`tests/lint.sh` can't run in this container; CI installs it via apt. Confirmed still true this session (not reinstalled, out of scope to modify the container).
- `pytest` missing → not needed; the suite uses stdlib `unittest`, run via `tests/run-tests.sh` or directly.
- Device/image-build tooling (`adb`, `fastboot`, `aapt`, `repo`, `mksquashfs`, `mkbootimg`, `cpio`) missing in this container → expected; hermetic tests stub these (`tests/stubs/`), real device/ISO work needs a host with them installed per `BUILD.md`.
- A `rootforge` subcommand exits 127 with "script not found... reinstall the rootforge scripts" → `runner.py`'s `find_script()` couldn't locate the wrapped script in `/usr/local/bin`, next to the Python package, or on `PATH` — this is `runner.py`'s own designed error path, not a crash.

---

## TESTING

**Run:** 2026-09-13 — both commands below executed directly in this session's container, results below are freshly observed, not carried forward from a prior session's numbers.

### 1. Framework and organization

- **Python:** stdlib `unittest`, 8 files under `tests/` (`test_avd_cli.py`, `test_boot_cli.py`, `test_brain.py`, `test_devices.py`, `test_doctor.py`, `test_flashing_cli.py`, `test_module_cli.py`, `test_ota_cli.py`) — one file per CLI subcommand group plus `brain.py`. No `test_config.py`/`test_device.py` exist, consistent with those modules not existing yet (§12/§11 above).
- **Shell:** `tests/run-tests.sh` drives both the Python suite and a set of shell-level checks; `tests/lint.sh` is the shellcheck+byte-compile pass shared with CI; `tests/check-hooks.sh` and `tests/check-tests.sh` are static self-checks (read files, don't execute them) added specifically to catch two previously-real bug classes: a swallowed `curl | sh` failure inside a hook, and a test block that never actually invoked the code it claimed to cover.
- **Hermeticity mechanism** (per `tests/README.md`, read in Phase 1): fake `adb`/`fastboot`/etc. under `tests/stubs/` placed on `PATH`, a scratch `$HOME` per test run, and explicit environment-variable seams (`ROOTFORGE_SYSCTL_FILE`, `ROOTFORGE_GRUB_DEFAULTS`, etc.) so scripts that would otherwise write to real system paths can be redirected. `tests/README.md` documents a real prior incident where the suite modified the host running it before these seams existed.

### 2. Commands to run tests (all verified this session)

```
python3 -m unittest discover -s tests -p 'test_*.py'    # Python only
bash tests/run-tests.sh                                   # full suite (shell + python)
make test                                                  # same as run-tests.sh, via Makefile
```
No per-component/single-test-file shortcut beyond standard `unittest` mechanics is documented in `tests/README.md` (e.g. `python3 -m unittest tests.test_doctor` works via normal `unittest` discovery rules — not a project-specific feature).

### 3. Results, this session

- `python3 -m unittest discover -s tests -p 'test_*.py'` → **129 tests, all pass.** Re-run directly in this session; matches every prior session's figure exactly.
- `bash tests/run-tests.sh` → **420 passed, 0 failed.** Re-run directly in this session; matches Phase 1's figure exactly (not the 2026-09-11 session's 421-check figure — see Open Questions; the 1-check delta remains unexplained and not re-investigated here, since root-causing it is explicitly carried forward as a Phase 3 tooling item rather than blocking this session's documentation work).
- `tests/lint.sh` / `make lint` — still cannot run: `shellcheck` not installed in this container (same as every prior session).

### 4. Coverage vs. gaps

Covered: every existing CLI subcommand group (`doctor`, `devices`, `module`, `flash`/`backup`, `ota`, `boot`, `avd`), `brain.py`, and static hook/test-quality self-checks. **Not covered, because the code doesn't exist yet:** `rootforge.core.config`, `rootforge.core.device`, `rootforge.core.log` — there is nothing to test. **Not covered despite the code existing:** no test boots the produced ISO in a VM (IMPLEMENTATION_PLAN P3 item 18 explicitly names this "the single highest-value testing gap identified in the audit," still open) — CI's `lint.yml` only checks that `lb build` mechanics resolve packages, not that a booted system works.

---

## TESTING

`[Populated during Phase 3. Not yet run.]`

---

## DEVELOPMENT WORKFLOW

**Run:** 2026-09-13. This section documents the workflow actually observed in this repo's history and CLAUDE.md's own protocol — it does not introduce new process.

### Session workflow for this repo specifically

1. Read PROJECT STATE at the top of this file before anything else.
2. Verify claims against real code — this repo's own history (the 2026-08-08 audit going stale within weeks) is direct evidence that trusting prior documentation without re-checking produces wrong answers.
3. For any change to a script under `usr/local/bin/`: check whether `sh/common.sh` already has the helper you need (`rf_confirm`, `rf_sha256_*`, `rf_adb_serials`/`rf_fastboot_serials`, `rf_shell_quote`, `rf_write_private`, `rf_download_cached`, `rf_require_cmd`) before writing new logic — every one of them exists because the same mistake was independently made in more than one script.
4. For any change to `rootforge.core`: add or update the matching `tests/test_*.py` file — the existing 8 files are one-per-subcommand-group, so a new subcommand group gets a new test file, not lines added somewhere unrelated.
5. Run `python3 -m unittest discover -s tests -p 'test_*.py'` and `bash tests/run-tests.sh` after any change; both are fast (well under a second and a few seconds respectively) and require no root, device, or network.
6. Run `bash tests/lint.sh` if `shellcheck` is available in the environment; if not (as in this container), note that explicitly rather than claiming lint-clean status.
7. Update this file's PROJECT STATE section before ending the session, per the file's own protocol.

### Branching convention (observed, not separately documented elsewhere in the repo)

Feature branches named `claude/<slug>-<random>` merged into `main` via PR — confirmed by this session's own `git log` (`claude/build-per-claude-md-661ey0`, `claude/rootforge-os-setup-ul61a4`, `claude/rootforge-os-readme-o5fb0x`, `claude/tool-bugs-improvements-06qywn`, and this session's own `claude/phase-5-continuation-vy64cu`) plus the many `claude/p0-*`/`p1-*`/`p2-*` branches noted on the remote in Phase 0. No branch-naming or commit-message convention is enforced by tooling (no commit-msg hook, no CI check on branch name) — it is a practiced convention, not an enforced rule.

### Proceed autonomously vs. ask first (applying CLAUDE.md's general rule to this repo's actual structure)

**Proceed autonomously:** documentation corrections against verified code (e.g. the README `scripts/` path issue flagged in Phase 1), adding a wrapper subcommand for an *existing* script (P2-shaped work), adding tests, fixing a script bug that matches an already-established pattern (e.g. another script has the same unguarded-`$2` or missing-`rf_confirm` class of bug `sh/common.sh`'s own comments describe).

**Ask first:** anything touching `config/hooks/*.hook.chroot` fetch-and-run-as-root logic (build-time trust boundary — see SECURITY CONSIDERATIONS), any new external dependency (a new apt package, a new Python package — there are currently zero third-party Python dependencies, confirmed this session), any change to `Makefile`'s `flash`/`clean`/`distclean` targets (destructive, operate on raw block devices or wipe build state), starting P3 work (`rootforge-kernel`, dynamic-partition tooling, GUI) before P0–P2 are stable, per the plan's own explicit sequencing rule, and — per this repo's session protocol — skipping ahead to a later CLAUDE.md phase without being explicitly told to (this session itself is a documented exception: the user explicitly requested continuing to Phase 5, so Phases 2–4 were completed first in the same session rather than skipped).

---

## ARCHITECTURAL DECISIONS

**Run:** 2026-09-13. Decisions below are inferred from code, comments, and `docs/IMPLEMENTATION_PLAN.md`'s own stated rationale — not invented.

1. **Wrap, don't rewrite, the 27 shell scripts.** `runner.py`'s own docstring states the rationale directly: "their behavior is proven and the shell is where the device work actually happens." The CLI's value-add is validated argument parsing (`argparse` catches the four recurring shell bug classes `runner.py` names: unguarded positional access, missing catch-all case arms, lying exit codes, `pipefail` aborts before an error message prints) — not a reimplementation. `docs/IMPLEMENTATION_PLAN.md` states this as an explicit constraint: "Nothing in this plan authorizes deleting or disabling existing working scripts."
2. **`allow_abbrev=False` on every argparse parser, not just the top-level one.** A conscious tradeoff of convenience for safety: unambiguous-prefix abbreviation is normally harmless, but on flags like `--both-slots` (which flashes both A/B slots) an abbreviation whose meaning silently changes as new flags are added is judged not worth the four saved keystrokes. Documented inline in `cli.py`.
3. **Confirmation gates prompt on `/dev/tty`, never stdout/stdin.** Because `fleet_orchestrate.sh` redirects each child script's stdout to a per-device log file, a prompt written to stdout is invisible and the run appears hung. Prompting on the controlling terminal directly, and failing closed (refusing to proceed) when there is no terminal at all, was chosen over defaulting to non-interactive — a destructive operation with no operator present must not proceed silently.
4. **Backup integrity via a `SHA256SUMS` sidecar, not a rewrite of the manifest format wholesale.** `rf_sha256_file`/`rf_sha256_verify` were added to `common.sh` and wired into `backup_partitions.sh`/`restore_partitions.sh` incrementally, reusing the existing `dd`/partition-read logic rather than replacing it — consistent with decision 1's wrap-don't-rewrite pattern applied to a specific subsystem.
5. **`runner.py` does not capture subprocess output by default.** A deliberate choice, not an oversight: capturing would swallow the confirmation prompts the scripts print to `/dev/tty`/stderr, silently reintroducing the same hang-under-redirection bug decision 3 fixed. `capture=True` exists as an opt-in for callers that genuinely need it.
6. **Exit codes from wrapped scripts pass through unmodified.** Several scripts use a non-zero exit to report a legitimate finding (e.g. `rootforge devices` returns 1 when no device is *usable*, not as an error) rather than a crash — `runner.py` and `cli.py` both preserve this rather than normalizing it away, so callers don't lose information.
7. **No central config system yet, on purpose (not an oversight).** `docs/IMPLEMENTATION_PLAN.md` sequences device abstraction (P1.5) before config (P1.6) explicitly because "config's device-override layer references it" — i.e., the ordering is a real dependency, not an arbitrary priority call.
8. **GUI is deferred, not missing by neglect.** `docs/IMPLEMENTATION_PLAN.md` item 17 states this directly: "no business logic should live only in the GUI; it calls the same `rootforge-core` functions the CLI does, once that core is stable enough to have a GUI put in front of it." This matches CLAUDE.md's own CLI-first architectural principle.

---

## SECURITY CONSIDERATIONS

**Run:** 2026-09-13. This section reflects the actual, verified security-relevant code in the repo — not a generic security checklist. See ARCHITECTURE §3 above for the same material framed as component documentation; this section states the implications.

### What's actually implemented

- **Destructive-operation confirmation gate (`rf_confirm`).** Every script that writes a boot/data partition or restores from backup requires a typed exact-word match, on `/dev/tty`, before proceeding. `ROOTFORGE_ASSUME_YES=1` is the only bypass — scoped to `fleet_orchestrate.sh`'s own upfront batch confirmation, and it prints a loud notice whenever it takes effect rather than silently skipping the gate.
- **Backup/restore integrity (`rf_sha256_file`/`rf_sha256_verify`).** `restore_partitions.sh` re-hashes every image against a `SHA256SUMS` sidecar before flashing and refuses on mismatch — this closes a real prior gap where a truncated backup could be silently restored to a device.
- **Secret-handling in `setup_ai_tools.sh`.** `rf_shell_quote` prevents a quote character in a pasted API key from breaking (or, worse, executing part of) a sourced shell file; `rf_write_private` ensures the file holding those keys is mode 0600 from the moment it's created, not after a `chmod` that runs after a world-readable window. Both fix real, specific, previously-shipped bugs (documented in `sh/common.sh`'s own comments) rather than being speculative hardening.
- **Download integrity for cached fetches (`rf_download_cached`).** Downloads land in a sibling temp file and are renamed only on success with a minimum-size sanity check, closing a verified real incident (a 9-byte truncated download cached and later pushed to a device as an installable module).
- **Exit-code honesty.** Every script `docs/IMPLEMENTATION_PLAN.md`'s P0.5 section lists (`restore_partitions.sh`, `fleet_orchestrate.sh`, `build_matrix.sh`, `build_magisk_module.sh`) previously reported success after total failure; all now propagate real exit codes, which matters because nothing wraps them programmatically otherwise.

### What's explicitly NOT implemented (gaps, not oversights the docs hide)

- ~~**No SHA-256 verification of build-time tool downloads.**~~ **RESOLVED 2026-09-13 (Phase 6 session).** All six chroot hooks (`0040-rpi-imager`, `0050-starship-eza`, `0060-magiskboot`, `0062-payload-dumper`, `0085-avbtool`, `0095-zygisk-headers`) now pin a specific upstream version/commit and verify a SHA-256 hash before installing anything, failing the build hard on a mismatch. See `docs/IMPLEMENTATION_PLAN.md` P1 item 9 for the pinned versions and hashes, and how each hash was obtained/verified. This was the single most consequential unresolved security gap identified anywhere in this repo's own documentation; it is no longer open. (Historical note, left for context: this bullet previously read "confirmed still true this session," referring to the 2026-09-13 Phase 2-5 session — that was accurate at the time.)
- **No authentication/authorization layer.** As stated in ARCHITECTURE §11: there is no concept of which user is permitted to run a destructive `rootforge` subcommand beyond OS file permissions and physical access to the device. Anyone who can run the CLI and pass `rf_confirm`'s typed-word prompt (or set `ROOTFORGE_ASSUME_YES=1`) can flash a device.
- **No secret redaction in logging**, because there is no unified logging module yet (`rootforge.core.log`, PLANNED) — individual scripts' plain-text logs under `${ROOTFORGE_HOME}/logs` have no designed-in redaction pass. (Note: this session's own git log shows a prior, already-fixed instance of exactly this class of bug — commit `d6d812c "An API key was written in plaintext to a world-readable log"` — so the risk is not hypothetical; the fix for that specific instance landed, but no systemic redaction mechanism exists to prevent a recurrence elsewhere.)
- **`proot-distro` plugin ships a placeholder checksum**, not a verified one, pending a real tagged release (ARCHITECTURE §7) — this is disclosed honestly in the script's own comment rather than silently shipping a wrong hash, but it means checksum verification for that specific install path is not yet actually protective.
- **Root/privilege boundaries are implicit, not enforced by the CLI.** `make build`/`make flash`/`make clean` gate on `check-root` (a `Makefile` target checking `id -u == 0`), but `rootforge` itself does not check or drop privileges — it assumes whatever the invoking shell's privilege level already is. This is consistent with CLAUDE.md's "never silently escalate privileges" principle (nothing here escalates), but there is also no explicit least-privilege enforcement to point to as implemented.

### Security-relevant principle followed correctly

Least-privilege and secure-defaults are respected in the specific mechanisms above (fail-closed confirmation, private-file creation, integrity-checked backups/downloads); they are not respected system-wide because the surrounding systems (auth, unified logging, build-artifact integrity) that would make "system-wide" a meaningful claim don't exist yet. Do not describe RootForge-OS as having a security model beyond what's listed here.

---

## KNOWN LIMITATIONS & CONSTRAINTS

**Run:** 2026-09-13.

1. **No cross-platform core exists yet**, despite CLAUDE.md's architecture diagram depicting one — see ARCHITECTURE §1. Any work assuming `rootforge.core` is already platform-independent in a meaningful (not just language-choice) sense is working from the aspirational diagram, not current code.
2. **Windows and Android-application support are both 0% started.** Not partially implemented, not scaffolded — no files exist for either.
3. **This dev container cannot build or verify the ISO/Termux rootfs, and cannot run `shellcheck`.** Every claim in this file about those paths working rests on script/CI inspection and documented prior CI runs, not on independent execution in this environment. Say so explicitly whenever it's relevant, rather than implying local verification that didn't happen.
4. **The 421-vs-420 shell-test-count discrepancy from the 2026-09-11 session remains unexplained** — carried forward again this session (re-confirmed 420 this session, not re-investigated further; flagged for whoever next has reason to touch `tests/run-tests.sh`/`tests/check-tests.sh`).
5. ~~**No build-time artifact integrity verification**~~ **Fixed 2026-09-13 (Phase 6 session)** — see SECURITY CONSIDERATIONS above. This was the most significant concrete security gap when this item was written; it no longer applies.
6. **No formal API/IPC layer** means any future GUI or remote client has exactly two integration options today: shell out to `rootforge`, or import `rootforge.core` modules directly in-process. Neither is a stable, versioned interface yet.
7. **This file (CLAUDE.md) is large and manually maintained.** Its own accuracy depends entirely on future sessions actually re-verifying claims against code rather than trusting this file's prose — the same discipline this file required of itself regarding `docs/ARCHITECTURE_AUDIT.md`.

---

## ARCHITECTURAL DECISIONS

`[Populated during Phase 4. Not yet run.]`

---

## SECURITY CONSIDERATIONS

`[Populated during Phase 4. Not yet run.]`

---

## KNOWN LIMITATIONS & CONSTRAINTS

`[Populated during Phase 4. Not yet run.]`

---

## CURRENT STATE AUDIT

**Run:** 2026-09-13, same session as Phases 2–4 above. This audit cross-checks every claim made in this file against the code read this session and in Phase 1; nothing here is newly invented.

### 1. Comprehensive implementation status (consolidated from Phases 1–2 above)

| Area | Status |
|---|---|
| Linux ISO build (live-build) | IMPLEMENTED (code-complete; boot/install success rests on prior CI, not re-verified this session) |
| Calamares installer integration | IMPLEMENTED |
| Termux/PRoot rootfs build | IMPLEMENTED |
| `proot-distro` plugin | PARTIALLY IMPLEMENTED (placeholder SHA-256) |
| `rootforge` CLI + `doctor`/`devices` | IMPLEMENTED |
| `module`/`flash`/`backup`/`ota`/`avd` wrapper groups | IMPLEMENTED |
| `boot` wrapper group | PARTIALLY IMPLEMENTED (patch/flash-last only) |
| `rootforge.core.device` | PLANNED |
| `rootforge.core.config` | PLANNED |
| `rootforge.core.log` | PLANNED |
| 27 standalone shell scripts + `common.sh` safety library | IMPLEMENTED |
| second-brain (`brain`/`brain.py`) | IMPLEMENTED |
| Hermetic test suite (Python + shell) | IMPLEMENTED, green (129 + 420 checks, re-verified this session) |
| Lint pipeline | IMPLEMENTED (CI-verified design; not locally runnable here) |
| CI `lint.yml` | IMPLEMENTED, presumed green (not re-run this session) |
| CI `release.yml` | IMPLEMENTED, unexercised on real infra per its own comment |
| Build-time artifact SHA-256 verification | ~~MISSING~~ **IMPLEMENTED 2026-09-13 (Phase 6)** — all 6 hooks pin version + hash |
| Reproducibility manifest (`system-manifest.json`) | MISSING |
| `Dockerfile.ndk-matrix` dedup | ~~MISSING (still-open P0 item)~~ **Correction (2026-09-13): already done in commit `6dbd7dd`, before this Phase 5 session ran** |
| Windows platform | MISSING |
| Android APK application | MISSING |
| RootForge management GUI | MISSING |
| Formal API/IPC layer | MISSING |
| Auth/authz beyond `rf_confirm` | MISSING |
| Remote/multi-node administration | MISSING |
| Unified structured logging/audit | MISSING |
| `rootforge-kernel` subsystem | MISSING |
| Dynamic-partition (`lpunpack`/`lpmake`) tooling | MISSING |
| README `scripts/` path references | ~~INCORRECT (documentation bug — see item 2 below, still unfixed)~~ **Fixed** — confirmed 2026-09-13 (Phase 6): only the intentional exception remains |

### 2. Scope-adherence audit

No new scope violation found this session beyond what Phase 1 already identified. Two items carried forward, unresolved:

- ~~**README.md's `scripts/` path references remain uncorrected.**~~ **Correction (2026-09-13, Phase 6 session): this claim contradicted this very file's own PROJECT STATE summary for the same session, which said the README fix landed. Direct check confirms the fix is real** — only 1 `scripts/` match remains in README.md, and it's the intentional exception (Magisk's own upstream `scripts/boot_patch.sh`). Whichever half of that self-contradiction was true at the time, it is resolved now.
- **`esp32_toolkit.sh`, `rpi_fleet_tools.sh`, and `brain`** sit outside the narrower charter of the older `docs/ARCHITECTURE_AUDIT.md` but inside CLAUDE.md's own broader "complete operating-system/platform ecosystem" scope — Phase 1's judgment call that these are not violations under this file's actual governing scope stands; re-confirmed by reading this file's own SCOPE section again this session, no change in reasoning.

No secrets, credentials, or committed user data were found in tracked files this session — but, consistent with Phase 1's own caveat, no dedicated secret-scanning tool was run; this is inspection-based, not scan-verified.

### 3. What's missing relative to the full RootForge vision (CLAUDE.md's own "Long-Term Platform Support" list)

Of the 7 platform-support items in CLAUDE.md's vision: 2 are implemented (Linux ISO, headless CLI), 1 is implemented as a second build target (Android Terminal Environment via Termux), and 4 are entirely unstarted (Windows, Android APK application, GUI, Remote/Local Administration as a real protocol — `fleet_orchestrate.sh` is adjacent but not equivalent, per ARCHITECTURE §13). This is a ~40% platform-support completion rate against the stated vision, and that vision is explicitly long-term in CLAUDE.md's own framing — this is not a failing grade, it's an accurate current-progress snapshot.

### 4. Known bugs and incomplete work (still open, confirmed this session)

- ~~No build-time SHA-256 verification on 6 chroot hooks fetching external content (P1 item 9).~~ **Fixed 2026-09-13 (Phase 6 session)** — all 6 hooks now pin version + SHA-256.
- ~~`Dockerfile.ndk-matrix` byte-identical duplicate not yet removed (P0 item 4)~~ **Correction (2026-09-13): this was never actually true — the duplicate was removed in commit `6dbd7dd`, before this Phase 5 session or any of its predecessors ran. The claim here was carried forward from `docs/ARCHITECTURE_AUDIT.md` without re-checking.**
- `boot` CLI group missing `inspect`/`unpack`/`repack`/`verify` (P2 item 11, partial). Still open.
- No CI VM-boot test for the produced ISO (P3 item 18) — the single highest-value testing gap per the plan's own words, still open.
- The 421-vs-420 shell-check-count discrepancy from 2026-09-11 remains unexplained. Still open (not investigated in the 2026-09-13 Phase 6 session either — both suite runs that session again reported 420/0, consistent with every session since 2026-09-13's Phase 1).
- ~~README's `scripts/` path drift (§2 above), unfixed.~~ Fixed — see §2 above.

### 5. External resource links (as they appear in this repo's own tracked files — not independently fetched/verified this session)

- Repository: `https://github.com/Victorious93/rootforge-os` (confirmed via Phase 0's `git remote show origin`).
- CI workflows: `.github/workflows/lint.yml`, `.github/workflows/release.yml`.
- Prebuilt release artifacts: published to the repo's own GitHub Releases by `release.yml` on tagged pushes (per the Makefile's own header comment) — no releases were independently checked to exist this session.
- Termux:X11: referenced in README §17 as `github.com/termux/termux-x11` releases, for the optional desktop path — this is the README's own citation, not independently fetched this session.

### 6. Table of contents (this file, current section order)

PROJECT STATE → Phase 0 Findings → HOW TO USE THIS FILE → FOUNDATIONAL PRINCIPLES → PROJECT IDENTITY → REPOSITORY SCOPE & BOUNDARIES → ARCHITECTURE PRINCIPLES → PHASED WORKFLOW (Phase 0–7 definitions) → RULES FOR CLAUDE CODE → SOURCE OF TRUTH → INSPECTION REPORT (Phase 1) → CURRENT ARCHITECTURE & IMPLEMENTATION STATE (Phase 2) → BUILD SYSTEM & TOOLING (Phase 3) → TESTING (Phase 3) → DEVELOPMENT WORKFLOW (Phase 4) → ARCHITECTURAL DECISIONS (Phase 4) → SECURITY CONSIDERATIONS (Phase 4) → KNOWN LIMITATIONS & CONSTRAINTS (Phase 4) → CURRENT STATE AUDIT (Phase 5, this section) → RECOMMENDED DEVELOPMENT PRIORITY (Phase 5) → QUICK REFERENCE (Phase 5).

### 7. Final accuracy review

Every status claim in this Phase 5 audit traces to either: a file read directly this session (cli.py, runner.py, doctor.py, common.sh, flash_patched_boot.sh, Makefile, IMPLEMENTATION_PLAN.md, README.md section headers, git log), a command actually executed this session (both test-suite runs), or an explicitly-labeled carry-forward from Phase 0/1 with a note that it was not re-verified. No feature is described as implemented without a corresponding file this session or Phase 1 actually inspected. Where something could not be verified in this container (ISO boot, release.yml execution, shellcheck-based lint, secret scanning), that limitation is stated plainly rather than assumed away — consistent with this file's own non-negotiable accuracy rule.

---

## RECOMMENDED DEVELOPMENT PRIORITY

**Run:** 2026-09-13, updated 2026-09-13 (Phase 6 session). This order follows `docs/IMPLEMENTATION_PLAN.md`'s own P0→P3 sequencing (the dependency reasoning holds up: e.g. device abstraction genuinely must precede config's per-device override layer).

**Done as of the Phase 6 session (2026-09-13) — do not redo these:**
- ~~P0 remainder — `Dockerfile.ndk-matrix` dedup (item 4).~~ Turned out to already be done (commit `6dbd7dd`, predating this whole documentation effort) — the "still open" claim in every prior session was a stale-carry-forward error, corrected this session. No code change was needed.
- ~~P1 item 9 — SHA-256 verification on the 6 fetch-and-run-in-chroot hooks.~~ Landed this session: all 6 hooks pin a specific version/commit and verify a real SHA-256 before installing anything. See `docs/IMPLEMENTATION_PLAN.md` item 9 for the pinned versions/hashes and how they were obtained.
- ~~Documentation debt — fix README's `scripts/` path references.~~ Already fixed in an earlier session; this session corrected this file's own self-contradictory claim that it was still open.

**Remaining, in order:**
1. **P1 item 5 — `rootforge.core.device` (Device abstraction).** Explicitly sequenced before config per the plan's own reasoning (config's per-device override layer needs it); also removes the current duplication where `flash_patched_boot.sh`, `backup_partitions.sh`, and `unlock_bootloader.sh` each independently re-derive device state. No new dependencies needed — good next session to pick up.
2. **P1 item 6 — `rootforge.core.config`.** Depends on item 1. Introduces the repo's first third-party Python dependency (`python3-yaml`) — flag this explicitly when it lands, since CLAUDE.md's ask-first list includes new dependencies; consider asking before adding it.
3. **P1 item 7 — `rootforge.core.log`.** Independent of items 1–2 but most valuable once they exist (structured logs can then include device/config context). Also the natural place to close the plaintext-secret-in-logs risk class systemically, rather than one-off per incident as commit `d6d812c` was.
4. **P1 item 8 — backup integrity CLI (`rootforge backup verify`).** Builds on the SHA256SUMS mechanism that already exists in `common.sh`/the scripts; mostly CLI surface work at this point.
5. **P2 item 11 remainder — finish the `boot` subcommand group** (`inspect`/`unpack`/`repack`/`verify`), now that `patch`/`flash-last` establish the pattern.
6. **P3 — do not start** (`rootforge-kernel`, dynamic-partition tooling, GUI, CI VM-boot testing) **until P0–P2 above are stable**, per the plan's own explicit rule. GUI in particular should wait until `rootforge.core` (config/device/log) is stable enough to be a real dependency for a GUI to call into — building a GUI against today's core would mean rebuilding it once that core lands.

**Rationale for this ordering:** now that P0 and the highest-value P1 security item are actually done, genuine architectural dependencies (device before config) come before convenience/completeness work (log, backup CLI, boot subcommands), which comes before net-new subsystems (P3). This matches CLAUDE.md's own stated priority: correctness → architecture → security → testability → maintainability → functionality.

---

## QUICK REFERENCE

**Run:** 2026-09-13.

### Run the test suite
```
python3 -m unittest discover -s tests -p 'test_*.py'   # Python only, ~0.14s, 129 tests
bash tests/run-tests.sh                                  # full suite, 420 checks
make test                                                 # same as above
```

### Lint (requires shellcheck — not available in this container)
```
bash tests/lint.sh
make lint
```

### CLI usage
```
rootforge --version
rootforge doctor [--json] [--quiet] [--strict]
rootforge devices [--json] [-l|--detailed]
rootforge module <scaffold|lint|build> ...
rootforge flash ... / rootforge backup ...
rootforge ota <extract|inspect> ...
rootforge boot <patch|flash-last> ...
rootforge avd <create|boot|list> ...
```

### Build the ISO (needs root, ~20GB disk, 60–90 min — not run in this container)
```
sudo make build
make checksum
make list-usb
sudo make flash USB=/dev/sdX
```

### Key files to read before touching each area
| Area | Read first |
|---|---|
| CLI dispatch | `config/includes.chroot/usr/local/lib/rootforge/core/cli.py` |
| Script wrapping mechanism | `.../core/runner.py` |
| Shared shell safety helpers | `config/includes.chroot/usr/local/lib/rootforge/sh/common.sh` |
| Roadmap / priorities | `docs/IMPLEMENTATION_PLAN.md` |
| Historical audit (dated, partially stale — see Phase 1) | `docs/ARCHITECTURE_AUDIT.md` |
| Host build prerequisites | `BUILD.md` |
| Contributor internals | `HACKING.md` |
| Test hermeticity mechanism | `tests/README.md` |

### Known gaps to keep in mind before claiming a feature works
No `rootforge.core.config`/`device`/`log` · no Windows/Android-app/GUI/remote-admin · `shellcheck`/device tooling not installed in this container. (Build-artifact checksum verification and README's `scripts/` paths were both fixed as of the 2026-09-13 Phase 6 session — don't assume either is still a gap without checking.)

---

*End of CLAUDE.md*

---

## RECOMMENDED DEVELOPMENT PRIORITY

`[Populated during Phase 5. Not yet run.]`

---

## QUICK REFERENCE

`[Populated during Phase 5. Not yet run.]`

---

*End of CLAUDE.md*
