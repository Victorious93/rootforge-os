# RootForge OS — default .bashrc
# Victorious Framework | Origin Source Labs

# Non-interactive shell: bail out
case $- in
  *i*) ;;
  *) return ;;
esac

# History
HISTCONTROL=ignoreboth
HISTSIZE=10000
HISTFILESIZE=20000
shopt -s histappend checkwinsize

# Colour ls and grep
export LS_COLORS='di=1;34:ln=36:so=32:pi=33:ex=1;32:bd=34;46:cd=34;43:su=30;41:sg=30;43:tw=30;42:ow=30;42'
alias grep='grep --color=auto'
alias ls='eza --icons --group-directories-first 2>/dev/null || ls --color=auto'
alias ll='eza -lah --icons --git 2>/dev/null || ls -lah'
alias cat='bat --paging=never 2>/dev/null || cat'

# ~/.local/bin on PATH (rootforge-session launcher lives here)
[[ ":$PATH:" != *":$HOME/.local/bin:"* ]] && export PATH="$HOME/.local/bin:$PATH"

# RootForge environment (SDK paths set once 00_bootstrap_distro.sh has run)
[[ -f /etc/profile.d/rootforge.sh ]] && . /etc/profile.d/rootforge.sh

# Shell integrations
command -v zoxide   >/dev/null 2>&1 && eval "$(zoxide init bash)"
command -v starship >/dev/null 2>&1 && eval "$(starship init bash)"

# Aliases — Android dev shortcuts
alias adblog='adb logcat -s Magisk:* KernelSU:* zygisk:* *:E'
alias devices='adb devices -l && echo && fastboot devices'
alias forge='cd "${ROOTFORGE_HOME:-$HOME/rootforge}"'
