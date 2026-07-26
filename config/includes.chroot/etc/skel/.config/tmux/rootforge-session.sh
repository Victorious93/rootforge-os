#!/bin/sh
# RootForge OS — rootforge-session tmux launcher
# Victorious Framework | Origin Source Labs
#
# Three-pane layout: logcat | device-watch | build shell
# Called by setup_terminal.sh after tmux/starship are configured.
# Run manually: rootforge-session.sh [device-serial]

SESSION="rootforge"
DEVICE="${1:-}"

tmux new-session -d -s "$SESSION" -n "build"

# Pane 0 (left): logcat — filter Magisk and KernelSU tags
tmux rename-window -t "$SESSION:0" "rootforge"
if [ -n "$DEVICE" ]; then
  tmux send-keys -t "$SESSION:0" "adb -s $DEVICE logcat -s Magisk:* KernelSU:* zygisk:* *:E" Enter
else
  tmux send-keys -t "$SESSION:0" "adb logcat -s Magisk:* KernelSU:* zygisk:* *:E" Enter
fi

# Pane 1 (top-right): device watcher
tmux split-window -t "$SESSION:0" -h
tmux send-keys -t "$SESSION:0.1" "watch -n2 'adb devices -l'" Enter

# Pane 2 (bottom-right): build shell — starts in rootforge workspace
tmux split-window -t "$SESSION:0.1" -v
tmux send-keys -t "$SESSION:0.2" "cd \${ROOTFORGE_HOME:-\$HOME/rootforge}" Enter

# Give build pane focus
tmux select-pane -t "$SESSION:0.2"

tmux attach-session -t "$SESSION"
