# RootForge OS toolkit image

Debian Bookworm image with the `rootforge` CLI, Debian's `adb`/`fastboot`, a
headless JSON API on port 8787, and a small web UI at `/`. **Not signed. Not
run against a real phone in CI.**

Build from the repository root:

```bash
docker build -f docker/Dockerfile -t rootforge-os-toolkit .
```

## USB passthrough

The image does not magically see a phone. The host must pass USB through.

Linux (recommended shape):

```bash
# Token is required; the API refuses unauthenticated POSTs.
export ROOTFORGE_API_TOKEN="$(openssl rand -hex 16)"

docker run --rm -it \
  --name rootforge \
  --privileged \
  --device /dev/bus/usb \
  -v /dev/bus/usb:/dev/bus/usb \
  -p 127.0.0.1:8787:8787 \
  -e ROOTFORGE_API_TOKEN \
  rootforge-os-toolkit
```

- `--device /dev/bus/usb` plus the bind of `/dev/bus/usb` is what most hosts
  need so newly plugged devices appear without recreating the container.
- `--privileged` is the blunt option that also covers `/dev/bus/usb` and
  device nodes created after start. Prefer it only when `--device` alone is
  not enough (common with fastboot/EDL re-enumeration).
- Publish the port on **127.0.0.1** only. The process inside the container
  listens on `0.0.0.0:8787`; Docker's publish list is the LAN gate.

Then:

```bash
# CLI instead of the API
docker exec -it rootforge rootforge devices
docker exec -it rootforge adb devices

# API
curl -sS -H "Authorization: Bearer $ROOTFORGE_API_TOKEN" http://127.0.0.1:8787/health
```

The web UI is `http://127.0.0.1:8787/` — paste the token there; it is not
put in the page source.

Windows / macOS Docker Desktop: USB passthrough is **not** the same as Linux.
Typical path is `usbipd-win` (Windows) or a Linux VM. That combination is
**unverified** here. The native Windows desktop app talks to host
`adb.exe`/`fastboot.exe` and does not need Docker USB.

## API

`POST /v1/bridge` with the same JSON envelope as
`docs/DROIDCOMMAND_INTEGRATION.md`. Destructive operations need
`"confirmation": "FLASH boot"` (or `UNLOCK` / `LOCK` / `SLOT a`) in the
request object. Module zip building is **not** reimplemented in Python;
use the desktop/Android library builder or `rootforge module scaffold`.
