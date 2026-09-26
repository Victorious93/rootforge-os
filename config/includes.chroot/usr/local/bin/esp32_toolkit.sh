#!/usr/bin/env bash
# RootForge OS — ESP32 toolkit
# Victorious Framework
#
# esptool.py + PlatformIO for ESP32 firmware work. Specifically pulls in
# library support for CC1101 (sub-GHz) and PN532 (NFC) — the exact
# peripheral pair on the Origin Claw PCB concept — rather than generic
# ESP32 boilerplate. Also drops a minimal tool-node firmware scaffold,
# which happens to be a reasonable starting point if the ESP32 tool-server
# half of PyraClaw resumes later — this doesn't assume that work is active,
# it just avoids starting from nothing if it is.
#
# Usage: ./esp32_toolkit.sh install | new-project <name> | flash <firmware.bin> [port]

set -euo pipefail

CMD="${1:-install}"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$ROOTFORGE_HOME/esp32-projects" "$LOG_DIR"
LOG_FILE="$LOG_DIR/esp32_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[esp32] $*" | tee -a "$LOG_FILE"; }

case "$CMD" in
  install)
    log "Installing esptool.py and PlatformIO"
    command -v pipx >/dev/null 2>&1 || sudo apt-get install -y pipx | tee -a "$LOG_FILE"
    pipx install esptool 2>&1 | tee -a "$LOG_FILE"
    pipx install platformio 2>&1 | tee -a "$LOG_FILE"

    log "Adding dialout group for USB-serial access to ESP32 boards"
    sudo usermod -aG dialout "${SUDO_USER:-$USER}" || true

    log "Installed. New tool-node scaffold: $0 new-project <name>"
    ;;

  new-project)
    NAME="${2:?Usage: esp32_toolkit.sh new-project <name>}"
    # NAME is used as one directory name under esp32-projects/. Unvalidated,
    # `new-project ../../evil` scaffolds outside the tree entirely — the same
    # traversal the device backup/restore paths had.
    case "$NAME" in
      ""|*/*|*..*)
        echo "Invalid project name '$NAME' — must not be empty or contain '/' or '..'." >&2
        echo "It is used as a directory name under \$ROOTFORGE_HOME/esp32-projects/." >&2
        exit 1 ;;
    esac
    PROJ_DIR="$ROOTFORGE_HOME/esp32-projects/$NAME"
    [[ -d "$PROJ_DIR" ]] && { echo "Project already exists: $PROJ_DIR" >&2; exit 1; }
    mkdir -p "$PROJ_DIR/src"

    cat > "$PROJ_DIR/platformio.ini" <<'EOF'
; RootForge OS — ESP32 tool-node scaffold
; Victorious Framework
[env:esp32-s3]
platform = espressif32
board = esp32-s3-devkitc-1
framework = arduino
monitor_speed = 115200
lib_deps =
    ; CC1101 (sub-GHz) — matches Origin Claw's radio module
    LSatan/SmartRC-CC1101-Driver-Lib
    ; PN532 (NFC) — matches Origin Claw's NFC module
    adafruit/Adafruit-PN532
EOF

    cat > "$PROJ_DIR/src/main.cpp" <<'EOF'
// RootForge OS — ESP32 tool-node scaffold
// Victorious Framework
//
// Bare skeleton: a serial-command loop is the common shape for a
// dispatcher-driven tool node (receive a command over UART/WiFi, run it,
// report back). Fill in actual CC1101/PN532 handling as needed.

#include <Arduino.h>

void setup() {
  Serial.begin(115200);
  Serial.println("RootForge ESP32 tool-node scaffold — Victorious Framework");
}

void loop() {
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    // Dispatch cmd here.
    Serial.print("ack: ");
    Serial.println(cmd);
  }
}
EOF
    log "Scaffolded at $PROJ_DIR (PlatformIO project, esp32-s3-devkitc-1 target)"
    log "Build: cd $PROJ_DIR && pio run"
    log "Flash: cd $PROJ_DIR && pio run -t upload"
    ;;

  flash)
    FW="${2:?Usage: esp32_toolkit.sh flash <firmware.bin> [port] [offset]}"
    PORT="${3:-}"
    # 0x0 was wrong for the thing this script tells you to build.
    #
    # `pio run` produces firmware.bin, an APPLICATION image, and the app
    # partition starts at 0x10000 in the default partition table. 0x0 on an
    # ESP32-S3 — the target in this script's own scaffold — is where the
    # second-stage bootloader lives. Writing the app there overwrites the
    # bootloader, and the board stops booting until it is fully erased and
    # reflashed. 0x0 is correct only for a combined image from
    # `esptool merge_bin`, which is not what `pio run` emits.
    OFFSET="${4:-0x10000}"
    [[ -f "$FW" ]] || { echo "Firmware not found: $FW" >&2; exit 1; }
    case "$OFFSET" in
      0x[0-9a-fA-F]*|[0-9]*) ;;
      *) echo "Offset must be numeric (e.g. 0x10000): $OFFSET" >&2; exit 1 ;;
    esac
    if [[ -z "$PORT" ]]; then
      # `ls | head -1` hands ls a SIGPIPE, which pipefail turns into a failed
      # assignment; the glob does the same job without a pipe.
      PORTS=(/dev/ttyUSB* /dev/ttyACM*)
      for candidate in "${PORTS[@]}"; do
        [[ -e "$candidate" ]] && { PORT="$candidate"; break; }
      done
      [[ -z "$PORT" ]] && { echo "No serial port found — specify one explicitly." >&2; exit 1; }
      log "Auto-detected port: $PORT"
    fi
    if [[ "$OFFSET" == "0x0" || "$OFFSET" == "0" ]]; then
      log "NOTE: flashing at $OFFSET overwrites the bootloader. That is correct only"
      log "      for a combined image (esptool merge_bin), not for pio's firmware.bin."
    fi
    log "Flashing $FW to $PORT at $OFFSET"
    esptool.py --port "$PORT" write_flash "$OFFSET" "$FW" 2>&1 | tee -a "$LOG_FILE"
    ;;

  *)
    echo "Usage: $0 install | new-project <name> | flash <firmware.bin> [port]" >&2
    exit 1
    ;;
esac

# Victorious Framework
