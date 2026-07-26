# Hacking on RootForge OS
**Victorious Framework | Origin Source Labs**

## Repo layout

```
rootforge-os/
├── auto/config          lb config invocation — edit to change distro/arch/desktop
├── auto/build           build wrapper — run with sudo
├── Makefile             convenience targets (build / clean / distclean / flash)
├── BUILD.md             host prerequisites and build instructions
└── config/
    ├── package-lists/   apt packages installed into the squashfs
    │   ├── rootforge.list.chroot          core toolchain + GNOME
    │   ├── rootforge-installer.list.chroot  Calamares + GRUB
    │   ├── rootforge-ai.list.chroot       Node.js/npm hook dep
    │   └── rootforge-flagship.list.chroot  opt-in tools (hardening, VPN, etc.)
    ├── hooks/live/      shell scripts run inside the chroot at build time
    │   ├── 0005-*       live user groups (kvm, plugdev, docker)
    │   ├── 0010-*       NodeSource LTS repo + Node.js
    │   ├── 0020-*       Ollama binary + service
    │   ├── 0030-*       Claude Code CLI
    │   ├── 0040-*       rpi-imager .deb
    │   ├── 0050-*       starship + eza
    │   ├── 006x-*       magiskboot, repo, payload-dumper-go
    │   ├── 007x-*       workspace skel
    │   ├── 008x-*       GNOME defaults, avbtool
    │   ├── 009x-*       Plymouth, Zygisk headers, branding, ccache
    │   └── (numbered ascending — gaps left for future insertion)
    ├── hooks/normal/    hooks run in the installed chroot by Calamares
    ├── includes.chroot/ files overlaid onto the squashfs verbatim
    │   ├── etc/calamares/   installer config + branding
    │   ├── etc/skel/        default files for every new user
    │   ├── etc/udev/        Android USB rules
    │   ├── etc/systemd/     first-boot service
    │   ├── usr/local/bin/   all 27 automation scripts
    │   └── usr/local/share/rootforge/  docker/, zygisk-api/ (added by hooks)
    └── bootloaders/grub-pc/grub.cfg   live ISO boot menu
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
- Not in Debian repos → write a numbered hook in `config/hooks/live/`

## Adding a chroot hook

Hooks are numbered `NNNN-description.hook.chroot` and run in numeric order.
- `0001–0099`: system setup (groups, repos, core binaries)
- `0100+`: reserved for future feature hooks
- Always set `set -e` at the top
- Make the hook non-fatal for optional features: `|| { echo "WARNING: ..."; exit 0; }`
- Must be executable (`chmod +x`)

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
