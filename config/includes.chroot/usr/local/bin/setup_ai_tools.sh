#!/usr/bin/env bash
# RootForge OS — AI API key management + tooling setup
# Victorious Framework
#
# Manage API keys for any number of AI providers — Anthropic, OpenAI, xAI,
# Gemini, Mistral, Cohere, OpenRouter, DeepSeek, Groq, HuggingFace are known
# by name (right env var, live verification against the real endpoint); any
# other provider works too via --env-var, just without live verification.
# Keys live in ~/.rootforge/ai-keys.env (chmod 600, sourced from the shell
# rc, never exported globally in /etc/environment). `add`/`remove` only ever
# touch their own provider's lines, so keys accumulate across runs instead
# of the whole file getting clobbered — this also covers the local/no-key
# tooling (Ollama service, AMD GPU/ROCm tuning, Claude Code CLI, Hermes).
#
# Usage:
#   setup_ai_tools.sh add <provider> [--key KEY] [--env-var NAME] [--no-verify]
#   setup_ai_tools.sh remove <provider>
#   setup_ai_tools.sh list
#   setup_ai_tools.sh setup [--non-interactive --anthropic-key KEY --xai-key KEY]
#     (legacy full flow: Anthropic + xAI keys, Ollama service, AMD GPU/ROCm
#      tuning, Claude Code CLI check, optional Hermes pull. Also runs with
#      no subcommand at all, for backward compatibility.)
#
# Known providers: anthropic openai xai gemini mistral cohere openrouter
#                   deepseek groq huggingface

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
KEY_FILE="$HOME/.rootforge/ai-keys.env"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR" "$HOME/.rootforge"
LOG_FILE="$LOG_DIR/ai_tools_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[ai-tools] $*" | tee -a "$LOG_FILE"; }
die() { echo "[ai-tools] ERROR: $*" >&2; exit 1; }

declare -A PROVIDER_ENV=(
  [anthropic]=ANTHROPIC_API_KEY
  [openai]=OPENAI_API_KEY
  [xai]=XAI_API_KEY
  [gemini]=GEMINI_API_KEY
  [mistral]=MISTRAL_API_KEY
  [cohere]=COHERE_API_KEY
  [openrouter]=OPENROUTER_API_KEY
  [deepseek]=DEEPSEEK_API_KEY
  [groq]=GROQ_API_KEY
  [huggingface]=HF_TOKEN
)

usage() {
  sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

ensure_key_file() {
  mkdir -p "$(dirname "$KEY_FILE")"
  chmod 700 "$(dirname "$KEY_FILE")" 2>/dev/null || true
  if [[ ! -f "$KEY_FILE" ]]; then
    rf_write_private "$KEY_FILE" <<'HEADER'
# RootForge OS — AI tool API keys
# Victorious Framework — chmod 600, not committed anywhere
HEADER
  fi
  chmod 600 "$KEY_FILE"
}

ensure_shell_sourcing() {
  local rc
  for rc in "$HOME/.bashrc" "$HOME/.zshrc"; do
    [[ -f "$rc" ]] || continue
    grep -q "rootforge/ai-keys.env" "$rc" 2>/dev/null || {
      echo "[ -f \$HOME/.rootforge/ai-keys.env ] && source \$HOME/.rootforge/ai-keys.env" >> "$rc"
      log "Added key-file source line to $rc"
    }
  done
}

# Prints "ok" (HTTP 200), "skip" (no known check for this provider), or the
# raw HTTP status/000 on failure — callers branch on the returned string.
#
# The request is handed to curl as a config file on stdin (-K -) rather than
# as command-line flags. Process arguments are readable from
# /proc/<pid>/cmdline by other users on the box, so `curl -H "Authorization:
# Bearer $key"` published the key for the lifetime of the request; gemini's
# check was worse still, carrying it in the URL. Nothing about the request
# changes — only where curl reads it from.
verify_provider_key() {
  local provider="$1" key="$2" url="" code
  local -a headers=()

  case "$provider" in
    anthropic)
      url="https://api.anthropic.com/v1/models"
      headers=("x-api-key: $key" "anthropic-version: 2023-06-01") ;;
    openai)
      url="https://api.openai.com/v1/models"
      headers=("Authorization: Bearer $key") ;;
    xai)
      url="https://api.x.ai/v1/models"
      headers=("Authorization: Bearer $key") ;;
    mistral)
      url="https://api.mistral.ai/v1/models"
      headers=("Authorization: Bearer $key") ;;
    cohere)
      url="https://api.cohere.ai/v1/models"
      headers=("Authorization: Bearer $key") ;;
    openrouter)
      url="https://openrouter.ai/api/v1/models"
      headers=("Authorization: Bearer $key") ;;
    deepseek)
      url="https://api.deepseek.com/v1/models"
      headers=("Authorization: Bearer $key") ;;
    groq)
      url="https://api.groq.com/openai/v1/models"
      headers=("Authorization: Bearer $key") ;;
    huggingface)
      url="https://huggingface.co/api/whoami-v2"
      headers=("Authorization: Bearer $key") ;;
    gemini)
      # Gemini authenticates with a query parameter rather than a header.
      url="https://generativelanguage.googleapis.com/v1beta/models?key=${key}" ;;
    *)
      echo "skip"; return 0 ;;
  esac

  code="$(build_curl_config "$url" "${headers[@]+"${headers[@]}"}" \
    | curl -K - 2>>"$LOG_FILE" || echo 000)"

  [[ "$code" == "200" ]] && { echo "ok"; return 0; }
  echo "${code:-000}"
}

# Emit a curl config for one request. Values in a curl config file are
# double-quoted with backslash escapes, so escape both characters rather
# than assuming a key contains neither.
build_curl_config() {
  local url="$1"; shift
  local header
  printf 'silent\n'
  printf 'show-error\n'
  printf 'max-time = 20\n'
  printf 'output = "/dev/null"\n'
  printf 'write-out = "%%{http_code}"\n'
  printf 'url = "%s"\n' "$(escape_curl_value "$url")"
  for header in "$@"; do
    printf 'header = "%s"\n' "$(escape_curl_value "$header")"
  done
}

escape_curl_value() {
  local v="$1"
  v="${v//\\/\\\\}"
  v="${v//\"/\\\"}"
  printf '%s' "$v"
}

# Writes/replaces one provider's export + tracking-comment lines in
# $KEY_FILE, leaving every other provider's lines untouched.
store_provider_key() {
  local provider="$1" env_var="$2" key="$3"
  ensure_key_file

  # The key is escaped rather than dropped into '...' as-is: this file is
  # sourced by every interactive shell, so an unescaped quote in a key made
  # the whole file a syntax error (silently loading NO keys at all) or, with
  # a hostile key, ran shell commands at every startup. See rf_shell_quote.
  local quoted
  quoted="$(rf_shell_quote "$key")"

  # Build the new contents in memory, then write once through
  # rf_write_private — the previous temp-file dance created a 0644 file
  # holding every key, and `mv` handed that mode to the real file.
  local rest
  rest="$(grep -v -e "^export ${env_var}=" -e "^# provider:${provider}:" "$KEY_FILE" || true)"
  {
    printf '%s\n' "$rest"
    printf 'export %s=%s\n' "$env_var" "$quoted"
    printf '# provider:%s:%s\n' "$provider" "$env_var"
  } | rf_write_private "$KEY_FILE"

  ensure_shell_sourcing
}

cmd_add() {
  local provider="" key="" env_var="" no_verify=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      # Without the count check, a trailing bare `--key` reads an unset $2
      # and aborts with a raw "unbound variable" under `set -u`.
      --key)     [[ $# -ge 2 ]] || die "--key needs a value"; key="$2"; shift 2 ;;
      --env-var) [[ $# -ge 2 ]] || die "--env-var needs a value"; env_var="$2"; shift 2 ;;
      --no-verify) no_verify=1; shift ;;
      -*) die "Unknown option: $1" ;;
      *)
        [[ -z "$provider" ]] || die "Provider given twice: '$provider' and '$1'"
        provider="$1"; shift
        ;;
    esac
  done
  [[ -z "$provider" ]] && die "Usage: setup_ai_tools.sh add <provider> [--key KEY] [--env-var NAME] [--no-verify]"
  provider="$(echo "$provider" | tr '[:upper:]' '[:lower:]')"

  if [[ -z "$env_var" ]]; then
    env_var="${PROVIDER_ENV[$provider]:-}"
    if [[ -z "$env_var" ]]; then
      env_var="$(echo "${provider}_API_KEY" | tr '[:lower:]-' '[:upper:]_')"
      log "Unrecognized provider '$provider' — guessing env var $env_var (pass --env-var to override)."
    fi
  fi

  if [[ -z "$key" ]]; then
    read -r -s -p "API key for '$provider' (env: $env_var, blank to cancel): " key
    echo ""
  fi
  [[ -z "$key" ]] && die "No key provided — nothing stored."

  store_provider_key "$provider" "$env_var" "$key"

  if [[ $no_verify -eq 0 ]]; then
    log "Verifying $provider API key"
    local result
    result="$(verify_provider_key "$provider" "$key")"
    case "$result" in
      ok)   log "$provider key verified (HTTP 200)" ;;
      skip) log "No built-in verification for '$provider' — key stored, not checked live." ;;
      *)    log "$provider key check returned HTTP $result — key may be invalid, or the API is unreachable from here." ;;
    esac
  fi

  log "Stored $env_var in $KEY_FILE (mode 600). Open a new shell or 'source ~/.bashrc' to pick it up."
}

cmd_remove() {
  local provider="${1:-}"
  [[ -z "$provider" ]] && die "Usage: setup_ai_tools.sh remove <provider>"
  provider="$(echo "$provider" | tr '[:upper:]' '[:lower:]')"
  [[ -f "$KEY_FILE" ]] || die "No key file at $KEY_FILE — nothing configured yet."

  # `env_var="$(grep ... | cut ...)"` exits the whole script under
  # `set -o pipefail` when grep matches nothing, so `remove <unknown>` died
  # silently and the "No stored key found" message below could never print.
  local env_var
  env_var="$(grep -m1 "^# provider:${provider}:" "$KEY_FILE" | cut -d: -f3 || true)"
  [[ -z "$env_var" ]] && env_var="${PROVIDER_ENV[$provider]:-}"
  [[ -z "$env_var" ]] && die "No stored key found for provider '$provider'"

  local rest
  rest="$(grep -v -e "^export ${env_var}=" -e "^# provider:${provider}:" "$KEY_FILE" || true)"
  printf '%s\n' "$rest" | rf_write_private "$KEY_FILE"
  log "Removed $provider ($env_var) from $KEY_FILE"
}

cmd_list() {
  if [[ ! -f "$KEY_FILE" ]]; then
    echo "No AI API keys configured yet. Add one with: setup_ai_tools.sh add <provider>"
    return
  fi
  echo "Configured AI API keys ($KEY_FILE):"
  local seen="" line
  while IFS= read -r line; do
    [[ "$line" =~ ^#\ provider:([a-z0-9_-]+):([A-Za-z0-9_]+)$ ]] || continue
    printf '  %-14s env=%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
    seen="$seen ${BASH_REMATCH[2]}"
  done < "$KEY_FILE"
  while IFS= read -r line; do
    [[ "$line" =~ ^export\ ([A-Za-z0-9_]+)= ]] || continue
    local var="${BASH_REMATCH[1]}"
    [[ "$var" == "HSA_OVERRIDE_GFX_VERSION" ]] && continue
    [[ " $seen " == *" $var "* ]] && continue
    printf '  %-14s env=%s (unregistered — added by hand or an older script version)\n' "?" "$var"
  done < "$KEY_FILE"
}

cmd_setup() {
  local noninteractive=0 anthropic_key_arg="" xai_key_arg=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --non-interactive) noninteractive=1 ;;
      --anthropic-key) [[ $# -ge 2 ]] || die "--anthropic-key needs a value"; anthropic_key_arg="$2"; shift ;;
      --xai-key)       [[ $# -ge 2 ]] || die "--xai-key needs a value";       xai_key_arg="$2";       shift ;;
      # An unrecognized flag used to be skipped in silence, so a typo like
      # --noninteractive left the run waiting on an interactive prompt that
      # an automated caller was never going to answer.
      *) die "Unknown option: $1 (expected --non-interactive, --anthropic-key, --xai-key)" ;;
    esac
    shift
  done

  if [[ -n "$anthropic_key_arg" || -n "$xai_key_arg" ]]; then
    log "NOTE: a key passed on the command line is visible to other users on this box"
    log "      via 'ps', and lands in your shell history. Prefer the interactive prompt,"
    log "      or 'setup_ai_tools.sh add <provider>' which reads the key without echo."
  fi

  # ---- 1. API key configuration -----------------------------------------
  log "Configuring API keys at $KEY_FILE (mode 600, not world-readable)"

  local anthropic_key="" xai_key=""
  if [[ -n "$anthropic_key_arg" ]]; then
    anthropic_key="$anthropic_key_arg"
  elif [[ $noninteractive -eq 0 ]]; then
    read -r -s -p "Anthropic API key for Claude Code (blank to skip/keep existing): " anthropic_key
    echo ""
  fi
  [[ -n "$anthropic_key" ]] && store_provider_key "anthropic" "ANTHROPIC_API_KEY" "$anthropic_key"

  if [[ -n "$xai_key_arg" ]]; then
    xai_key="$xai_key_arg"
  elif [[ $noninteractive -eq 0 ]]; then
    read -r -s -p "xAI API key for Grok (blank to skip/keep existing): " xai_key
    echo ""
  fi
  [[ -n "$xai_key" ]] && store_provider_key "xai" "XAI_API_KEY" "$xai_key"

  ensure_key_file
  ensure_shell_sourcing

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
  local gpu_info
  gpu_info="$(lspci 2>/dev/null | grep -i 'VGA\|3D' | grep -i 'AMD\|ATI' || true)"

  if [[ -n "$gpu_info" ]]; then
    log "AMD GPU detected: $gpu_info"
    local gfx_override=""
    if echo "$gpu_info" | grep -qiE '6800|6900|6700 XT|6750'; then
      gfx_override="10.3.0"   # RDNA2 desktop (gfx1030 family)
      log "Recognized as RDNA2 desktop class (gfx1030 family)"
    fi

    if [[ -n "$gfx_override" ]]; then
      if ! grep -q "HSA_OVERRIDE_GFX_VERSION" "$KEY_FILE" 2>/dev/null; then
        echo "export HSA_OVERRIDE_GFX_VERSION=$gfx_override" >> "$KEY_FILE"
        log "Set HSA_OVERRIDE_GFX_VERSION=$gfx_override in $KEY_FILE"
      fi
      local rocm_ver
      rocm_ver="$(dpkg -l 2>/dev/null | grep -oP 'rocm-core\s+\K[0-9.]+' | head -1 || echo unknown)"
      log "ROCm package version at time of this configuration: $rocm_ver"
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
  if [[ -n "$xai_key" ]]; then
    log "Testing Grok (xAI) API key"
    local result
    result="$(verify_provider_key "xai" "$xai_key")"
    case "$result" in
      ok) log "Grok API key verified (HTTP 200)" ;;
      *)  log "Grok API check returned HTTP $result — key may be invalid, or api.x.ai is unreachable from here." ;;
    esac
  fi

  # ---- 6. Optional Hermes pull (explicit opt-in, not automatic) ----------
  if [[ $noninteractive -eq 0 ]] && command -v ollama >/dev/null 2>&1; then
    echo ""
    local pull_hermes
    read -r -p "Pull the Hermes 3 model via Ollama now? This is several GB. [y/N]: " pull_hermes
    if [[ "$pull_hermes" =~ ^[Yy]$ ]]; then
      log "Pulling hermes3 via Ollama"
      ollama pull hermes3 2>&1 | tee -a "$LOG_FILE"
    else
      log "Skipped. Pull later with: ollama pull hermes3"
    fi
  fi

  # ---- 7. Optional embedding model pull, for the second brain (`brain`) --
  if [[ $noninteractive -eq 0 ]] && command -v ollama >/dev/null 2>&1; then
    echo ""
    local pull_embed
    read -r -p "Pull nomic-embed-text via Ollama now? ~274MB, needed for 'brain index'/'brain search'/'brain ask' (see ~/second-brain/README.md). [y/N]: " pull_embed
    if [[ "$pull_embed" =~ ^[Yy]$ ]]; then
      log "Pulling nomic-embed-text via Ollama"
      ollama pull nomic-embed-text 2>&1 | tee -a "$LOG_FILE"
    else
      log "Skipped. Pull later with: ollama pull nomic-embed-text"
    fi
  fi

  echo ""
  log "AI tooling setup complete. Open a new shell (or 'source ~/.bashrc') to pick up"
  log "the API keys and any GPU override just written to $KEY_FILE."
  echo ""
  log "Other providers: setup_ai_tools.sh add <provider> — e.g. openai, gemini, mistral,"
  log "cohere, openrouter, deepseek, groq, huggingface, or any custom name via --env-var."
}

CMD="${1:-}"
case "$CMD" in
  add)    shift; cmd_add "$@" ;;
  remove) shift; cmd_remove "$@" ;;
  list)   shift; cmd_list "$@" ;;
  setup)  shift; cmd_setup "$@" ;;
  -h|--help) usage 0 ;;
  # No subcommand, or legacy flags straight from the command line — run the
  # full interactive/non-interactive setup flow, same as always.
  ""|--non-interactive|--anthropic-key|--xai-key) cmd_setup "$@" ;;
  *) die "Unknown command: $CMD (expected add|remove|list|setup)" ;;
esac

# Victorious Framework
