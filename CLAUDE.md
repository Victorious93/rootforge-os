# CLAUDE.md — RootForge-OS Development Guide & Progress Tracker

> **This file is the single source of truth for Claude Code sessions working on RootForge-OS.**
> Read the **PROJECT STATE** section first, every session, before doing anything else.
> Update the **PROJECT STATE** section at the end of every session before finishing.

---

## 🔖 PROJECT STATE (READ THIS FIRST)

**Last Updated:** `2026-09-11`
**Last Session Summary:** `Phase 0 completed. Confirmed repo access, branch/remote state, and local toolchain. No blockers found other than two missing optional tools (see Open Questions / Blockers). Repository already contains substantial, tested implementation (CLI, tests, docs) predating this CLAUDE.md — Phase 1 inspection has NOT been done yet and must not be assumed from this summary.`

### Current Phase

`[x] PHASE 0 COMPLETE — READY FOR PHASE 1`

| Phase | Status | Completed Date | Notes |
|-------|--------|-----------------|-------|
| Phase 0 — Setup & Access | ✅ Complete | 2026-09-11 | See "Phase 0 Findings" below |
| Phase 1 — Repository Inspection | ⬜ Not Started | — | — |
| Phase 2 — Core Architecture Documentation | ⬜ Not Started | — | — |
| Phase 3 — Build System, Testing & Tooling | ⬜ Not Started | — | — |
| Phase 4 — Workflow & Architecture Rules | ⬜ Not Started | — | — |
| Phase 5 — Final Audit & Next Steps | ⬜ Not Started | — | — |
| Phase 6 — Active Development (ongoing) | ⬜ Not Started | — | — |
| Phase 7 — Pull Request / Build & Release | ⬜ Not Started | — | — |

**Status Legend:** ⬜ Not Started · 🟨 In Progress · ✅ Complete · 🔁 Needs Revisit

### What To Do Next

`Begin Phase 1 — Repository Inspection & Assessment. The repository is not empty or scaffolded: it already contains a working Python CLI (rootforge), a live-build-based Debian ISO pipeline, Termux/PRoot integration scripts, and a 129-test Python unit suite plus a 421-check hermetic shell test suite, all passing as of this session. Phase 1 must inventory this real, existing implementation (not greenfield-plan it) and produce the IMPLEMENTED / PARTIALLY IMPLEMENTED / SCAFFOLDED / PLANNED / MISSING breakdown called for in the Phase 1 deliverable. Start by reading docs/ARCHITECTURE_AUDIT.md and docs/IMPLEMENTATION_PLAN.md, which appear to already contain prior architecture/planning notes — verify their claims against actual code rather than taking them at face value.`

### Open Questions / Blockers

- `shellcheck` is not installed in this environment, so `tests/lint.sh` / `make lint` cannot be run locally here (CI installs it via apt in `.github/workflows/lint.yml`). Not a blocker for inspection/documentation work, but blocks locally verifying lint-clean status before a push — note this in Phase 3 tooling docs.
- `pytest` is not installed, but is not required: `tests/test_*.py` use Python's stdlib `unittest` and are run via `python3 -m unittest discover` / through `tests/run-tests.sh`, not pytest. Confirmed working.
- Android-specific tooling (`adb`, `fastboot`, `aapt`, `repo`) and image-build tooling (`mksquashfs`, `mkbootimg`, `cpio`) are not installed in this container. The test suite stubs these (see `tests/stubs/`, `tests/README.md`) so the hermetic suite does not need them; they would be required for real on-device flashing/building work, which is out of scope unless a session is explicitly asked to do it.
- `docker` is present (29.3.1) but its role in this project, if any, has not yet been assessed — defer to Phase 1.

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
