# Desktop mode — RootForge OS on a VictorSuite X11 session

Status **2026-10-20.** The Android host-phone app can show a Dashboard card
that asks VictorSuite to start a Linux desktop (XFCE) on this phone, then run
RootForge’s own desktop UI and VS Code inside that session. **Not run on a
phone.** Nothing here flashes a device.

VictorSuite is a Termux-based companion
([Victorious93/VictorSuite](https://github.com/Victorious93/VictorSuite)).
Read on 2026-10-20: its `applicationId` is **`com.termux`** (same as upstream
Termux; the two cannot be installed together). It already exports Termux
`RUN_COMMAND`. It does **not** yet export the narrower Desktop-mode action
specified below.

## What RootForge does today

On Android, Dashboard shows a **Desktop mode** card:

| VictorSuite | Card |
|---|---|
| Not installed | Plain-language hint. No crash. |
| Installed, no RUN_COMMAND and no Desktop-mode action | “Not supported yet”, with a pointer at this document. |
| Installed | Owner must **pin** the package + signing-certificate SHA-256 (first time, and again if the cert changes). |
| Pinned, RUN_COMMAND only | Start / Set up / Status / Stop send **one documented command**, never a free-form shell. |
| Pinned, dedicated action present | Same buttons, via `ai.victorsuite.action.DESKTOP_MODE`. |

The desktop JVM client hides the card: that process already *is* the desktop UI.

RootForge and VictorSuite **do not share a signing key**. Identity is package
name plus certificate hash, the same rule as the droidcommand-ai bridge, in
the opposite direction (RootForge is the caller).

## Intent API VictorSuite already has (RUN_COMMAND)

VictorSuite’s manifest (confirmed 2026-10-20):

- Permission (dangerous): `com.termux.permission.RUN_COMMAND`
- Service: `com.termux.app.RunCommandService` (exported, protected by that permission)
- Action: `com.termux.RUN_COMMAND`

RootForge declares that permission, lists `com.termux` in `<queries>`, and
sends:

| Extra | Value |
|---|---|
| `com.termux.RUN_COMMAND_PATH` | `/data/data/com.termux/files/usr/bin/bash` |
| `com.termux.RUN_COMMAND_ARGUMENTS` | `["-lc", "command -v rootforge-desktop-mode >/dev/null && exec rootforge-desktop-mode <op> || { echo '…'; exit 1; }"]` |
| `com.termux.RUN_COMMAND_WORKDIR` | `/data/data/com.termux/files/home` |
| `com.termux.RUN_COMMAND_BACKGROUND` | `false` |
| `com.termux.RUN_COMMAND_COMMAND_LABEL` | `RootForge Desktop mode` |
| `com.termux.RUN_COMMAND_SESSION_ACTION` | `"0"` (switch to the new session) |

`<op>` is exactly one of `start`, `stop`, `setup`, `status`. The UI never
splices typed text into that line.

VictorSuite still requires `allow-external-apps=true` in
`~/.termux/termux.properties` for RUN_COMMAND, as upstream Termux does. The
owner must also grant RootForge the RUN_COMMAND permission.

## Intent API VictorSuite should add

A dedicated action is safer than RUN_COMMAND: it cannot run an arbitrary
executable, and it should check the caller’s package **and** signing
certificate.

### Permission

```xml
<permission
    android:name="ai.victorsuite.permission.DESKTOP_MODE"
    android:label="Start RootForge Desktop mode"
    android:protectionLevel="dangerous" />
```

Do **not** use `signature` / `signatureOrSystem`. That would force a shared
signing key.

### Service

```xml
<service
    android:name=".zerocore.desktop.DesktopModeService"
    android:exported="true"
    android:permission="ai.victorsuite.permission.DESKTOP_MODE">
    <intent-filter>
        <action android:name="ai.victorsuite.action.DESKTOP_MODE" />
    </intent-filter>
</service>
```

### Extras

| Extra | Values |
|---|---|
| `ai.victorsuite.extra.OPERATION` | `start` \| `stop` \| `setup` \| `status` |
| `ai.victorsuite.extra.DISPLAY` | X display, default `:0` |

Reject anything else. Do not accept a shell string.

### Caller check (required)

On each start:

1. `Binder.getCallingUid()` → packages for that UID. Refuse a shared UID
   (more than one package).
2. Read that package’s signing certificates; SHA-256 hex, lowercase,
   comma-joined if multiple, same encoding RootForge uses on the bridge.
3. Allow only:
   - `ai.rootforge.os`
   - `ai.rootforge.os.debug`
   whose certificate hash is on an allow-list the owner manages in
   VictorSuite (pin on first sight, like RootForge’s Pin button). A
   re-signed RootForge must be pinned again.
4. Then run `rootforge-desktop-mode <operation>` inside the Linux
   environment (PRoot `--shared-tmp` or the chroot launcher).

Until this service exists, RootForge keeps using RUN_COMMAND and the card
says so.

## Scripts in this repository

Run **inside the Debian rootfs** that VictorSuite hosts:

| Script | Role |
|---|---|
| `setup_desktop_mode.sh` | Idempotent install: XFCE, tigervnc (VNC fallback), VS Code if apt has `code-oss` (or pinned `code-server`), autostart launchers, optional Termux wrapper |
| `rootforge_desktop_mode.sh` | `start` / `stop` / `setup` / `status`. X11 if the Termux:X11 socket is visible; otherwise tigervnc on `:1` |
| `termux/desktop-mode-entry.sh` | Termux-side `rootforge-desktop-mode`: `proot-distro login rootforge --shared-tmp -- /usr/local/bin/rootforge_desktop_mode.sh <op>` |

`setup_desktop_mode.sh` does not download code-server unless both
`ROOTFORGE_CODE_SERVER_URL` and `ROOTFORGE_CODE_SERVER_SHA256` are set. The
RootForge Compose desktop binary is not downloaded; copy a `:desktop`
distribution to `/opt/rootforge-os` or `$HOME/rootforge/desktop`.

Existing `rootforge_desktop.sh` (XFCE against Termux:X11 only) is unchanged.

## Phone test steps (when you have a host phone)

1. Install RootForge debug APK (`ai.rootforge.os.debug`) and VictorSuite
   (`com.termux`). Uninstall upstream Termux first if it is present.
2. Open RootForge → Dashboard. **Desktop mode** should name `com.termux`.
   Without VictorSuite the card is only a hint and the app stays up.
3. Tap **Pin this VictorSuite**. Settings → Apps → RootForge → Permissions
   → allow “Run commands in Termux environment” if Android asks.
4. In VictorSuite, set `allow-external-apps=true` in `~/.termux/termux.properties`
   and start its X11 path (internal or Termux:X11).
5. Install a RootForge Debian rootfs (PRoot or chroot). Inside it run
   `setup_desktop_mode.sh`, or tap **Set up environment**.
6. Tap **Start desktop**. VictorSuite should open a session running
   `rootforge-desktop-mode start`. XFCE appears on the X11 view; VS Code
   and RootForge’s desktop UI start if those binaries were installed.
7. **Stop** ends the session. Revoke the pin by clearing app data, or
   re-pin after a VictorSuite rebuild with a new cert.

If step 6 does nothing: the dedicated action is absent (expected today),
RUN_COMMAND may be blocked by `allow-external-apps`, or the wrapper is not
on PATH. The card must not crash the app in any of those cases.

## Honest limits

- **Not run on a phone** in the session that added this.
- VictorSuite does not implement `ai.victorsuite.action.DESKTOP_MODE` yet.
  RUN_COMMAND is the only live transport; it still needs
  `allow-external-apps` and a granted permission.
- RootForge’s Compose desktop UI inside the rootfs needs a Linux aarch64
  `:desktop` build copied in. This repo does not fetch one.
- `code-oss` is often absent from stock Debian apt. code-server is
  installed only with a pinned URL+digest.
- VNC fallback listens on localhost. How you view it on the phone (a
  VictorSuite viewer, or another app) is not implemented here.
