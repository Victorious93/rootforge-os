#!/bin/sh
# RootForge OS toolkit image. Default: headless API. Pass `rootforge …` (or
# any other command) to run the CLI instead.
set -eu
export PYTHONPATH="${PYTHONPATH:-}${PYTHONPATH:+:}/opt/rootforge-api:/usr/local/lib"
export PATH="/usr/local/bin:${PATH}"

if [ "$#" -eq 0 ] || [ "${1#-}" != "$1" ]; then
  exec python3 /opt/rootforge-api/rootforge_api.py "$@"
fi

exec "$@"
