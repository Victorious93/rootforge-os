#!/usr/bin/env bash
# RootForge OS — upgraded terminal stack
# Victorious Framework
#
# tmux with a RootForge-specific session layout, starship prompt with a
# custom segment showing connected-device count, and a handful of modern
# CLI replacements (eza, bat, fzf, zoxide) wired into the shell rc.
#
# Usage: ./setup_terminal.sh

set -euo pipefail

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/terminal_setup_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[terminal] $*" | tee -a "$LOG_FILE"; }

log "Installing terminal tooling"
sudo apt-get update -y | tee -a "$LOG_FILE"
sudo apt-get install -y --no-install-recommends tmux fzf bat eza zoxide | tee -a "$LOG_FILE" || true
# Debian's bat/eza package names occasionally differ by release; fall back
# to the batcat/exa names some Debian versions still ship under.
command -v bat >/dev/null 2>&1 || command -v batcat >/dev/null 2>&1 || log "bat/batcat unavailable in this repo — skipping, not fatal."

log "Installing starship prompt"
# `curl -sS <url> | sh` has two holes: without -f curl exits 0 on an HTTP
# error and pipes the error *body* into sh, which tries to run it; and the
# `command -v ... ||` guard binds to the curl alone, so the pipeline ran even
# when starship was already installed. Fetch to a file, check it is
# non-empty, then run it.
if ! command -v starship >/dev/null 2>&1; then
  STARSHIP_INSTALLER="$(mktemp)"
  if curl -fsSL -o "$STARSHIP_INSTALLER" https://starship.rs/install.sh && [[ -s "$STARSHIP_INSTALLER" ]]; then
    sh "$STARSHIP_INSTALLER" -y 2>&1 | tee -a "$LOG_FILE"
  else
    log "Could not download the starship installer — skipping (not fatal; the prompt just won't be configured)."
  fi
  rm -f "$STARSHIP_INSTALLER"
fi

# ---- tmux: RootForge session layout ------------------------------------
TMUX_CONF="$HOME/.tmux.conf"
if ! grep -q "RootForge" "$TMUX_CONF" 2>/dev/null; then
  cat >> "$TMUX_CONF" <<'EOF'

# RootForge OS — tmux config
# Victorious Framework
set -g mouse on
set -g history-limit 10000
set -g status-style bg=black,fg=green
bind r source-file ~/.tmux.conf \; display "Reloaded"
EOF
  log "Appended RootForge block to $TMUX_CONF"
fi

TMUX_LAYOUT="$HOME/.local/bin/rootforge-session"
mkdir -p "$(dirname "$TMUX_LAYOUT")"
cat > "$TMUX_LAYOUT" <<'EOF'
#!/usr/bin/env bash
# RootForge OS — standard tmux workspace: logcat / fastboot watch / build shell
# Victorious Framework
SESSION="rootforge"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux attach -t "$SESSION"
  exit 0
fi
tmux new-session -d -s "$SESSION" -n main
tmux split-window -h -t "$SESSION:main"
tmux split-window -v -t "$SESSION:main.1"
tmux send-keys -t "$SESSION:main.0" "adb logcat" C-m
tmux send-keys -t "$SESSION:main.1" "watch -n2 'fastboot devices; echo; adb devices'" C-m
tmux select-pane -t "$SESSION:main.2"
tmux attach -t "$SESSION"
EOF
chmod +x "$TMUX_LAYOUT"
log "Session launcher at $TMUX_LAYOUT — run 'rootforge-session' (ensure ~/.local/bin is on PATH)"

# ---- starship: device-status segment -----------------------------------
STARSHIP_CONFIG="$HOME/.config/starship.toml"
mkdir -p "$(dirname "$STARSHIP_CONFIG")"
if ! grep -q "rootforge_devices" "$STARSHIP_CONFIG" 2>/dev/null; then
  cat >> "$STARSHIP_CONFIG" <<'EOF'

# RootForge OS — device-status segment
# Victorious Framework
[custom.rootforge_devices]
command = "n=$(adb devices 2>/dev/null | grep -c device$); [ \"$n\" -gt 0 ] && echo \"📱$n\""
when = true
shell = ["bash", "-c"]
format = "[$output]($style) "
style = "bold cyan"
EOF
  log "Appended device-status segment to $STARSHIP_CONFIG"
fi

# `${RC##*.}` on "$HOME/.bashrc" strips everything up to the last dot and
# yields "bashrc", not "bash" — so this wrote `eval "$(starship init
# bashrc)"` into the rc file. Both starship and zoxide reject that ("invalid
# value 'bashrc'"), and because it lands in the rc itself the error printed
# on every new shell from then on, with no prompt configured. Derive the
# shell name explicitly instead of pattern-matching the filename.
for RC in "$HOME/.bashrc" "$HOME/.zshrc"; do
  [[ -f "$RC" ]] || continue
  case "$RC" in
    *.bashrc) SHELL_NAME="bash" ;;
    *.zshrc)  SHELL_NAME="zsh" ;;
    *) log "Skipping $RC — no known shell name for it"; continue ;;
  esac

  if command -v starship >/dev/null 2>&1; then
    grep -q 'starship init' "$RC" 2>/dev/null || {
      echo "eval \"\$(starship init $SHELL_NAME)\"" >> "$RC" && \
        log "Wired starship into $RC ($SHELL_NAME)"
    }
  else
    log "starship not on PATH — not adding its init line to $RC (it would error on every new shell)"
  fi

  if command -v zoxide >/dev/null 2>&1; then
    grep -q 'zoxide init' "$RC" 2>/dev/null || {
      echo "eval \"\$(zoxide init $SHELL_NAME)\"" >> "$RC" && \
        log "Wired zoxide into $RC ($SHELL_NAME)"
    }
  else
    log "zoxide not on PATH — not adding its init line to $RC"
  fi
done

log "Terminal stack configured. Open a new shell to pick up starship/zoxide,"
log "  then run 'rootforge-session' for the standard tmux layout."

# Victorious Framework
