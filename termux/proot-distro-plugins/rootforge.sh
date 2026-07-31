# shellcheck shell=bash
# RootForge OS proot-distro plugin — Victorious Framework | Origin Source Labs
#
# No shebang — proot-distro sources this file, it never executes it directly.
#
# [Likely] this matches proot-distro's current plugin API (DISTRO_NAME/
# DISTRO_COMMENT strings, TARBALL_URL/TARBALL_SHA256 associative arrays
# keyed by `uname -m`-style arch names, an optional distro_setup() hook
# with $DISTRO_PATH available) — check against a current plugin shipped in
# proot-distro's own repo if `proot-distro install` rejects this file, the
# API has been stable but isn't something this repo can pin a version of.
#
# Install into Termux with:
#   pkg install proot-distro
#   mkdir -p $PREFIX/etc/proot-distro
#   curl -o $PREFIX/etc/proot-distro/rootforge.sh \
#     https://raw.githubusercontent.com/Victorious93/rootforge-os/main/termux/proot-distro-plugins/rootforge.sh
#   proot-distro install rootforge
#   proot-distro login rootforge
#
# TARBALL_URL / TARBALL_SHA256 below are placeholders. Build the real
# tarball with termux/build-rootfs.sh, publish it (and its .sha256) to a
# GitHub Release, and fill these in before this plugin is usable — there is
# no rootfs hosted by this repo automatically, this is the definition a
# maintainer wires up after a release.

DISTRO_NAME="rootforge"
DISTRO_COMMENT="RootForge OS — Android root module dev toolchain, non-root/PRoot (Victorious Framework)"

TARBALL_URL['aarch64']="https://github.com/Victorious93/rootforge-os/releases/latest/download/rootforge-proot-arm64.tar.xz"
TARBALL_SHA256['aarch64']="REPLACE_WITH_SHA256_FROM_BUILD_ROOTFS_SH_OUTPUT"

# x86_64 only matters for x86 Android hardware (rare) or a desktop-Linux
# PRoot sandbox used for testing this plugin without a phone. Real Android
# hardware almost always wants aarch64 above.
TARBALL_URL['x86_64']="https://github.com/Victorious93/rootforge-os/releases/latest/download/rootforge-proot-amd64.tar.xz"
TARBALL_SHA256['x86_64']="REPLACE_WITH_SHA256_FROM_BUILD_ROOTFS_SH_OUTPUT"

distro_setup() {
	# Real DNS for the container — Termux's own resolv.conf isn't visible
	# inside the PRoot mount namespace by default.
	mkdir -p "${DISTRO_PATH}/etc"
	{
		echo "nameserver 1.1.1.1"
		echo "nameserver 8.8.8.8"
	} > "${DISTRO_PATH}/etc/resolv.conf"
}
