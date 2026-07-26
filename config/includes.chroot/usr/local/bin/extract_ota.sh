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

INPUT="${1:?Usage: extract_ota.sh <ota.zip|payload.bin> [output_dir] [--partitions a,b,c]}"
OUTPUT_DIR="${2:-./ota_extracted_$(date +%Y%m%d_%H%M%S)}"
PARTITIONS="boot,init_boot,vendor_boot,dtbo,vbmeta"

shift 2 || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --partitions) PARTITIONS="$2"; shift ;;
  esac
  shift
done

[[ -f "$INPUT" ]] || { echo "Input not found: $INPUT" >&2; exit 1; }

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
BIN_DIR="$ROOTFORGE_HOME/bin"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$BIN_DIR" "$LOG_DIR" "$OUTPUT_DIR"
LOG_FILE="$LOG_DIR/extract_ota_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[extract-ota] $*" | tee -a "$LOG_FILE"; }

ensure_payload_dumper() {
  local bin="$BIN_DIR/payload-dumper-go"
  if [[ -x "$bin" ]]; then
    echo "$bin"
    return
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
  local tmp="/tmp/payload-dumper-go.tar.gz"
  curl -sSL -o "$tmp" "$url"
  mkdir -p /tmp/pdg_extract
  tar -xzf "$tmp" -C /tmp/pdg_extract 2>/dev/null || cp "$tmp" "$bin"
  found="$(find /tmp/pdg_extract -type f -name 'payload-dumper-go*' | head -1 || true)"
  [[ -n "$found" ]] && cp "$found" "$bin"
  chmod +x "$bin"
  rm -rf /tmp/pdg_extract "$tmp"
  echo "$bin"
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

DUMPER="$(ensure_payload_dumper)"

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
