# CLAUDE.md — RootForge-OS Development Guide & Progress Tracker

> **This file is the single source of truth for Claude Code sessions working on RootForge-OS.**
> Read the **PROJECT STATE** section first, every session, before doing anything else.
> Update the **PROJECT STATE** section at the end of every session before finishing.

---

## 🔖 PROJECT STATE (READ THIS FIRST)

**Last Updated:** `2026-09-13`
**Last Session Summary:** `Phase 3 completed (same session as Phases 1-2). Documented the 3 build paths (live-build ISO, termux/build-rootfs.sh, no-build Python) with commands transcribed from BUILD.md/Makefile/termux scripts read directly; documented the test suite's actual organization and commands. Resolved the 420-vs-421 discrepancy as far as possible: confirmed via git log that tests/run-tests.sh and tests/check-tests.sh have been unchanged since 2026-09-01 (before both the 2026-09-11 and 2026-09-13 sessions), re-ran the suite twice more (420/0 both times, stable), and found no environment-conditional branch that could explain a genuine 1-check difference — concluded the "421" figure was most likely a recording discrepancy in the prior session, not a reproducible bug. Also found a second real doc-accuracy bug (BUILD.md's git-clone URL points to a different GitHub org than the actual remote) and confirmed this container actually has root+loop-device access for an ISO build, though one was deliberately not attempted (20-60 min, disproportionate to a documentation session, not requested). Full detail in the new BUILD SYSTEM & TOOLING and TESTING sections below.`

### Current Phase

`[x] PHASE 3 COMPLETE — READY FOR PHASE 4`

| Phase | Status | Completed Date | Notes |
|-------|--------|-----------------|-------|
| Phase 0 — Setup & Access | ✅ Complete | 2026-09-11 | See "Phase 0 Findings" below |
| Phase 1 — Repository Inspection | ✅ Complete | 2026-09-13 | See "INSPECTION REPORT" below |
| Phase 2 — Core Architecture Documentation | ✅ Complete | 2026-09-13 | See "CURRENT ARCHITECTURE & IMPLEMENTATION STATE" below |
| Phase 3 — Build System, Testing & Tooling | ✅ Complete | 2026-09-13 | See "BUILD SYSTEM & TOOLING" and "TESTING" below |
| Phase 4 — Workflow & Architecture Rules | ⬜ Not Started | — | — |
| Phase 5 — Final Audit & Next Steps | ⬜ Not Started | — | — |
| Phase 6 — Active Development (ongoing) | ⬜ Not Started | — | — |
| Phase 7 — Pull Request / Build & Release | ⬜ Not Started | — | — |

**Status Legend:** ⬜ Not Started · 🟨 In Progress · ✅ Complete · 🔁 Needs Revisit

### What To Do Next

`Begin Phase 4 — Workflow & Architecture Rules, using the INSPECTION REPORT, CURRENT ARCHITECTURE, BUILD SYSTEM & TOOLING, and TESTING sections as factual base. Document: the Claude Code session workflow specific to this repo (this file's own protocol, now exercised across 4 phases — worth reflecting on what's worked); security considerations (the confirmation-gate pattern in common.sh, the secrets-file hygiene in rf_write_private, the still-open gaps from Phase 1 §9 — no SHA-256 verification on 6 build-time download hooks, no reproducibility manifest); architectural decisions already made with rationale (Python stdlib-first per docs/ARCHITECTURE_AUDIT.md §6, wrap-not-rewrite per docs/IMPLEMENTATION_PLAN.md, allow_abbrev=False everywhere per Phase 2 §7); privilege handling (root requirement for auto/build and termux/build-rootfs.sh, the rf_confirm gate, sudoers.d/rootforge-live — still not read in full, flagged in Phase 2 §10); coding standards actually followed (HACKING.md's "Adding a command: prefer the CLI over a new script" and "Adding a script" sections, read this session, are real and specific — use them as the basis rather than inventing generic standards). Two still-outstanding, low-risk documentation fixes from Phases 1 and 3 (not yet acted on): README.md's ~15 stale scripts/ path references, and BUILD.md's git-clone URL pointing to the wrong GitHub org.`

### Open Questions / Blockers

- `shellcheck` is not installed in this environment, so `tests/lint.sh` / `make lint` cannot be run locally here (CI installs it via apt in `.github/workflows/lint.yml`). Confirmed still true this session. Not a blocker for inspection/documentation work, but blocks locally verifying lint-clean status before a push — carry into Phase 3 tooling docs.
- `pytest` is not installed, but is not required: `tests/test_*.py` use Python's stdlib `unittest` and are run via `python3 -m unittest discover` / through `tests/run-tests.sh`, not pytest. Confirmed working this session (129/129 pass).
- Android-specific tooling (`adb`, `fastboot`, `aapt`, `repo`) and image-build tooling (`mksquashfs`, `mkbootimg`, `cpio`) are not installed in this container. The test suite stubs these (see `tests/stubs/`, `tests/README.md`) so the hermetic suite does not need them; they would be required for real on-device flashing/building work, which is out of scope unless a session is explicitly asked to do it.
- **Resolved this session:** `docker`'s role — it is an in-ISO runtime package (`docker.io`, installed via `config/package-lists/*.list.chroot` and the bootstrap hook that adds the login user to the `docker` group) used exclusively by `build_matrix.sh` for isolated NDK/API version-matrix builds *on a running RootForge OS install*. It is not used by this repo's own build, lint, or test tooling — the `docker` binary present in this dev container is incidental (base image tooling) and irrelevant to RootForge-OS's own pipeline.
- **Resolved (as far as possible) in the Phase 3 session:** the 420-vs-421 shell-check-count discrepancy. `git log` confirms `tests/run-tests.sh`/`tests/check-tests.sh` unchanged since 2026-09-01 — before both the 2026-09-11 and 2026-09-13 sessions — and re-running the suite twice more this session gave 420/0 both times, with no environment-conditional branch found that could explain a genuine delta. `[Likely]` the "421" was a recording discrepancy in the 2026-09-11 session rather than a reproducible bug; full investigation in the TESTING section §3. Treat 420 as the current baseline going forward — a different count on an unmodified suite is the thing worth investigating next time, not this historical figure.
- **New:** `docs/ARCHITECTURE_AUDIT.md` is dated 2026-08-08 and states as fact that no `rootforge` CLI, no `tests/` directory, and no Python package exist in this repository. All three claims are now false — confirmed by direct inspection this session (see INSPECTION REPORT). This is not a contradiction to resolve by editing that file (it's a dated audit, valid as of its own commit), but Phase 2 documentation must not cite it uncritically — cite the current tree instead. `docs/IMPLEMENTATION_PLAN.md`'s own "P0.5 (landed)" section already documents that this gap was closed after the audit was written, which is consistent with what direct inspection shows.

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

One real, byte-identical file duplication the audit flagged (`Dockerfile.ndk-matrix` under both `opt/rootforge/docker/` and `usr/local/share/rootforge/docker/`) was checked this session and **both copies still exist** — `docs/IMPLEMENTATION_PLAN.md` item 4 ("Deduplicate Dockerfile.ndk-matrix") is listed under P0 but is **not** in the "P0.5 (landed)" section, so this is confirmed still-open work, not yet done despite being P0-priority.

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
| `magiskboot`, `avbtool`, `mkbootimg`/`unpack_bootimg`/`repack_bootimg` | Boot-image tooling, baked into ISO at build time from AOSP/Magisk upstream sources | Required (baked in) | Fetched from real upstream sources per hooks `0060`/`0085`; **no SHA-256 verification of these downloads exists** — confirmed still true this session (grepped the 6 hooks the audit named; none pipe through a checksum check). This is `docs/IMPLEMENTATION_PLAN.md` item 9, P1, and remains open. |
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
| Artifact SHA-256 verification (fetched build-time tools) | MISSING |
| Reproducibility manifest (`system-manifest.json`) | MISSING |
| Dockerfile.ndk-matrix dedup | MISSING (P0 item, not yet done) |
| Windows platform | MISSING |
| Android APK application | MISSING |
| RootForge GUI | MISSING |
| Remote/multi-node administration | MISSING |
| `rootforge-kernel` subsystem | MISSING |
| Dynamic-partition (`lpunpack`/`lpmake`) tooling | MISSING |
| README `scripts/` path references | INCORRECT (documentation bug, not a code bug) |

**Exit criteria met:** actual code and structure inventoried directly (not from memory or prior docs' claims alone); IMPLEMENTED / PARTIALLY IMPLEMENTED / PLANNED / MISSING breakdown produced above for every major area; no fabricated claims — items not run this session (ISO boot, release.yml, CI green-status, secret scan) are explicitly marked as not independently verified rather than assumed. Phase 2 can proceed.

---

## CURRENT ARCHITECTURE & IMPLEMENTATION STATE

**Run:** 2026-09-13, same session as Phase 1, branch `claude/build-per-claude-md-661ey0`. Every module named below was opened and read this session (not inferred from the Phase 1 inventory or from `docs/IMPLEMENTATION_PLAN.md`'s descriptions) unless marked otherwise. `[Certain]` unless flagged.

### 1. RootForge Core — what platform-independent functionality actually exists **[Certain]**

There is **no single unified "RootForge Core"** yet in the sense CLAUDE.md's architecture diagram describes (one platform-independent layer under Linux/Windows/Android). What exists instead is **two separate, non-interoperating "shared foundation" layers**, both Linux/Debian-specific:

1. **Python side** — `rootforge.core.runner` (`find_script`/`run_script`/`exec_script`): the one piece of real shared infrastructure, used by every CLI wrapper module (`flashing.py`, `module.py`, `ota.py`, `boot.py`, `avd.py`) to locate and invoke the standalone shell scripts, passing their exit code through untouched. `rootforge.core.doctor` and `rootforge.core.devices` are shared *logic* (environment checks, device enumeration) consumed by the CLI, but not yet consumed by the shell scripts themselves — `doctor.py`'s `check_adb_devices()` imports `devices.list_devices`, but `backup_partitions.sh` (shell) still has its own device-detection path via `common.sh`, not this Python module.
2. **Shell side** — `config/includes.chroot/usr/local/lib/rootforge/sh/common.sh`: sourced by the 27 standalone scripts. Provides `rf_confirm` (typed-confirmation gate, reads `/dev/tty` so it survives `fleet_orchestrate.sh` redirecting a child's stdout to a log; `ROOTFORGE_ASSUME_YES=1` bypasses it for that one caller), `rf_sha256_file`/`rf_sha256_verify` (backup integrity), `rf_adb_serials`/`rf_fastboot_serials`/`rf_have_*_device` (device enumeration — the shell-side equivalent of `devices.py`, implemented independently, not shared code), `rf_shell_quote`/`rf_write_private` (secrets handling — quoting for `ai-keys.env`, mode-600-from-creation writes), `rf_require_cmd`, and `rf_download_cached` (atomic-rename download caching, replacing a pattern that shipped a 9-byte truncated Magisk module to a device in a real prior incident per the file's own comment).

These two layers do not call each other: Python invokes the shell scripts as subprocesses (one-way), and the shell scripts don't call into Python. This is consistent with `docs/IMPLEMENTATION_PLAN.md`'s own phasing (P2 wraps scripts via subprocess; deeper integration is P3+) but is worth stating plainly: "RootForge Core" today is two parallel toolkits, not one.

**Confirmed still absent** (checked by file listing + grep, not just citing the plan): `rootforge.core.config` (central config system, P1 item 6) and `rootforge.core.device` (typed `Device` dataclass shared across subsystems, P1 item 5) do not exist. `devices.py`'s `Device` dataclass is real but local to the CLI's `devices`/`doctor` commands — it is not imported anywhere else, and no YAML/config-file parser exists anywhere in the repo (`python3-yaml` is not in any package list; no `*.yaml`/`*.yml` config schema for RootForge itself, as opposed to CI workflow YAML). Structured JSON logging (P1 item 7, `rootforge.core.log`) also does not exist — no `log.py` in `core/`, confirmed by the module listing in §3 of the Phase 1 report.

### 2. Component inventory: purpose, location, status, dependencies, tests **[Certain]**

| Component | Location | Purpose | Depends on | Test coverage |
|---|---|---|---|---|
| `rootforge` CLI entrypoint | `usr/local/bin/rootforge` | `sh` shim → `python3 -m rootforge.core.cli` | `rootforge.core.cli` | Exercised indirectly by every `test_*_cli.py` file (they invoke `cli.main()`/parsers directly, not the shim) |
| CLI dispatcher | `core/cli.py` (145 lines) | `argparse` top-level parser, `doctor`/`devices` inline, delegates rest | all `*_cmd` modules below | No dedicated `test_cli.py`; covered indirectly via the per-subcommand test files |
| `rootforge doctor` | `core/doctor.py` (284 lines) | 17 independent host/environment checks (tools present, disk space, Ollama reachability, second-brain vault, device state); `--json`/`--quiet`/`--strict` | `shutil`, `urllib`, lazily `rootforge.core.devices` | `tests/test_doctor.py` |
| `rootforge devices` | `core/devices.py` (159 lines) | Merges `adb devices` + `fastboot devices` into one `Device` list; parses adb's state column correctly (fixes the "trailing blank line counted as a device" bug); `-l`/`--json` | `adb`/`fastboot` binaries (absent → empty list, not an error) | `tests/test_devices.py` |
| `rootforge module scaffold\|lint\|build` | `core/module.py` (102 lines) | Wraps `new_module_scaffold.sh`/`lint_module.sh`/`build_magisk_module.sh`; validates module id format (shared regex with the linter) before invoking the script | `rootforge.core.runner`, the 3 named shell scripts | `tests/test_module_cli.py` |
| `rootforge flash\|backup` | `core/flashing.py` (151 lines) | Wraps `flash_patched_boot.sh`/`backup_partitions.sh`/`restore_partitions.sh`; validates image existence/non-emptiness, device-serial shape, and a codename/timestamp path-traversal guard (`..` can't escape `$ROOTFORGE_HOME/devices/`) | `rootforge.core.runner`, the 3 named shell scripts | `tests/test_flashing_cli.py` |
| `rootforge ota extract\|inspect` | `core/ota.py` (110 lines) | Wraps `extract_ota.sh`/`inspect_partition_image.sh`; makes the output-directory-vs-flag ambiguity that once caused a real bug (`extract_ota.sh ota.zip --partitions boot` extracting into a directory literally named `--partitions`) structurally unrepresentable | `rootforge.core.runner`, the 2 named shell scripts | `tests/test_ota_cli.py` |
| `rootforge boot patch\|flash-last` | `core/boot.py` (128 lines) | Wraps `kernelsu_patch_boot.sh` only — **partial** implementation of P2 item 11; `unpack`/`repack`/`verify` around magiskboot/avbtool/mkbootimg are explicitly not built (no such scripts exist yet to wrap) | `rootforge.core.runner`, `kernelsu_patch_boot.sh` | `tests/test_boot_cli.py` |
| `rootforge avd create\|boot\|list` | `core/avd.py` (122 lines) | Wraps `setup_rooted_avd.sh`; blocks an invalid rooted+Play-image combination before `sdkmanager` would download a system image that can never be rooted | `rootforge.core.runner`, `setup_rooted_avd.sh` | `tests/test_avd_cli.py` |
| Script runner | `core/runner.py` (101 lines) | `find_script` (installed path → checkout-relative path → `PATH`, in that order) + `run_script`/`exec_script` (passes exit codes through untouched; does not capture output by default, since destructive scripts prompt on `/dev/tty`) | — | Exercised via every `test_*_cli.py` (they patch/inspect its calls) |
| Shell common helpers | `sh/common.sh` (217 lines) | `rf_confirm`, `rf_sha256_file`/`_verify`, `rf_adb_serials`/`rf_fastboot_serials`/`rf_have_*_device`, `rf_shell_quote`, `rf_write_private`, `rf_require_cmd`, `rf_download_cached` | sourced by the 27 scripts | Covered by the shell half of `tests/run-tests.sh` (per-script sections), not by a dedicated `common.sh` test file |
| second-brain (`brain`) | `usr/local/bin/brain` (shim) + `usr/local/lib/rootforge/second-brain/brain.py` (452 lines) | Stdlib-only PARA-method note vault: `init`/`new`/`daily`/`index`/`search`/`ask`/`list`/`stats`; embeddings + chat via Ollama's HTTP API (`urllib`), optional `--provider claude` for `ask` shelling out to the Claude Code CLI; sqlite3-backed index | Ollama (optional, degrades to an error message, not a crash, when unreachable — not independently re-verified this session, based on reading the code's `try/except urllib.error` pattern), optionally the `claude` CLI | `tests/test_brain.py` |
| 27 standalone device scripts | `usr/local/bin/*.sh` | The actual device-facing implementation (flashing, backup/restore, module scaffold/lint/build, AVD, root-detection, AI-tooling setup, hardening, VPN/proxy, LSPosed, ESP32/RPi fleet tools, terminal setup, etc.) — unchanged in location, independently invocable, source `common.sh` | `adb`/`fastboot`/`magiskboot`/`avbtool`/etc. depending on script | Covered by `tests/run-tests.sh`'s shell sections (hermetic, stubbed tooling) |
| ISO build pipeline | `auto/config`, `auto/build`, `config/` | Assembles the bootable/installable Debian 12 amd64 ISO | `live-build`, `debootstrap`, `squashfs-tools`, `xorriso` (host-side, not installed in this dev container) | `.github/workflows/release.yml`'s `build-iso` job (not re-run this session) |
| Termux/PRoot rootfs builder | `termux/build-rootfs.sh` (292 lines) | debootstrap-based rootfs, 2 archs × 2 flavors (`proot`/`chroot`) + optional `--with-x11`; reuses `config/hooks/*.hook.chroot` for tools that work identically under PRoot | `debootstrap`, `qemu-user-static`, `binfmt-support` (host-side) | `.github/workflows/release.yml`'s `build-termux-rootfs` matrix (not re-run this session) |

### 3. Linux implementation **[Certain]** for code presence; **not independently re-verified** for boot/install behavior this session

Real code, not a stub: `auto/config`/`auto/build` invoke live-build correctly (the `lb build noauto` recursion-avoidance fix from the audit is present in the current `auto/build`, read this session), 15 numbered chroot hooks install AI tooling/boot-image tools/desktop config, `config/includes.chroot` overlays Calamares branding + systemd units + a udev rule for Android USB access + a Plymouth theme, and the `rootforge` CLI + 27 scripts are baked into the image via that same overlay. Whether a built ISO actually boots and installs correctly is **not verified in this session** — that claim rests entirely on `docs/ARCHITECTURE_AUDIT.md`'s citation of a specific prior CI run (31269821588) and on `release.yml`'s design, neither of which this session re-ran (would need ~20GB disk, loop-device access, 60-90 minutes).

### 4. Windows implementation **[Certain]**

Zero code. No `.sln`/`.csproj`/PowerShell/`.bat` files, no WSL-integration scripts, no Windows-specific branch anywhere in any script (grepped this session). CLAUDE.md's Windows vision item is entirely **PLANNED**, not started at any scaffolding level.

### 5. Android implementation **[Certain]**

Two things are easy to conflate here, and the repository only has one of them:

- **Android *device* tooling** (real, extensive): the 27 scripts + the `rootforge` wrappers around several of them manage physical/emulated Android devices *from* the Linux ISO — flashing, backup, module install, AVD management, root detection. This is "RootForge OS interacts with Android hardware," not "RootForge runs on Android."
- **An Android *application*** (CLAUDE.md's "RootForge-OS Android APK" vision item — GUI, embedded Termux, headless CLI, local/remote management, all running as an app on an Android device): **zero code**. No `AndroidManifest.xml`, no `.apk`, no Gradle Android project, no Kotlin/Java source, confirmed by extension search this session. **PLANNED only.**

### 6. Termux integration **[Certain]**

Real, working (as code — not device-tested this session, no Android hardware or emulator available in this container):

- `termux/build-rootfs.sh` (292 lines) — builds a debootstrap rootfs for two archs (arm64 default/real hardware, amd64/x86 rare or desktop-sandbox) and **two genuinely different flavors**: `proot` (unrooted, ptrace-emulated, the default) and `chroot` (rooted, real `chroot` via `su`, gets real device nodes — loop-mounted partition images, USB adb/fastboot, `/dev/net/tun` for VPN — that PRoot structurally cannot provide). The chroot flavor deliberately **excludes** `harden_kernel.sh`/`harden_system.sh`: those need kernel subsystems (AppArmor, auditd, nftables, USBGuard) Android kernels don't ship, and root doesn't change which kernel you're on — a real, correctly-reasoned platform limitation, not an oversight.
- `termux/install.sh` (78 lines) — one-command Termux-side installer: installs `proot-distro` if needed, fetches the plugin definition, detects root (`su -c 'id -u'`) and tells the user which variant (PRoot vs. chroot) is applicable, since picking wrong wastes a multi-GB download.
- `termux/proot-distro-plugins/rootforge.sh` — the `proot-distro` plugin end users install; **partially implemented** — real URLs, placeholder SHA-256 (see Phase 1 report §7).
- `termux/rootforge-chroot.sh` (178 lines), `termux/bootstrap_proot.sh` (82 lines), `termux/proot-setup.sh` (68 lines) — not read in full this session; read only for line counts and cross-references from `install.sh`/`build-rootfs.sh`'s comments. Their existence and role (chroot launcher; PRoot bootstrap; PRoot first-run setup, respectively) is `[Likely]` based on filename and the references above, not independently verified line-by-line.
- Optional Termux:X11 desktop layer (`--with-x11` in `build-rootfs.sh`) — real flag, roughly triples tarball size per the script's own comment, opt-in.

This is genuine platform-appropriate design (per CLAUDE.md's "do not assume Termux built in means bundling an APK" instruction) — it correctly treats root as something to detect, not assume, and treats PRoot/chroot as materially different capability sets rather than one "Termux support" checkbox.

### 7. CLI **[Certain]**

Fully documented in §2's table above. Summary: `rootforge {doctor, devices, module, flash, backup, ota, boot, avd}`, version `0.3.0` (from `core/__init__.py`, read this session). Design principles enforced consistently across every subcommand module (read directly, not inferred): `allow_abbrev=False` everywhere (an abbreviation could silently start meaning something different once a new flag is added — unacceptable on commands that write boot partitions), argparse `type=` validators that reject bad input *before* a script runs rather than after (path-traversal guards, device-serial shape, module-id format shared with the linter, GitHub release-tag shape guarding against a real prior SSRF-shaped bug where an unvalidated tag redirected a `curl` request to a different repository), and exit codes passed through from the wrapped script untouched rather than translated (since several scripts use non-zero deliberately to report a finding, not a failure).

### 8. GUI **[Certain]**

No RootForge-authored GUI exists. Calamares (third-party, `calamares-settings-debian` base + RootForge branding/QML under `etc/calamares/`) provides the *installer's* GUI only — one specific task (disk installation), not an ongoing management interface, and not RootForge's own code. This matches `docs/IMPLEMENTATION_PLAN.md` item 17's explicit deferral and CLAUDE.md's own layering principle (GUI is a client of core services, added once core is stable) — correctly not started yet rather than a gap in an otherwise-GUI-first project.

### 9. APIs / IPC **[Certain]**

No RootForge-defined API, RPC, or IPC mechanism exists. The only inter-process communication in the codebase is: (a) `subprocess.run` from `runner.py` (Python → shell script, one-way, by design — see §1), and (b) plain HTTP via `urllib` from `doctor.py`/`brain.py` to a local Ollama server (`GET /api/tags`, and presumably `/api/embed`/`/api/generate` for `brain.py`'s actual embedding/chat calls — not verified line-by-line this session beyond the `doctor.py` reachability check). No REST API, gRPC, Unix socket, or D-Bus service of RootForge's own exists — CLAUDE.md's "RootForge API / Service Layer" architectural diagram is aspirational, not yet built.

### 10. Authentication / authorization **[Certain]**

No RootForge-specific auth system exists — there are no user accounts, API keys, or role-based access control anywhere in the codebase. What exists instead, correctly scoped to a single-operator local tool rather than a multi-tenant service:

- **Confirmation gates, not authorization**: `rf_confirm` (shell) gates destructive operations behind a typed word, reading `/dev/tty` specifically so it can't be silently bypassed by output redirection; `ROOTFORGE_ASSUME_YES=1` is a documented, loudly-logged bypass for exactly one caller (`fleet_orchestrate.sh`), not a general escape hatch.
- **File-permission hygiene, not RootForge's authorization**: `rf_write_private` writes secrets (`~/.rootforge/ai-keys.env`) at mode 600 from the moment the file exists (fixing a real prior window where a rewrite-through-temp-file pattern left keys world-readable at the default umask before the final `chmod`).
- **OS-level, not RootForge-level**: `config/includes.chroot/etc/sudoers.d/rootforge-live` (not read in full this session) presumably grants the live-session user specific sudo rights — `[Likely]`, based on filename and location, not verified content this session.

CLAUDE.md's "Security Foundations" list (auth/authz, secrets management, audit logging, command validation) is partially met (secrets-file hygiene and confirmation gates are real) and partially not (no audit logging system — see §13 — and no authentication concept at all, which is arguably correct for a single-user dev tool but is a real gap against the multi-node "Remote RootForge Management" vision item, which would need one).

### 11. Configuration system **[Certain]**

No config *file* system exists. Every tunable is an environment variable read ad hoc, with its own default hardcoded at the point of use — confirmed by reading `doctor.py` (`OLLAMA_HOST`, `ROOTFORGE_BRAIN_VAULT`), `devices.py` (none), `runner.py` (`INSTALLED_BIN` is a hardcoded constant, not configurable), `brain.py` (`BRAIN_VAULT`, `OLLAMA_HOST`, `BRAIN_EMBED_MODEL`, `BRAIN_CHAT_MODEL`), and `common.sh`/scripts (`ROOTFORGE_HOME`, `ROOTFORGE_ASSUME_YES`). Several of these are the *same concept* under different names between the Python and shell worlds (`ROOTFORGE_BRAIN_VAULT` vs. `BRAIN_VAULT`) — a real, minor inconsistency worth flattening whenever the P1 central-config item is actually built, not urgent on its own. `docs/IMPLEMENTATION_PLAN.md`'s P1 item 6 (`~/.config/rootforge/config.yaml` + `rootforge.yaml` + per-device override files, PyYAML dependency) remains **entirely unbuilt** — confirmed again this session, not merely carried forward from Phase 1.

### 12. Remote management **[Certain]**

Confirmed still absent, same finding as Phase 1: no node discovery, no remote transport/protocol, no multi-node state sync. `fleet_orchestrate.sh` (not read in full this session, per its cross-references from `common.sh`'s comments) drives multiple **locally USB-attached** devices sequentially from one operator's machine — not a client/server or networked architecture. CLAUDE.md's "Remote Architecture" diagram (a Controller with Linux/Windows/Android/Headless nodes) has **no implementation of any kind** — not a client, not a server, not a transport, not even a stub.

### 13. Logging / audit **[Certain]**

No structured or centralized logging exists. Each of the 27 shell scripts implements its own `log()` function writing prose to `~/rootforge/logs/` — confirmed as still true this session (no `rootforge.core.log` module exists in the Python package, per the file listing read in §2/§1). This is exactly the "~20 near-identical copies of the same six lines" duplication `docs/ARCHITECTURE_AUDIT.md` §3.3 originally flagged, and it remains unresolved: `docs/IMPLEMENTATION_PLAN.md` P1 item 7 (JSON-lines logging with per-invocation execution IDs and secret redaction) has not landed. No audit trail exists for destructive operations beyond whatever a given script's own `log()` call happens to write — there is no tamper-evident or centrally-queryable record of, e.g., every `flash boot` invocation across a fleet.

### 14. Component dependency diagram **[Certain]** (structure) — reflects what was read this session, not a formal design document

```
                         ┌─────────────────────────┐
                         │   rootforge (sh shim)    │
                         └────────────┬─────────────┘
                                      │ exec
                         ┌────────────▼─────────────┐
                         │   rootforge.core.cli      │  argparse dispatch
                         └──┬────┬────┬────┬────┬────┘
             ┌──────────────┘    │    │    │    └───────────────┐
             ▼                   ▼    ▼    ▼                    ▼
        doctor.py           devices.py  flashing.py module.py ota.py boot.py avd.py
             │                   │           │          │        │      │      │
             │  (lazy import)    │           └──────────┴────────┴──────┴──────┘
             └──────────────────►│                        │  all call
                                  │                        ▼
                                  │                 rootforge.core.runner
                                  │                (find_script / exec_script)
                                  │                        │  subprocess.run
                                  ▼                        ▼
                          adb / fastboot            usr/local/bin/*.sh (27 scripts)
                          (external binaries)               │
                                                              │ source
                                                              ▼
                                                   sh/common.sh (rf_confirm,
                                                   rf_sha256_*, rf_adb_serials,
                                                   rf_shell_quote, ...)

   second-brain (brain / brain.py) — standalone, not wired into rootforge.core
        │
        ├─► sqlite3 (local index)
        ├─► Ollama HTTP API (embeddings + chat, via urllib)
        └─► optionally: claude CLI subprocess (--provider claude for `ask`)

   ISO build (auto/config, auto/build, config/*) and
   Termux rootfs build (termux/build-rootfs.sh) — independent of the CLI's
   runtime graph above; they are what BAKES the CLI + scripts + brain.py
   into a filesystem image, not something the CLI depends on at runtime.
```

Note the two structural facts this diagram makes visible: (1) `brain`/`brain.py` is **not** wired into `rootforge.core` at all — no `cli.py` subcommand calls it, it's a fully independent entrypoint discovered only via `doctor.py`'s vault-presence check; (2) the CLI's entire "core" is a dispatch + validation layer over the pre-existing shell scripts, with exactly one shared Python module (`runner.py`) and one shared shell module (`common.sh`) — there is no deeper shared abstraction yet (confirmed absent: config, device dataclass, logging).

### 15. Full implementation status table

| Area | Status | Basis |
|---|---|---|
| RootForge Core (unified, cross-platform) | **PARTIALLY IMPLEMENTED** — two parallel, Linux-only foundations (Python `runner.py`/`doctor.py`/`devices.py`; shell `common.sh`), not one platform-independent core | §1 |
| `rootforge.core.config` | **PLANNED** | §11 |
| `rootforge.core.device` (typed, shared) | **PLANNED** (only `devices.py`'s CLI-local dataclass exists) | §1, §2 |
| `rootforge.core.log` (structured logging) | **PLANNED** | §13 |
| `rootforge` CLI (8 subcommand groups) | **IMPLEMENTED** (one, `boot`, partial — see §2) | §2, §7 |
| 27 standalone device scripts | **IMPLEMENTED** | §2 |
| second-brain (`brain`) | **IMPLEMENTED**, architecturally standalone from `rootforge.core` | §2, §14 |
| Linux ISO build + Calamares install | **IMPLEMENTED** (code); boot/install behavior **not re-verified this session** | §3 |
| Termux/PRoot + chroot rootfs | **IMPLEMENTED** (code); device behavior **not tested this session** (no hardware/emulator available) | §6 |
| Windows platform | **MISSING** | §4 |
| Android APK application | **MISSING** | §5 |
| RootForge GUI | **MISSING** (Calamares is third-party, installer-only) | §8 |
| RootForge API/IPC layer | **MISSING** (only ad hoc subprocess + Ollama HTTP calls exist) | §9 |
| Authentication/authorization (RootForge-level) | **MISSING** beyond confirmation gates + file-permission hygiene | §10 |
| Remote/multi-node management | **MISSING** | §12 |
| Centralized/structured logging & audit trail | **MISSING** | §13 |

**Exit criteria met:** every component named above was read directly this session; every status is traceable to a specific file/line/command in this document; nothing planned is presented as implemented, and nothing implemented is understated (the CLI, 27 scripts, second-brain, and both build pipelines are real and were exercised or read, not assumed). Phase 3 can proceed.

---

## BUILD SYSTEM & TOOLING

**Run:** 2026-09-13, same session as Phases 1–2, branch `claude/build-per-claude-md-661ey0`. `BUILD.md` and the relevant `HACKING.md`/`Makefile` sections were read directly this session. **A full ISO or Termux-rootfs build was deliberately not attempted this session** — see "What was and wasn't verified" below for why, and what would be needed to verify it.

### 1. The three build targets

| Target | Toolchain | Entry point | Output |
|---|---|---|---|
| Linux ISO | `live-build` | `sudo auto/build` (or `sudo make build`, which also writes the checksum) | `rootforge-os-amd64.hybrid.iso` in the repo root |
| Termux/PRoot or chroot rootfs | `debootstrap` + `qemu-user-static` (for cross-arch) | `sudo termux/build-rootfs.sh [arm64\|amd64] [output-dir] [--flavor proot\|chroot] [--with-x11]` | a timestamped `.tar.xz` (+ `.sha256`) under `dist/` (or the given output dir) |
| `rootforge` CLI / `brain` / `rootforge.core` | none — plain Python 3, stdlib only | `python3 -m rootforge.core.cli ...` / the `rootforge`/`brain` shims | no build artifact; runs directly from source or from its installed location |

These are independent — building the CLI requires nothing (it's source), and neither the ISO nor the Termux builds require the other to have been built first (Termux's builder reuses `config/hooks/*.hook.chroot` as source input, not a built ISO).

### 2. Prerequisites (per `BUILD.md`, read this session)

**ISO build**, on a Debian 12 (Bookworm) or Ubuntu 22.04+ host:
```bash
sudo apt-get install -y live-build debootstrap squashfs-tools xorriso isolinux syslinux-utils
losetup -f          # confirm a free loop device; should print /dev/loopN
modprobe loop        # if not
```
Must run **as root**. `BUILD.md` recommends **20 GB free disk + 4 GB RAM minimum**; the GNOME squashfs itself compresses to ~3–4 GB. Build time is documented as **20–60 minutes**, dependent on network speed, because several chroot hooks fetch external tools at build time (NodeSource, Ollama, Claude Code, magiskboot, eza, starship, `repo`, payload-dumper-go).

**Termux rootfs build**, per `termux/build-rootfs.sh`'s own header (read in Phase 2):
```bash
apt-get install debootstrap qemu-user-static binfmt-support
```
Also root-required; cross-building arm64 (the common case — real Android hardware) on an amd64 host works via `qemu-user-static`'s binfmt registration.

**CLI/`brain` development**: `python3` only (stdlib-only, confirmed in Phase 2 — no `requirements.txt`/`pyproject.toml` exists).

**Documentation-accuracy finding (new this session):** `BUILD.md` line 31 instructs `git clone https://github.com/origin-source-labs/rootforge-os.git` — this is a **different GitHub organization** than the repository's actual remote, confirmed in Phase 0 as `https://github.com/Victorious93/rootforge-os`. This is a second real, repo-wide-relevant doc-accuracy bug (alongside Phase 1's `scripts/` path finding in README.md) — worth fixing in the same documentation pass as that one, since both are in files a new contributor reads first.

### 3. Verified build commands

| Command | Verified this session? | Notes |
|---|---|---|
| `make test` | ✅ **Yes** — ran `bash tests/run-tests.sh` directly (same script `make test` invokes), 420/420 passed | No root needed |
| `make lint` | ❌ **No** — `shellcheck` not installed in this container; `tests/lint.sh` fails immediately with a clear "shellcheck not installed" message rather than a false pass | Confirmed same gap as Phase 0/1 |
| `sudo make build` / `sudo auto/build` | ❌ **Not attempted this session** | See below |
| `sudo termux/build-rootfs.sh ...` | ❌ **Not attempted this session** | See below |
| `make checksum` | ❌ Not run (depends on an ISO existing) | Trivial (`sha256sum`), not a meaningful verification target on its own |
| `make list-usb` | ❌ Not run | Read-only (`lsblk`), low-risk, just not exercised |
| `sudo make flash USB=...` | ❌ Not run, and would not be run without explicit user instruction naming a target device — this overwrites a whole block device | Correctly gated behind a 5-second abort window + checksum verification in the `Makefile`, per Phase 1 reading |

### 4. What was and wasn't verified, and why

**This container does have root (`uid=0`), ~30 GB free disk, and working loop devices** — checked this session (`id`, `df -h`, `ls /dev/loop*`). So a real ISO build is *technically possible* here, unlike what Phase 0/1's framing might imply about tool-availability gaps. It was **not attempted** in this session because: (a) it takes 20–60 minutes and installs a large, mostly build-only package set into this container, both disproportionate to a documentation-phase session that wasn't asked to produce a built artifact; (b) 30 GB free is close to `BUILD.md`'s own stated 20 GB minimum, not comfortably above it; (c) no user request to actually produce or ship an ISO exists in this conversation. This is a **deliberate scope decision, not a capability gap** — flagging the distinction explicitly per CLAUDE.md's honesty rules, since a reader could otherwise assume "not verified" means "couldn't be verified here."

Everything else in this section (prerequisites, command syntax, artifact names/locations, the `--flavor`/`--with-x11` flags) is transcribed from reading `BUILD.md`, `Makefile`, and `termux/build-rootfs.sh` directly, cross-checked against each other for consistency (they agree), but the *behavior* of actually running `lb build` or `debootstrap` end-to-end in this specific container is **not independently confirmed this session**. The most recent independent confirmation on record is `docs/ARCHITECTURE_AUDIT.md`'s citation of CI run 31269821588 (2026-08-08 era) — dated evidence, not this session's.

### 5. Build artifacts and locations

| Artifact | Path | Produced by | Gitignored? |
|---|---|---|---|
| ISO | `rootforge-os-amd64.hybrid.iso` (repo root) | `auto/build` (renamed from live-build's `binary.hybrid.iso`/`binary.iso` — no `--image-name` flag exists in this live-build version, per `auto/build`'s own comment, read in Phase 1) | Yes (`*.iso`) |
| ISO checksum | `rootforge-os-amd64.hybrid.iso.sha256` | `make checksum` / CI | Not explicitly listed in `.gitignore` but sits alongside a gitignored `.iso`, so unlikely to be accidentally committed in practice — not independently verified this session whether `git status` would flag it if present |
| Build log | `rootforge-build-<timestamp>.log` (repo root) | `auto/build` | Yes (`*.log`) |
| Termux rootfs tarball | `<output-dir>/rootforge-<flavor>-<arch>-<timestamp>.tar.xz` (+ `.sha256`), default output dir `./dist` | `termux/build-rootfs.sh` | Yes (`dist/`, `rootforge-proot-*.tar.*`) |
| live-build's own cache/intermediate dirs | `cache/`, `chroot/`, `binary/`, `live-image/`, `.build/` | `lb build` internals | Yes (all listed explicitly in `.gitignore`) |

### 6. CI/CD pipeline behavior

Already documented in the Phase 1 INSPECTION REPORT §5 in detail (job-by-job breakdown of `lint.yml` and `release.yml`); not re-verified again this session (would mean re-running both workflows, out of scope). Restated briefly for this section's completeness: `lint.yml` runs on every PR and push to `main` (5 jobs: shellcheck, YAML dup-key lint, package-list resolution, the hermetic test suite, a CLI smoke test); `release.yml` runs on `v*` tags or manual dispatch (ISO build, 4-way Termux matrix, then a **draft** GitHub Release with checksummed artifacts attached — never auto-published).

### 7. Release/packaging process

Per `release.yml` (read in Phase 1) and `BUILD.md` (read this session): the *only* documented release path is `git tag v<version> && git push --tags` (or the workflow's manual `workflow_dispatch` trigger, which builds artifacts for inspection without touching Releases, since a manual run may lack a tag ref). There is no separate changelog file (`CHANGELOG.md` does not exist — confirmed by file listing; it's listed as future work, `docs/IMPLEMENTATION_PLAN.md` P3 item 19), no version-bump script, and no code-signing step for the ISO or Termux tarballs beyond the SHA-256 checksums already covered in Phase 1's external-dependencies findings (§9: **no signing of any kind** exists — checksums prove integrity of *this* download, not authenticity against tampering at the source). **Do not invent a release process beyond what's in `release.yml`** — this is the complete, real one.

### 8. Common build troubleshooting (as documented, not personally reproduced this session)

Transcribed directly from `auto/build`'s own inline comments (read in Phase 1) and `BUILD.md`, since these represent real prior incidents the maintainer already fixed and documented, not speculation:

- **`lb build` recurses forever / fails with a fast, mysterious "exit 126"** — caused by invoking plain `lb build` instead of `lb build noauto`; `auto/build` already does this correctly (confirmed by reading the file), so this only bites someone bypassing the wrapper and calling `lb build` directly.
- **Build reports success but `rootforge-os-amd64.hybrid.iso.sha256` verification fails, or no ISO appears** — `auto/build` explicitly checks for `binary.hybrid.iso`/`binary.iso` after `lb build` exits 0 and errors loudly if neither exists, rather than silently producing nothing; check the timestamped `rootforge-build-*.log` (auto-tailed to the last 200 lines on failure by `auto/build` itself).
- **"No free loop devices found"** — `auto/build` checks this before starting (`losetup -f`) and gives the exact remediation (`modprobe loop`, or free one via `losetup -a`).
- **Termux tarball has a stale/placeholder proot-distro hash** — expected until a real tagged release publishes one; see Phase 1 report §7.

No troubleshooting steps beyond what's written in these files were fabricated or inferred — this list is intentionally short because it reflects only what the repo's own maintainers documented from real incidents, per CLAUDE.md's "never invent troubleshooting steps" implication of its accuracy rules.

---

## TESTING

**Run:** 2026-09-13, same session as the rest of Phase 3. Builds directly on Phase 1 report §8, adds the resolution of the 420-vs-421 discrepancy and a coverage-vs-gaps breakdown per Phase 3's task list.

### 1. Frameworks, organization, locations

| Layer | Framework | Location | Driver |
|---|---|---|---|
| Python unit tests | stdlib `unittest` (no pytest) | `tests/test_avd_cli.py`, `test_boot_cli.py`, `test_brain.py`, `test_devices.py`, `test_doctor.py`, `test_flashing_cli.py`, `test_module_cli.py`, `test_ota_cli.py` (8 files) | `python3 -m unittest discover -s tests -p 'test_*.py'`, or via `tests/run-tests.sh python` |
| Shell/integration tests | Hand-rolled `assert_eq`/`assert_contains`/`assert_not_contains` harness (no BATS/shunit2) | `tests/run-tests.sh` (2,112 lines, 19 `section()`-delimited groups covering `common.sh`, `flash_patched_boot.sh`, `extract_ota.sh`, `backup_partitions.sh`/`restore_partitions.sh`, `kernelsu_patch_boot.sh`, `fleet_orchestrate.sh`, `build_matrix.sh`, `build_magisk_module.sh`, secret handling, `setup_ai_tools.sh`, `setup_rooted_avd.sh`, `flash_pi_image.sh`, `join_headscale.sh`, `setup_vpn.sh`, `setup_intercept_proxy.sh`, `harden_kernel.sh`, and more — read this session for structure, not exhaustively line-by-line) | `bash tests/run-tests.sh [shell\|python\|all]` (default `all`) |
| Static suite self-checks | Custom (reads files, doesn't execute them) | `tests/check-hooks.sh`, `tests/check-tests.sh` | Invoked by `tests/lint.sh`, and thus by `make lint` / `lint.yml`'s `shellcheck` job |
| Fixture stubs | Fake binaries on `PATH` | `tests/stubs/{adb, adb-device-shell, adb-quiet-probes, am, curl-github-releases, fastboot, getent}` | Sourced into `PATH` by `tests/run-tests.sh`'s sandbox setup |

### 2. Actual commands to run tests

| Scope | Command | Verified this session |
|---|---|---|
| Everything | `tests/run-tests.sh` or `tests/run-tests.sh all` | ✅ Run twice, 420/420 both times |
| Shell tests only | `tests/run-tests.sh shell` | ❌ Not separately re-run this session (implied by the `all` run's shell section passing) |
| Python tests only | `tests/run-tests.sh python`, or directly `python3 -m unittest discover -s tests -p 'test_*.py'` | ✅ Both forms run, 129/129 |
| A single Python test file | `python3 -m unittest tests.test_doctor` (standard `unittest` module-path syntax — not itself re-verified this session, but it's stdlib `unittest`'s documented behavior, not a project-specific mechanism) | ❌ Not run this session |
| A single shell test *section* | Not directly supported — `tests/run-tests.sh` has no `--section`/filter flag (confirmed by reading its `case "$WHICH" in shell\|python\|all\|*)` dispatch, the only branching point in the file); isolating one `section()` block means commenting out the others or reading its output and ignoring the rest | Confirmed by code reading, not by attempting a workaround |
| Lint (shellcheck + hook safety + suite self-check + Python byte-compile) | `tests/lint.sh` or `make lint` | ❌ Still blocked — `shellcheck` not installed in this container, same as every prior session |

### 3. The 420-vs-421 discrepancy — resolved as far as this session can resolve it

Investigated this session, not carried forward unexamined:

- `git log -1 -- tests/run-tests.sh` → last modified **2026-09-01** (commit `d6d812c`). `git log -1 -- tests/check-tests.sh` → last modified **2026-09-01** (commit `707ccea`). Both dates are **before** the 2026-09-11 session that recorded "421" and before this 2026-09-13 session that recorded "420".
- This session's branch (`claude/build-per-claude-md-661ey0`) is based directly on `efacac8`, the exact commit the 2026-09-11 Phase 0 session's work was merged into `main` as. There is **no code difference whatsoever** in `tests/` between what that session ran and what this session ran — same bytes, same commit lineage.
- Ran `bash tests/run-tests.sh` **twice more** this session: **420 passed, 0 failed**, identical both times. No run-to-run nondeterminism observed in this container.
- Checked for environment-conditional branches that could change the total (a `command -v`-gated assert, a loop over a variable-length list): found `for`-loops over **fixed literal lists** (e.g. 5 hostile-input strings at line 473, 5 more at line 1189, `seq 1 25` at line 726, 4 tool names at line 1956) whose iteration counts are hardcoded in the script, not environment-derived — none of these would produce a different total between two runs of the identical file.
- The suite counts the entire Python `unittest` run as **one** pass/fail entry toward `$PASS`/`$FAIL` (`pass "python unittest suite"` on success — confirmed by reading the `test_python()` function), not 129 individually, so the 420 total is (shell `assert_*` executions across all sections and loop iterations) + 1, not a sum of two independently-varying suites.

**Conclusion:** the code is proven identical between the two sessions, and this session's count is stable and reproducible in its own container. The most likely explanation is a recording/transcription discrepancy in the 2026-09-11 session's summary (`[Likely]`, not `[Certain]` — this session cannot inspect that session's actual container or terminal output to rule out a genuine one-off environment difference there, such as a stray leftover file affecting one test's control flow on that run only). **This is not a currently-reproducible bug** — a fresh session running the current tree gets 420, consistently. Recommend: if a future session sees a number other than 420 from an unmodified `tests/run-tests.sh`, that is the signal worth investigating (a real regression or environment issue), not the historical "421" figure, which should now be treated as resolved/superseded rather than re-opened each session.

### 4. Test coverage vs. gaps

**Covered** (per Phase 1/2 findings plus this session's structural read of `tests/run-tests.sh`):
- Every `rootforge` CLI subcommand group has a dedicated Python test file (`test_avd_cli.py`, `test_boot_cli.py`, `test_devices.py`, `test_doctor.py`, `test_flashing_cli.py`, `test_module_cli.py`, `test_ota_cli.py`) — 7 of 8 core modules. **`test_cli.py` (for `cli.py` itself) and `test_runner.py` (for `runner.py` itself) do not exist as separate files** — confirmed by the file listing in Phase 1; both are exercised only indirectly through the subcommand test files, not tested for their own dispatch/argv-parsing logic in isolation.
- `brain.py` has `test_brain.py`.
- A large fraction of the 27 shell scripts have dedicated `section()` blocks in `tests/run-tests.sh` (confirmed: `flash_patched_boot.sh`, `extract_ota.sh`, `backup_partitions.sh`, `restore_partitions.sh`, `kernelsu_patch_boot.sh`, `fleet_orchestrate.sh`, `build_matrix.sh`, `build_magisk_module.sh`, `setup_ai_tools.sh`, `setup_rooted_avd.sh`, `flash_pi_image.sh`, `join_headscale.sh`, `setup_vpn.sh`, `setup_intercept_proxy.sh`, `harden_kernel.sh`, `lint_module.sh`, `check_root_detection.sh` per the section-header grep in Phase 1 and this session's loop/grep passes) — **not confirmed exhaustive**: this session did not cross-check all 27 script names against all `section()` headers one-by-one to produce a definitive covered/uncovered list, so treat "most scripts are covered" as `[Likely]`, not a verified complete inventory.
- `common.sh`'s helpers are covered by two dedicated sections (`"common.sh — device enumeration"`, `"common.sh — confirmation gate"`, `"common.sh — secret handling"`).

**Confirmed gaps** (from Phase 1's findings, restated here as the "coverage vs. gaps" task requires, plus one new item):
- **No VM boot test** — CI's `release.yml` validates that `lb build` exits 0 and produces a file of the expected shape; it has never booted the resulting ISO to confirm it works as an OS. This is `docs/IMPLEMENTATION_PLAN.md` P3 item 18, explicitly still open.
- **No dedicated `test_cli.py`/`test_runner.py`** (new finding this session, from the file listing) — the top-level dispatcher and the shared script-invocation helper are tested only as a byproduct of testing the things that call them, not for their own behavior (e.g., `cli.py`'s `--version`/`--help`/no-args-prints-help paths, `runner.py`'s three-tier `find_script` fallback order) in isolation.
- **No config-system tests** — moot until `rootforge.core.config` (still PLANNED per Phase 2) is actually built.
- **`shellcheck` cannot run in this dev container** — a real, recurring gap in what can be *locally* verified before pushing, not a gap in the suite's own design; CI covers it.
- **No test coverage measurement tool** (no `coverage.py`/`nose`/similar) — confirmed by no such dependency existing anywhere; test completeness is judged by direct reading of `tests/run-tests.sh`'s section list against `usr/local/bin/`'s script list, not by a generated coverage percentage. This means any coverage claim in this document (including "most scripts are covered" above) is a manual estimate, not a tool-verified metric.

### 5. Development environment setup (reproducible from this document alone)

```bash
git clone https://github.com/Victorious93/rootforge-os.git   # NOT the origin-source-labs URL in BUILD.md — see §2 above
cd rootforge-os
python3 -m unittest discover -s tests -p 'test_*.py'   # 129 tests, no extra setup needed (stdlib only)
bash tests/run-tests.sh                                  # 420 checks, no extra setup needed (self-contained sandboxing)
```
No virtualenv, no `pip install`, no Node/npm setup is required for CLI/test development — confirmed by this session's own successful runs using only what was already in this container (`python3`, `bash`, coreutils). `shellcheck` is the one real, recurring local-dev gap (`apt-get install shellcheck` on Debian/Ubuntu; not installable in this specific session's container per prior sessions' notes, reason not independently diagnosed).

**Exit criteria met:** every build/test command above is either verified this session (marked ✅) or explicitly marked as not run, with the reason stated — nothing is presented as verified that wasn't actually run. The historical 420-vs-421 test-count question raised in Phase 1 is now investigated and resolved to the extent possible without access to the prior session's actual runtime environment. Phase 4 can proceed.

---

## DEVELOPMENT WORKFLOW

`[Populated during Phase 4. Not yet run.]`

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

`[Populated during Phase 5. Not yet run.]`

---

## RECOMMENDED DEVELOPMENT PRIORITY

`[Populated during Phase 5. Not yet run.]`

---

## QUICK REFERENCE

`[Populated during Phase 5. Not yet run.]`

---

*End of CLAUDE.md*
