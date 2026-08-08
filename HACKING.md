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
    │   ├── etc/skel/        default files for every new user
    │   ├── etc/udev/        Android USB rules
    │   ├── etc/systemd/     first-boot service
    │   ├── usr/local/bin/   all 27 automation scripts
    │   └── usr/local/share/rootforge/  docker/, zygisk-api/ (added by hooks)
    ├── archives/rootforge-security.list   correct bookworm-security apt
    │   line — live-build's own built-in security handling hardcodes a
    │   pre-bullseye suite path that 404s; see auto/config's header
    └── bootloaders/isolinux/   live ISO's own boot theme (BIOS-only —
        see auto/config's header for why syslinux/isolinux, not grub2).
        Copied from live-build's bundled default except isolinux.bin and
        vesamenu.c32, which were stale symlinks to a pre-repackaging
        syslinux layout; these now point at the real current paths
        (isolinux package's /usr/lib/ISOLINUX/isolinux.bin and
        syslinux-common's /usr/lib/syslinux/modules/bios/vesamenu.c32)
```

## Adding a script

1. Write it to `config/includes.chroot/usr/local/bin/your_script.sh`
2. `chmod 0755` it
3. Add it to the script count in `BUILD.md`
4. Sign it: `# Victorious Framework | Origin Source Labs` in the header comment

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
