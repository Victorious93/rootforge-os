# RootForge × DroidCommand AI — bridge protocol (canonical)

This file is the RootForge-side contract. droidcommand-ai's `core-rootforge` module is a hand-maintained Kotlin mirror of the same envelope (`BridgeRequest` / `BridgeResponse`, `BRIDGE_PROTOCOL_MAJOR = 1`). Keep the majors in lockstep; an unknown major is rejected, never guessed.

RootForge is **not** a companion APK. Companions (HackerAI, Pentest-Swarm) bind with a signature permission and refuse to work if DCA is missing. A RootForge node that DCA has never paired with, or cannot reach, loses nothing. Removing DCA must not disable any RootForge feature. See droidcommand-ai `COMPANION_PROTOCOL.md` ("External nodes are not companions").

## Transports

Same JSON lines, two pipes:

| Transport | Where | How |
|---|---|---|
| SSH | RootForge OS **node** (the Debian install / Termux rootfs), `rootforge bridge` when that command exists | OpenSSH, pinned host key, no agent/forwarding. DCA `SshRootForgeTransport`. **Passive only** on the DCA side today (`capabilities`, `devices.list`). |
| Android Messenger | RootForge OS **app** on a host phone (`ai.rootforge.os`) | Bound service `ai.rootforge.os.action.BRIDGE`. One `Message` per request (`what=1`, `request` string); one reply (`what=2`, `response` string). Caller identity is the kernel UID's package + SHA-256 of its signing certs, **never** a field in the JSON. |

The phone app is the transport this document specifies in full. The SSH node is still the passive slice described in droidcommand-ai `docs/ROOTFORGE_INTEGRATION.md`.

## Envelope

One JSON object per line. No credentials, environment overrides, or file contents.

Request:

```json
{
  "protocol_major": 1,
  "request_id": "dca-…",
  "target_node_id": "rf-…32 hex…",
  "operation": "rootforge.devices.list",
  "timeout_ms": 10000,
  "parameters": {}
}
```

Response, success:

```json
{
  "protocol_major": 1,
  "request_id": "dca-…",
  "node_id": "rf-…",
  "ok": true,
  "result": { }
}
```

Response, failure (`error.category` is the stable branch; `message` is for humans):

```json
{
  "protocol_major": 1,
  "request_id": "dca-…",
  "node_id": "rf-…",
  "ok": false,
  "error": { "category": "not_paired", "message": "…" }
}
```

Categories: `malformed_request`, `protocol_mismatch`, `wrong_node`, `not_paired`, `bridge_disabled`, `unknown_operation`, `bad_parameters`, `denied_by_user`, `no_target`, `timeout`, `operation_failed`.

## Gates, in this order

1. Well-formed JSON, size-capped.
2. `protocol_major == 1`.
3. `target_node_id` is this install's node id (`Settings` in the app; generated once, 16 random bytes as `rf-` + hex).
4. External control is **on** (`Settings` → Allow external controllers; default off).
5. Caller is **paired**: same package name **and** same cert hash the owner approved. A package that was paired and then re-signed shows up as pending again (`not_paired` / "different signing certificate").
6. Operation exists.
7. Parameters parse; no extra power. Images are **library file names**, not paths. A request cannot raise its own tier or skip a prompt.
8. **Read** operations run. **Control** operations prompt the person at the host ("Allow `rootforge.adb.shell`?"). **Destructive** operations run the same typed-confirmation path the GUI uses (`FLASH boot`, `UNLOCK`, …). A timeout or Cancel is `denied_by_user`.
9. The operation runs under `timeout_ms` (clamped).

Pairing happens only in the RootForge UI. The first request from an unknown caller is recorded as pending and refused.

## Operations (app)

| Name | Tier | Parameters | Result |
|---|---|---|---|
| `rootforge.capabilities.get` | read | — | `protocol_major`, `node_kind=android-host-app`, `app_version`, `node_id`, `usb_host`, `operations[]` |
| `rootforge.devices.list` | read | — | `{ "devices": [{ "serial", "mode", "state", "usable", "note" }] }` — same shape as DCA's `RemoteDevice` |
| `rootforge.device.info` | read | — | `{ "info": { "Model": "…", … } }` of the connected target |
| `rootforge.adb.shell` | control | `command` | `{ "exit_code", "output", "truncated" }` |
| `rootforge.adb.reboot` | control | `target?` (`""`/`bootloader`/`recovery`/`sideload`/`fastboot`/`edl`) | `{ "requested" }` |
| `rootforge.fastboot.reboot` | control | `target?` (`system`/`bootloader`/`recovery`/`fastbootd`) | `{ "requested" }` |
| `rootforge.module.build` | control | `id`, `name`, `version?`, `version_code?`, `author?`, `description?`, `target?` | `{ "library_file", "sha256" }` — zip is stored in the app library, no device is touched |
| `rootforge.fastboot.flash` | destructive | `partition`, `image` (library name) | `{ "flashed", "image" }` |
| `rootforge.fastboot.slot.set` | destructive | `slot` = `a`\|`b` | `{ "active_slot" }` |
| `rootforge.fastboot.unlock` | destructive | — | `{ "requested": "unlock" }` |
| `rootforge.fastboot.lock` | destructive | — | `{ "requested": "lock" }` |

EDL, sideload, and raw `fastboot oem` are **not** on the bridge. Use the GUI. A future write/build slice on the SSH node stays gated on the hardware validation listed in `docs/REVIEW.md`.

## DCA wiring (app)

Until `core-rootforge` grows an Android `RootForgeTransport`, a one-file client is:

1. `bindService` on `ai.rootforge.os.action.BRIDGE` (debug builds: `ai.rootforge.os.debug`).
2. `Messenger.send` with `what=1` and `data.request` = the request line; `replyTo` set.
3. Read `data.response`.

Do **not** add a `BIND_ROOTFORGE` signature permission that would force a shared signing key. Pairing is the access control; the two apps may be signed differently.

CLI-side (`DROIDCOMMAND_CLI_ROOTFORGE_NODES_FILE`) remains SSH-only and unchanged.

## Protocol sync

- App: `android-app/.../bridge/Protocol.kt` (`BRIDGE_PROTOCOL_MAJOR`).
- Tests: `android-app/.../bridge/BridgeExecutorTest.kt`.
- DCA: `core-rootforge/.../Protocol.kt`. A vendored schema/fixture set (blueprint §10) is still not done.
