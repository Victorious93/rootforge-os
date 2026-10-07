> **SUPERSEDED IN PART — 2026-10-07.** A later review found problems this one did not,
> and some statements below were wrong for the tree it reviewed:
> the device profiler "aligned with the tested contract" read `fastboot getvar` from stdout
> only (real fastboot writes to stderr) and treated `secure` as lock state; `ota inspect`
> was wired to the partition-image mount script; `00_bootstrap_distro.sh` could provision
> root's home; the six download hooks' SHA-256 pins had been disabled by a merge. The test
> counts below (439/161) are historical; at the head of the 2026-10-07 branch the harness
> reports 929 checks including 351 Python tests. See `CHANGELOG.md` and `docs/ARCHITECTURE.md`.

---

# RootForge OS project review — 2026-10-04

## Assessment

The project has a coherent core: a Debian live-build distribution for Android development, with shell tools for device/image workflows and a Python CLI that joins those tools. The live-build configuration, Termux/PRoot path, scripts, tests, and documentation are substantial and have a real implementation behind them.

The weak point was integration quality. Several Python command modules and the top-level CLI had drifted apart: tests exercised parser/dispatch APIs the modules did not provide, the CLI imported or routed to stale interfaces, and device profiling did not match the tested contract. The README also described commands and paths that were no longer present. The latest GitHub lint run at the reviewed main revision had failed; in particular, its Python/test jobs lacked the product's PyYAML runtime dependency.

## What was fixed

- Reconnected the CLI to command-owned parser and dispatch functions for module, boot, OTA, and AVD; retained device, doctor, config, flash, and backup entry points.
- Implemented and aligned device profiling, module-ID validation, OTA argument validation, AVD validation, and boot-image command dispatch with the existing tests and scripts.
- Made doctor checks report individual failures without aborting the whole report.
- Closed shell-script input validation and build-matrix preflight gaps; made the test harness's privileged-command shim safe in the restricted test environment.
- Installed the project's declared PyYAML dependency in CI jobs that execute Python tests or the Python CLI.
- Corrected README command examples, the NDK Dockerfile path, build repository URL, and project status description.

## Verification

- `bash tests/run-tests.sh`: **439 passed, 0 failed** (includes 161 Python unit tests).
- `bash tests/lint.sh`: **clean** (ShellCheck, hook safety, test-harness checks, Python byte-compilation).
- `git diff --check`: clean.
- `auto/build`: attempted, stopped immediately with `No free loop devices found`. The container has no `/dev/loop*` devices and cannot supply the loop-device prerequisite. Therefore no ISO was produced and the live-build pipeline has not been verified end-to-end.

## What makes sense

- Keep Debian live-build as the host OS delivery path and keep the scripts independently usable; the unified CLI should remain a small, tested front end to those scripts.
- Keep the host ISO and Termux/PRoot rootfs as distinct deployment paths with explicit capability and validation statements.
- Preserve hermetic tests and fast CI checks, then validate release artifacts on a real Debian/Ubuntu build host with loop devices.

## What does not make sense yet / remaining work

- Treating the ISO/release flow as proven: this review could not build an ISO. A successful clean-host build and boot/install smoke test remain release gates.
- Treating README/CLAUDE.md as automatically current: several stale command, path, status, and priority statements had accumulated. They need to track the code and CI state.
- Treating every aspirational README feature as implemented or tested. Hardware flashing, emulator execution, OTA extraction against real payloads, and installer behavior need hardware/image or VM validation beyond this hermetic suite.
- Product scope is broad (desktop distro, Android build environment, module workflow, device tooling, emulator, OTA, and Termux/PRoot). Keep the current release scope explicit and defer unsupported platforms/features rather than implying equivalent Windows or native Android-app support.

## Suggested next steps

1. Run `make build` on a supported host with free loop devices; retain the build log and checksum.
2. Boot the ISO in a VM, test Calamares install and first-boot provisioning, and verify the resulting system manifest.
3. Run a real-device smoke test for ADB/fastboot profiling and a safe, reversible image workflow.
4. Keep CI dependency setup and command documentation in sync as interfaces evolve.
