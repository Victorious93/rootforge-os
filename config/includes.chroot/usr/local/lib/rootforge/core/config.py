"""rootforge.core.config — layered YAML configuration.

Precedence (lowest to highest): built-in defaults < user config
(~/.config/rootforge/config.yaml) < project config (rootforge.yaml, found
by walking up from the current directory) < per-device override
($ROOTFORGE_HOME/devices/<codename>/rootforge.yaml, when a codename is
known) < an explicit command-line option (e.g. `backup create --partitions`).
Layers merge recursively on nested dicts, so a device override can set just
`backup.compress: true` without repeating the rest of a project's `backup:`
block.

Every layer is validated before it is merged (see `_validate_layer`), and an
invalid value is a ConfigError naming the file — nothing runs on a value that
was never checked. The schema deliberately contains no key that can switch
off a safety check (device validation, typed confirmation, integrity
verification); those are not configurable.

A missing file at any layer is not an error — every layer is optional.
Malformed YAML IS an error (raised as ConfigError): silently ignoring a
config that fails to parse would hide a user's mistake rather than surface
it.
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml

DEFAULTS: Dict[str, Any] = {
    "backup": {
        "partitions": [
            "boot",
            "init_boot",
            "vendor_boot",
            "dtbo",
            "vbmeta",
            "vbmeta_system",
        ],
    },
}


class ConfigError(Exception):
    """A config file exists but failed to parse or was shaped wrong."""


def _rootforge_home() -> Path:
    return Path(os.environ.get("ROOTFORGE_HOME", str(Path.home() / "rootforge")))


def _user_config_path() -> Path:
    config_home = os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))
    return Path(config_home) / "rootforge" / "config.yaml"


def _find_project_config(start: Optional[Path] = None) -> Optional[Path]:
    """Walk up from `start` (default: cwd) looking for rootforge.yaml."""
    current = (start or Path.cwd()).resolve()
    for candidate in (current, *current.parents):
        path = candidate / "rootforge.yaml"
        if path.is_file():
            return path
    return None


def _device_config_path(codename: str) -> Path:
    return _rootforge_home() / "devices" / codename / "rootforge.yaml"


def _load_yaml(path: Path) -> Dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh)
    except yaml.YAMLError as exc:
        raise ConfigError(f"{path}: invalid YAML — {exc}") from exc
    except OSError as exc:
        raise ConfigError(f"{path}: cannot be read — {exc.strerror or exc}") from exc
    if data is None:
        return {}
    if not isinstance(data, dict):
        raise ConfigError(
            f"{path}: expected a mapping at the top level, got {type(data).__name__}"
        )
    _validate_layer(path, data)
    return data


_PARTITION_RE = re.compile(r"^[a-z0-9_]+$")


def _validate_layer(path: Path, data: Dict[str, Any]) -> None:
    """Reject values of the wrong type or shape in one config layer."""
    backup = data.get("backup")
    if backup is None:
        return
    if not isinstance(backup, dict):
        raise ConfigError(f"{path}: 'backup' must be a mapping, got {type(backup).__name__}")
    partitions = backup.get("partitions")
    if partitions is None:
        return
    if not isinstance(partitions, list) or not partitions:
        raise ConfigError(f"{path}: backup.partitions must be a non-empty list of partition names")
    for name in partitions:
        if not isinstance(name, str) or not _PARTITION_RE.match(name):
            raise ConfigError(
                f"{path}: backup.partitions entry {name!r} is not a partition name "
                f"(lowercase letters, digits and underscores only)"
            )
    if len(set(partitions)) != len(partitions):
        raise ConfigError(f"{path}: backup.partitions lists a partition more than once")


def _merge(base: Dict[str, Any], overlay: Dict[str, Any]) -> Dict[str, Any]:
    result = dict(base)
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = _merge(result[key], value)
        else:
            result[key] = value
    return result


def load_config(
    codename: Optional[str] = None, project_dir: Optional[Path] = None
) -> Dict[str, Any]:
    """Load and merge every config layer that exists.

    Returns a plain dict with one extra key, `_sources`, listing the paths
    actually read (empty if only defaults applied) — useful for `rootforge
    config show` and for debugging which file set a given value.
    """
    config: Dict[str, Any] = dict(DEFAULTS)
    sources: List[Path] = []

    user_path = _user_config_path()
    if user_path.is_file():
        config = _merge(config, _load_yaml(user_path))
        sources.append(user_path)

    project_path = _find_project_config(project_dir)
    if project_path is not None:
        config = _merge(config, _load_yaml(project_path))
        sources.append(project_path)

    if codename:
        device_path = _device_config_path(codename)
        if device_path.is_file():
            config = _merge(config, _load_yaml(device_path))
            sources.append(device_path)

    config["_sources"] = [str(p) for p in sources]
    return config


def cmd_show(codename: Optional[str] = None, as_json: bool = False) -> int:
    try:
        config = load_config(codename=codename)
    except ConfigError as exc:
        print(f"Config error: {exc}", file=sys.stderr if as_json else sys.stdout)
        return 1

    if as_json:
        print(json.dumps(config, indent=2, sort_keys=True))
        return 0

    sources = config.pop("_sources", [])
    print("Effective RootForge config")
    print("===========================")
    if sources:
        print("Loaded from:")
        for source in sources:
            print(f"  {source}")
    else:
        print("Loaded from: (defaults only — no config files found)")
    print()
    print(yaml.safe_dump(config, sort_keys=True, default_flow_style=False))
    return 0
