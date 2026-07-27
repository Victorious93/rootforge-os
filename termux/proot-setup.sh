#!/bin/sh
# RootForge OS — PRoot-specific first-image setup
# Victorious Framework | Origin Source Labs
#
# Runs once inside the chroot during termux/build-rootfs.sh, after the
# shared ISO hooks and the RootForge script copy. Writes the Termux/PRoot
# variant's own motd (the desktop ISO's motd references GNOME/Calamares/
# sudo 00_bootstrap_distro.sh, none of which apply here) and creates the
# workspace layout for the container's default user.

set -e

echo "==> Writing Termux/PRoot motd"
cat > /etc/motd <<'EOF'

  ██████╗  ██████╗  ██████╗ ████████╗███████╗ ██████╗ ██████╗  ██████╗ ███████╗
  ██╔══██╗██╔═══██╗██╔═══██╗╚══██╔══╝██╔════╝██╔═══██╗██╔══██╗██╔════╝ ██╔════╝
  ██████╔╝██║   ██║██║   ██║   ██║   █████╗  ██║   ██║██████╔╝██║  ███╗█████╗
  ██╔══██╗██║   ██║██║   ██║   ██║   ██╔══╝  ██║   ██║██╔══██╗██║   ██║██╔══╝
  ██║  ██║╚██████╔╝╚██████╔╝   ██║   ██║     ╚██████╔╝██║  ██║╚██████╔╝███████╗
  ╚═╝  ╚═╝ ╚═════╝  ╚═════╝   ╚═╝   ╚═╝      ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚══════╝
  Victorious Framework | Origin Source Labs — Termux/PRoot (non-root)

  Quick start:
    bootstrap_proot.sh              provision Android SDK/NDK (needs network)
    new_module_scaffold.sh          scaffold a new Magisk/KernelSU/Xposed module
    setup_rooted_avd.sh             create an Android emulator (unaccelerated
                                     without /dev/kvm — see caveats below)
    setup_terminal.sh               configure starship prompt + tmux layout
    setup_ai_tools.sh add <provider> add an AI API key (Claude, OpenAI, Grok, ...)

  Works here: module dev/build/lint, magiskboot/avbtool/mkbootimg patch
  flows, ADB (adb connect over Wi-Fi, or termux-usb for direct USB), OTA/
  partition extraction, LSPosed module scaffolding, Claude Code / Ollama
  (as plain processes — no systemd in a PRoot container).

  NOT available without root on the Android host:
    setup_rooted_avd.sh   — no /dev/kvm; the emulator falls back to
                             unaccelerated software rendering (usable for a
                             smoke test, not real work)
    harden_kernel.sh / harden_system.sh, setup_vpn.sh, join_headscale.sh
                          — need real kernel lockdown/security-module/
                             network-stack access this container doesn't
                             have; not included in this variant. Use
                             Termux's own VPN apps for mesh/VPN instead.
    GNOME desktop / Calamares — this rootfs isn't bootable, it's a PRoot
                             container inside Termux

  All scripts are in /usr/local/bin — tab-complete to explore.
  Full details: https://github.com/Victorious93/rootforge-os#termux

EOF

echo "==> Creating rootforge workspace skeleton in /etc/skel and /root"
for HOME_DIR in /etc/skel /root; do
  mkdir -p \
    "$HOME_DIR/rootforge/devices" \
    "$HOME_DIR/rootforge/modules/.cache" \
    "$HOME_DIR/rootforge/kernels" \
    "$HOME_DIR/rootforge/keys" \
    "$HOME_DIR/rootforge/avd-profiles" \
    "$HOME_DIR/rootforge/avd-work" \
    "$HOME_DIR/rootforge/bin" \
    "$HOME_DIR/rootforge/logs"
  chmod 700 "$HOME_DIR/rootforge/keys"
done

echo "==> PRoot-specific setup complete"
