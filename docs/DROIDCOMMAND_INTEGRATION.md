# RootForge × DroidCommand AI — bridge protocol (canonical)

This file is the RootForge-side contract. droidcommand-ai's `core-rootforge` module is a hand-maintained Kotlin mirror of the same envelope (`BridgeRequest` / `BridgeResponse`, `BRIDGE_PROTOCOL_MAJOR = 1`). Keep the majors in lockstep; an unknown major is rejected, never guessed.

RootForge is **not** a companion APK. Companions (HackerAI, Pentest-Swarm) bind with a signature permission and refuse to work if DCA is missing. A RootForge node that DCA has never paired with, or cannot reach, loses nothing. Removing DCA must not disable any RootForge feature. See droidcommand-ai `COMPANION_PROTOCOL.md` ("External nodes are not companions").

## Transports

Same JSON lines, two pipes:

| Transport | Where | How |
|---|---|---|
| SSH | RootForge OS **node** (the Debian install / Termux rootfs), `rootforge bridge` when that command exists | OpenSSH, pinned host key, no agent/forwarding. DCA `SshRootForgeTransport`. **Passive only** on the DCA side today (`capabilities`, `devices.list`). |
| Android Messenger | RootForge OS **app** on a host phone (`ai.rootforge.os`) | Bound service `ai.rootforge.os.action.BRIDGE`. One `Message` per request (`what=1`, `request` string); one reply (`what=2`, `response` string). Caller identity is the kernel UID's package + SHA-256 of its signing certs, **never** a field in the JSON. |

The phone app is the transport this document specifies in full. The SSH node is still the passive slice described in droidcommand-ai `docs/ROOTFORGE_INTEGRATION.md`. Desktop / Docker reuse the same JSON via HTTP (`docs/DESKTOP.md`); that is not the Messenger path.

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

Messenger constants (also in `Protocol.kt`): `BRIDGE_ACTION`, `BRIDGE_MSG_REQUEST = 1`, `BRIDGE_MSG_RESPONSE = 2`, `BRIDGE_KEY_REQUEST = "request"`, `BRIDGE_KEY_RESPONSE = "response"`. Debug APK package is `ai.rootforge.os.debug`; the action string does not change.

## Gates, in this order

1. Well-formed JSON, size-capped (64 KiB).
2. `protocol_major == 1`.
3. `target_node_id` is this install's node id (`Settings` in the app; generated once, 16 random bytes as `rf-` + hex).
4. If the caller is not paired, they are recorded as **pending** (so Settings can show them). A request while the switch is off still appears there.
5. External control is **on** (`Settings` → Allow external controllers; default off). Off → `bridge_disabled`.
6. Caller is **paired**: same package name **and** same cert hash the owner approved. A package that was paired and then re-signed shows up as pending again (`not_paired` / "different signing certificate").
7. Operation exists. EDL, sideload-zip, raw `fastboot oem`, erase, boot-once, logcat, APK install, push/pull, recovery install, and Firehose are **not** operations. They return `unknown_operation`.
8. Parameters parse; only the keys listed for that operation are accepted (any other key is `bad_parameters`). Images are **library file names**, not paths. A request cannot raise its own tier or skip a prompt.
9. **Read** operations run. **Control** operations prompt the person at the host ("Allow `rootforge.adb.shell`?"). **Destructive** operations run the same typed-confirmation path the GUI uses (`FLASH boot`, `UNLOCK`, …) inside `TargetController`, not a second executor prompt. A timeout or Cancel is `denied_by_user`.
10. The operation runs under `timeout_ms` (clamped: reads 1 s–15 min; control/destructive at least 120 s).

Pairing happens only in the RootForge UI. The first request from an unknown caller is recorded as pending and refused. Pending is in memory (lost if the process dies); the paired list is persisted.

## Operations (app)

| Name | Tier | Parameters | Result |
|---|---|---|---|
| `rootforge.capabilities.get` | read | — | `protocol_major`, `node_kind` (`android-host-app` on the phone; `desktop-host` on the desktop client), `app` (stable extra: `rootforge-os-android`), `app_version`, `node_id`, `usb_host`, `operations[]` |
| `rootforge.devices.list` | read | — | `{ "devices": [{ "serial", "mode", "state", "usable", "note" }] }` — same shape as DCA's `RemoteDevice` |
| `rootforge.device.info` | read | — | `{ "info": { "Model": "…", … } }` of the connected target |
| `rootforge.adb.shell` | control | `command` | `{ "exit_code", "output", "truncated" }` |
| `rootforge.adb.reboot` | control | `target?` (`""`/`bootloader`/`recovery`/`sideload`/`fastboot`/`edl`) | `{ "requested" }` — reboot **to** that mode; this is not sideload-of-a-zip |
| `rootforge.fastboot.reboot` | control | `target?` (`system`/`bootloader`/`recovery`/`fastbootd`) | `{ "requested" }` |
| `rootforge.module.build` | control | `id`, `name`, `version?`, `version_code?`, `author?`, `description?`, `target?` | `{ "library_file", "sha256" }` — zip is stored in the app library, no device is touched. Extra module fields (`files`, `webroot`, …) are rejected. |
| `rootforge.fastboot.flash` | destructive | `partition`, `image` (library name) | `{ "flashed", "image" }` |
| `rootforge.fastboot.slot.set` | destructive | `slot` = `a`\|`b` | `{ "active_slot" }` |
| `rootforge.fastboot.unlock` | destructive | — | `{ "requested": "unlock" }` |
| `rootforge.fastboot.lock` | destructive | — | `{ "requested": "lock" }` |

EDL, sideload of a zip, and raw `fastboot oem` are **not** on the bridge. Use the GUI. Termux stays with VictorSuite. Sahara / Firehose stay with EDL-Commander (or the in-app EDL screen); RootForge does not call them from this envelope. A future write/build slice on the SSH node stays gated on the hardware validation listed in `docs/REVIEW.md`.

## DCA wiring (app)

Until `core-rootforge` grows an Android `RootForgeTransport`, a one-file client is:

1. `bindService` on `ai.rootforge.os.action.BRIDGE` (debug builds: package `ai.rootforge.os.debug`).
2. `Messenger.send` with `what=1` and `data.request` = the request line; `replyTo` set.
3. Read `data.response`.

Do **not** add a `BIND_ROOTFORGE` signature permission that would force a shared signing key. Pairing is the access control; the two apps may be signed differently.

CLI-side (`DROIDCOMMAND_CLI_ROOTFORGE_NODES_FILE`) remains SSH-only and unchanged.

### Pairing a debug DCA build

On the host phone, with both debug APKs installed:

1. Install RootForge `app-debug.apk` (`ai.rootforge.os.debug`). Open it, Settings → External control, copy **Node id**.
2. In the DCA debug build, bind `ai.rootforge.os.debug` / `ai.rootforge.os.action.BRIDGE` and send `rootforge.capabilities.get` with that `target_node_id`. The first reply is `not_paired` or `bridge_disabled`; that is expected.
3. RootForge Settings shows the pending caller (package + cert hash prefix). Turn **Allow external controllers** on if it is still off, then **Pair**.
4. Repeat the capabilities call. After that, reads run; control ops pop an on-phone prompt; destructive ops ask for the same typed phrase the GUI uses.

A re-signed DCA (new debug keystore) shows as pending again with a "different signing certificate" note. Pair replaces the old cert. Revoke forgets the package.

`<queries>` lists `ai.droidcommand.app` and `ai.droidcommand.app.debug` so the host can resolve the caller's signing certs on Android 11+. If a debug DCA uses another applicationId, add it there or the identity check returns `not_paired` / "could not establish the calling app's identity".

## Protocol sync

- App: `android-app/core/src/main/kotlin/ai/rootforge/os/bridge/Protocol.kt` (`BRIDGE_PROTOCOL_MAJOR`, `BRIDGE_PROTOCOL_MINOR = 1`, Messenger constants, `BridgeOp`).
- Executor + pairing: `BridgeExecutor.kt`, `PairingStore.kt`.
- Fleet remote-control envelope (minor 1: `op` / `requestId` / nonce): `FleetExecutor.kt`, `docs/MANUAL.md` (Fleet integration). Same Messenger service; `BridgeIngress` routes on a top-level `op` field.
- Messenger service: `android-app/app/.../bridge/RootForgeBridgeService.kt` (exported, no signature permission). Caller UID is `Message.sendingUid`, with `Binder.getCallingUid()` as fallback.
- Tests: `BridgeExecutorTest.kt`, `PairingStoreTest.kt`, `FleetEnvelopeTest.kt`, `ReplayGuardTest.kt`, `FleetAuthTest.kt`, `FleetCapabilitiesTest.kt`, `FleetExecutorTest.kt`.
- DCA: `core-rootforge/.../Protocol.kt`. A vendored schema/fixture set (blueprint §10) is still not done.

## Honest status

Implemented and unit-tested in `:core` (no phone, no USB):

- Envelope, all eleven operations above, pairing by package+cert, pending queue, cert-change, extra-parameter rejection, library-name images, control prompts, `denied_by_user` when the typed phrase is refused.
- Messenger service, Settings pairing UI, pending-controller notification.

Still not a live DCA session:

- `core-rootforge` still has no Android `RootForgeTransport` (DCA must bind with the one-file client above).
- The APK has not been installed next to a debug DCA on a phone. USB ops still need a real target.
- SSH `rootforge bridge` is still the passive slice, not this document's write path.
