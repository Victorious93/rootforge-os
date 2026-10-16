#!/usr/bin/env bash
# RootForge OS — release metadata generator for the Termux/PRoot artifacts
# Victorious Framework | Origin Source Labs
#
# Turns the tarballs a release build produced into the install metadata that
# ships with them, binding tag, CPU architecture, flavor, URL and SHA-256
# together so nothing in an installer is typed by hand or left as a placeholder:
#
#   rootforge-proot-plugin.sh   proot-distro plugin with the real digests
#   install.sh                  installer pinned to this tag and verifying the plugin
#   rootforge-chroot.sh         the rooted launcher, copied so its digest is published
#   release-metadata.json       machine-readable binding of the above
#   SHA256SUMS                  sha256sum-format digests of every file listed here
#
# Usage:
#   termux/make-release-metadata.sh --tag v1.2.3 --dist <dir> --out <dir>
#       [--repo owner/name] [--base-url <url>] [--arches arm64,amd64] [--flavors proot,chroot]
#
# <dist> holds rootforge-<flavor>-<arch>.tar.xz and a matching .tar.xz.sha256
# (build-rootfs.sh writes the digest alone; "digest  name" is also accepted).
# It fails — writing nothing usable — if an expected tarball is missing or
# empty, its recorded digest does not match the file, it is not a readable
# archive, its own /etc/rootforge/build-info disagrees with its filename about
# flavor or architecture, or any placeholder survives in the output.
#
# For a locally built rootfs, point --base-url at wherever you will host the
# files (e.g. a LAN web server) and restrict --arches/--flavors to what you built.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATES="$SCRIPT_DIR/templates"

die() { echo "make-release-metadata: ERROR: $*" >&2; exit 1; }
log() { echo "make-release-metadata: $*"; }

TAG=""; DIST=""; OUT=""
REPO="Victorious93/rootforge-os"
BASE_URL=""
ARCHES="arm64,amd64"
FLAVORS="proot,chroot"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag|--dist|--out|--repo|--base-url|--arches|--flavors)
      [[ $# -ge 2 ]] || die "$1 needs a value"
      case "$1" in
        --tag) TAG="$2" ;; --dist) DIST="$2" ;; --out) OUT="$2" ;; --repo) REPO="$2" ;;
        --base-url) BASE_URL="$2" ;; --arches) ARCHES="$2" ;; --flavors) FLAVORS="$2" ;;
      esac
      shift 2 ;;
    -h|--help) sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

command -v jq >/dev/null 2>&1 || die "jq is required (apt install jq)"
[[ -n "$TAG" && -n "$DIST" && -n "$OUT" ]] || die "--tag, --dist and --out are required"
[[ -d "$DIST" ]] || die "--dist is not a directory: $DIST"
if [[ -z "$BASE_URL" ]]; then
  [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] \
    || die "tag '$TAG' is not a release tag like v1.2.3 (use --base-url for a local build)"
  [[ "$REPO" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || die "--repo must look like owner/name"
  BASE_URL="https://github.com/$REPO/releases/download/$TAG"
else
  [[ "$TAG" =~ ^[A-Za-z0-9._-]+$ ]] || die "tag '$TAG' may contain only letters, digits, '.', '_' and '-'"
  [[ "$BASE_URL" =~ ^https?://[A-Za-z0-9._~:/@%+-]+$ ]] || die "--base-url must be an http(s) URL made of letters, digits and . _ ~ : / @ % + -"
  BASE_URL="${BASE_URL%/}"
fi

IFS=',' read -r -a ARCH_LIST <<<"$ARCHES"
IFS=',' read -r -a FLAVOR_LIST <<<"$FLAVORS"
for a in "${ARCH_LIST[@]}"; do [[ "$a" == arm64 || "$a" == amd64 ]] || die "unknown arch '$a' (arm64, amd64)"; done
for f in "${FLAVOR_LIST[@]}"; do [[ "$f" == proot || "$f" == chroot ]] || die "unknown flavor '$f' (proot, chroot)"; done

declare -A DIGEST
ARTIFACTS_JSON='[]'

# --- verify each expected tarball ---------------------------------------------
for flavor in "${FLAVOR_LIST[@]}"; do
  for arch in "${ARCH_LIST[@]}"; do
    name="rootforge-$flavor-$arch.tar.xz"
    file="$DIST/$name"
    sumfile="$file.sha256"
    [[ -f "$file" ]] || die "missing artifact: $name (expected in $DIST)"
    [[ -s "$file" ]] || die "artifact is empty: $name"
    [[ -f "$sumfile" ]] || die "missing digest file: $name.sha256"
    recorded="$(awk 'NF { print $1; exit }' "$sumfile")"
    [[ "$recorded" =~ ^[0-9a-f]{64}$ ]] || die "$name.sha256 does not contain a SHA-256 digest"
    actual="$(sha256sum "$file" | awk '{print $1}')"
    [[ "$recorded" == "$actual" ]] || die "$name does not match its recorded digest
       recorded: $recorded
       actual:   $actual"
    tar -tJf "$file" >/dev/null 2>&1 || die "$name is not a readable .tar.xz archive"

    info="$(tar -xJOf "$file" ./etc/rootforge/build-info 2>/dev/null || tar -xJOf "$file" etc/rootforge/build-info 2>/dev/null || true)"
    [[ -n "$info" ]] || die "$name has no /etc/rootforge/build-info, so its flavor and architecture cannot be confirmed"
    got_flavor="$(sed -n 's/^flavor=//p' <<<"$info" | head -n 1)"
    got_arch="$(sed -n 's/^arch=//p' <<<"$info" | head -n 1)"
    got_x11="$(sed -n 's/^x11=//p' <<<"$info" | head -n 1)"
    [[ "$got_flavor" == "$flavor" ]] || die "$name claims flavor '$flavor' but its build-info says '${got_flavor:-<none>}'"
    [[ "$got_arch" == "$arch" ]] || die "$name claims arch '$arch' but its build-info says '${got_arch:-<none>}'"

    DIGEST["$flavor-$arch"]="$actual"
    size="$(stat -c %s "$file")"
    ARTIFACTS_JSON="$(jq -c --arg n "$name" --arg f "$flavor" --arg a "$arch" --arg u "$BASE_URL/$name" \
      --arg h "$actual" --argjson s "$size" --arg x "${got_x11:-0}" \
      '. + [{name:$n, flavor:$f, arch:$a, url:$u, sha256:$h, size_bytes:$s, desktop_layer:($x == "1")}]' <<<"$ARTIFACTS_JSON")"
    log "verified $name ($actual)"
  done
done

mkdir -p "$OUT"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

fill() {  # fill <template> <out> — substitute tokens, drop lines for arches not built
  local template="$1" out="$2" arch_filter
  arch_filter=""
  [[ " ${ARCH_LIST[*]} " == *" arm64 "* ]] || arch_filter+="/# arch:aarch64\$/d;"
  [[ " ${ARCH_LIST[*]} " == *" amd64 "* ]] || arch_filter+="/# arch:x86_64\$/d;"
  sed -e "${arch_filter}s/  # arch:[a-z0-9_]*\$//" "$template" \
    | sed -e "s|@TAG@|$TAG|g" \
          -e "s|@BASE_URL@|$BASE_URL|g" \
          -e "s|@SHA256_AARCH64@|${DIGEST[proot-arm64]:-}|g" \
          -e "s|@SHA256_X86_64@|${DIGEST[proot-amd64]:-}|g" \
          -e "s|@CHROOT_SHA256_ARM64@|${DIGEST[chroot-arm64]:-}|g" \
          -e "s|@CHROOT_SHA256_AMD64@|${DIGEST[chroot-amd64]:-}|g" \
          -e "s|@PLUGIN_SHA256@|${PLUGIN_SHA:-}|g" \
          -e "s|@LAUNCHER_SHA256@|${LAUNCHER_SHA:-}|g" \
    > "$out"
}

[[ -n "${DIGEST[proot-arm64]:-}${DIGEST[proot-amd64]:-}" ]] || die "no proot artifacts were selected, so there is no plugin to generate"
fill "$TEMPLATES/proot-plugin.sh.in" "$STAGE/rootforge-proot-plugin.sh"
PLUGIN_SHA="$(sha256sum "$STAGE/rootforge-proot-plugin.sh" | awk '{print $1}')"
install -m 0755 "$SCRIPT_DIR/rootforge-chroot.sh" "$STAGE/rootforge-chroot.sh"
LAUNCHER_SHA="$(sha256sum "$STAGE/rootforge-chroot.sh" | awk '{print $1}')"
fill "$TEMPLATES/install.sh.in" "$STAGE/install.sh"
chmod 0755 "$STAGE/install.sh"

# --- refuse anything that still looks like a placeholder ---------------------------
for generated in rootforge-proot-plugin.sh install.sh; do
  if grep -nE '@[A-Z0-9_]+@|REPLACE_WITH' "$STAGE/$generated" >/dev/null; then
    die "$generated still contains an unfilled placeholder:
$(grep -nE '@[A-Z0-9_]+@|REPLACE_WITH' "$STAGE/$generated" | head -5)"
  fi
  bash -n "$STAGE/$generated" || die "$generated is not valid shell after substitution"
done

jq -n --arg tag "$TAG" --arg base "$BASE_URL" --argjson artifacts "$ARTIFACTS_JSON" \
      --arg plugin "$PLUGIN_SHA" --arg launcher "$LAUNCHER_SHA" \
  '{tag:$tag, base_url:$base, artifacts:$artifacts,
    generated:{"rootforge-proot-plugin.sh":$plugin, "rootforge-chroot.sh":$launcher}}' > "$STAGE/release-metadata.json"

# SHA256SUMS over every file this release's Termux path publishes. The
# tarballs live in <dist>; the generated files are in <out>.
{
  for flavor in "${FLAVOR_LIST[@]}"; do
    for arch in "${ARCH_LIST[@]}"; do
      echo "${DIGEST[$flavor-$arch]}  rootforge-$flavor-$arch.tar.xz"
    done
  done
  for generated in rootforge-proot-plugin.sh install.sh rootforge-chroot.sh release-metadata.json; do
    echo "$(sha256sum "$STAGE/$generated" | awk '{print $1}')  $generated"
  done
} > "$STAGE/SHA256SUMS"

for generated in rootforge-proot-plugin.sh install.sh rootforge-chroot.sh release-metadata.json SHA256SUMS; do
  install -m "$([[ $generated == *.json || $generated == SHA256SUMS ]] && echo 0644 || echo 0755)" "$STAGE/$generated" "$OUT/$generated"
done
log "wrote install metadata for $TAG to $OUT"
