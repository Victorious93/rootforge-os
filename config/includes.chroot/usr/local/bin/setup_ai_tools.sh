#!/usr/bin/env bash
# RootForge OS — AI/LLM tooling setup
# Victorious Framework
#
# First-run configuration for Claude Code, Grok (xAI), Ollama, and Hermes.
# Baked-in binaries (Node/npm, Claude Code CLI, Ollama) are assumed already
# installed by the ISO build — this script handles the parts that are
# personal (API keys) or optional (GPU tuning, model pulls) and therefore
# don't belong in an unattended build step.
#
# Usage: ./setup_ai_tools.sh [--non-interactive --anthropic-key KEY --xai-key KEY]

set -euo pipefail

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
KEY_FILE="$HOME/.rootforge/ai-keys.env"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR" "$HOME/.rootforge"
LOG_FILE="$LOG_DIR/ai_tools_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[ai-tools] $*" | tee -a "$LOG_FILE"; }

NONINTERACTIVE=0
ANTHROPIC_KEY_ARG=""
XAI_KEY_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --non-interactive) NONINTERACTIVE=1 ;;
    --anthropic-key) ANTHROPIC_KEY_ARG="$2"; shift ;;
    --xai-key) XAI_KEY_ARG="$2"; shift ;;
  esac
  shift
done

# ---- 1. API key configuration -----------------------------------------
log "Configuring API keys at $KEY_FILE (mode 600, not world-readable)"

if [[ -f "$KEY_FILE" ]]; then
  log "Existing key file found — will only overwrite keys you explicitly provide/enter."
  # shellcheck disable=SC1090
  source "$KEY_FILE"
fi

if [[ -n "$ANTHROPIC_KEY_ARG" ]]; then
  ANTHROPIC_API_KEY="$ANTHROPIC_KEY_ARG"
elif [[ $NONINTERACTIVE -eq 0 ]]; then
  read -r -s -p "Anthropic API key for Claude Code (blank to skip/keep existing): " INPUT_KEY
  echo ""
  [[ -n "$INPUT_KEY" ]] && ANTHROPIC_API_KEY="$INPUT_KEY"
fi

if [[ -n "$XAI_KEY_ARG" ]]; then
  XAI_API_KEY="$XAI_KEY_ARG"
elif [[ $NONINTERACTIVE -eq 0 ]]; then
  read -r -s -p "xAI API key for Grok (blank to skip/keep existing): " INPUT_KEY
  echo ""
  [[ -n "$INPUT_KEY" ]] && XAI_API_KEY="$INPUT_KEY"
fi

{
  echo "# RootForge OS — AI tool API keys"
  echo "# Victorious Framework — generated $(date), chmod 600, not committed anywhere"
  [[ -n "${ANTHROPIC_API_KEY:-}" ]] && echo "export ANTHROPIC_API_KEY='$ANTHROPIC_API_KEY'"
  [[ -n "${XAI_API_KEY:-}" ]] && echo "export XAI_API_KEY='$XAI_API_KEY'"
} > "$KEY_FILE"
chmod 600 "$KEY_FILE"

for RC in "$HOME/.bashrc" "$HOME/.zshrc"; do
  [[ -f "$RC" ]] || continue
  grep -q "rootforge/ai-keys.env" "$RC" 2>/dev/null || {
    echo "[ -f \$HOME/.rootforge/ai-keys.env ] && source \$HOME/.rootforge/ai-keys.env" >> "$RC"
    log "Added key-file source line to $RC"
  }
done

# ---- 2. Ollama service check -------------------------------------------
log "Checking Ollama service"
if command -v ollama >/dev/null 2>&1; then
  if systemctl is-active --quiet ollama 2>/dev/null; then
    log "Ollama service running"
  else
    log "Ollama installed but service not active — attempting to start"
    sudo systemctl enable --now ollama 2>>"$LOG_FILE" || log "Could not start the ollama service — start it manually: sudo systemctl start ollama"
  fi
else
  log "ollama binary not found — expected it baked into the ISO. Install manually:"
  log "  curl -fsSL https://ollama.com/install.sh | sh"
fi

# ---- 3. AMD GPU / ROCm detection for Ollama acceleration ----------------
log "Checking for AMD GPU (ROCm acceleration)"
GPU_INFO="$(lspci 2>/dev/null | grep -i 'VGA\|3D' | grep -i 'AMD\|ATI' || true)"

if [[ -n "$GPU_INFO" ]]; then
  log "AMD GPU detected: $GPU_INFO"
  GFX_OVERRIDE=""
  if echo "$GPU_INFO" | grep -qiE '6800|6900|6700 XT|6750'; then
    GFX_OVERRIDE="10.3.0"   # RDNA2 desktop (gfx1030 family)
    log "Recognized as RDNA2 desktop class (gfx1030 family)"
  fi

  if [[ -n "$GFX_OVERRIDE" ]]; then
    ENV_LINE="export HSA_OVERRIDE_GFX_VERSION=$GFX_OVERRIDE"
    if ! grep -q "HSA_OVERRIDE_GFX_VERSION" "$KEY_FILE" 2>/dev/null; then
      echo "$ENV_LINE" >> "$KEY_FILE"
      log "Set HSA_OVERRIDE_GFX_VERSION=$GFX_OVERRIDE in $KEY_FILE"
    fi
    ROCM_VER="$(dpkg -l 2>/dev/null | grep -oP 'rocm-core\s+\K[0-9.]+' | head -1 || echo unknown)"
    log "ROCm package version at time of this configuration: $ROCM_VER"
    log "[Likely] a newer ROCm release isn't automatically a more stable one for this"
    log "  card — if things get flaky after a system update, this logged version is"
    log "  what to diff against, not a guess."
  else
    log "AMD GPU present but not a recognized RDNA2-desktop model — not setting an"
    log "  override automatically. Check https://rocm.docs.amd.com for whether your"
    log "  card needs HSA_OVERRIDE_GFX_VERSION and set it manually in $KEY_FILE if so."
  fi
else
  log "No AMD GPU detected via lspci — skipping ROCm tuning (NVIDIA/CPU-only path unaffected)."
fi

# ---- 4. Claude Code CLI check ------------------------------------------
log "Checking Claude Code CLI"
if command -v claude >/dev/null 2>&1; then
  log "claude CLI found: $(claude --version 2>/dev/null || echo 'version check failed')"
else
  log "claude CLI not found — expected it baked into the ISO via npm. Install manually:"
  log "  npm install -g @anthropic-ai/claude-code"
fi

# ---- 5. Grok API reachability check ------------------------------------
if [[ -n "${XAI_API_KEY:-}" ]]; then
  log "Testing Grok (xAI) API key"
  HTTP_CODE="$(curl -sS -o /dev/null -w '%{http_code}' https://api.x.ai/v1/models \
    -H "Authorization: Bearer $XAI_API_KEY" 2>>"$LOG_FILE" || echo "000")"
  if [[ "$HTTP_CODE" == "200" ]]; then
    log "Grok API key verified (HTTP 200)"
  else
    log "Grok API check returned HTTP $HTTP_CODE — key may be invalid, or api.x.ai is unreachable from here."
  fi
fi

# ---- 6. Optional Hermes pull (explicit opt-in, not automatic) ----------
if [[ $NONINTERACTIVE -eq 0 ]] && command -v ollama >/dev/null 2>&1; then
  echo ""
  read -r -p "Pull the Hermes 3 model via Ollama now? This is several GB. [y/N]: " PULL_HERMES
  if [[ "$PULL_HERMES" =~ ^[Yy]$ ]]; then
    log "Pulling hermes3 via Ollama"
    ollama pull hermes3 2>&1 | tee -a "$LOG_FILE"
  else
    log "Skipped. Pull later with: ollama pull hermes3"
  fi
fi

echo ""
log "AI tooling setup complete. Open a new shell (or 'source ~/.bashrc') to pick up"
log "the API keys and any GPU override just written to $KEY_FILE."

# Victorious Framework
