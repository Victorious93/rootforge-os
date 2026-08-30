# Hacking on RootForge OS
**Victorious Framework | Origin Source Labs**

## Repo layout

```
rootforge-os/
├── auto/config          lb config invocation — edit to change distro/arch/desktop
├── auto/build           build wrapper — run with sudo
├── Makefile             convenience targets (build / checksum / list-usb / flash / clean / distclean)
├── BUILD.md             host prerequisites and build instructions
├── .github/workflows/release.yml   builds + publishes the ISO and Termux rootfs on a tagged push
├── termux/              non-root Termux/PRoot variant — see README section 17
│   ├── build-rootfs.sh          debootstrap-based rootfs builder (reuses config/hooks/*)
│   ├── package-lists/           pruned, PRoot-safe package list
│   ├── proot-distro-plugins/    the plugin Termux users install
│   ├── bootstrap_proot.sh       SDK/NDK fetch, replaces 00_bootstrap_distro.sh here
│   ├── proot-setup.sh           image-bake-time motd/workspace setup
│   └── install.sh               one-command Termux installer
├── tests/               hermetic test suite — no device, Docker, or network
│   ├── run-tests.sh             the runner (see "Running the tests" below)
│   ├── stubs/                   fake adb/fastboot that record their arguments
│   └── test_*.py                Python unit tests
└── config/
    ├── package-lists/   apt packages installed into the squashfs
    │   ├── rootforge.list.chroot          core toolchain + GNOME
    │   ├── rootforge-installer.list.chroot  Calamares + GRUB
    │   ├── rootforge-ai.list.chroot       Node.js/npm hook dep
    │   └── rootforge-flagship.list.chroot  opt-in tools (hardening, VPN, etc.)
    ├── hooks/           shell scripts run inside the chroot at build time
    │   ├── 0005-*       enable rootforge-live-groups.service (kvm/plugdev/docker
    │   │                for the live-session user — see that unit's own header
    │   │                for why this isn't a plain build-time usermod)
    │   ├── 0010-*       NodeSource LTS repo + Node.js
    │   ├── 0020-*       Ollama binary + service
    │   ├── 0030-*       Claude Code CLI
    │   ├── 0040-*       rpi-imager .deb
    │   ├── 0045-*       rsvg compat shim (rsvg-convert, for lb_binary_syslinux)
    │   ├── 0050-*       starship + eza
    │   ├── 006x-*       magiskboot, repo, payload-dumper-go
    │   ├── 007x-*       workspace skel
    │   ├── 008x-*       GNOME defaults, avbtool
    │   ├── 009x-*       Plymouth, Zygisk headers, ccache
    │   └── (numbered ascending — gaps left for future insertion)
    │
    │   IMPORTANT: this must be a FLAT directory. live-build's hook discovery
    │   (Find_files config/hooks/*.chroot, in lb_chroot_hooks) is a
    │   non-recursive glob — a hook nested one level deeper (as these used to
    │   be, under hooks/live/ and hooks/normal/) is invisible to it and
    │   silently never runs, in any build, ever. There is no live-build
    │   convention for a "normal" (post-install/Calamares-chroot) hook stage
    │   either; that distinction belongs to Calamares's own module sequence
    │   (settings.conf's exec: list) instead — see shellprocess.conf for the
    │   pattern this repo uses for it.
    ├── includes.chroot/ files overlaid onto the squashfs verbatim
    │   ├── etc/calamares/   installer config + branding
    │   ├── etc/skel/        default files for every new user, incl.
    │   │                    second-brain/ (PARA-method notes vault — the
    │   │                    brain CLI's actual code lives under
    │   │                    usr/local/lib/rootforge/second-brain/, wrapped
    │   │                    by usr/local/bin/brain, same convention as
    │   │                    avbtool/mkbootimg's wrapper scripts)
    │   ├── etc/udev/        Android USB rules
    │   ├── etc/systemd/     first-boot service
    │   ├── usr/local/bin/   all 29 automation scripts (incl. `rootforge`,
    │   │                    the thin wrapper for usr/local/lib/rootforge/core/,
    │   │                    and `brain`, the second-brain CLI wrapper)
    │   ├── usr/local/lib/rootforge/core/  rootforge CLI's Python package —
    │   │   `doctor` and `devices` today; see
    │   │   docs/IMPLEMENTATION_PLAN.md for what lands here next
    │   ├── usr/local/lib/rootforge/sh/common.sh  shared shell helpers
    │   │   (confirmation gate, checksums, device enumeration) sourced by
    │   │   the destructive scripts in usr/local/bin/ — see "Shared shell
    │   │   helpers" below
    │   └── usr/local/share/rootforge/  zygisk-api/ (added by hooks)
    ├── archives/rootforge-security.list   correct bookworm-security apt
    │   line — live-build's own built-in security handling hardcodes a
    │   pre-bullseye suite path that 404s; see auto/config's header
    └── bootloaders/isolinux/   live ISO's own boot theme (BIOS-only —
        see auto/config's header for why syslinux/isolinux, not grub2).
        Copied from live-build's bundled default except isolinux.bin and
        vesamenu.c32, which were stale symlinks to a pre-repackaging
        syslinux layout; these now point at the real current paths
        (isolinux package's /usr/lib/ISOLINUX/isolinux.bin and
        syslinux-common's /usr/lib/syslinux/modules/bios/vesamenu.c32).
        Also adds bootlogo (an empty cpio archive) — lb_binary_syslinux's
        "hack around the removal of support in gfxboot" step
        unconditionally does `cpio -i < bootlogo`, but only ever CREATES
        that file itself for LB_MODE=ubuntu (via gfxboot-theme-ubuntu);
        for our LB_MODE=debian it's otherwise never created at all, a gap
        in live-build itself, not something a --mode flag can route
        around. An empty archive extracts cleanly (0 entries) and the
        step repacks a real one afterward from the theme's own files.
```

## Running the tests

```
tests/run-tests.sh          # everything
tests/run-tests.sh shell    # shell-script behavior only
tests/run-tests.sh python   # Python unit tests only
```

No device, Docker, or network access needed: `tests/stubs/` puts fake `adb` and
`fastboot` binaries first on `PATH` that print canned output and record every
invocation, so a test can assert on the exact command a script *would* have run
against real hardware. `HOME` is redirected per test, so nothing touches your real
`~/rootforge`. See `tests/README.md`.

The same suite runs in CI (the `tests` job in `.github/workflows/lint.yml`).

## Adding a script

1. Write it to `config/includes.chroot/usr/local/bin/your_script.sh`
2. `chmod 0755` it
3. Add it to the script count in `BUILD.md`
4. Sign it: `# Victorious Framework | Origin Source Labs` in the header comment
5. Validate arguments before inspecting state, so a bad flag is reported as a bad
   flag rather than as whatever unrelated precondition is checked first. Give
   every option loop a catch-all `*)` arm — a silently-ignored typo'd flag means
   the script runs with defaults and says nothing.
6. If it does anything destructive (writes a partition, wipes data), gate it with
   `rf_confirm` from `usr/local/lib/rootforge/sh/common.sh` rather than a bare
   `read -r -p`. `rf_confirm` prompts on `/dev/tty`, so the gate stays visible when
   `fleet_orchestrate.sh` runs the script with stdout redirected to a per-device log
   — a plain `read` prompt disappears into that log and the run looks hung.
7. Add a test to `tests/run-tests.sh` for its argument handling and, if it has one,
   both sides of its confirmation gate. Pair every exit-code assertion with a
   check on the specific message: a script that exits 1 for an unrelated reason
   would otherwise make the test pass for the wrong reason.
8. Never write a secret into a file with `echo "export VAR='$value'"`. Use
   `rf_shell_quote` and `rf_write_private` — see "Shared shell helpers".

## Shared shell helpers

`config/includes.chroot/usr/local/lib/rootforge/sh/common.sh` is sourced by the
scripts in `usr/local/bin/` via a path relative to `${BASH_SOURCE[0]}` — the repo
checkout and the installed ISO have the same `usr/local/{bin,lib}` arrangement, so
one relative path works in both. It holds only the pieces that were being
reimplemented inconsistently across scripts:

| Helper | Why it's shared |
|---|---|
| `rf_confirm` | The typed-confirmation gate, prompting on `/dev/tty` |
| `rf_sha256_file` / `rf_sha256_verify` | Backup/restore integrity |
| `rf_adb_serials` / `rf_fastboot_serials` | One correct answer to "what's connected" |
| `rf_require_cmd` | A useful message instead of "command not found" under `set -e` |
| `rf_shell_quote` | Escaping a secret before it is written into a file the shell sources |
| `rf_write_private` | Writing a secrets file that is 0600 from the moment it exists |

Keep it small. A helper belongs here when a second script needs it, not before.

## Adding a package

Add it to the correct `.list.chroot` file:
- Core dev tool → `rootforge.list.chroot`
- Installer dependency → `rootforge-installer.list.chroot`
- Flagship / opt-in feature → `rootforge-flagship.list.chroot`
- Not in Debian repos → write a numbered hook in `config/hooks/`

## Adding a chroot hook

Hooks live directly in `config/hooks/` (flat — see the layout note above for
why), are numbered `NNNN-description.hook.chroot`, and run in numeric order.
- `0001–0099`: system setup (groups, repos, core binaries)
- `0100+`: reserved for future feature hooks
- Always set `set -e` at the top
- Make the hook non-fatal for optional features: `|| { echo "WARNING: ..."; exit 0; }`
- Must be executable (`chmod +x`)
- If it fetches a specific file path from a third-party repo (not just a
  pinned release tag), verify that path is actually correct against a real
  release/tag before assuming it — this audit found two hooks quietly
  fetching from paths that had moved or never existed (`0085-avbtool`,
  `0095-zygisk-headers`), silently degrading instead of failing loudly

## Calamares module configs

`config/includes.chroot/etc/calamares/` holds only our overrides.
`calamares-settings-debian` (installed as a package) provides the defaults.
Only add a module config here if you need to deviate from the Debian defaults.

## Desktop / first-boot split

**In squashfs (build-time):** stable binaries under ~100 MB — Node.js, Ollama, 
Claude Code, magiskboot, repo, payload-dumper-go, eza, starship, avbtool.

**First boot (`rootforge-firstboot.service`):** large/version-churny downloads —
Android SDK, NDK, emulator system images (~2–5 GB total). Requires network.

**User-initiated only:** `setup_ai_tools.sh` (API keys), all ten flagship scripts
(hardening, VPN, proxy, etc.) — see the service unit comment for why.

## Commit style

```
Short title: what changed (imperative, ≤72 chars)

- bullet explaining the why for non-obvious decisions
- another bullet if needed

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```
