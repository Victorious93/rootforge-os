#!/bin/sh
# RootForge OS — environment profile
# Victorious Framework | Origin Source Labs
#
# Sourced for all login shells. Sets Android SDK paths once
# 00_bootstrap_distro.sh has run (ROOTFORGE_HOME exists).

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"

if [ -d "$ROOTFORGE_HOME/android-sdk" ]; then
  export ANDROID_SDK_ROOT="$ROOTFORGE_HOME/android-sdk"
  export ANDROID_HOME="$ROOTFORGE_HOME/android-sdk"
  export PATH="$PATH:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin"
fi

# AI key env (written by setup_ai_tools.sh, chmod 600, never world-readable)
if [ -f "$HOME/.rootforge/ai-keys.env" ]; then
  # shellcheck disable=SC1090
  . "$HOME/.rootforge/ai-keys.env"
fi

# Rootforge scripts always available
export PATH="$PATH:/usr/local/bin"
