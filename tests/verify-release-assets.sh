#!/usr/bin/env bash
# RootForge OS — release asset verifier
# Victorious Framework | Origin Source Labs
#
# Run by .github/workflows/release.yml on the assembled asset directory before
# anything is attached to a release, and by the test suite against fixtures.
# It checks that what is about to be published is complete and internally
# consistent; it does not boot the ISO or install a rootfs.
#
# Usage: tests/verify-release-assets.sh <dir> [--tag vX.Y.Z] [--min-iso-bytes N]
#
# Checks, each fatal:
#   - every expected file exists and is non-empty: the ISO and its .sha256, the
#     four Termux rootfs tarballs and their .sha256, rootforge-proot-plugin.sh,
#     install.sh, rootforge-chroot.sh, release-metadata.json, SHA256SUMS
#   - SHA256SUMS and every *.sha256 sidecar verify against the files (sha256sum -c)
#   - the ISO is at least --min-iso-bytes (default 256 MiB) and carries the
#     ISO 9660 signature "CD001" at byte 32769
#   - each tarball is a readable .tar.xz whose /etc/rootforge/build-info agrees
#     with its filename about flavor and architecture
#   - release-metadata.json is valid JSON, names --tag when given, and lists
#     digests equal to the real files'
#   - nothing else is present (a stray log would be published with the release)
#   - no unfilled placeholder (@TOKEN@, REPLACE_WITH) survives in a generated file
#
# Exit 0 only if all hold.

set -uo pipefail

DIR=""; TAG=""; MIN_ISO=$((256 * 1024 * 1024))
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) [[ $# -ge 2 ]] || { echo "--tag needs a value" >&2; exit 2; }; TAG="$2"; shift 2 ;;
    --min-iso-bytes) [[ $# -ge 2 ]] || { echo "--min-iso-bytes needs a value" >&2; exit 2; }; MIN_ISO="$2"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "Unknown option: $1" >&2; exit 2 ;;
    *) [[ -z "$DIR" ]] || { echo "Unexpected argument: $1" >&2; exit 2; }; DIR="$1"; shift ;;
  esac
done
[[ -n "$DIR" && -d "$DIR" ]] || { echo "usage: $0 <dir> [--tag vX.Y.Z] [--min-iso-bytes N]" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 2; }
[[ "$MIN_ISO" =~ ^[0-9]+$ ]] || { echo "--min-iso-bytes must be a number" >&2; exit 2; }

FAIL=0
bad() { printf '  [FAIL] %s\n' "$*" >&2; FAIL=1; }

ISO="rootforge-os-amd64.hybrid.iso"
TARBALLS=(rootforge-proot-arm64.tar.xz rootforge-proot-amd64.tar.xz rootforge-chroot-arm64.tar.xz rootforge-chroot-amd64.tar.xz)
GENERATED=(rootforge-proot-plugin.sh install.sh rootforge-chroot.sh release-metadata.json)

expected=("$ISO" "$ISO.sha256")
for t in "${TARBALLS[@]}"; do expected+=("$t" "$t.sha256"); done
expected+=("${GENERATED[@]}" SHA256SUMS)

for f in "${expected[@]}"; do
  if [[ ! -e "$DIR/$f" ]]; then bad "missing: $f"
  elif [[ ! -s "$DIR/$f" ]]; then bad "empty: $f"
  fi
done
# Nothing else may ride along: a stray log or half-built file in the directory
# would be published with the release.
while IFS= read -r present; do
  known=0
  for f in "${expected[@]}"; do [[ "$present" == "$f" ]] && known=1; done
  [[ $known -eq 1 ]] || bad "unexpected file: $present"
done < <(cd "$DIR" && find . -mindepth 1 -maxdepth 1 -printf '%f\n' | sort)
# Without every file the remaining checks would only repeat the same failure.
[[ $FAIL -eq 0 ]] || { echo "==> release assets INCOMPLETE" >&2; exit 1; }

# --- digests -----------------------------------------------------------------------
( cd "$DIR" && sha256sum -c --quiet SHA256SUMS >/dev/null 2>&1 ) || bad "SHA256SUMS does not verify against the files in $DIR"
for f in "$ISO" "${TARBALLS[@]}"; do
  ( cd "$DIR" && sha256sum -c --quiet "$f.sha256" >/dev/null 2>&1 ) || bad "$f.sha256 does not verify"
done
# The aggregate list must also cover the ISO, or it vouches for only part of the release.
for f in "$ISO" "${TARBALLS[@]}" "${GENERATED[@]}"; do
  grep -qE "^[0-9a-f]{64}  $f\$" "$DIR/SHA256SUMS" || bad "SHA256SUMS has no entry for $f"
done

# --- ISO ----------------------------------------------------------------------------
size="$(stat -c %s "$DIR/$ISO")"
[[ "$size" -ge "$MIN_ISO" ]] || bad "$ISO is $size bytes, below the $MIN_ISO-byte minimum for a real image"
sig="$(dd if="$DIR/$ISO" bs=1 skip=32769 count=5 2>/dev/null | tr -d '\0')"
[[ "$sig" == "CD001" ]] || bad "$ISO has no ISO 9660 signature (found '${sig:-nothing}' at byte 32769)"

# --- tarballs -----------------------------------------------------------------------
for t in "${TARBALLS[@]}"; do
  flavor="${t#rootforge-}"; flavor="${flavor%%-*}"
  arch="${t#rootforge-"$flavor"-}"; arch="${arch%.tar.xz}"
  if ! tar -tJf "$DIR/$t" >/dev/null 2>&1; then bad "$t is not a readable .tar.xz"; continue; fi
  info="$(tar -xJOf "$DIR/$t" ./etc/rootforge/build-info 2>/dev/null || tar -xJOf "$DIR/$t" etc/rootforge/build-info 2>/dev/null || true)"
  [[ -n "$info" ]] || { bad "$t has no /etc/rootforge/build-info"; continue; }
  [[ "$(sed -n 's/^flavor=//p' <<<"$info" | head -n 1)" == "$flavor" ]] || bad "$t build-info flavor is not '$flavor'"
  [[ "$(sed -n 's/^arch=//p' <<<"$info" | head -n 1)" == "$arch" ]] || bad "$t build-info arch is not '$arch'"
done

# --- metadata, placeholders ---------------------------------------------------------
if jq -e . "$DIR/release-metadata.json" >/dev/null 2>&1; then
  if [[ -n "$TAG" ]]; then
    [[ "$(jq -r .tag "$DIR/release-metadata.json")" == "$TAG" ]] || bad "release-metadata.json tag is not $TAG"
  fi
  for t in "${TARBALLS[@]}"; do
    want="$(sha256sum "$DIR/$t" | awk '{print $1}')"
    got="$(jq -r --arg n "$t" '.artifacts[] | select(.name == $n) | .sha256' "$DIR/release-metadata.json")"
    [[ "$got" == "$want" ]] || bad "release-metadata.json digest for $t does not match the file"
  done
else
  bad "release-metadata.json is not valid JSON"
fi
for g in rootforge-proot-plugin.sh install.sh; do
  if grep -nE '@[A-Z0-9_]+@|REPLACE_WITH' "$DIR/$g" >/dev/null 2>&1; then
    bad "$g still contains an unfilled placeholder"
  fi
done
if [[ -n "$TAG" ]] && ! grep -q "$TAG" "$DIR/install.sh"; then bad "install.sh does not mention tag $TAG"; fi

if [[ $FAIL -eq 0 ]]; then
  echo "==> release assets OK (${#expected[@]} files)"
  exit 0
fi
echo "==> release assets FAILED verification" >&2
exit 1
