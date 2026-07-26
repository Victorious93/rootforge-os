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
    FW="${2:?Usage: esp32_toolkit.sh flash <firmware.bin> [port]}"
    PORT="${3:-}"
    [[ -f "$FW" ]] || { echo "Firmware not found: $FW" >&2; exit 1; }
    if [[ -z "$PORT" ]]; then
      PORT="$(ls /dev/ttyUSB* /dev/ttyACM* 2>/dev/null | head -1 || true)"
      [[ -z "$PORT" ]] && { echo "No serial port found — specify one explicitly." >&2; exit 1; }
      log "Auto-detected port: $PORT"
    fi
    log "Flashing $FW to $PORT"
    esptool.py --port "$PORT" write_flash 0x0 "$FW" 2>&1 | tee -a "$LOG_FILE"
    ;;

  *)
    echo "Usage: $0 install | new-project <name> | flash <firmware.bin> [port]" >&2
    exit 1
    ;;
esac

# Victorious Framework
