#!/usr/bin/env bash
# RootForge OS — real SSH round trip for the controller bridge.
#
# NOT part of tests/run-tests.sh: it needs OpenSSH (sshd, ssh, ssh-keygen) and
# opens a loopback listener. It starts a DISPOSABLE sshd on 127.0.0.1 with its
# own host key, config and authorized_keys under a scratch directory, never
# touching the machine's real sshd or ~/.ssh. If run as root it creates (and
# on exit removes) a throwaway unprivileged user, because the bridge must not
# run as root.
#
# Usage: tests/ssh-roundtrip.sh [--keep DIR]
#   --keep DIR  leave sshd running and write DIR/nodes.json + DIR/id_ed25519
#               for the DroidCommand AI client integration test; stop it later
#               with: kill $(cat DIR/sshd.pid); the throwaway user (printed) is
#               then removed with: userdel -r <user>
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_DIR="$REPO_ROOT/config/includes.chroot/usr/local/lib"
for t in sshd ssh ssh-keygen python3; do
  command -v "$t" >/dev/null 2>&1 || { echo "missing: $t" >&2; exit 2; }
done
SSHD="$(command -v sshd)"

KEEP=""
[ "${1:-}" = "--keep" ] && KEEP="${2:?--keep needs a directory}"
WORK="${KEEP:-$(mktemp -d)}"
mkdir -p "$WORK"; chmod 755 "$WORK"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; [ -n "${2:-}" ] && echo "       $2"; }

CREATED_USER=""
if [ "$(id -u)" = 0 ]; then
  BUSER="rfbridge$$"
  useradd -m -s /bin/sh "$BUSER" || { echo "cannot create user" >&2; exit 2; }
  CREATED_USER="$BUSER"
  usermod -p '*' "$BUSER"   # unlock for pubkey auth (useradd leaves it locked; no password is set)
  AS=(runuser -u "$BUSER" --)
else
  BUSER="$(id -un)"; AS=()
fi
cleanup() {
  [ -f "$WORK/sshd.pid" ] && [ -z "$KEEP" ] && kill "$(cat "$WORK/sshd.pid")" 2>/dev/null
  [ -n "$CREATED_USER" ] && [ -z "$KEEP" ] && userdel -r "$CREATED_USER" 2>/dev/null
  [ -z "$KEEP" ] && rm -rf "$WORK"
}
trap cleanup EXIT

# The bridge user must be able to read the checkout and write its state dir.
STATE="$WORK/state"; mkdir -p "$STATE"
[ -n "$CREATED_USER" ] && chown "$BUSER" "$STATE"
cat > "$WORK/rootforge" <<WRAP
#!/bin/sh
exec env PYTHONPATH="$LIB_DIR" python3 -m rootforge.core.cli "\$@"
WRAP
chmod 755 "$WORK/rootforge"
RF=("$WORK/rootforge")

ssh-keygen -q -t ed25519 -N '' -C host -f "$WORK/host_key"
ssh-keygen -q -t ed25519 -N '' -C ctl -f "$WORK/id_ed25519"
ssh-keygen -q -t ed25519 -N '' -C other -f "$WORK/id_other"
ssh-keygen -q -t ed25519 -N '' -C wronghost -f "$WORK/wrong_host_key"

NODE="$("${AS[@]}" "${RF[@]}" bridge init --state-dir "$STATE")"
"${AS[@]}" "${RF[@]}" bridge grant --state-dir "$STATE" --controller dca-test --grant inspect >/dev/null
"${RF[@]}" bridge authorized-key --state-dir "$STATE" --controller dca-test \
  --pubkey-file "$WORK/id_ed25519.pub" --exe "$WORK/rootforge" > "$WORK/authorized_keys"
chmod 644 "$WORK/authorized_keys"

PORT=$(python3 - <<'PY'
import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()
PY
)
cat > "$WORK/sshd_config" <<CFG
Port $PORT
ListenAddress 127.0.0.1
HostKey $WORK/host_key
PidFile $WORK/sshd.pid
AuthorizedKeysFile $WORK/authorized_keys
AllowUsers $BUSER
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM no
StrictModes no
LogLevel VERBOSE
CFG
chmod 600 "$WORK/host_key"
mkdir -p /run/sshd
"$SSHD" -f "$WORK/sshd_config" -E "$WORK/sshd.log" || { echo "sshd failed to start"; cat "$WORK/sshd.log"; exit 2; }
for _ in $(seq 1 50); do [ -s "$WORK/sshd.pid" ] && break; sleep 0.1; done

HOSTKEY_LINE="$(cut -d' ' -f1,2 "$WORK/host_key.pub")"
echo "[$HOSTKEY_LINE]" >/dev/null
echo "[127.0.0.1]:$PORT $HOSTKEY_LINE" > "$WORK/known_hosts"
SSHOPTS=(-T -o BatchMode=yes -o StrictHostKeyChecking=yes
  -o GlobalKnownHostsFile=/dev/null -o IdentitiesOnly=yes -o IdentityAgent=none -o ConnectTimeout=10
  -p "$PORT" -l "$BUSER")
# ssh honours the FIRST value of a repeated -o option, so the known_hosts file is
# chosen per call rather than overridden.
KH=(-o UserKnownHostsFile="$WORK/known_hosts")

req() { printf '{"protocol_major":1,"request_id":"%s","target_node_id":"%s","operation":"%s","parameters":{}}\n' "$1" "$NODE" "$2"; }
jget() { python3 -c 'import json,sys; d=json.loads(sys.stdin.readline()); print(eval(sys.argv[1]))' "$1"; }

echo "== real SSH round trip (node $NODE, port $PORT)"
OUT="$(req t1 rootforge.capabilities.get | ssh "${SSHOPTS[@]}" "${KH[@]}" -i "$WORK/id_ed25519" 127.0.0.1 2>"$WORK/err1")"
[ "$(echo "$OUT" | jget 'd["ok"]')" = "True" ] && ok "capabilities over SSH" || bad "capabilities over SSH" "$OUT $(cat "$WORK/err1")"
[ "$(echo "$OUT" | jget 'd["node_id"]')" = "$NODE" ] && ok "response carries the paired node id" || bad "node id"

OUT="$(req t2 rootforge.devices.list | ssh "${SSHOPTS[@]}" "${KH[@]}" -i "$WORK/id_ed25519" 127.0.0.1 2>/dev/null)"
[ "$(echo "$OUT" | jget 'd["ok"]')" = "True" ] && ok "devices.list over SSH" || bad "devices.list over SSH" "$OUT"

# Forced command: an arbitrary remote command must NOT run.
OUT="$(ssh "${SSHOPTS[@]}" "${KH[@]}" -i "$WORK/id_ed25519" 127.0.0.1 'id; echo PWNED' </dev/null 2>/dev/null)"
case "$OUT" in *PWNED*|*uid=*) bad "forced command ignored client command" "$OUT" ;; *) ok "client-requested command is not executed" ;; esac

# PTY and forwarding are refused by `restrict`.
OUT="$(ssh "${SSHOPTS[@]}" "${KH[@]}" -tt -i "$WORK/id_ed25519" 127.0.0.1 </dev/null 2>&1)"
echo "$OUT" | grep -qi "pty allocation request failed" && ok "PTY refused" || bad "PTY should be refused" "$OUT"
ssh "${SSHOPTS[@]}" "${KH[@]}" -i "$WORK/id_ed25519" -o ExitOnForwardFailure=yes -L 0:127.0.0.1:22 127.0.0.1 </dev/null >/dev/null 2>&1 \
  && bad "port forwarding should be refused" || ok "port forwarding refused"

# Unknown key.
req t3 rootforge.capabilities.get | ssh "${SSHOPTS[@]}" "${KH[@]}" -i "$WORK/id_other" 127.0.0.1 >/dev/null 2>&1 \
  && bad "unauthorized key accepted" || ok "unauthorized key rejected by sshd"

# Changed host identity: the client's pinned key no longer matches.
echo "[127.0.0.1]:$PORT $(cut -d' ' -f1,2 "$WORK/wrong_host_key.pub")" > "$WORK/known_hosts_wrong"
req t4 rootforge.capabilities.get | ssh "${SSHOPTS[@]}" -o UserKnownHostsFile="$WORK/known_hosts_wrong" -i "$WORK/id_ed25519" 127.0.0.1 >/dev/null 2>"$WORK/err4" \
  && bad "wrong host key accepted" || { grep -qi "host key verification failed\|REMOTE HOST IDENTIFICATION" "$WORK/err4" && ok "changed/wrong host key blocks the connection" || bad "host key mismatch not reported" "$(cat "$WORK/err4")"; }

# Revocation takes effect on the next connection without touching authorized_keys.
"${AS[@]}" "${RF[@]}" bridge revoke --state-dir "$STATE" --controller dca-test >/dev/null
OUT="$(req t5 rootforge.capabilities.get | ssh "${SSHOPTS[@]}" "${KH[@]}" -i "$WORK/id_ed25519" 127.0.0.1 2>/dev/null)"
[ "$(echo "$OUT" | jget 'd["error"]["category"]')" = "unauthorized" ] && ok "revoked controller gets unauthorized" || bad "revocation" "$OUT"
"${AS[@]}" "${RF[@]}" bridge grant --state-dir "$STATE" --controller dca-test --grant inspect >/dev/null

if [ -n "$KEEP" ]; then
  cat > "$KEEP/nodes.json" <<JSON
{"nodes":[{"node_id":"$NODE","host":"127.0.0.1","port":$PORT,"user":"$BUSER","identity_file":"$KEEP/id_ed25519","host_key":"$HOSTKEY_LINE"}]}
JSON
  cut -d' ' -f1,2 "$WORK/wrong_host_key.pub" > "$KEEP/wrong_host_key.line"
  echo "kept: $KEEP/nodes.json (sshd pid $(cat "$WORK/sshd.pid"))"
fi
echo "----------------------------------------"
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
