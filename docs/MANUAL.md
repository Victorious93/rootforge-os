# RootForge OS — Manual

Operator-facing notes. Architecture lives in `docs/ARCHITECTURE.md`; the
Android host-phone app in `docs/ANDROID_APP.md`; desktop/Docker in
`docs/DESKTOP.md`. This file is meant to stay readable if another branch
adds more sections around it.

## Fleet integration

RootForge OS is an **EXTERNAL_NODE** in the Victorious fleet (droidcommand-AI
is the hub). It is not a companion APK: there is no shared signing key, no
`DependencyGuard`, and removing DCA does not disable any RootForge feature.

This section is the frozen **protocol 1.1** remote-control contract
(`BRIDGE_PROTOCOL_MAJOR = 1`, `BRIDGE_PROTOCOL_MINOR = 1`). The older
snake_case envelope (`operation: rootforge.devices.list`, …) still works
on the same Messenger service; see `docs/DROIDCOMMAND_INTEGRATION.md`.

### Transport

JSON over Android Messenger. The host-phone app exports a bound service
for action `ai.rootforge.os.action.BRIDGE` (debug package
`ai.rootforge.os.debug`; the action string does not change).

| Direction | `Message.what` | Bundle key |
|---|---|---|
| Request | 1 | `request` (JSON string) |
| Reply | 2 | `response` (JSON string) |

Desktop / Docker reuse the same JSON on `POST /v1/bridge` (loopback bearer
token). That is not the Messenger path.

### Envelope (flat fields)

```json
{
  "op": "capabilities.get",
  "requestId": "dca-…",
  "targetNodeRef": "rf-…32 hex…",
  "actionRef": "rootforge.devices.list",
  "params": {},
  "nonce": "0123456789abcdef0123456789abcdef",
  "timestampMs": 1700000000000,
  "ttlMs": 120000,
  "destructive": false,
  "trigger": {}
}
```

| Field | Rule |
|---|---|
| `op` | `capabilities.get` \| `remote.command` \| `macro.step` \| `macro.run` |
| `requestId` | Echoed on the reply |
| `targetNodeRef` | This install's node id (Settings). Mismatch → `not_found` |
| `actionRef` | Required for `remote.command` and `macro.step` |
| `params` | Object, default `{}` |
| `nonce` | Unique 128-bit value, 32 hex characters |
| `timestampMs` | Epoch milliseconds |
| `ttlMs` | Default 120000, max 300000 |
| `destructive` | Advisory boolean only. Never lowers a tier or skips a confirm |
| `trigger` | Optional object. **Informational only** — never used to authorize, pair, or auto-approve |

`macro.step` also requires `macroId`, `stepIndex` (≥ 0), `onError` (`abort` \| `continue`).

`macro.run` also requires `macroId`, `steps` (array of `{actionRef, params?, onError?}`), and optional `dryRun` (boolean, default false). A dry run returns the plan and does not execute or prompt.

Optional `protocolMajor` / `protocolMinor` / `sourceNodeRef` may be present. `sourceNodeRef` is ignored for auth and for the replay cache.

### Authorization

Authorize **only** by the calling app's package name plus the SHA-256 of
its signing certificate, taken from `Message.sendingUid` (fallback
`Binder.getCallingUid`) and `PackageManager` signing info. Nothing in the
JSON — including `trigger.*` and `sourceNodeRef` — can pair a caller or
skip a gate.

Pairing is an allow-list the owner manages in **Settings → External
control**. The first request from an unknown caller is recorded as
pending and refused (`not_paired`). A re-signed package shows as pending
again.

### External control switch

**Off by default.** Settings → Allow external controllers. Off →
`external_control_disabled`. Pending callers are still recorded so you
can pair them after turning the switch on.

### Replay and expiry

A request is `expired` when `now > timestampMs + ttlMs` **or**
`|now - timestampMs| > 120000` (clock skew). A nonce already seen for the
same authenticated source (`package:certSha256`) is `replay_detected`.
The seen-nonce cache is kept **at least 10 minutes**. An expired nonce is
still remembered so a later replay with a fresh timestamp is refused.

### `capabilities.get`

Returns `protocolMajor`, `protocolMinor`, `fleetClass: external_node`,
node identity, then:

- `ops[]` — the four envelope ops
- `actions[]` — `remote.command` action refs that are remotely allowed

Each entry is `{name, kind, remoteAllowed, requiresConfirm}` where `kind`
is `read` \| `action` \| `destructive`.

### What a remote caller may run

Only actions that already exist on this node:

| `actionRef` | kind | Confirm |
|---|---|---|
| `rootforge.devices.list` | read | no |
| `rootforge.device.info` | read | no |
| `rootforge.adb.shell` | action | on-device prompt |
| `rootforge.adb.reboot` | action | on-device prompt |
| `rootforge.fastboot.reboot` | action | on-device prompt |
| `rootforge.module.build` | action | on-device prompt |
| `rootforge.fastboot.flash` | destructive | typed phrase on the device (`ApprovalCenter`) |
| `rootforge.fastboot.slot.set` | destructive | typed phrase |
| `rootforge.fastboot.unlock` | destructive | typed phrase |
| `rootforge.fastboot.lock` | destructive | typed phrase |
| `shizuku.readDeviceState` | action | on-device prompt |
| `shizuku.pmGrant` | destructive | typed `GRANT PERMISSION` |

`shizuku.exec` exists on the host but is **not** remotely allowed
(`capability_off`). EDL, recovery writes, sideload, `fastboot oem` /
erase / boot-once, APK install and push/pull are **not** fleet actions;
naming them returns `confirmation_required` and nothing is written.
A remote `trigger` never auto-approves a destructive op.

### Error codes

Exactly these `error.code` values (message is for humans):

`invalid_parameters`, `not_paired`, `not_found`, `internal`,
`unauthorized`, `replay_detected`, `expired`, `external_control_disabled`,
`confirmation_required`, `unsupported_op`, `capability_off`.

Success:

```json
{"ok":true,"protocolMajor":1,"protocolMinor":1,"requestId":"dca-…","nodeRef":"rf-…","result":{}}
```

Failure:

```json
{"ok":false,"protocolMajor":1,"protocolMinor":1,"requestId":"dca-…","nodeRef":"rf-…","error":{"code":"not_paired","message":"…"}}
```

### Pairing on a phone (unverified here)

1. Install RootForge `app-debug.apk` (`ai.rootforge.os.debug`). Open Settings → External control; copy **Node id**.
2. Install a debug droidcommand-AI build. Bind `ai.rootforge.os.action.BRIDGE` and send `capabilities.get` with that `targetNodeRef`, a unique 32-hex `nonce`, and `timestampMs` = now.
3. The first reply is `not_paired` or `external_control_disabled`. RootForge Settings shows the pending package + cert prefix.
4. Turn **Allow external controllers** on, then **Pair**.
5. Repeat `capabilities.get`. Reads run; action ops prompt on the phone; destructive ops need the same typed phrase the GUI uses.

This path has **not** been run on a phone in this repository.

### Code

- Envelope: `android-app/core/.../bridge/FleetProtocol.kt`, `FleetEnvelope.kt`
- Replay: `ReplayGuard.kt`
- Executor: `FleetExecutor.kt`, `RemoteActionCatalog.kt`
- Ingress (fleet vs legacy): `BridgeIngress.kt`
- Messenger: `android-app/app/.../bridge/RootForgeBridgeService.kt`
- Tests: `FleetEnvelopeTest`, `ReplayGuardTest`, `FleetAuthTest`, `FleetCapabilitiesTest`, `FleetExecutorTest`
