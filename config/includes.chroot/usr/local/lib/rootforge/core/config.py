"""rootforge.core.config — layered YAML configuration.

Precedence (lowest to highest): built-in defaults < user config
(~/.config/rootforge/config.yaml) < project config (rootforge.yaml, found
by walking up from the current directory) < per-device override
($ROOTFORGE_HOME/devices/<codename>/rootforge.yaml, when a codename is
known). Layers merge recursively on nested dicts, so a device override can
set just `backup.compress: true` without repeating the rest of a project's
`backup:` block.

A missing file at any layer is not an error — every layer is optional.
Malformed YAML IS an error (raised as ConfigError): silently ignoring a
config that fails to parse would hide a user's mistake rather than surface
it.
"""
from __future__ import annotations

import os
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
    if data is None:
        return {}
    if not isinstance(data, dict):
        raise ConfigError(
            f"{path}: expected a mapping at the top level, got {type(data).__name__}"
        )
    return data


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


def cmd_show(codename: Optional[str] = None) -> int:
    try:
        config = load_config(codename=codename)
    except ConfigError as exc:
        print(f"Config error: {exc}")
        return 1

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
