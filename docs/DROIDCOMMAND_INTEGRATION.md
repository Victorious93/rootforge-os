# DroidCommand AI integration — controller bridge (RF-DCA-0 / RF-DCA-1)

Status: **first slice implemented** (passive operations only). Design source:
the 2026-10-07 integration blueprint. Everything not listed under "What exists"
is still a proposal.

RootForge is, and stays, an independent product. The bridge is an *optional*
way for an authorized controller (DroidCommand AI, or anything that speaks the
protocol) to ask a RootForge node questions. Nothing else in RootForge imports
`rootforge.core.bridge`; with the bridge unused, uninstalled, or its SSH key
revoked, every other command behaves exactly as before. RootForge must **not**
be subjected to any "DCA must be installed" dependency guard.

## What exists

| Piece | Where |
|---|---|
| Protocol, server loop, grants, CLI | `config/includes.chroot/usr/local/lib/rootforge/core/bridge.py` |
| Unit + local-process tests | `tests/test_bridge.py` (part of `tests/run-tests.sh`) |
| Real SSH round trip (opt-in, needs OpenSSH) | `tests/ssh-roundtrip.sh` |

### Operations (protocol major 1)

| Operation | Grant | Notes |
|---|---|---|
| `rootforge.capabilities.get` | `inspect` | node id, version, runtime, `adb`/`fastboot` presence (PATH lookup only), operation list |
| `rootforge.devices.list` | `inspect` | `adb devices` + `fastboot devices` only. **Side effect:** `adb devices` starts the local adb server if it is not running. No per-device query, no `su`, no root. |

Deliberately **not** exposed, and not accepted as grants: device profiling,
workspaces, jobs, artifacts, builds, backup/restore, flash/unlock, terminal,
desktop. `rootforge device info` in particular is excluded because its profiler
runs `su -c "magisk -v"` / `su -c "ksud -V"` on the device — that is a root
request, not a passive read.

### Wire format

One JSON object per line on stdin; one response per line on stdout (stderr is
diagnostics only). Limits: 64 KiB per request, `timeout_ms` 1..60000.

```json
{"protocol_major":1,"request_id":"r1","target_node_id":"rf-…","operation":"rootforge.devices.list","timeout_ms":10000,"parameters":{}}
{"protocol_major":1,"request_id":"r1","node_id":"rf-…","ok":true,"result":{"devices":[]}}
{"protocol_major":1,"request_id":"r1","node_id":"rf-…","ok":false,"error":{"category":"unauthorized","message":"…"}}
```

Error categories: `unauthorized`, `unsupported_capability`, `invalid_request`,
`incompatible_protocol`, `wrong_node`, `timeout`, `internal`. Unknown request
fields and unexpected parameters are rejected. A request whose `target_node_id`
is not this node is refused (`wrong_node`).

### Security model

* **Identity is server-side.** The controller id is the `--controller` argument
  baked into the authorized_keys line; it is never read from request JSON.
* **Grants are re-read on every request** from `controllers.json` (mode 0600,
  atomically replaced), so `bridge revoke` applies to an already-open session.
  Any grants-file error denies the request (fail closed).
* **Restricted SSH.** `bridge authorized-key` emits
  `restrict,command="<rootforge> bridge serve --controller ID --state-dir DIR" <key>`.
  `restrict` disables PTY, port/agent/X11 forwarding and `~/.ssh/rc`; the forced
  command ignores whatever the client asks to run. Controller ids, state dirs
  and key material are validated against strict patterns so nothing can break
  out of the `command="…"` quoting.
* **Run the bridge as a dedicated unprivileged account**, with the state dir
  outside any build-writable location. The bridge never escalates; privileged
  operations (a later phase) would need a separate narrow helper, not sudo for a
  shell or interpreter.
* **Host identity** is pinned by the client from a host key you confirm out of
  band: `rootforge bridge host-key` prints the key and fingerprint.

### Setup (Linux node)

```
rootforge bridge init                       # prints the node id (rf-…)
rootforge bridge grant --controller dca-phone --grant inspect
rootforge bridge authorized-key --controller dca-phone --pubkey-file dca.pub >> ~rfbridge/.ssh/authorized_keys
rootforge bridge host-key                   # confirm this fingerprint on the controller
rootforge bridge list / revoke --controller dca-phone
```

Creating the `rfbridge` account and enabling sshd are the operator's decisions;
no RootForge script does either.

## Verified in this slice

* `bash tests/run-tests.sh` — 438 passed, 0 failed (the Python suite is one check inside
  that count; it grew from 161 to 192 `unittest` tests, 31 of them in `tests/test_bridge.py`).
* `bash tests/lint.sh` (ShellCheck installed for the run) — clean.
* `bash tests/ssh-roundtrip.sh` against a disposable loopback sshd: capabilities and
  devices over real SSH; client-requested command not executed; PTY refused;
  port forwarding refused; unknown key rejected; wrong pinned host key blocks the
  connection; revoked controller receives `unauthorized`.
* Not verified: Android clients, Windows/WSL, Termux/PRoot nodes, a physical device
  attached to a node (no `adb`/`fastboot` binaries or hardware here — `devices.list`
  was exercised with stubbed command output and with the tools absent).

## Gates before any write capability is exposed remotely

These are present in the current source (re-checked 2026-10-07) and are **why
no write/privileged operation exists in the bridge**:

1. `device.profile_fastboot` runs `fastboot getvar all` through a helper that
   captures stdout only, while fastboot reports getvar results on stderr; and it
   derives `bootloader_unlocked` from `unlocked` **or** `secure`, conflating two
   different properties.
2. `restore_partitions.sh` verifies `SHA256SUMS` (when present) but then flashes
   every `*.img` found in the directory; with no `SHA256SUMS` it only warns.
3. `rf_confirm` confirms on `/dev/tty`; a remote caller cannot satisfy it, and
   piping "yes"/`ROOTFORGE_ASSUME_YES` is not an acceptable substitute. Remote
   destructive operations need a RootForge-generated plan (node, device, partition,
   artifact digest, expiry) approved by an authorized principal, revalidated at
   dispatch.

## Not built yet (later phases)

Durable jobs, workspace snapshots, artifact transfer, build operations, owned
schedules, terminal/desktop sessions, MCP facade, HTTPS transport, Windows/Android
runtimes. Each needs its own scoping; see the blueprint's RF-DCA-2 .. RF-DCA-5.
