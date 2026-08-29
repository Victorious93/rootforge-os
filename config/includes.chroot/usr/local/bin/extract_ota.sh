#!/usr/bin/env bash
# RootForge OS — OTA / firmware extraction
# Victorious Framework
#
# Pulls boot/init_boot/vendor_boot/dtbo/vbmeta straight out of an official
# OTA zip or raw payload.bin, so you can get a real stock boot.img to patch
# without needing a physical device connected first. Wraps payload-dumper-go,
# self-installing it from GitHub releases on first run since Debian doesn't
# package it.
#
# Usage: ./extract_ota.sh <ota.zip|payload.bin> [output_dir] [--partitions boot,init_boot,vendor_boot,dtbo,vbmeta]

set -euo pipefail

usage() {
  echo "Usage: extract_ota.sh <ota.zip|payload.bin> [output_dir] [--partitions a,b,c]" >&2
  exit "${1:-1}"
}

# The output directory is optional, so it used to be read as "${2:-default}"
# and skipped with `shift 2 || true`. That misreads the common form
# `extract_ota.sh ota.zip --partitions boot`: $2 is the flag, so the
# extraction landed in a directory literally named "--partitions" and the
# partition list silently stayed at its default. Only treat $2 as the output
# directory when it isn't an option.
[[ $# -ge 1 ]] || usage
case "$1" in
  -h|--help) usage 0 ;;
esac

INPUT="$1"; shift
OUTPUT_DIR=""
PARTITIONS="boot,init_boot,vendor_boot,dtbo,vbmeta"

if [[ $# -gt 0 && "$1" != -* ]]; then
  OUTPUT_DIR="$1"
  shift
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --partitions)
      [[ $# -ge 2 ]] || { echo "--partitions needs a comma-separated list" >&2; usage; }
      PARTITIONS="$2"
      shift 2
      ;;
    -h|--help) usage 0 ;;
    *) echo "Unexpected argument: $1" >&2; usage ;;
  esac
done

[[ -n "$PARTITIONS" ]] || { echo "--partitions was given an empty list" >&2; exit 1; }
OUTPUT_DIR="${OUTPUT_DIR:-./ota_extracted_$(date +%Y%m%d_%H%M%S)}"

[[ -f "$INPUT" ]] || { echo "Input not found: $INPUT" >&2; exit 1; }

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
BIN_DIR="$ROOTFORGE_HOME/bin"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$BIN_DIR" "$LOG_DIR" "$OUTPUT_DIR"
LOG_FILE="$LOG_DIR/extract_ota_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[extract-ota] $*" | tee -a "$LOG_FILE"; }

# Sets DUMPER rather than echoing the path. It previously returned the path
# on stdout while also calling log(), which writes to stdout — so on the
# download path `DUMPER="$(ensure_payload_dumper)"` captured the log lines
# *and* the path into one string and the following invocation failed with a
# nonsense command name. The same capture swallowed the "could not resolve a
# release asset" guidance, leaving a bare exit 1 with no explanation.
DUMPER=""
ensure_payload_dumper() {
  local bin="$BIN_DIR/payload-dumper-go"
  if [[ -x "$bin" ]]; then
    DUMPER="$bin"
    return 0
  fi
  log "payload-dumper-go not found — fetching latest release binary"
  command -v jq >/dev/null 2>&1 || { echo "jq is required (apt install jq)" >&2; exit 1; }
  local api="https://api.github.com/repos/ssut/payload-dumper-go/releases/latest"
  local json url
  json="$(curl -sSL "$api")"
  url="$(echo "$json" | jq -r '.assets[] | select(.name | test("linux.*amd64")) | .browser_download_url' | head -1)"
  if [[ -z "$url" || "$url" == "null" ]]; then
    log "Could not resolve a release asset automatically. Install payload-dumper-go"
    log "manually (https://github.com/ssut/payload-dumper-go) and place the binary at $bin"
    exit 1
  fi

  # Fixed /tmp paths are a collision (and, on a shared host, a symlink)
  # hazard — a second run, or another user, races the same names.
  local workdir
  workdir="$(mktemp -d)"
  local tmp="$workdir/payload-dumper-go.tar.gz"
  local extract="$workdir/extract"
  mkdir -p "$extract"

  curl -sSL -o "$tmp" "$url"
  local found=""
  if tar -xzf "$tmp" -C "$extract" 2>/dev/null; then
    found="$(find "$extract" -type f -name 'payload-dumper-go*' -print -quit)"
  fi
  if [[ -n "$found" ]]; then
    cp "$found" "$bin"
  else
    # Some releases publish the bare binary rather than a tarball.
    log "Release asset was not a tarball containing payload-dumper-go — treating it as the binary itself"
    cp "$tmp" "$bin"
  fi
  chmod +x "$bin"
  rm -rf "$workdir"

  "$bin" --help >/dev/null 2>&1 || log "Warning: $bin did not respond to --help; the downloaded asset may not be a usable binary."
  DUMPER="$bin"
}

PAYLOAD_BIN="$INPUT"
CLEANUP_PAYLOAD=0

if [[ "$INPUT" == *.zip ]]; then
  log "Input is a zip — extracting payload.bin"
  TMP_EXTRACT="$(mktemp -d)"
  unzip -o -q "$INPUT" payload.bin -d "$TMP_EXTRACT" 2>>"$LOG_FILE" || {
    log "No payload.bin at zip root — this OTA format may be pre-A/B (full image zip)."
    log "Check the zip contents manually: unzip -l '$INPUT'"
    exit 1
  }
  PAYLOAD_BIN="$TMP_EXTRACT/payload.bin"
  CLEANUP_PAYLOAD=1
fi

ensure_payload_dumper

log "Dumping partitions [$PARTITIONS] from $PAYLOAD_BIN -> $OUTPUT_DIR"
"$DUMPER" -o "$OUTPUT_DIR" -p "$PARTITIONS" "$PAYLOAD_BIN" 2>>"$LOG_FILE" \
  || { log "payload-dumper-go failed — see $LOG_FILE. Some OTAs use full (non-incremental) payloads only decodable with -c 0 or need a matching source build for delta payloads."; exit 1; }

[[ $CLEANUP_PAYLOAD -eq 1 ]] && rm -rf "$(dirname "$PAYLOAD_BIN")"

log "Extraction complete. Contents:"
ls -la "$OUTPUT_DIR" | tee -a "$LOG_FILE"
log ""
log "Sparse .img files here are typically already raw from payload-dumper-go, but if"
log "fastboot rejects one as a sparse image, convert first: simg2img in.img out.img"
log "To inspect an extracted image read-only, use: inspect_partition_image.sh <img>"

# Victorious Framework
